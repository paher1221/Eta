package io.github.mangi.eta.agent.device

import android.net.Uri
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.core.AgentLogger
import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AgentFileReferenceGatewayTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun appReadableFilesAndDirectoriesResolveWithoutExtraPermissions() {
        val file = temporaryFolder.newFile("report.txt").apply { writeText("ordinary file") }
        val gateway = AgentFileReferenceGateway()
        val result = gateway.resolveAbsolutePath(file.absolutePath, AgentFileReferenceKind.File)
            as AgentFileReferenceGateway.Resolution.Success
        assertEquals(file.canonicalPath, result.reference.absolutePath)
        assertFailure(
            AgentFileReferenceGateway.Error.TypeMismatch,
            gateway.resolveAbsolutePath(file.absolutePath, AgentFileReferenceKind.Directory),
        )
        assertTrue(gateway.resolveAbsolutePath(temporaryFolder.root.path) is AgentFileReferenceGateway.Resolution.Success)
        assertFailure(
            AgentFileReferenceGateway.Error.PathNotFound,
            gateway.resolveAbsolutePath(File(temporaryFolder.root, "missing").path),
        )
    }

    @Test
    fun documentWithNoFilesystemPathIsImportedIntoPersistentWorkspace() {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://cloud.example/document/report")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream("授权内容".toByteArray()))
        val gateway = AgentFileReferenceGateway(context, NoOpLogger)

        val result = gateway.resolveDocumentUri(uri, AgentFileReferenceKind.File)
            as AgentFileReferenceGateway.Resolution.Success
        val imported = File(result.reference.absolutePath)
        assertTrue(imported.path.startsWith(File(context.filesDir, "terminal-user/workspace/imports").path + "/"))
        assertEquals("授权内容", imported.readText())
        imported.parentFile?.deleteRecursively()
    }

    @Test
    fun importNamesCannotEscapeTheirPrivateDirectory() {
        assertEquals(".._.._secret_name", AgentFileReferenceGateway.safeImportName("../../secret\nname"))
        assertEquals("imported-file", AgentFileReferenceGateway.safeImportName(".."))
    }

    @Test
    fun mapPrimaryStorageDocument_acceptsPrimaryVolumeOnly() {
        assertEquals(
            "/storage/emulated/0/Download/report.txt",
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:Download/report.txt",
            ),
        )
        assertEquals(
            "/storage/emulated/0",
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:",
            ),
        )
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                "com.android.providers.downloads.documents",
                "primary:Download/report.txt",
            )
        )
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "1234-5678:report.txt",
            )
        )
    }

    @Test
    fun mapPrimaryStorageDocument_rejectsTraversalAndControlCharacters() {
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:Download/../secret",
            )
        )
        assertNull(
            AgentFileReferenceGateway.mapPrimaryStorageDocument(
                AgentFileReferenceGateway.EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY,
                "primary:Download/bad\nname",
            )
        )
    }

    @Test
    fun resolveAbsolutePath_returnsCanonicalFileAndDirectory() {
        val gateway = AgentFileReferenceGateway()
        val file = temporaryFolder.newFile("report.txt")
        val directory = temporaryFolder.newFolder("project")

        assertEquals(
            AgentFileReferenceGateway.Resolution.Success(
                AgentFileReference(
                    displayName = "report.txt",
                    absolutePath = file.canonicalPath,
                    kind = AgentFileReferenceKind.File,
                )
            ),
            gateway.resolveAbsolutePath(file.absolutePath, AgentFileReferenceKind.File),
        )
        assertEquals(
            AgentFileReferenceGateway.Resolution.Success(
                AgentFileReference(
                    displayName = "project",
                    absolutePath = directory.canonicalPath,
                    kind = AgentFileReferenceKind.Directory,
                )
            ),
            gateway.resolveAbsolutePath(directory.absolutePath),
        )
    }

    @Test
    fun resolveAbsolutePath_mapsFilesystemFailures() {
        val gateway = AgentFileReferenceGateway()
        assertFailure(
            expected = AgentFileReferenceGateway.Error.UnsupportedFileType,
            result = gateway.resolveAbsolutePath(createSpecialFile().absolutePath),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.PathNotFound,
            result = gateway.resolveAbsolutePath(File(temporaryFolder.root, "missing").path),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.InvalidPath,
            result = gateway.resolveAbsolutePath("relative/path"),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.InvalidPath,
            result = gateway.resolveAbsolutePath("/data/local/tmp/bad\npath"),
        )
        assertFailure(
            expected = AgentFileReferenceGateway.Error.TypeMismatch,
            result = gateway.resolveAbsolutePath(
                temporaryFolder.newFile("typed.txt").absolutePath,
                AgentFileReferenceKind.Directory,
            ),
        )
    }

    @Test
    fun resolveDocumentUri_usesLocalPathResolvedFromMediaDocument() {
        val uri = Uri.parse(
            "content://com.android.providers.media.documents/document/image%3A24708"
        )
        val localPath = temporaryFolder.newFile("example.jpg").apply { writeText("image") }
        val gateway = AgentFileReferenceGateway(
            resolveDocumentPath = { selectedUri ->
                assertEquals(uri, selectedUri)
                localPath.absolutePath
            },
        )

        assertEquals(
            AgentFileReferenceGateway.Resolution.Success(
                AgentFileReference(
                    displayName = "example.jpg",
                    absolutePath = localPath.canonicalPath,
                    kind = AgentFileReferenceKind.File,
                )
            ),
            gateway.resolveDocumentUri(uri, AgentFileReferenceKind.File),
        )
    }

    @Test
    fun resolveDocumentUri_rejectsProviderWithoutLocalPath() {
        val gateway = AgentFileReferenceGateway(resolveDocumentPath = { null })

        assertFailure(
            expected = AgentFileReferenceGateway.Error.UnsupportedDocumentProvider,
            result = gateway.resolveDocumentUri(
                Uri.parse("content://cloud.example/document/remote%3A1"),
                AgentFileReferenceKind.File,
            ),
        )
    }

    private fun createSpecialFile(): File {
        val link = File(temporaryFolder.root, "device-link")
        java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Path.of("/dev/null"))
        return link
    }

    private fun assertFailure(
        expected: AgentFileReferenceGateway.Error,
        result: AgentFileReferenceGateway.Resolution,
    ) {
        assertEquals(expected, (result as AgentFileReferenceGateway.Resolution.Failure).error)
    }

    private object NoOpLogger : AgentLogger {
        override fun debug(message: () -> String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, throwable: Throwable?) = Unit
    }
}
