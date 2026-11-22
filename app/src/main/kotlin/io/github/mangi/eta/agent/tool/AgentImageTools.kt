package io.github.mangi.eta.agent.tool

import android.content.Context
import android.net.Uri
import io.github.mangi.eta.agent.device.BoundedFileCopy
import io.github.mangi.eta.agent.media.AgentImageCodec
import io.github.mangi.eta.agent.media.ModelImageBudget
import io.github.mangi.eta.agent.model.AgentModelClient
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import org.json.JSONObject

/** 读取用户已明确指定的单张图片，流式采样并按当前渠道预算编码后作为临时视觉附件交给模型。 */
internal class AgentImageTools(
    private val context: Context,
    private val imageBudgetProvider: () -> ModelImageBudget = { ModelImageBudget.DEFAULT },
) {
    fun readImage(args: JSONObject): AgentModelClient.ToolResult {
        val source = args.getString("path").removePrefix("file://")
        val sourceKind = when {
            source.startsWith("content://") -> ImageSourceKind.ContentUri
            source.startsWith("/") && !source.contains('\u0000') -> ImageSourceKind.File
            else -> return sensitive(error("IMAGE_PATH_DENIED", "图片路径必须是绝对路径、file URI 或已授权的 content URI"))
        }
        val budget = imageBudgetProvider()

        // 应用可直接读取的本地图片零拷贝流式解码，不落地额外副本、不设原图体积上限。
        if (sourceKind == ImageSourceKind.File) {
            val directFile = File(source)
            if (directFile.isFile && directFile.canRead()) {
                val image = AgentImageCodec.fromToolFile(
                    file = directFile,
                    source = "tool_read_image",
                    budget = budget,
                ) ?: return sensitive(error("IMAGE_UNSUPPORTED", "文件不是可识别的图片"))
                return successResult(source, image)
            }
        }

        val cacheDir = imageCacheDirectory()
        val temporaryFile = runCatching {
            File.createTempFile("eta-read-image-", ".img", cacheDir)
        }.getOrElse {
            return sensitive(error("IMAGE_TEMPORARY_FILE_FAILED", "无法创建图片临时文件"))
        }
        return try {
            val staged = copyAsApp(source, sourceKind, temporaryFile, cacheDir)
            if (!staged) {
                return sensitive(error("IMAGE_ACCESS_DENIED", "Eta 无法读取此图片；请先通过文件选择器导入或授予读取权限"))
            }
            val image = AgentImageCodec.fromToolFile(
                file = temporaryFile,
                source = "tool_read_image",
                budget = budget,
            ) ?: return sensitive(error("IMAGE_UNSUPPORTED", "文件不是可识别的图片"))
            successResult(source, image)
        } catch (_: BoundedFileCopy.InsufficientStorageException) {
            sensitive(error("IMAGE_INSUFFICIENT_STORAGE", "设备可用存储空间不足，无法暂存图片"))
        } finally {
            temporaryFile.delete()
        }
    }

    private fun successResult(
        source: String,
        image: AgentModelClient.ModelImage,
    ): AgentModelClient.ToolResult = sensitive(
        content = JSONObject()
            .put("ok", true)
            .put("tool", "read_image")
            .put("path", source)
            .put("image_attached", true)
            .toString(),
        images = listOf(image),
    )

    private fun copyAsApp(
        source: String,
        sourceKind: ImageSourceKind,
        destination: File,
        cacheDir: File,
    ): Boolean = try {
        val input = when (sourceKind) {
            ImageSourceKind.File -> File(source).takeIf { it.isFile && it.canRead() }?.inputStream()
            ImageSourceKind.ContentUri -> context.contentResolver.openInputStream(Uri.parse(source))
        }
        if (input == null) {
            false
        } else {
            val declaredBytes = if (sourceKind == ImageSourceKind.File) File(source).length() else -1L
            input.use { sourceStream ->
                destination.outputStream().use { target ->
                    BoundedFileCopy.copyWithStorageReserve(
                        input = sourceStream,
                        output = target,
                        targetDirectory = cacheDir,
                        declaredBytes = declaredBytes,
                    )
                }
            }
            true
        }
    } catch (storage: BoundedFileCopy.InsufficientStorageException) {
        throw storage
    } catch (interrupted: InterruptedIOException) {
        throw interrupted
    } catch (_: SecurityException) {
        false
    } catch (_: IOException) {
        false
    }

    private fun imageCacheDirectory(): File =
        context.externalCacheDir
            ?.takeIf { it.isDirectory || it.mkdirs() }
            ?: context.cacheDir

    private fun error(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("message", message).toString()

    private fun sensitive(
        content: String,
        images: List<AgentModelClient.ModelImage> = emptyList(),
    ) = AgentModelClient.ToolResult(content = content, images = images, sensitive = true)

    private enum class ImageSourceKind {
        File,
        ContentUri,
    }
}
