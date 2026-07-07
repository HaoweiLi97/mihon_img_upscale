package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.await
import eu.kanade.tachiyomi.network.jsonMime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
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
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

class CloudSyncService(
    private val networkHelper: NetworkHelper = Injekt.get(),
) {

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
        onProgress: (Int) -> Unit,
    ) = withIOContext {
        val fileName = file.name ?: error("Missing upload file name")
        var alistFailure: Throwable? = null

        config.alistApiBaseUrlOrNull()?.let { alistApiBaseUrl ->
            val result = runCatching {
                uploadAlistApiCbz(
                    config = config,
                    alistApiBaseUrl = alistApiBaseUrl,
                    remoteDirectory = remoteDirectory,
                    fileName = fileName,
                    file = file,
                    onProgress = onProgress,
                )
            }
            if (result.isSuccess) {
                return@withIOContext
            }
            alistFailure = result.exceptionOrNull()
        }

        runCatching {
            uploadWebDavCbz(
                config = config,
                remoteDirectory = remoteDirectory,
                fileName = fileName,
                file = file,
                onProgress = onProgress,
            )
        }.onFailure { error ->
            alistFailure?.let(error::addSuppressed)
        }.getOrThrow()
    }

    private suspend fun uploadWebDavCbz(
        config: CloudSyncConfig,
        remoteDirectory: String,
        fileName: String,
        file: UniFile,
        onProgress: (Int) -> Unit,
    ) {
        ensureDirectory(config, remoteDirectory)

        onProgress(0)
        val request = Request.Builder()
            .url(config.resolveUrl(remoteDirectory, fileName))
            .headers(config.authHeaders())
            .put(UniFileRequestBody(file, CBZ_MEDIA_TYPE, onProgress))
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            if (response.code !in SUCCESS_CODES) {
                throw IllegalStateException("WebDAV upload failed: HTTP ${response.code}")
            }
        }
        onProgress(100)
    }

    private suspend fun uploadAlistApiCbz(
        config: CloudSyncConfig,
        alistApiBaseUrl: HttpUrl,
        remoteDirectory: String,
        fileName: String,
        file: UniFile,
        onProgress: (Int) -> Unit,
    ) {
        ensureDirectory(config, remoteDirectory)

        val token = loginAlist(config, alistApiBaseUrl)
        val requestBody = UniFileRequestBody(file, CBZ_MEDIA_TYPE, onProgress)
        val remoteFilePath = normalizePath("${normalizePath(remoteDirectory)}/$fileName")

        onProgress(0)
        val request = Request.Builder()
            .url(alistApiBaseUrl.resolveAlistApi("fs", "put"))
            .header("Authorization", token)
            .header("File-Path", remoteFilePath.urlEncode())
            .header("Content-Length", requestBody.contentLength().toString())
            .put(requestBody)
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("aList upload failed: HTTP ${response.code}")
            }
            response.body.string().throwIfAlistApiFailed("aList upload failed")
        }
        onProgress(100)
    }

    private suspend fun loginAlist(config: CloudSyncConfig, alistApiBaseUrl: HttpUrl): String {
        val payload = buildJsonObject {
            put("username", config.username)
            put("password", config.password)
        }
        val request = Request.Builder()
            .url(alistApiBaseUrl.resolveAlistApi("auth", "login"))
            .post(payload.toString().toRequestBody(jsonMime))
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("aList login failed: HTTP ${response.code}")
            }

            val body = response.body.string()
            body.throwIfAlistApiFailed("aList login failed")

            val token = Json.parseToJsonElement(body)
                .jsonObject["data"]
                ?.jsonObject
                ?.get("token")
                ?.jsonPrimitive
                ?.contentOrNull

            return token?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("aList login failed: missing token")
        }
    }

    private suspend fun ensureDirectory(config: CloudSyncConfig, path: String) {
        var currentPath = ""
        normalizePath(path)
            .trim('/')
            .split('/')
            .filter { it.isNotBlank() }
            .forEach { segment ->
                currentPath = normalizePath("$currentPath/$segment")
                mkcol(config, currentPath)
            }
    }

    private suspend fun mkcol(config: CloudSyncConfig, path: String) {
        val request = Request.Builder()
            .url(config.resolveUrl(path, trailingSlash = true))
            .headers(config.authHeaders())
            .method("MKCOL", EMPTY_BODY)
            .build()

        networkHelper.client.newCall(request).await().use { response ->
            if (response.code !in MKCOL_SUCCESS_CODES) {
                throw IllegalStateException("WebDAV MKCOL failed: HTTP ${response.code}")
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

    private fun CloudSyncConfig.alistApiBaseUrlOrNull(): HttpUrl? {
        val base = url.toHttpUrl()
        val pathSegments = base.pathSegments.filter { it.isNotBlank() }
        val davIndex = pathSegments.indexOfFirst { it.equals("dav", ignoreCase = true) }
        val looksLikeAlist = davIndex >= 0 || base.host.contains("alist", ignoreCase = true)
        if (!looksLikeAlist) return null

        val apiBaseSegments = if (davIndex >= 0) {
            pathSegments.take(davIndex)
        } else {
            pathSegments
        }
        return base.newBuilder()
            .encodedPath("/")
            .apply { apiBaseSegments.forEach(::addPathSegment) }
            .build()
    }

    private fun HttpUrl.resolveAlistApi(vararg pathSegments: String): HttpUrl {
        return newBuilder()
            .addPathSegment("api")
            .apply { pathSegments.forEach(::addPathSegment) }
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

    private fun String.urlEncode(): String {
        return URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
    }

    private fun String.throwIfAlistApiFailed(messagePrefix: String) {
        if (isBlank()) return

        val jsonObject = runCatching { Json.parseToJsonElement(this).jsonObject }.getOrNull() ?: return
        val code = jsonObject["code"]?.jsonPrimitive?.intOrNull ?: return
        if (code == ALIST_SUCCESS_CODE) return

        val message = jsonObject["message"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?: "code $code"
        throw IllegalStateException("$messagePrefix: $message")
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
        private val SUCCESS_CODES = setOf(200, 201, 204)
        private val MKCOL_SUCCESS_CODES = setOf(200, 201, 204, 405)
        private const val ALIST_SUCCESS_CODE = 200
        private const val SEGMENT_SIZE = 8L * 1024L
    }
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
