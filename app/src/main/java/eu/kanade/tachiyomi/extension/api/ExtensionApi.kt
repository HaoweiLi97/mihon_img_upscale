package eu.kanade.tachiyomi.extension.api

import android.content.Context
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.LoadResult
import eu.kanade.tachiyomi.extension.util.ExtensionLoader
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import logcat.LogPriority
import mihon.domain.extensionrepo.interactor.GetExtensionRepo
import mihon.domain.extensionrepo.interactor.UpdateExtensionRepo
import mihon.domain.extensionrepo.model.ExtensionRepo
import mihon.domain.extensionrepo.service.ExtensionStoreIndex
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import uy.kohesive.injekt.injectLazy
import java.time.Instant
import kotlin.time.Duration.Companion.days

internal class ExtensionApi {

    private val networkService: NetworkHelper by injectLazy()
    private val preferenceStore: PreferenceStore by injectLazy()
    private val getExtensionRepo: GetExtensionRepo by injectLazy()
    private val updateExtensionRepo: UpdateExtensionRepo by injectLazy()
    private val extensionManager: ExtensionManager by injectLazy()
    private val json: Json by injectLazy()

    private val lastExtCheck: Preference<Long> by lazy {
        preferenceStore.getLong(Preference.appStateKey("last_ext_check"), 0)
    }

    suspend fun findExtensions(): List<Extension.Available> {
        return withIOContext {
            getExtensionRepo.getAll()
                .map { async { getExtensions(it) } }
                .awaitAll()
                .flatten()
        }
    }

    private suspend fun getExtensions(extRepo: ExtensionRepo): List<Extension.Available> {
        return try {
            when {
                extRepo.baseUrl.endsWith(".pb") || extRepo.baseUrl.endsWith(".pb.gz") -> {
                    fetchStoreExtensions(extRepo.baseUrl, extRepo.baseUrl)
                }
                extRepo.baseUrl.endsWith("/repo.json") -> {
                    runCatching {
                        getStoreExtensions(fetchStore(extRepo.baseUrl), extRepo.baseUrl)
                    }.getOrElse {
                        fetchLegacyExtensions(extRepo.baseUrl.removeSuffix("/repo.json"))
                    }
                }
                else -> {
                    val repoBaseUrl = extRepo.baseUrl
                    networkService.client.newCall(GET("$repoBaseUrl/index.min.json"))
                        .awaitSuccess()
                        .use { response ->
                            with(json) {
                                response.parseAs<List<ExtensionJsonObject>>().toExtensions(repoBaseUrl)
                            }
                        }
                }
            }
        } catch (e: Throwable) {
            logcat(LogPriority.ERROR, e) { "Failed to get extensions from ${extRepo.baseUrl}" }
            emptyList()
        }
    }

    private suspend fun fetchStoreExtensions(indexUrl: String, repoUrl: String): List<Extension.Available> {
        return getStoreExtensions(fetchStore(indexUrl), repoUrl)
    }

    private suspend fun getStoreExtensions(store: ExtensionStoreIndex, repoUrl: String): List<Extension.Available> {
        val list: ExtensionStoreIndex.ExtensionList = store.extensionList ?: store.extensionListUrl?.let { url ->
            fetchExtensionList(resolveUrl(repoUrl, url))
        } ?: ExtensionStoreIndex.ExtensionList(emptyList())
        return list.toExtensions(repoUrl)
    }

    private suspend fun fetchStore(url: String): ExtensionStoreIndex {
        val bytes = networkService.client.newCall(GET(url)).awaitSuccess().use { it.body.bytes() }.decompressGzip()
        return if (bytes.firstNonWhitespace() == '{'.code.toByte()) {
            json.decodeFromString(bytes.decodeToString())
        } else {
            ProtoBuf.decodeFromByteArray(bytes)
        }
    }

    private suspend fun fetchExtensionList(url: String): ExtensionStoreIndex.ExtensionList {
        val bytes = networkService.client.newCall(GET(url)).awaitSuccess().use { it.body.bytes() }.decompressGzip()
        return if (bytes.firstNonWhitespace() == '{'.code.toByte()) {
            json.decodeFromString(bytes.decodeToString())
        } else {
            ProtoBuf.decodeFromByteArray(bytes)
        }
    }

    private suspend fun fetchLegacyExtensions(repoBaseUrl: String): List<Extension.Available> {
        return networkService.client.newCall(GET("$repoBaseUrl/index.min.json"))
            .awaitSuccess()
            .use { response ->
                with(json) {
                    response.parseAs<List<ExtensionJsonObject>>().toExtensions(repoBaseUrl)
                }
            }
    }

    private fun resolveUrl(baseUrl: String, url: String): String =
        url.toHttpUrlOrNull()?.toString() ?: baseUrl.toHttpUrlOrNull()?.resolve(url)?.toString() ?: url

    private fun ByteArray.decompressGzip(): ByteArray {
        if (size < 2 || this[0] != 0x1f.toByte() || this[1] != 0x8b.toByte()) return this
        return java.util.zip.GZIPInputStream(inputStream()).use { it.readBytes() }
    }

    private fun ByteArray.firstNonWhitespace(): Byte? = firstOrNull { it > 0x20 }

    suspend fun checkForUpdates(
        context: Context,
        fromAvailableExtensionList: Boolean = false,
    ): List<Extension.Installed>? {
        // Limit checks to once a day at most
        if (!fromAvailableExtensionList &&
            Instant.now().toEpochMilli() < lastExtCheck.get() + 1.days.inWholeMilliseconds
        ) {
            return null
        }

        // Update extension repo details
        updateExtensionRepo.awaitAll()

        val extensions = if (fromAvailableExtensionList) {
            extensionManager.availableExtensionsFlow.value
        } else {
            findExtensions().also { lastExtCheck.set(Instant.now().toEpochMilli()) }
        }

        val installedExtensions = ExtensionLoader.loadExtensions(context)
            .filterIsInstance<LoadResult.Success>()
            .map { it.extension }

        val extensionsWithUpdate = mutableListOf<Extension.Installed>()
        for (installedExt in installedExtensions) {
            val pkgName = installedExt.pkgName
            val availableExt = extensions.find { it.pkgName == pkgName } ?: continue
            val hasUpdatedVer = availableExt.versionCode > installedExt.versionCode
            val hasUpdatedLib = availableExt.libVersion > installedExt.libVersion
            val hasUpdate = hasUpdatedVer || hasUpdatedLib
            if (hasUpdate) {
                extensionsWithUpdate.add(installedExt)
            }
        }

        if (extensionsWithUpdate.isNotEmpty()) {
            ExtensionUpdateNotifier(context).promptUpdates(extensionsWithUpdate.map { it.name })
        }

        return extensionsWithUpdate
    }

    private fun List<ExtensionJsonObject>.toExtensions(repoUrl: String): List<Extension.Available> {
        return this
            .filter {
                val libVersion = it.extractLibVersion()
                ExtensionLoader.isSupportedLibVersion(libVersion)
            }
            .map {
                Extension.Available(
                    name = it.name.substringAfter("Tachiyomi: "),
                    pkgName = it.pkg,
                    versionName = it.version,
                    versionCode = it.code,
                    libVersion = it.extractLibVersion(),
                    lang = it.lang,
                    isNsfw = it.nsfw == 1,
                    sources = it.sources?.map(extensionSourceMapper).orEmpty(),
                    apkName = it.apk,
                    iconUrl = "$repoUrl/icon/${it.pkg}.png",
                    repoUrl = repoUrl,
                )
            }
    }

    fun getApkUrl(extension: Extension.Available): String {
        return extension.apkName.toHttpUrlOrNull()?.toString() ?: "${extension.repoUrl}/apk/${extension.apkName}"
    }

    private fun ExtensionJsonObject.extractLibVersion(): Double {
        return version.substringBeforeLast('.').toDouble()
    }

    private fun ExtensionStoreIndex.ExtensionList.toExtensions(repoUrl: String): List<Extension.Available> {
        return extensions
            .filter {
                val version = it.extensionLib.toDoubleOrNull() ?: return@filter false
                ExtensionLoader.isSupportedLibVersion(version)
            }
            .map { extension ->
                val languages = extension.sources.map { it.language }.toSet()
                Extension.Available(
                    name = extension.name,
                    pkgName = extension.packageName,
                    versionName = extension.versionName,
                    versionCode = extension.versionCode,
                    libVersion = extension.extensionLib.toDouble(),
                    lang = languages.singleOrNull() ?: "all",
                    isNsfw = extension.contentWarning >= ExtensionStoreIndex.ContentWarning.MIXED,
                    sources = extension.sources.map {
                        Extension.Available.Source(it.id, it.language, it.name, it.homeUrl)
                    },
                    apkName = extension.resources.apkUrl,
                    iconUrl = extension.resources.iconUrl,
                    repoUrl = repoUrl,
                )
            }
    }
}

@Serializable
private data class ExtensionJsonObject(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Long,
    val version: String,
    val nsfw: Int,
    val sources: List<ExtensionSourceJsonObject>?,
)

@Serializable
private data class ExtensionSourceJsonObject(
    val id: Long,
    val lang: String,
    val name: String,
    val baseUrl: String,
)

private val extensionSourceMapper: (ExtensionSourceJsonObject) -> Extension.Available.Source = {
    Extension.Available.Source(
        id = it.id,
        lang = it.lang,
        name = it.name,
        baseUrl = it.baseUrl,
    )
}
