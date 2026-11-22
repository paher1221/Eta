package io.github.mangi.eta.agent.device

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.hasUnsupportedControlCharacter
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import io.github.mangi.eta.core.AgentLogger
import java.io.File
import java.io.IOException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

internal class AgentFileReferenceGateway(
    private val resolveDocumentPath: (Uri) -> String? = { null },
    private val importDocument: ((Uri) -> Resolution)? = null,
) {
    constructor(logger: AgentLogger) : this()

    constructor(
        context: Context,
        logger: AgentLogger,
    ) : this(
        resolveDocumentPath = { uri -> queryLocalDocumentPath(context.applicationContext, uri) },
        importDocument = { uri -> importDocumentUri(context.applicationContext, uri) },
    )

    fun resolveDocumentUri(
        uri: Uri,
        expectedKind: AgentFileReferenceKind,
    ): Resolution {
        val documentId = runCatching {
            if (DocumentsContract.isTreeUri(uri)) {
                DocumentsContract.getTreeDocumentId(uri)
            } else {
                DocumentsContract.getDocumentId(uri)
            }
        }.getOrNull()
        val mappedPath = documentId?.let { mapPrimaryStorageDocument(uri.authority, it) }
            ?: resolveDocumentPath(uri)
        val resolved = mappedPath?.let { resolveAbsolutePath(it, expectedKind) }
        if (resolved is Resolution.Success) return resolved
        if (expectedKind == AgentFileReferenceKind.File && uri.scheme == "content") {
            importDocument?.let { return it(uri) }
        }
        return resolved ?: Resolution.Failure(Error.UnsupportedDocumentProvider)
    }

    fun resolveAbsolutePath(
        rawPath: String,
        expectedKind: AgentFileReferenceKind? = null,
    ): Resolution {
        val path = rawPath
        if (
            path.isEmpty() ||
            !path.startsWith('/') ||
            path.hasUnsupportedControlCharacter()
        ) {
            return Resolution.Failure(Error.InvalidPath)
        }
        val localResult = resolveAsApp(path, expectedKind)
        return localResult
    }

    private fun resolveAsApp(path: String, expectedKind: AgentFileReferenceKind?): Resolution = try {
        val file = File(path).canonicalFile
        require(!file.path.hasUnsupportedControlCharacter())
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
        val kind = when {
            attributes.isRegularFile -> AgentFileReferenceKind.File
            attributes.isDirectory -> AgentFileReferenceKind.Directory
            else -> null
        }
        when {
            kind == null -> Resolution.Failure(Error.UnsupportedFileType)
            expectedKind != null && expectedKind != kind -> Resolution.Failure(Error.TypeMismatch)
            !Files.isReadable(file.toPath()) ||
                (kind == AgentFileReferenceKind.Directory && !Files.isExecutable(file.toPath())) ->
                Resolution.Failure(Error.AccessDenied)
            else -> Resolution.Success(
                AgentFileReference(
                    displayName = file.name.ifBlank { file.path },
                    absolutePath = file.path,
                    kind = kind,
                ),
            )
        }
    } catch (_: IllegalArgumentException) {
        Resolution.Failure(Error.InvalidPath)
    } catch (_: NoSuchFileException) {
        Resolution.Failure(Error.PathNotFound)
    } catch (_: AccessDeniedException) {
        Resolution.Failure(Error.AccessDenied)
    } catch (_: SecurityException) {
        Resolution.Failure(Error.AccessDenied)
    } catch (_: IOException) {
        Resolution.Failure(Error.AccessDenied)
    }

    internal enum class Error {
        UnsupportedDocumentProvider,
        InvalidPath,
        PathNotFound,
        UnsupportedFileType,
        TypeMismatch,
        RootUnavailable,
        AccessDenied,
        ImportFailed,
        InsufficientStorage,
        ValidationTimedOut,
    }

    internal sealed interface Resolution {
        data class Success(val reference: AgentFileReference) : Resolution
        data class Failure(val error: Error) : Resolution
    }

    internal companion object {
        const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
        const val SHARED_STORAGE_ROOT = "/storage/emulated/0"

        private const val MEDIA_DOCUMENTS_AUTHORITY = "com.android.providers.media.documents"

        private data class DocumentMetadata(
            val displayName: String?,
            val declaredSize: Long,
        )

        internal fun importDocumentUri(context: Context, uri: Uri): Resolution {
            val workspace = TerminalPrivateStorage.workspace(context.filesDir)
            val importDirectory = File(workspace, "imports/${UUID.randomUUID()}")
            var completed = false
            return try {
                val metadata = documentMetadata(context, uri)
                val displayName = safeImportName(metadata.displayName)
                if (!importDirectory.mkdirs()) return Resolution.Failure(Error.ImportFailed)
                val importedFile = File(importDirectory, displayName)
                val input = context.contentResolver.openInputStream(uri)
                    ?: return Resolution.Failure(Error.ImportFailed)
                input.use { source ->
                    importedFile.outputStream().use { destination ->
                        BoundedFileCopy.copyWithStorageReserve(
                            input = source,
                            output = destination,
                            targetDirectory = importDirectory,
                            declaredBytes = metadata.declaredSize,
                        )
                    }
                }
                completed = true
                Resolution.Success(
                    AgentFileReference(
                        displayName = displayName,
                        absolutePath = importedFile.absolutePath,
                        kind = AgentFileReferenceKind.File,
                    ),
                )
            } catch (_: BoundedFileCopy.InsufficientStorageException) {
                Resolution.Failure(Error.InsufficientStorage)
            } catch (_: SecurityException) {
                Resolution.Failure(Error.AccessDenied)
            } catch (_: IOException) {
                Resolution.Failure(Error.ImportFailed)
            } catch (_: RuntimeException) {
                Resolution.Failure(Error.ImportFailed)
            } finally {
                if (!completed) importDirectory.deleteRecursively()
            }
        }

        private fun documentMetadata(context: Context, uri: Uri): DocumentMetadata = try {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    val name = if (nameIndex >= 0 && !cursor.isNull(nameIndex)) cursor.getString(nameIndex) else null
                    val size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else -1L
                    DocumentMetadata(name, size)
                } else {
                    DocumentMetadata(null, -1L)
                }
            } ?: DocumentMetadata(null, -1L)
        } catch (_: RuntimeException) {
            // 元数据是可选提示；实际读取授权与剩余空间在流式拷贝时实时校验。
            DocumentMetadata(null, -1L)
        }

        internal fun safeImportName(name: String?): String = name.orEmpty()
            .map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }
            .joinToString("")
            .take(80)
            .takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "imported-file"

        fun mapPrimaryStorageDocument(authority: String?, documentId: String): String? {
            if (authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) return null
            if (documentId.hasUnsupportedControlCharacter()) return null
            val separator = documentId.indexOf(':')
            if (separator < 0 || !documentId.substring(0, separator).equals("primary", ignoreCase = true)) {
                return null
            }
            val relativePath = documentId.substring(separator + 1)
            if (
                relativePath.startsWith('/') ||
                relativePath.split('/').any { it == "." || it == ".." }
            ) {
                return null
            }
            return if (relativePath.isEmpty()) {
                SHARED_STORAGE_ROOT
            } else {
                "$SHARED_STORAGE_ROOT/$relativePath"
            }
        }

        private fun queryLocalDocumentPath(context: Context, uri: Uri): String? {
            queryDataColumn(context, uri)?.let { return it }
            if (
                uri.authority != EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY &&
                uri.authority != MEDIA_DOCUMENTS_AUTHORITY
            ) {
                return null
            }
            val mediaUri = try {
                MediaStore.getMediaUri(context, uri)
            } catch (_: RuntimeException) {
                null
            } ?: return null
            return queryDataColumn(context, mediaUri)
        }

        @Suppress("DEPRECATION")
        private fun queryDataColumn(context: Context, uri: Uri): String? = try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns.DATA),
                null,
                null,
                null,
            )?.use { cursor ->
                val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (dataIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(dataIndex)) {
                    cursor.getString(dataIndex)
                } else {
                    null
                }
            }
        } catch (_: RuntimeException) {
            // 文档提供方可以拒绝非标准列；这表示它没有可引用的本地绝对路径。
            null
        }
    }
}
