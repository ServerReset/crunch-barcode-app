package com.crunchbarcode.app.data.api

import com.crunchbarcode.app.data.model.BarcodeResponse
import com.crunchbarcode.app.data.model.LoginResponse
import com.crunchbarcode.app.data.model.UserCredentials
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class CrunchApi(private val baseUrl: String = BASE_URL) {

    companion object {
        const val BASE_URL = "https://crunch-fitness-container.netpulse.com"
        private const val LOGIN_PATH = "/np/exerciser/login"
        private const val BARCODE_PATH = "/np/exerciser/%s/membership-barcode"
        const val GOOGLE_PAY_BARCODE_PATH = "/np/exercisers/%s/google/pay/barcode"
    }

    private val cookieStore = mutableMapOf<String, List<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            cookieStore[url.host] = cookies
            cookies.find { it.name == "JSESSIONID" }?.let { sessionId = it.value }
        }
        override fun loadForRequest(url: HttpUrl) = cookieStore[url.host] ?: emptyList()
    }

    var sessionId: String? = null
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "CrunchBarcode/1.0")
                .build())
        }
        .build()

    fun login(username: String, password: String): Result<LoginResponse> {
        val request = Request.Builder()
            .url("$baseUrl$LOGIN_PATH")
            .post(FormBody.Builder().add("login", username).add("password", password).build())
            .build()

        return try {
            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            if (code != 200) {
                val msg = try { JSONObject(body).optString("message", "") } catch (_: Exception) { "" }
                val cause = try { JSONObject(body).optString("cause", "") } catch (_: Exception) { "" }
                val hint = if (msg.isNotEmpty()) msg else body.take(150)
                return Result.failure(CrunchAuthException(code, msg, cause, hint))
            }

            val json = JSONObject(body)
            val sid = sessionId ?: ""
            val uuid = json.optString("uuid", "")
            if (uuid.isEmpty()) {
                return Result.failure(Exception("Server response missing uuid. Body: ${body.take(200)}"))
            }

            Result.success(LoginResponse.fromJson(json, sid))
        } catch (e: CrunchAuthException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(Exception("Can't reach server: ${e.localizedMessage ?: "check your connection"}"))
        } catch (e: Exception) {
            Result.failure(Exception("Server response error: ${e.localizedMessage ?: body.take(200)}"))
        }
    }

    fun getBarcode(credentials: UserCredentials): Result<BarcodeResponse> {
        return try {
            val url = "$baseUrl${String.format(BARCODE_PATH, credentials.uuid)}"
            val response = client.newCall(Request.Builder().url(url).get().build()).execute()
            val body = response.body?.string() ?: throw IOException("Empty response")
            if (!response.isSuccessful)
                return Result.failure(IOException("Barcode fetch failed (${response.code})"))
            Result.success(BarcodeResponse.fromJson(JSONObject(body)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getGooglePayBarcodeJwt(credentials: UserCredentials): Result<String> {
        return try {
            val url = "$baseUrl${String.format(GOOGLE_PAY_BARCODE_PATH, credentials.uuid)}"
                .toHttpUrl().newBuilder()
                .addQueryParameter("appVersion", "1")
                .addQueryParameter("backgroundColor", "#000000")
                .build()
            val response = client.newCall(Request.Builder().url(url).get().build()).execute()
            val body = response.body?.string() ?: throw IOException("Empty response")
            if (!response.isSuccessful)
                return Result.failure(IOException("Google Pay barcode fetch failed (${response.code})"))
            Result.success(body.trim().removeSurrounding("\""))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class CrunchAuthException(
    val httpCode: Int,
    val apiMessage: String,
    val apiCause: String,
    val hint: String = ""
) : Exception("Server returned $httpCode: ${apiMessage.ifEmpty { hint.take(100) }}")
