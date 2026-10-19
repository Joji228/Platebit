package app.morsel.backup

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Signed-in account as Google reports it. */
@Serializable
data class GoogleAccount(val email: String, val name: String? = null, val picture: String? = null)

/** Google said the token is no longer valid; get a fresh one and try again. */
class TokenExpiredException : IOException("Google sign-in expired")

/**
 * The few Drive REST calls the backup needs, all inside the hidden app folder ("appDataFolder").
 * Plain HTTPS through OkHttp instead of Google's Drive library, which would add megabytes.
 */
class DriveClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class FileList(val files: List<DriveFile> = emptyList())

    @Serializable
    private data class DriveFile(val id: String, val name: String = "", val appProperties: Map<String, String> = emptyMap()) {
        fun toCloudFile() = CloudFile(
            id = id,
            name = name,
            rev = appProperties[REV],
            deviceId = appProperties[DEVICE_ID],
            deviceName = appProperties[DEVICE_NAME],
            savedAt = appProperties[SAVED_AT]?.toLongOrNull(),
            meals = appProperties[MEALS]?.toIntOrNull(),
        )
    }

    suspend fun account(token: String): GoogleAccount =
        json.decodeFromString(GoogleAccount.serializer(), call(token, Request.Builder().url(USERINFO)).decodeToString())

    suspend fun list(token: String): List<CloudFile> {
        val url = "$API/files".toHttpUrl().newBuilder()
            .addQueryParameter("spaces", "appDataFolder")
            .addQueryParameter("fields", "files(id,name,appProperties)")
            .addQueryParameter("pageSize", "100")
            .build()
        return json.decodeFromString(FileList.serializer(), call(token, Request.Builder().url(url)).decodeToString())
            .files.map { it.toCloudFile() }
    }

    suspend fun download(token: String, id: String): ByteArray = call(token, Request.Builder().url("$API/files/$id?alt=media"))

    /** Creates [name] in the app folder, or replaces the contents of [existingId]. */
    suspend fun upload(token: String, existingId: String?, name: String, bytes: ByteArray, properties: Map<String, String>): CloudFile {
        val metadata = buildJsonObject {
            if (existingId == null) {
                put("name", name)
                put("parents", buildJsonArray { add(JsonPrimitive("appDataFolder")) })
            }
            put("appProperties", buildJsonObject { properties.forEach { (k, v) -> put(k, v) } })
        }
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody("application/gzip".toMediaType()))
            .build()
        val fields = "uploadType=multipart&fields=id,name,appProperties"
        val request = if (existingId == null) {
            Request.Builder().url("$UPLOAD/files?$fields").post(body)
        } else {
            Request.Builder().url("$UPLOAD/files/$existingId?$fields").patch(body)
        }
        return json.decodeFromString(DriveFile.serializer(), call(token, request).decodeToString()).toCloudFile()
    }

    suspend fun delete(token: String, id: String) {
        call(token, Request.Builder().url("$API/files/$id").delete())
    }

    /** Cancelling the coroutine cancels the request too. */
    private suspend fun call(token: String, builder: Request.Builder): ByteArray {
        val call = http.newCall(builder.header("Authorization", "Bearer $token").build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = cont.resumeWithException(e)

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val body = runCatching { it.body.bytes() }.getOrElse { e -> return cont.resumeWithException(e) }
                        when {
                            it.isSuccessful -> cont.resume(body)
                            it.code == 401 -> cont.resumeWithException(TokenExpiredException())
                            else -> cont.resumeWithException(IOException(describe(it.code, body.decodeToString())))
                        }
                    }
                }
            })
        }
    }

    private fun describe(code: Int, body: String) = when {
        code == 403 && ("accessNotConfigured" in body || "SERVICE_DISABLED" in body) ->
            "Google Drive isn't switched on for this app's Google Cloud project yet"
        code == 403 && "storageQuotaExceeded" in body -> "Your Google Drive is full"
        code == 403 -> "Google Drive refused the request (403)"
        code == 404 -> "The backup file wasn't found in Google Drive"
        code >= 500 -> "Google Drive is having trouble right now ($code)"
        else -> "Google Drive error $code"
    }

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val USERINFO = "https://www.googleapis.com/oauth2/v3/userinfo"

        const val REV = "rev"
        const val DEVICE_ID = "deviceId"
        const val DEVICE_NAME = "device"
        const val SAVED_AT = "savedAt"
        const val MEALS = "meals"
    }
}
