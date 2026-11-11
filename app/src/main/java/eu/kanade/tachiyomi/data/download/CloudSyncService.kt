package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
import org.xml.sax.SAXException
import tachiyomi.core.common.util.lang.withIOContext
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

@Inject
class CloudSyncService(
    private val networkHelper: NetworkHelper,
) {
    private val uploadClient: OkHttpClient by lazy {
        networkHelper.client.newBuilder()
            .readTimeout(UPLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .writeTimeout(UPLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .callTimeout(UPLOAD_TIMEOUT_MINUTES, TimeUnit.MINUTES)
            .retryOnConnectionFailure(false)
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
                parseDirectories(config, path, input)
                    .filter { directory ->
                        val parent = directory.path.substringBeforeLast('/').ifBlank { "/" }
                        directory.path != normalizePath(path) && parent == normalizePath(path)
                    }
            }
        }
    }

    suspend fun uploadCbz(
        config: CloudSyncConfig,
        remoteDirectory: String,
        file: UniFile,
        fileName: String = file.name ?: error("Missing upload file name"),
        onProgress: (Int) -> Unit,
    ) = withIOContext {
        val directory = ensureDirectory(config, remoteDirectory)
        val created = putNewFile(config, directory, fileName, file, CBZ_MEDIA_TYPE, onProgress)
        if (!created && remoteFileSha256OrNull(config, directory, fileName) != file.sha256()) {
            throw CloudUploadConflictException(fileName)
        }
        onProgress(100)
    }

    suspend fun replaceFile(
        config: CloudSyncConfig,
        remoteDirectory: String,
        file: UniFile,
        fileName: String,
    ) = withIOContext {
        val directory = ensureDirectory(config, remoteDirectory)
        val stagingName = "$fileName.${UUID.randomUUID()}.tmp"
        try {
            check(putNewFile(config, directory, stagingName, file, GENERIC_BINARY_MEDIA_TYPE) {})
            val request = Request.Builder()
                .url(config.resolveUrl(directory, stagingName))
                .headers(config.authHeaders())
                .header("Destination", config.resolveUrl(directory, fileName).toString())
                .header("Overwrite", "T")
                .method("MOVE", null)
                .build()
            uploadClient.newCall(request).await().use { response ->
                if (response.code !in SUCCESS_CODES) {
                    error("WebDAV MOVE failed: HTTP ${response.code}")
                }
            }
        } catch (error: Exception) {
            withContext(NonCancellable) {
                runCatching { deleteRemoteFile(config, directory, stagingName) }
            }
            throw error
        }
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
            .cacheControl(CacheControl.FORCE_NETWORK)
            .get()
            .build()

        uploadClient.newCall(request).await().use { response ->
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

    internal suspend fun downloadCbz(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
        file: UniFile,
    ): Boolean = withIOContext {
        val request = Request.Builder()
            .url(config.resolveUrl(remoteDirectory, fileName))
            .headers(config.authHeaders())
            .cacheControl(CacheControl.FORCE_NETWORK)
            .get()
            .build()
        networkHelper.client.newCall(request).await().use { response ->
            if (response.code == 404) return@withIOContext false
            check(response.code == 200) { "WebDAV download failed: HTTP ${response.code}" }
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            file.openOutputStream().use { output ->
                val input = response.body.byteStream()
                while (true) {
                    ensureActive()
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
            }
            true
        }
    }

    internal suspend fun remoteFileSize(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
    ): Long? = withIOContext {
        val request = Request.Builder()
            .url(config.resolveUrl(normalizePath(remoteDirectory), fileName))
            .headers(config.authHeaders())
            .header("Accept-Encoding", "identity")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .head()
            .build()
        networkHelper.client.newCall(request).await().use { response ->
            if (response.code != 200) return@use null
            response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0L }
        }
    }

    /**
     * Lists the requested direct children. A null result means directory listing is unavailable,
     * and a null size means an individual file needs a HEAD request.
     */
    internal suspend fun listRemoteFileSizes(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileNames: Set<String>,
    ): Map<String, Long?>? = withIOContext {
        val directory = config.resolveUrl(remoteDirectory, trailingSlash = true)
        val request = Request.Builder()
            .url(directory)
            .headers(config.authHeaders())
            .header("Depth", "1")
            .cacheControl(CacheControl.FORCE_NETWORK)
            .method("PROPFIND", PROPFIND_FILE_SIZES_BODY)
            .build()
        networkHelper.client.newCall(request).await().use { response ->
            when (response.code) {
                207 -> response.body.byteStream().use { parseFileSizes(directory, fileNames, it) }
                404 -> emptyMap()
                403, 405, 501 -> null
                else -> error("WebDAV PROPFIND failed: HTTP ${response.code}")
            }
        }
    }

    private fun parseFileSizes(directory: HttpUrl, fileNames: Set<String>, input: InputStream): Map<String, Long?>? {
        val multistatus = parseDavDocument(input).documentElement
        if (multistatus.namespaceURI != DAV_NAMESPACE || multistatus.localName != "multistatus") return null
        val directorySegments = directory.pathSegments.dropLast(1)
        val resources = multistatus.getElementsByTagNameNS(DAV_NAMESPACE, "response")
        return buildMap {
            for (index in 0 until resources.length) {
                val resource = resources.item(index) as Element
                val href = resource.firstTextByTagName("href", DAV_NAMESPACE) ?: continue
                val url = directory.resolve(href) ?: continue
                if (url.scheme != directory.scheme || url.host != directory.host || url.port != directory.port ||
                    url.pathSegments.dropLast(1) != directorySegments
                ) {
                    continue
                }
                val fileName = url.pathSegments.last()
                if (fileName !in fileNames) continue

                var isCollection = false
                var size: Long? = null
                val results = resource.getElementsByTagNameNS(DAV_NAMESPACE, "propstat")
                for (resultIndex in 0 until results.length) {
                    val result = results.item(resultIndex) as Element
                    val status = result.firstTextByTagName("status", DAV_NAMESPACE)
                    if (status?.split(' ', limit = 3)?.getOrNull(1) != "200") continue
                    val properties = result.getElementsByTagNameNS(DAV_NAMESPACE, "prop").item(0) as? Element
                        ?: continue
                    isCollection =
                        isCollection || properties.getElementsByTagNameNS(DAV_NAMESPACE, "collection").length > 0
                    properties.firstTextByTagName("getcontentlength", DAV_NAMESPACE)?.toLongOrNull()
                        ?.takeIf { it > 0 }?.let { size = it }
                }
                if (!isCollection) put(fileName, size)
            }
        }
    }

    private suspend fun deleteRemoteFile(
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

    internal suspend fun deleteMangaCollection(config: CloudSyncConfig, directory: String) = withIOContext {
        val request = Request.Builder()
            .url(config.resolveUrl(directory, trailingSlash = true))
            .headers(config.authHeaders())
            .header("Depth", "infinity")
            .delete()
            .build()
        uploadClient.newCall(request).await().use { response ->
            if (response.code !in setOf(200, 204, 404)) {
                error("WebDAV collection delete failed: HTTP ${response.code}")
            }
        }
    }

    private suspend fun putNewFile(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
        file: UniFile,
        mediaType: MediaType,
        onProgress: (Int) -> Unit,
    ): Boolean {
        onProgress(0)
        val request = Request.Builder()
            .url(config.resolveUrl(remoteDirectory, fileName))
            .headers(config.authHeaders())
            .header("If-None-Match", "*")
            .put(UniFileRequestBody(file, mediaType, onProgress))
            .build()

        uploadClient.newCall(request).await().use { response ->
            if (response.code == 412) return false
            if (response.code !in SUCCESS_CODES) {
                throw IllegalStateException("WebDAV upload failed: HTTP ${response.code}")
            }
        }
        return true
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
                response.code in AUTH_FAILURE_CODES -> throw IllegalStateException(
                    "WebDAV PROPFIND failed: HTTP ${response.code}",
                )
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
                response.code in AUTH_FAILURE_CODES -> throw IllegalStateException(
                    "WebDAV MKCOL failed: HTTP ${response.code}",
                )
                else -> MkcolResult.Failure(
                    path = path,
                    code = response.code,
                    message = response.body.string().takeIf { it.isNotBlank() },
                )
            }
        }
    }

    private fun parseDirectories(
        config: CloudSyncConfig,
        directory: String,
        input: InputStream,
    ): List<CloudSyncDirectory> {
        val document = parseDavDocument(input)
        val responses = document.getElementsByTagNameNS("*", "response")
        return (0 until responses.length)
            .asSequence()
            .mapNotNull { index ->
                val node = responses.item(index)
                val element = node as? Element ?: return@mapNotNull null
                val href = element.firstTextByTagName("href") ?: return@mapNotNull null
                val isCollection = element.getElementsByTagNameNS("*", "collection").length > 0
                if (!isCollection) return@mapNotNull null

                val path = config.relativePathFromHref(href, directory) ?: return@mapNotNull null
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

    private fun parseDavDocument(input: InputStream) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .apply { setEntityResolver { _, _ -> throw SAXException("External entities are unsupported") } }
        .parse(input)

    private fun Element.firstTextByTagName(localName: String, namespace: String = "*"): String? {
        return getElementsByTagNameNS(namespace, localName)
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

    private fun CloudSyncConfig.relativePathFromHref(href: String, directory: String): String? {
        val base = url.toHttpUrl().pathSegments.filter { it.isNotBlank() }
        val hrefUrl = resolveUrl(directory, trailingSlash = true).resolve(href) ?: return null
        val segments = hrefUrl.pathSegments.filter { it.isNotBlank() }
        if (segments.take(base.size) != base) return null
        return normalizePath(segments.drop(base.size).joinToString("/"))
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
                        onProgress((uploaded * 100 / total).toInt().coerceAtMost(99))
                    }
                }
            }
        }
    }

    companion object {
        private const val DAV_NAMESPACE = "DAV:"
        private val PROPFIND_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:displayname />
                <d:resourcetype />
              </d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody("application/xml; charset=utf-8".toMediaType())
        private val PROPFIND_FILE_SIZES_BODY = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop>
                <d:getcontentlength />
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
        get() = url.toHttpUrlOrNull() != null && username.isNotBlank() && password.isNotBlank()
}

data class CloudSyncDirectory(
    val name: String,
    val path: String,
)

internal class CloudUploadConflictException(fileName: String) :
    IllegalStateException("WebDAV already contains a different file named $fileName")

fun normalizePath(path: String): String {
    val normalized = path
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() }
        .joinToString(separator = "/", prefix = "/")
    return normalized.takeUnless { it.isBlank() } ?: "/"
}
