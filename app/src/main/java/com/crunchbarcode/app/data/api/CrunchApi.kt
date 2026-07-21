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
import okhttp3.logging.HttpLoggingInterceptor
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
        .addInterceptor(HttpLoggingInterceptor { msg ->
            if (msg.contains("HTTP FAILED") || msg.contains("HTTP ") || msg.contains("Exception"))
                android.util.Log.w("CrunchAPI", msg)
        }.apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()

    fun login(username: String, password: String): Result<LoginResponse> {
        val url = "$baseUrl$LOGIN_PATH"
        val formBody = FormBody.Builder()
            .add("login", username)
            .add("password", password)
            .build()

        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        try {
            val response = client.newCall(request).execute()
            val code = response.code
            val body = response.body?.string() ?: ""

            android.util.Log.i("CrunchAPI", "Login response $code: ${body.take(300)}")

            return when {
                code == 200 -> {
                    val json = try { JSONObject(body) } catch (e: Exception) {
                        return Result.failure(Exception("Bad JSON (HTTP $code): ${body.take(200)}"))
                    }
                    val uuid = json.optString("uuid", "")
                    if (uuid.isEmpty()) {
                        return Result.failure(Exception("No uuid in response (HTTP $code): ${body.take(200)}"))
                    }
                    val sid = sessionId ?: ""
                    Result.success(LoginResponse.fromJson(json, sid))
                }
                code == 401 -> {
                    val msg = try { JSONObject(body).optString("message", "") } catch (_: Exception) { "" }
                    val cause = try { JSONObject(body).optString("cause", "") } catch (_: Exception) { "" }
                    val desc = if (msg.isNotEmpty()) msg else body.take(150)
                    Result.failure(CrunchAuthException(code, msg, cause, desc))
                }
                code == 403 -> {
                    Result.failure(CrunchAuthException(code, "Migration required", "", body.take(150)))
                }
                code == 400 -> {
                    Result.failure(CrunchAuthException(code, "Bad request", "", body.take(150)))
                }
                else -> {
                    Result.failure(CrunchAuthException(code, "Unexpected response", "", body.take(200)))
                }
            }
        } catch (e: IOException) {
            android.util.Log.e("CrunchAPI", "IO error: ${e.message}", e)
            Result.failure(Exception("Network error: ${e.message ?: "could not reach server"}"))
        } catch (e: Exception) {
            android.util.Log.e("CrunchAPI", "Unexpected error: ${e.message}", e)
            Result.failure(Exception("Error: ${e.message ?: e.javaClass.simpleName}"))
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
) : Exception("$httpCode: ${apiMessage.ifEmpty { hint.take(100) }}")
