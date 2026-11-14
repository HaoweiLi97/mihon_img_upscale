package eu.kanade.tachiyomi.data.download

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import tachiyomi.core.common.util.lang.withIOContext
import java.io.DataInputStream
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration.Companion.seconds

internal class OneDriveStorage(
    driveId: String,
    private val api: OkHttpClient = OneDriveHttp.api,
    private val transfer: OkHttpClient = OneDriveHttp.transfer,
    private val accessToken: suspend (rejected: String?) -> String,
) : CloudStorage {
    private val drive = OneDriveHttp.graph.newBuilder().addPathSegment("drives").addPathSegment(driveId).build()

    override suspend fun listDirectories(path: String): List<CloudSyncDirectory> = withIOContext {
        val normalized = normalizePath(path)
        val directories = mutableListOf<CloudSyncDirectory>()
        check(
            visitChildren(normalized) { item ->
                if (item.isLocalFolder()) {
                    val name = item.getString("name")
                    directories += CloudSyncDirectory(name, normalizePath("$normalized/$name"))
                }
                true
            },
        ) { "OneDrive folder was not found" }
        directories.sortedBy { it.name.lowercase() }
    }

    override suspend fun uploadFile(
        remoteDirectory: String,
        file: UniFile,
        fileName: String,
        overwrite: Boolean,
        onProgress: (Int) -> Unit,
    ): Boolean = withIOContext {
        val existing = lookup(itemUrl(remoteDirectory, fileName))
        if (existing != null) {
            check(existing.isLocalFile()) { "The OneDrive item is not a file in your drive" }
            if (!overwrite) return@withIOContext false
            val request = Request.Builder().url(itemById(existing.getString("id"))).delete()
            existing.optString("eTag").takeIf { it.isNotBlank() }?.let { request.header("If-Match", it) }
            graph(request.build()).use { response ->
                if (response.code != 404) response.requireSuccess("file removal")
            }
        }
        onProgress(0)
        val uploaded = upload(remoteDirectory, file, fileName, onProgress)
        if (!uploaded && overwrite) error("The remote file changed during upload: $fileName")
        if (uploaded) onProgress(100)
        uploaded
    }

    override suspend fun downloadCbz(
        remoteDirectory: String,
        fileName: String,
        file: UniFile,
    ): Boolean = withIOContext {
        // Leave the output untouched if Graph says the cloud copy is missing.
        val response = openFile(remoteDirectory, fileName) ?: return@withIOContext false
        response.use {
            file.openOutputStream().use { output ->
                copyContent(response) { bytes, size -> output.write(bytes, 0, size) }
            }
        }
        true
    }

    override suspend fun remoteFileSize(remoteDirectory: String, fileName: String): Long? = withIOContext {
        lookup(itemUrl(remoteDirectory, fileName))?.takeIf { it.isLocalFile() }?.fileSize()?.takeIf { it > 0 }
    }

    override suspend fun lookupRemoteFileSize(remoteDirectory: String, fileName: String): Long? = withIOContext {
        val file = lookup(itemUrl(remoteDirectory, fileName)) ?: return@withIOContext null
        if (!file.isNull("folder") || !file.isNull("remoteItem")) return@withIOContext null
        check(file.isLocalFile()) { "OneDrive file lookup did not return file metadata" }
        file.fileSize() ?: error("OneDrive file lookup did not return a file size")
    }

    override suspend fun listRemoteFileSizes(remoteDirectory: String, fileNames: Set<String>): Map<String, Long?> =
        withIOContext {
            val requestedNames = fileNames.groupBy(String::lowercase)
            val sizes = mutableMapOf<String, Long?>()
            if (requestedNames.isEmpty()) return@withIOContext sizes
            visitChildren(remoteDirectory) { item ->
                val names = requestedNames[item.optString("name").lowercase()]
                if (names != null && item.isLocalFile()) {
                    val size = item.fileSize()
                    names.forEach { sizes[it] = size }
                }
                sizes.size < fileNames.size
            }
            sizes
        }

    override suspend fun deleteMangaCollection(directory: String) = withIOContext {
        require(normalizePath(directory) != "/")
        val item = lookup(itemUrl(directory)) ?: return@withIOContext
        check(item.isLocalFolder()) { "The OneDrive item is not a folder in your drive" }
        val request = Request.Builder().url(itemById(item.getString("id"))).delete()
        item.optString("eTag").takeIf { it.isNotBlank() }?.let { request.header("If-Match", it) }
        graph(request.build()).use { response ->
            if (response.code != 404) response.requireSuccess("folder removal")
        }
    }

    private suspend fun upload(
        directory: String,
        file: UniFile,
        fileName: String,
        onProgress: (Int) -> Unit,
    ): Boolean {
        requirePathSegment(fileName)
        val length = file.length().takeIf { it > 0 } ?: file.openInputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var length = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                length += count
            }
            length
        }
        check(length > 0) { "Cannot upload an empty file to OneDrive" }
        val parent = ensureDirectory(directory)
        val url = itemById(parent, addressChild = true).newBuilder()
            .addPathSegment("$fileName:")
            .addPathSegment("createUploadSession")
            .build()
        val options = JSONObject().put("@microsoft.graph.conflictBehavior", "fail")
        val request = Request.Builder().url(url).post(JSONObject().put("item", options).jsonBody()).build()
        val session = graph(request).use { response ->
            if (response.code == 409) return false
            response.requireSuccess("upload setup")
            JSONObject(response.body.string()).getString("uploadUrl").let(::requireHttpsUrl)
        }

        var committed = false
        try {
            DataInputStream(file.openInputStream()).use { input ->
                val bytes = ByteArray(CHUNK_SIZE)
                var offset = 0L
                while (offset < length) {
                    coroutineContext.ensureActive()
                    val count = minOf(bytes.size.toLong(), length - offset).toInt()
                    input.readFully(bytes, 0, count)
                    val nextOffset = offset + count
                    val put = Request.Builder()
                        .url(session)
                        .header("Content-Range", "bytes $offset-${nextOffset - 1}/$length")
                        .put(bytes.toRequestBody(BINARY, 0, count))
                        .build()
                    transfer.newCall(put).await().use { response ->
                        if (response.code == 409) return false
                        val expected = if (nextOffset == length) setOf(200, 201) else setOf(202)
                        if (response.code !in expected) throw response.httpFailure("upload")
                    }
                    offset = nextOffset
                    onProgress((offset * 100 / length).toInt().coerceAtMost(99))
                }
            }
            committed = true
            return true
        } finally {
            if (!committed) {
                withContext(NonCancellable) {
                    withTimeoutOrNull(5.seconds) {
                        runCatching {
                            val cancel = Request.Builder().url(session).delete().build()
                            api.newCall(cancel).await().close()
                        }
                    }
                }
            }
        }
    }

    private suspend fun ensureDirectory(directory: String): String {
        val normalized = normalizePath(directory)
        val item = lookup(itemUrl(normalized))
        if (item != null) {
            check(item.isLocalFolder()) { "The OneDrive destination is not a folder in your drive" }
            return item.getString("id")
        }
        check(normalized != "/") { "OneDrive root was not found" }
        val parent = ensureDirectory(normalized.substringBeforeLast('/'))
        val create = JSONObject()
            .put("name", normalized.substringAfterLast('/'))
            .put("folder", JSONObject())
            .put("@microsoft.graph.conflictBehavior", "fail")
        val url = itemById(parent).newBuilder().addPathSegment("children").build()
        graph(Request.Builder().url(url).post(create.jsonBody()).build()).use { response ->
            if (response.code != 409) {
                response.requireSuccess("folder creation")
                return JSONObject(response.body.string()).getString("id")
            }
        }
        // Another chapter upload can create the same folder at the same time.
        val concurrent = checkNotNull(lookup(itemUrl(normalized))?.takeIf { it.isLocalFolder() }) {
            "OneDrive destination conflicts with an existing item"
        }
        return concurrent.getString("id")
    }

    private suspend fun visitChildren(directory: String, visit: (JSONObject) -> Boolean): Boolean {
        var next: HttpUrl? = itemUrl(directory, operation = "children")
        var first = true
        while (next != null) {
            val page = graph(Request.Builder().url(next).build()).use { response ->
                if (first && response.code == 404) return false
                response.requireSuccess("folder listing")
                JSONObject(response.body.string())
            }
            val items = page.getJSONArray("value")
            for (index in 0 until items.length()) {
                if (!visit(items.getJSONObject(index))) return true
            }
            next = if (page.isNull("@odata.nextLink")) null else requireHttpsUrl(page.getString("@odata.nextLink"))
            first = false
        }
        return true
    }

    private suspend fun lookup(url: HttpUrl): JSONObject? = graph(Request.Builder().url(url).build()).use { response ->
        if (response.code == 404) return null
        response.requireSuccess("file lookup")
        JSONObject(response.body.string())
    }

    private suspend fun copyContent(response: Response, consume: (ByteArray, Int) -> Unit) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val input = response.body.byteStream()
        while (true) {
            coroutineContext.ensureActive()
            val count = input.read(buffer)
            if (count == -1) return
            consume(buffer, count)
        }
    }

    private suspend fun openFile(directory: String, fileName: String): Response? {
        val request = Request.Builder().url(itemUrl(directory, fileName, "content")).build()
        val response = graph(request)
        if (response.code == 200) return response
        val location = response.use {
            if (it.code == 404) return null
            if (it.code != 302) throw it.httpFailure("download")
            requireHttpsUrl(checkNotNull(it.header("Location")))
        }
        // The content URL is already authorized. Never send the Graph bearer token to it.
        return transfer.newCall(Request.Builder().url(location).build()).await().also {
            if (it.code != 200) {
                it.close()
                throw it.httpFailure("download")
            }
        }
    }

    private suspend fun graph(request: Request): Response {
        check(request.url.isHttps && request.url.host == OneDriveHttp.graph.host && request.url.port == 443)
        val token = accessToken(null)
        val response = api.newCall(request.authorize(token)).await()
        if (response.code != 401) return response
        response.close()
        return api.newCall(request.authorize(accessToken(token))).await()
    }

    private fun Request.authorize(token: String) = newBuilder().header("Authorization", "Bearer $token").build()

    private fun itemById(id: String, addressChild: Boolean = false): HttpUrl = drive.newBuilder()
        .addPathSegment("items")
        .addPathSegment(if (addressChild) "$id:" else id)
        .build()

    private fun itemUrl(directory: String, fileName: String? = null, operation: String? = null): HttpUrl {
        val segments = normalizePath(directory).split('/').filter(String::isNotEmpty) + listOfNotNull(fileName)
        segments.forEach(::requirePathSegment)
        return drive.newBuilder().apply {
            if (segments.isEmpty()) {
                addPathSegment("root")
            } else {
                addPathSegment("root:")
                segments.dropLast(1).forEach(::addPathSegment)
                addPathSegment(segments.last() + if (operation == null) "" else ":")
            }
            operation?.let(::addPathSegment)
        }.build()
    }

    private fun JSONObject.isLocalFolder() = !isNull("folder") && isNull("remoteItem")

    private fun JSONObject.isLocalFile() = !isNull("file") && isNull("remoteItem")

    private fun JSONObject.fileSize() = optLong("size", -1).takeIf { it >= 0 }

    private fun JSONObject.jsonBody() = toString().toRequestBody(JSON)

    private fun Response.requireSuccess(operation: String) {
        if (!isSuccessful) throw httpFailure(operation)
    }

    private fun Response.httpFailure(operation: String) = CloudStorageHttpException(
        code,
        "OneDrive $operation failed: HTTP $code",
        header("Retry-After")?.toLongOrNull(),
    )

    private fun requireHttpsUrl(value: String): HttpUrl =
        checkNotNull(value.toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }) {
            "OneDrive returned an invalid URL"
        }

    private fun requirePathSegment(segment: String) {
        require(segment.isNotBlank() && segment != "." && segment != ".." && '/' !in segment && '\\' !in segment) {
            "Invalid OneDrive file or folder name"
        }
    }

    companion object {
        // Graph requires every nonfinal chunk to be a multiple of 320 KiB.
        internal const val CHUNK_SIZE = 10 * 320 * 1024
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val BINARY = "application/octet-stream".toMediaType()
    }
}
