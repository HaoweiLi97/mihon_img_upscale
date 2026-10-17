package mihon.data.extension.model

import io.kotest.matchers.shouldBe
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class NetworkExtensionStoreTest {

    @Test
    fun `protobuf store index decodes extension metadata`() {
        val store = NetworkExtensionStore(
            name = "Test store",
            badgeLabel = "Test",
            signingKey = "fingerprint",
            contact = NetworkExtensionStore.Contact(website = "https://example.com", discord = null),
            extensionListUrl = null,
            extensionList = NetworkExtensionStore.ExtensionList(
                listOf(
                    NetworkExtensionStore.Extension(
                        name = "Test extension",
                        packageName = "example.extension",
                        resources = NetworkExtensionStore.Resources(
                            apkUrl = "https://example.com/extension.apk",
                            iconUrl = "https://example.com/icon.png",
                        ),
                        extensionLib = "1.5",
                        versionCode = 1,
                        versionName = "1.0.0",
                        contentWarning = NetworkExtensionStore.ContentWarning.SAFE,
                        sources = listOf(
                            NetworkExtensionStore.Source(
                                id = 1,
                                name = "Test source",
                                language = "en",
                                homeUrl = "https://example.com",
                            ),
                        ),
                    ),
                ),
            ),
        )

        val encoded = ProtoBuf.encodeToByteArray(NetworkExtensionStore.serializer(), store)
        val decoded = ProtoBuf.decodeFromByteArray(NetworkExtensionStore.serializer(), encoded)

        decoded shouldBe store
    }

    @Test
    fun `legacy repo exposes index v2 subscription url`() {
        val repo = Json.decodeFromString<NetworkLegacyExtensionRepo>(
            """
            {
              "index_v2": "https://example.com/index.pb",
              "meta": {
                "name": "Test store",
                "shortName": "Test",
                "website": "https://example.com",
                "signingKeyFingerprint": "fingerprint"
              }
            }
            """.trimIndent(),
        )

        repo.indexV2 shouldBe "https://example.com/index.pb"
    }
}
