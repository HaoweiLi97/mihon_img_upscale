package mihon.domain.extensionrepo.service

import kotlinx.serialization.Serializable
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.JsonNames
import kotlinx.serialization.SerialName
import kotlinx.serialization.protobuf.ProtoNumber
import mihon.domain.extensionrepo.model.ExtensionRepo

@Serializable
data class ExtensionRepoMetaDto(
    @SerialName("index_v2") val indexV2: String? = null,
    val meta: ExtensionRepoDto,
)

@Serializable
data class ExtensionRepoDto(
    val name: String,
    val shortName: String?,
    val website: String,
    val signingKeyFingerprint: String,
)

fun ExtensionRepoMetaDto.toExtensionRepo(baseUrl: String): ExtensionRepo {
    return ExtensionRepo(
        baseUrl = baseUrl,
        name = meta.name,
        shortName = meta.shortName,
        website = meta.website,
        signingKeyFingerprint = meta.signingKeyFingerprint,
    )
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExtensionStoreIndex(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) @JsonNames("badge_label") val badgeLabel: String,
    @ProtoNumber(3) val signingKey: String,
    @ProtoNumber(4) val contact: Contact,
    @ProtoNumber(101) @JsonNames("extension_list") val extensionList: ExtensionList? = null,
    @ProtoNumber(102) @JsonNames("extension_list_url") val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String,
        @ProtoNumber(2) val discord: String? = null,
    )

    @Serializable
    data class ExtensionList(@ProtoNumber(1) val extensions: List<Extension>)

    @Serializable
    data class Extension(
        @ProtoNumber(1) val name: String,
        @ProtoNumber(2) val packageName: String,
        @ProtoNumber(3) val resources: Resources,
        @ProtoNumber(4) @JsonNames("extension_lib") val extensionLib: String,
        @ProtoNumber(5) val versionCode: Long,
        @ProtoNumber(6) val versionName: String,
        @ProtoNumber(7) @JsonNames("content_warning") val contentWarning: ContentWarning = ContentWarning.UNSPECIFIED,
        @ProtoNumber(8) val sources: List<Source>,
    )

    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String,
        @ProtoNumber(2) val iconUrl: String,
    )

    @Serializable
    data class Source(
        @ProtoNumber(1) val id: Long,
        @ProtoNumber(2) val name: String,
        @ProtoNumber(3) val language: String,
        @ProtoNumber(4) @JsonNames("home_url") val homeUrl: String = "",
    )

    @Serializable
    enum class ContentWarning {
        @ProtoNumber(0) @JsonNames("CONTENT_WARNING_UNSPECIFIED") UNSPECIFIED,
        @ProtoNumber(1) @JsonNames("CONTENT_WARNING_SAFE") SAFE,
        @ProtoNumber(2) @JsonNames("CONTENT_WARNING_MIXED") MIXED,
        @ProtoNumber(3) @JsonNames("CONTENT_WARNING_NSFW") NSFW,
    }
}

fun ExtensionStoreIndex.toExtensionRepo(indexUrl: String): ExtensionRepo = ExtensionRepo(
    baseUrl = indexUrl,
    name = name,
    shortName = badgeLabel,
    website = contact.website,
    signingKeyFingerprint = signingKey,
)
