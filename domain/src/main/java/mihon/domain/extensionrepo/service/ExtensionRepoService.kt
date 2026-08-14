package mihon.domain.extensionrepo.service

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.domain.extensionrepo.model.ExtensionRepo
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

@OptIn(ExperimentalSerializationApi::class)
class ExtensionRepoService(
    networkHelper: NetworkHelper,
    private val json: Json,
) {
    val client = networkHelper.client

    suspend fun fetchRepoDetails(indexUrl: String): ExtensionRepo? = fetchRepoDetails(indexUrl, forceStore = false)

    private suspend fun fetchRepoDetails(indexUrl: String, forceStore: Boolean): ExtensionRepo? {
        return withIOContext {
            try {
                if (indexUrl.endsWith(".pb") || indexUrl.endsWith(".pb.gz")) {
                    val bytes = client.newCall(GET(indexUrl)).awaitSuccess().use { it.body.bytes() }
                    val store = ProtoBuf.decodeFromByteArray<ExtensionStoreIndex>(bytes.decompressGzip())
                    return@withIOContext store.toExtensionRepo(indexUrl)
                }

                val repoUrl = indexUrl.removeSuffix("/index.min.json")
                val repoIndexUrl = if (indexUrl.endsWith("/repo.json")) indexUrl else "$repoUrl/repo.json"
                val body = client.newCall(GET(repoIndexUrl)).awaitSuccess().use { it.body.bytes() }
                val decompressedBody = body.decompressGzip()
                val store = runCatching {
                    json.decodeFromString<ExtensionStoreIndex>(decompressedBody.decodeToString())
                        .toExtensionRepo(repoIndexUrl)
                }.getOrNull()
                if (store != null) {
                    return@withIOContext store
                }

                check(!forceStore) { "Extension store index is not in the current format" }
                val legacyRepo = json.decodeFromString<ExtensionRepoMetaDto>(decompressedBody.decodeToString())
                legacyRepo.indexV2?.let { indexV2 ->
                    return@withIOContext fetchRepoDetails(resolveUrl(repoIndexUrl, indexV2), forceStore = true)
                }
                legacyRepo.toExtensionRepo(baseUrl = repoUrl)
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Failed to fetch repo details" }
                null
            }
        }
    }

    private fun ByteArray.decompressGzip(): ByteArray {
        if (size < 2 || this[0] != 0x1f.toByte() || this[1] != 0x8b.toByte()) return this
        return java.util.zip.GZIPInputStream(inputStream()).use { it.readBytes() }
    }

    private fun resolveUrl(baseUrl: String, url: String): String =
        url.toHttpUrlOrNull()?.toString() ?: baseUrl.toHttpUrlOrNull()?.resolve(url)?.toString() ?: url
}
