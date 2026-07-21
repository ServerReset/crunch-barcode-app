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
    private val sessionCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val host = url.host
            cookieStore[host] = cookies
            val sessionCookie = cookies.find { it.name == "JSESSIONID" }
            if (sessionCookie != null) sessionId = sessionCookie.value
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            return cookieStore[host] ?: emptyList()
        }
    }

    var sessionId: String? = null
        private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(sessionCookieJar)
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "CrunchBarcode/1.0")
                .build())
        }
        .build()

    fun login(username: String, password: String): Result<LoginResponse> {
        return try {
            val formBody = FormBody.Builder()
                .add("login", username)
                .add("password", password)
                .build()

            val request = Request.Builder()
                .url("$baseUrl$LOGIN_PATH")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw IOException("Empty response body")
            val code = response.code

            if (code == 200) {
                val json = JSONObject(body)
                val sid = sessionId ?: ""
                Result.success(LoginResponse.fromJson(json, sid))
            } else {
                val errorJson = try { JSONObject(body) } catch (_: Exception) { null }
                val apiMsg = errorJson?.optString("message", "") ?: ""
                val apiCause = errorJson?.optString("cause", "") ?: ""
                val snippet = if (apiMsg.isNotEmpty()) apiMsg
                    else body.take(200)
                Result.failure(CrunchAuthException(code, apiMsg, apiCause, snippet))
            }
        } catch (e: CrunchAuthException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(Exception("Network error: ${e.localizedMessage ?: "Could not reach server"}"))
        } catch (e: Exception) {
            val snippet = e.localizedMessage ?: "Unknown error"
            Result.failure(Exception("Response error: $snippet"))
        }
    }

    fun getBarcode(credentials: UserCredentials): Result<BarcodeResponse> {
        return try {
            val path = String.format(BARCODE_PATH, credentials.uuid)
            val request = Request.Builder().url("$baseUrl$path").get().build()
            val response = client.newCall(request).execute()
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
            val path = String.format(GOOGLE_PAY_BARCODE_PATH, credentials.uuid)
            val url = "$baseUrl$path".toHttpUrl().newBuilder()
                .addQueryParameter("appVersion", "1")
                .addQueryParameter("backgroundColor", "#000000")
                .build()
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
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
    val snippet: String = ""
) : Exception("Server returned $httpCode: ${apiMessage.ifEmpty { snippet.take(100) }}")
