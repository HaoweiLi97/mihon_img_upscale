package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.hippo.unifile.UniFile
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.core.common.preference.AndroidPreferenceStore
import tachiyomi.domain.download.service.CloudSyncStorage
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import kotlin.io.path.createTempDirectory

@RunWith(AndroidJUnit4::class)
class OneDriveStorageIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun microsoftLoginUsesPkceAndRejectsAnUnsolicitedCallback() = runBlocking {
        val dir = createTempDirectory(context.cacheDir.toPath(), "onedrive-oauth").toFile()
        val wrapped = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = dir
        }
        val prefs = DownloadPreferences(AndroidPreferenceStore(context.getSharedPreferences(dir.name, 0)))
        val auth = OneDriveAuth(wrapped, prefs)
        try {
            val clientId = UUID.randomUUID().toString()
            val authorize = Uri.parse(auth.authorizationUrl(clientId))
            assertEquals("login.microsoftonline.com", authorize.host)
            assertEquals("S256", authorize.getQueryParameter("code_challenge_method"))
            assertTrue(authorize.getQueryParameter("code_challenge").orEmpty().isNotBlank())
            assertEquals(auth.redirectUri, authorize.getQueryParameter("redirect_uri"))
            assertEquals(clientId, authorize.getQueryParameter("client_id"))

            val forged = Uri.parse(auth.redirectUri).buildUpon()
                .appendQueryParameter("state", "unrelated")
                .appendQueryParameter("code", "unsolicited")
                .build()
            assertFalse(auth.completeLogin(forged))
            assertEquals("", prefs.cloudSyncOneDriveId().get())
            assertTrue(File(dir, "onedrive-login").exists())

            // Microsoft adds a slash to the custom URI when sending the user back to Android.
            val denied = Uri.parse("${auth.redirectUri}/").buildUpon()
                .appendQueryParameter("state", authorize.getQueryParameter("state"))
                .appendQueryParameter("error", "access_denied")
                .build()
            assertFalse(auth.completeLogin(denied))
            assertFalse(File(dir, "onedrive-login").exists())
        } finally {
            dir.deleteRecursively()
            context.deleteSharedPreferences(dir.name)
        }
    }

    @Test
    fun switchingProvidersKeepsTheLegacyWebDavTargetAndIsolatesOneDriveAccounts() {
        val preferencesName = "storage-test-${UUID.randomUUID()}"
        val store = AndroidPreferenceStore(context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE))
        val prefs = DownloadPreferences(store)
        try {
            prefs.cloudSyncEnabled().set(true)
            prefs.cloudSyncUrl().set("https://example.com/dav/")
            prefs.cloudSyncUsername().set("alice")
            prefs.cloudSyncPassword().set("fixture")
            prefs.cloudSyncDestination().set("/books")
            prefs.cloudSyncPreferRemoteDownloads().set(false)
            val legacy = MessageDigest.getInstance("SHA-256")
                .digest("https://example.com/dav\u0000alice\u0000books".toByteArray())
                .toHexString()
            assertEquals(legacy, prefs.activeCloudSyncTarget()?.id)
            prefs.markChaptersUploadedToCloud(listOf(42), legacy)

            prefs.cloudSyncStorage().set(CloudSyncStorage.OneDrive)
            assertNull(prefs.activeCloudSyncTarget())
            prefs.cloudSyncOneDriveId().set("drive-a")
            assertNull(prefs.activeCloudSyncTarget())
            prefs.cloudSyncDestination().set("/Comics")
            val firstDrive = checkNotNull(prefs.activeCloudSyncTarget())
            assertEquals(OneDriveConfig("drive-a"), firstDrive.config)
            assertFalse(prefs.isChapterUploadedToCloud(42, firstDrive.id))

            prefs.cloudSyncOneDriveId().set("drive-b")
            assertNotEquals(firstDrive.id, prefs.activeCloudSyncTarget()?.id)
            prefs.cloudSyncStorage().set(CloudSyncStorage.WebDav)
            assertEquals("/books", prefs.cloudSyncDestination().get())
            assertEquals(legacy, prefs.activeCloudSyncTarget()?.id)
            assertTrue(prefs.isChapterUploadedToCloud(42, prefs.cloudSyncTargetId()))
            assertFalse(prefs.cloudSyncPreferRemoteDownloads().get())
            prefs.cloudSyncStorage().set(CloudSyncStorage.OneDrive)
            assertEquals("/Comics", prefs.cloudSyncDestination().get())
        } finally {
            context.deleteSharedPreferences(preferencesName)
        }
    }

    @Test
    fun listsBothPagesAndDownloadsWithoutSharingTheGraphToken() = runBlocking {
        val data = byteArrayOf(1, 2, 3)
        var token = "expired"
        val remote = OneDriveStorage(
            "drive-a",
            api = client { request ->
                when (request.url.encodedPath) {
                    "/v1.0/drives/drive-a/root/children" -> when (request.url.queryParameter("page")) {
                        "2" -> {
                            assertEquals("Bearer fresh", request.header("Authorization"))
                            response(request, 200, """{"value":[{"name":"漫画","folder":{}}]}""")
                        }
                        else -> {
                            if (request.header("Authorization") == "Bearer expired") {
                                response(request, 401)
                            } else {
                                response(
                                    request,
                                    200,
                                    """
                                    {
                                      "value":[{"name":"Archive","folder":{}},{"name":"shared","folder":{},"remoteItem":{}}],
                                      "@odata.nextLink":"https://graph.microsoft.com/v1.0/drives/drive-a/root/children?page=2"
                                    }
                                    """.trimIndent(),
                                )
                            }
                        }
                    }
                    "/v1.0/drives/drive-a/root:/%E6%BC%AB%E7%94%BB/Chapter%201%20%231.cbz:/content" ->
                        response(request, 302, location = "https://download.example/chapter")
                    "/v1.0/drives/drive-a/root:/%E6%BC%AB%E7%94%BB/missing.cbz:/content" -> response(request, 404)
                    else -> error("Unexpected request: $request")
                }
            },
            transfer = client { request ->
                assertEquals("https://download.example/chapter", request.url.toString())
                assertNull(request.header("Authorization"))
                response(request, 200).newBuilder().body(data.toResponseBody()).build()
            },
        ) { rejected ->
            if (rejected == "expired") token = "fresh"
            token
        }

        assertEquals(listOf("/Archive", "/漫画"), remote.listDirectories("/").map { it.path })
        withFile(byteArrayOf()) { file, uniFile ->
            assertTrue(remote.downloadCbz("/漫画", "Chapter 1 #1.cbz", uniFile))
            assertArrayEquals(data, file.readBytes())
            assertFalse(remote.downloadCbz("/漫画", "missing.cbz", uniFile))
            assertArrayEquals(data, file.readBytes())
        }
    }

    @Test
    fun uploadCreatesMissingFolderAndSendsOrderedRangesWithoutReplacingAChapter() = runBlocking {
        val bytes = ByteArray(OneDriveStorage.CHUNK_SIZE + 17) { (it % 233).toByte() }
        val uploaded = Buffer()
        val ranges = mutableListOf<String?>()
        val progress = mutableListOf<Int>()
        val remote = OneDriveStorage(
            "drive-a",
            api = client { request ->
                val path = request.url.encodedPath
                when {
                    request.method == "GET" && path.endsWith("/root:/Books") -> response(request, 404)
                    request.method == "GET" && path.endsWith("/root") ->
                        response(request, 200, """{"id":"root-id","folder":{}}""")
                    request.method == "POST" && path.endsWith("/items/root-id/children") -> {
                        assertEquals("Books", request.jsonBody().getString("name"))
                        response(request, 201, """{"id":"folder-id","folder":{}}""")
                    }
                    request.method == "POST" && path.endsWith("/items/folder-id:/Chapter.cbz:/createUploadSession") -> {
                        assertEquals(
                            "fail",
                            request.jsonBody().getJSONObject("item").getString("@microsoft.graph.conflictBehavior"),
                        )
                        response(request, 200, """{"uploadUrl":"https://upload.example/session"}""")
                    }
                    else -> error("Unexpected request: $request")
                }
            },
            transfer = client { request ->
                assertEquals("https://upload.example/session", request.url.toString())
                assertNull(request.header("Authorization"))
                ranges += request.header("Content-Range")
                checkNotNull(request.body).writeTo(uploaded)
                response(request, if (ranges.size == 1) 202 else 201)
            },
        ) { "token" }

        withFile(bytes) { _, file -> remote.uploadCbz("/Books", file, "Chapter.cbz", progress::add) }
        val chunk = OneDriveStorage.CHUNK_SIZE
        assertEquals(
            listOf("bytes 0-${chunk - 1}/${bytes.size}", "bytes $chunk-${bytes.lastIndex}/${bytes.size}"),
            ranges,
        )
        assertArrayEquals(bytes, uploaded.readByteArray())
        assertEquals(0, progress.first())
        assertEquals(100, progress.last())
    }

    @Test
    fun conflictAcceptsIdenticalContentButKeepsDifferentContent() = runBlocking {
        val bytes = byteArrayOf(7, 8)
        val remote = OneDriveStorage(
            "drive-a",
            api = client { request ->
                val path = request.url.encodedPath
                when {
                    path.endsWith("/root:/Books") -> response(request, 200, """{"id":"folder-id","folder":{}}""")
                    path.endsWith("/createUploadSession") -> response(request, 409)
                    path.endsWith(
                        "/content",
                    ) -> response(request, 200).newBuilder().body(bytes.toResponseBody()).build()
                    else -> error("Unexpected request: $request")
                }
            },
        ) { "token" }
        withFile(bytes) { _, file -> remote.uploadCbz("/Books", file, "Chapter.cbz") {} }
        withFile(byteArrayOf(1)) { _, file ->
            val failure = runCatching { remote.uploadCbz("/Books", file, "Chapter.cbz") {} }.exceptionOrNull()
            assertTrue(failure is CloudUploadConflictException)
        }
    }

    @Test
    fun removesOnlyTheFolderResolvedInTheActiveDrive() = runBlocking {
        val remote = OneDriveStorage(
            "drive-a",
            api = client { request ->
                when {
                    request.method == "GET" && request.url.encodedPath == "/v1.0/drives/drive-a/root:/Books/Manga" ->
                        response(request, 200, """{"id":"manga-id","folder":{},"eTag":"expected"}""")
                    request.method == "DELETE" && request.url.encodedPath == "/v1.0/drives/drive-a/items/manga-id" -> {
                        assertEquals("expected", request.header("If-Match"))
                        response(request, 204)
                    }
                    else -> error("Unexpected request: $request")
                }
            },
        ) { "token" }
        assertTrue(runCatching { remote.deleteMangaCollection("/") }.exceptionOrNull() is IllegalArgumentException)
        remote.deleteMangaCollection("/Books/Manga")
    }

    private inline fun withFile(bytes: ByteArray, block: (File, UniFile) -> Unit) {
        val file = File.createTempFile("onedrive", ".cbz", context.cacheDir)
        try {
            file.writeBytes(bytes)
            block(file, checkNotNull(UniFile.fromFile(file)))
        } finally {
            file.delete()
        }
    }

    private fun client(respond: (Request) -> Response): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor {
            try {
                respond(it.request())
            } catch (error: AssertionError) {
                throw IOException("Unexpected Graph request", error)
            } catch (error: IllegalStateException) {
                throw IOException("Unexpected Graph request", error)
            }
        }
        .build()

    private fun response(request: Request, code: Int, json: String = "", location: String? = null): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("Graph fixture")
            .body(json.toResponseBody())
            .apply { location?.let { header("Location", it) } }
            .build()

    private fun Request.jsonBody() = JSONObject(Buffer().also { checkNotNull(body).writeTo(it) }.readUtf8())
}
