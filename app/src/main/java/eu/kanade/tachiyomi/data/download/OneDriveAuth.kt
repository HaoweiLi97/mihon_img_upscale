package eu.kanade.tachiyomi.data.download

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.backup.models.BackupOneDriveSession
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import logcat.LogPriority
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.download.service.CloudSyncStorage
import tachiyomi.domain.download.service.DownloadPreferences
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Inject
@SingleIn(AppScope::class)
class OneDriveAuth(
    context: Context,
    private val preferences: DownloadPreferences,
) {
    val redirectUri = "${context.packageName}://onedrive-auth"
    private val pending = AtomicFile(File(context.noBackupFilesDir, "onedrive-login"))
    private val credentials = OneDriveCredentials(File(context.noBackupFilesDir, "onedrive-session"))
    private val lock = Mutex()
    private var cachedToken: AccessToken? = null

    internal suspend fun exportSessionForBackup(): BackupOneDriveSession? = withIOContext {
        lock.withLock {
            val saved = credentials.read() ?: return@withLock null
            val driveId = saved.getString("driveId")
            if (driveId != preferences.cloudSyncOneDriveId().get()) return@withLock null
            BackupOneDriveSession(
                driveId = driveId,
                clientId = saved.getString("clientId"),
                refreshToken = saved.getString("refresh"),
                destination = preferences.cloudSyncDestination(CloudSyncStorage.OneDrive).get(),
            )
        }
    }

    internal suspend fun restoreSessionFromBackup(session: BackupOneDriveSession) {
        withIOContext {
            require(
                session.driveId.isNotBlank() && isClientIdValid(session.clientId) && session.refreshToken.isNotBlank(),
            )
            lock.withLock {
                val currentDrive = preferences.cloudSyncOneDriveId()
                val fingerprint = preferences.cloudSyncOneDriveAccountFingerprint()
                val previousAccount = accountFingerprint(currentDrive.get()) ?: fingerprint.get()
                val account = checkNotNull(accountFingerprint(session.driveId))
                val folder = preferences.cloudSyncDestination(CloudSyncStorage.OneDrive)
                credentials.save(session.driveId, session.clientId, session.refreshToken)
                pending.delete()
                cachedToken = null
                if (currentDrive.get().isNotBlank() && currentDrive.get() != session.driveId) {
                    currentDrive.delete()
                }
                if (session.destination != null) {
                    folder.set(session.destination)
                } else if (previousAccount.isNotBlank() && previousAccount != account) {
                    folder.delete()
                }
                fingerprint.set(account)
                currentDrive.set(session.driveId)
            }
        }
    }

    suspend fun authorizationUrl(clientId: String): String = withIOContext {
        require(isClientIdValid(clientId))
        val verifier = randomString()
        val state = randomString()
        val challenge = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()).base64Url()
        lock.withLock {
            val login = JSONObject()
                .put("clientId", clientId)
                .put("verifier", verifier)
                .put("state", state)
                .put("expiresAt", System.currentTimeMillis() + LOGIN_TIMEOUT_MS)
            pending.writeBytes(login.toString().toByteArray())
        }
        AUTHORITY.newBuilder()
            .addPathSegment("authorize")
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("response_mode", "query")
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("state", state)
            .addQueryParameter("code_challenge", challenge)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("prompt", "select_account")
            .build()
            .toString()
    }

    internal suspend fun completeLogin(uri: Uri): Boolean = withIOContext {
        lock.withLock {
            val state = uri.getQueryParameter("state") ?: return@withLock false
            // Microsoft's browser redirect can include a trailing slash for the empty path.
            val callback = uri.buildUpon().clearQuery().fragment(null).build().toString()
            if ((callback != redirectUri && callback != "$redirectUri/") ||
                !pending.baseFile.exists()
            ) {
                return@withLock false
            }
            val login = JSONObject(pending.readFully().decodeToString())
            if (login.getString("state") != state) return@withLock false
            pending.delete()
            if (System.currentTimeMillis() >= login.getLong("expiresAt")) return@withLock false
            val code = uri.getQueryParameter("code") ?: run {
                val reason = uri.getQueryParameter("error").orEmpty().takeIf { it.matches(OAUTH_ERROR_CODE) }
                val providerCode = uri.getQueryParameter("error_description")?.let(AADSTS_CODE::find)?.value
                val errorDetails = listOfNotNull(reason, providerCode).joinToString(" ").ifEmpty { "no code" }
                logcat(LogPriority.WARN) { "OneDrive authorization returned $errorDetails" }
                return@withLock false
            }
            val clientId = login.getString("clientId")
            val token = requestToken(
                clientId,
                "grant_type" to "authorization_code",
                "code" to code,
                "code_verifier" to login.getString("verifier"),
                "redirect_uri" to redirectUri,
            )
            val driveRequest = Request.Builder()
                .url(OneDriveHttp.graph.newBuilder().addPathSegments("me/drive").build())
                .header("Authorization", "Bearer ${token.access}")
                .build()
            val driveId = OneDriveHttp.api.newCall(driveRequest).await().use { response ->
                if (!response.isSuccessful) {
                    logcat(LogPriority.WARN) { "OneDrive drive discovery failed (HTTP ${response.code})" }
                }
                check(response.isSuccessful) { "OneDrive could not open your drive (HTTP ${response.code})" }
                JSONObject(response.body.string()).getString("id").also { check(it.isNotBlank()) }
            }
            val currentDrive = preferences.cloudSyncOneDriveId()
            val fingerprint = preferences.cloudSyncOneDriveAccountFingerprint()
            val previousAccount = accountFingerprint(currentDrive.get()) ?: fingerprint.get()
            val account = checkNotNull(accountFingerprint(driveId))
            credentials.save(driveId, clientId, checkNotNull(token.refresh))
            if (previousAccount.isNotBlank() && previousAccount != account) {
                preferences.cloudSyncDestination(CloudSyncStorage.OneDrive).delete()
            }
            fingerprint.set(account)
            cachedToken = token.copy(driveId = driveId, refresh = null)
            currentDrive.set(driveId)
            true
        }
    }

    suspend fun disconnect() = withIOContext {
        lock.withLock {
            pending.delete()
            clearConnection()
        }
    }

    internal suspend fun accessToken(driveId: String, rejected: String?): String = withIOContext {
        lock.withLock {
            check(preferences.cloudSyncOneDriveId().get() == driveId) { "Reconnect your OneDrive account" }
            cachedToken?.let {
                if (it.driveId == driveId && it.access != rejected && it.expiresAt > SystemClock.elapsedRealtime()) {
                    return@withLock it.access
                }
            }
            val saved = credentials.read()
            if (saved == null || saved.getString("driveId") != driveId) {
                clearConnection()
                error("Reconnect your OneDrive account")
            }
            val clientId = saved.getString("clientId")
            val refresh = saved.getString("refresh")
            val token = try {
                requestToken(clientId, "grant_type" to "refresh_token", "refresh_token" to refresh)
            } catch (error: OneDriveReauthorizationRequired) {
                clearConnection()
                throw error
            }
            token.refresh?.let { credentials.save(driveId, clientId, it) }
            cachedToken = token.copy(driveId = driveId, refresh = null)
            token.access
        }
    }

    private fun clearConnection() {
        val currentDrive = preferences.cloudSyncOneDriveId()
        accountFingerprint(currentDrive.get())?.let { preferences.cloudSyncOneDriveAccountFingerprint().set(it) }
        currentDrive.delete()
        cachedToken = null
        credentials.delete()
    }

    private fun accountFingerprint(driveId: String): String? =
        preferences.cloudSyncTargetId(CloudSyncStorage.OneDrive, driveId, "/")

    private suspend fun requestToken(clientId: String, vararg parameters: Pair<String, String>): AccessToken {
        val body = FormBody.Builder().apply {
            add("client_id", clientId)
            add("scope", SCOPES)
            parameters.forEach { (key, value) -> add(key, value) }
        }.build()
        val request = Request.Builder().url(AUTHORITY.newBuilder().addPathSegment("token").build()).post(body).build()
        return OneDriveHttp.api.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                val result = runCatching { JSONObject(response.body.string()) }.getOrNull()
                val error = result?.optString("error")?.takeIf { it.matches(OAUTH_ERROR_CODE) }
                val providerCode = result?.optJSONArray("error_codes")?.optInt(0, 0)?.takeIf { it > 0 }
                val errorDetails = listOfNotNull("HTTP ${response.code}", error, providerCode?.let { "AADSTS$it" })
                logcat(LogPriority.WARN) {
                    "OneDrive token request failed (${errorDetails.joinToString()})"
                }
                if (error == "invalid_grant" || error == "interaction_required") throw OneDriveReauthorizationRequired()
                throw CloudStorageHttpException(
                    response.code,
                    "Microsoft sign-in failed (HTTP ${response.code})",
                    response.header("Retry-After")?.toLongOrNull(),
                )
            }
            val result = JSONObject(response.body.string())
            AccessToken(
                access = result.getString("access_token"),
                refresh = result.optString("refresh_token").takeIf { it.isNotBlank() },
                expiresAt = SystemClock.elapsedRealtime() + (result.getLong("expires_in") - 60).coerceAtLeast(0) * 1000,
            )
        }
    }

    private class OneDriveReauthorizationRequired : IllegalStateException("Reconnect your OneDrive account")

    private data class AccessToken(
        val access: String,
        val refresh: String?,
        val expiresAt: Long,
        val driveId: String? = null,
    )

    companion object {
        private val AUTHORITY = "https://login.microsoftonline.com/common/oauth2/v2.0".toHttpUrl()
        private const val SCOPES = "https://graph.microsoft.com/Files.ReadWrite offline_access"
        private const val LOGIN_TIMEOUT_MS = 15 * 60 * 1000L
        private val OAUTH_ERROR_CODE = Regex("[a-z_]{1,64}")
        private val AADSTS_CODE = Regex("AADSTS[0-9]{4,10}")

        private fun isClientIdValid(value: String) = runCatching {
            UUID.fromString(value).toString().equals(value, ignoreCase = true)
        }.getOrDefault(false)

        private fun randomString() = ByteArray(32).also(SecureRandom()::nextBytes).base64Url()

        private fun ByteArray.base64Url() = Base64.encodeToString(
            this,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
    }
}

private class OneDriveCredentials(file: File) {
    private val store = AtomicFile(file)

    fun read(): JSONObject? {
        if (!store.baseFile.exists()) return null
        return runCatching {
            val bytes = store.readFully()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_SIZE))
            JSONObject(cipher.doFinal(bytes, IV_SIZE, bytes.size - IV_SIZE).decodeToString())
        }.getOrNull()
    }

    fun save(driveId: String, clientId: String, refresh: String) {
        val value = JSONObject().put("driveId", driveId).put("clientId", clientId).put("refresh", refresh)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(cipher.iv.size == IV_SIZE)
        store.writeBytes(cipher.iv + cipher.doFinal(value.toString().toByteArray()))
    }

    fun delete() = store.delete()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build(),
                )
                generateKey()
            }
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALIAS = "mihon-onedrive-session"
        private const val IV_SIZE = 12
    }
}

private fun AtomicFile.writeBytes(bytes: ByteArray) {
    val output = startWrite()
    try {
        output.write(bytes)
        finishWrite(output)
    } catch (error: Exception) {
        failWrite(output)
        throw error
    }
}

internal object OneDriveHttp {
    val graph = "https://graph.microsoft.com/v1.0/".toHttpUrl()

    // Keep OAuth tokens and preauthenticated URLs out of the shared cookie jar and network logs.
    val api = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(30.seconds)
        .readTimeout(30.seconds)
        .callTimeout(2.minutes)
        .build()
    val transfer = api.newBuilder()
        .readTimeout(15.minutes)
        .writeTimeout(15.minutes)
        .callTimeout(15.minutes)
        .retryOnConnectionFailure(false)
        .build()
}
