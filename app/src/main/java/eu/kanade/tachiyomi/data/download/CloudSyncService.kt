package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import logcat.LogPriority
import logcat.logcat
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import okio.BufferedSink
import okio.source
import org.w3c.dom.Element
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

class CloudSyncService(
    private val networkHelper: NetworkHelper = Injekt.get(),
) {
    private val uploadClient: OkHttpClient by lazy {
        networkHelper.client.newBuilder()
            .readTimeout(UPLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .writeTimeout(UPLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .callTimeout(UPLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .build()
    }

    suspend fun testConnection(config: CloudSyncConfig) {
        listDirectories(config, "/")
    }

    suspend fun listDirectories(config: CloudSyncConfig, path: String): List<CloudSyncDirectory> = withIOContext {
        val request = Request.Builder()
            .url(config.resolveUrl(path, trailingSlash = true))
            .headers(config.authHeaders())
            .header("Depth", "1")
            .method("PROPFIND", PROPFIND_BODY)
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("WebDAV PROPFIND failed: HTTP ${response.code}")
            }
            response.body.byteStream().use { input ->
                parseDirectories(config, input.readBytes())
                    .filterNot { it.path == normalizePath(path) }
            }
        }
    }

    suspend fun uploadCbz(
        config: CloudSyncConfig,
        remoteDirectory: String,
        file: UniFile,
        fileName: String = file.name ?: error("Missing upload file name"),
        onProgress: (Int) -> Unit,
    ) = uploadFile(
        config = config,
        remoteDirectory = remoteDirectory,
        file = file,
        fileName = fileName,
        mediaType = CBZ_MEDIA_TYPE,
        overwrite = false,
        onProgress = onProgress,
    )

    suspend fun uploadFile(
        config: CloudSyncConfig,
        remoteDirectory: String,
        file: UniFile,
        fileName: String = file.name ?: error("Missing upload file name"),
        mediaType: MediaType = GENERIC_BINARY_MEDIA_TYPE,
        overwrite: Boolean = false,
        onProgress: (Int) -> Unit,
    ) = withIOContext {
        logcat(LogPriority.INFO) { "CloudSync: uploading via WebDAV" }
        uploadWebDavCbz(
            config = config,
            remoteDirectory = remoteDirectory,
            fileName = fileName,
            file = file,
            mediaType = mediaType,
            overwrite = overwrite,
            onProgress = onProgress,
        )
        logcat(LogPriority.INFO) { "CloudSync: WebDAV upload completed" }
    }

    suspend fun remoteFileSha256OrNull(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
    ): String? = withIOContext {
        val effectiveRemoteDirectory = normalizePath(remoteDirectory)
        val request = Request.Builder()
            .url(config.resolveUrl(effectiveRemoteDirectory, fileName))
            .headers(config.authHeaders())
            .get()
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            if (response.code == 404) return@withIOContext null
            if (!response.isSuccessful) {
                throw IllegalStateException("WebDAV file lookup failed: HTTP ${response.code}")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            response.body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHexString()
        }
    }

    suspend fun deleteRemoteFile(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
    ) = withIOContext {
        val effectiveRemoteDirectory = normalizePath(remoteDirectory)
        val request = Request.Builder()
            .url(config.resolveUrl(effectiveRemoteDirectory, fileName))
            .headers(config.authHeaders())
            .delete()
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            if (response.code == 404) return@withIOContext
            if (response.code !in SUCCESS_CODES) {
                throw IllegalStateException("WebDAV delete failed: HTTP ${response.code}")
            }
        }
    }

    private suspend fun uploadWebDavCbz(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
        file: UniFile,
        mediaType: MediaType,
        overwrite: Boolean,
        onProgress: (Int) -> Unit,
    ) {
        val effectiveRemoteDirectory = ensureDirectory(config, remoteDirectory)

        onProgress(0)
        val request = Request.Builder()
            .url(config.resolveUrl(effectiveRemoteDirectory, fileName))
            .headers(config.authHeaders())
            .put(UniFileRequestBody(file, mediaType, onProgress))
            .build()

        uploadClient.newCall(request).await().use { response ->
            if (response.code !in SUCCESS_CODES) {
                throw IllegalStateException("WebDAV upload failed: HTTP ${response.code}")
            }
        }
        onProgress(100)
    }

    private suspend fun ensureDirectory(config: CloudSyncConfig, path: String): String {
        var currentPath = ""
        normalizePath(path)
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
            .forEach { segment ->
                val targetPath = normalizePath("$currentPath/$segment")
                when (val result = mkcol(config, targetPath)) {
                    is MkcolResult.Success -> currentPath = targetPath
                    is MkcolResult.Failure -> {
                        if (directoryExists(config, targetPath)) {
                            currentPath = targetPath
                        } else {
                            throw IllegalStateException(
                                "WebDAV MKCOL failed: HTTP ${result.code} for ${result.path}",
                            ).apply {
                                result.message?.let { addSuppressed(IllegalStateException(it)) }
                            }
                        }
                    }
                }
            }
        return currentPath.ifBlank { "/" }
    }

    private suspend fun directoryExists(config: CloudSyncConfig, path: String): Boolean {
        val request = Request.Builder()
            .url(config.resolveUrl(path, trailingSlash = true))
            .headers(config.authHeaders())
            .header("Depth", "0")
            .method("PROPFIND", PROPFIND_BODY)
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            return when {
                response.isSuccessful -> true
                response.code == 404 -> false
                response.code in AUTH_FAILURE_CODES -> throw IllegalStateException("WebDAV PROPFIND failed: HTTP ${response.code}")
                else -> false
            }
        }
    }

    private suspend fun mkcol(config: CloudSyncConfig, path: String): MkcolResult {
        val request = Request.Builder()
            .url(config.resolveUrl(path, trailingSlash = true))
            .headers(config.authHeaders())
            .method("MKCOL", EMPTY_BODY)
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            return when {
                response.code in MKCOL_SUCCESS_CODES -> MkcolResult.Success
                response.code in AUTH_FAILURE_CODES -> throw IllegalStateException("WebDAV MKCOL failed: HTTP ${response.code}")
                else -> MkcolResult.Failure(
                    path = path,
                    code = response.code,
                    message = response.body.string().takeIf { it.isNotBlank() },
                )
            }
        }
    }

    private fun parseDirectories(config: CloudSyncConfig, bytes: ByteArray): List<CloudSyncDirectory> {
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(bytes.inputStream())

        val responses = document.getElementsByTagNameNS("*", "response")
        return (0 until responses.length)
            .asSequence()
            .mapNotNull { index ->
                val node = responses.item(index)
                val element = node as? Element ?: return@mapNotNull null
                val href = element.firstTextByTagName("href") ?: return@mapNotNull null
                val isCollection = element.getElementsByTagNameNS("*", "collection").length > 0
                if (!isCollection) return@mapNotNull null

                val path = config.relativePathFromHref(href)
                val name = element.firstTextByTagName("displayname")
                    ?.takeUnless { it.isBlank() }
                    ?: path.trim('/').substringAfterLast('/', missingDelimiterValue = "/")
                CloudSyncDirectory(
                    name = if (path == "/") "/" else name,
                    path = path,
                )
            }
            .toList()
            .distinctBy { it.path }
            .sortedWith(compareBy({ it.path != "/" }, { it.name.lowercase() }))
    }

    private fun Element.firstTextByTagName(localName: String): String? {
        return getElementsByTagNameNS("*", localName)
            .item(0)
            ?.textContent
            ?.trim()
    }

    private fun CloudSyncConfig.authHeaders(): Headers {
        return Headers.Builder()
            .add("Authorization", Credentials.basic(username, password))
            .build()
    }

    private fun CloudSyncConfig.resolveUrl(
        remotePath: String,
        fileName: String? = null,
        trailingSlash: Boolean = false,
    ): HttpUrl {
        val base = url.toHttpUrl()
        val segments = buildList {
            addAll(base.pathSegments.filter { it.isNotBlank() })
            addAll(normalizePath(remotePath).trim('/').split('/').filter { it.isNotBlank() })
            if (!fileName.isNullOrBlank()) {
                add(fileName)
            }
        }
        return base.newBuilder()
            .encodedPath("/")
            .apply {
                segments.forEach(::addPathSegment)
                if (trailingSlash) addPathSegment("")
            }
            .build()
    }

    private fun CloudSyncConfig.relativePathFromHref(href: String): String {
        val hrefPath = runCatching {
            URLDecoder.decode(java.net.URI(href).path ?: href, Charsets.UTF_8.name())
        }.getOrElse {
            URLDecoder.decode(href, Charsets.UTF_8.name())
        }
        val basePath = "/" + url.toHttpUrl().pathSegments.filter { it.isNotBlank() }.joinToString("/")
        val relativePath = hrefPath
            .removePrefix(basePath)
            .trim('/')
        return normalizePath(relativePath)
    }

    private fun ByteArray.toHexString(): String {
        return joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private class UniFileRequestBody(
        private val file: UniFile,
        private val mediaType: MediaType,
        private val onProgress: (Int) -> Unit,
    ) : RequestBody() {
        private var resolvedContentLength = Long.MIN_VALUE

        override fun contentType(): MediaType = mediaType

        override fun contentLength(): Long {
            if (resolvedContentLength != Long.MIN_VALUE) {
                return resolvedContentLength
            }

            resolvedContentLength = file.length().takeIf { it >= 0 } ?: file.openInputStream().source().use { source ->
                var total = 0L
                val buffer = Buffer()
                while (true) {
                    val read = source.read(buffer, SEGMENT_SIZE)
                    if (read == -1L) break
                    total += read
                    buffer.clear()
                }
                total
            }

            return resolvedContentLength
        }

        override fun writeTo(sink: BufferedSink) {
            val total = contentLength()
            var uploaded = 0L
            val buffer = Buffer()

            file.openInputStream().source().use { source ->
                while (true) {
                    val read = source.read(buffer, SEGMENT_SIZE)
                    if (read == -1L) break
                    sink.write(buffer, read)
                    uploaded += read
                    if (total > 0) {
                        onProgress((uploaded * 100 / total).toInt())
                    }
                }
            }
        }
    }

    companion object {
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname />
                <d:resourcetype />
              </d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
        private val CBZ_MEDIA_TYPE = "application/vnd.comicbook+zip".toMediaType()
        private val GENERIC_BINARY_MEDIA_TYPE = "application/octet-stream".toMediaType()
        private val SUCCESS_CODES = setOf(200, 201, 204)
        private val MKCOL_SUCCESS_CODES = setOf(200, 201, 204, 405)
        private val AUTH_FAILURE_CODES = setOf(401, 407)
        private const val UPLOAD_TIMEOUT_MINUTES = 15L
        private const val SEGMENT_SIZE = 8L * 1024L
    }
}

private sealed class MkcolResult {
    data object Success : MkcolResult()

    data class Failure(
        val path: String,
        val code: Int,
        val message: String?,
    ) : MkcolResult()
}

data class CloudSyncConfig(
    val url: String,
    val username: String,
    val password: String,
) {
    val isValid: Boolean
        get() = url.isNotBlank() && username.isNotBlank() && password.isNotBlank()
}

data class CloudSyncDirectory(
    val name: String,
    val path: String,
)

fun normalizePath(path: String): String {
    val normalized = path
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString(separator = "/", prefix = "/")
    return normalized.takeUnless { it.isBlank() } ?: "/"
}
