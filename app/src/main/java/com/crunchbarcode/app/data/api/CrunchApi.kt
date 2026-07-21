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

            if (!response.isSuccessful) {
                val errorJson = try { JSONObject(body) } catch (_: Exception) { null }
                val message = errorJson?.optString("message", "") ?: ""
                val cause = errorJson?.optString("cause", "") ?: ""
                return Result.failure(CrunchAuthException(response.code, message, cause))
            }

            val json = JSONObject(body)
            val sid = sessionId ?: ""
            Result.success(LoginResponse.fromJson(json, sid))
        } catch (e: CrunchAuthException) {
            Result.failure(e)
        } catch (e: IOException) {
            val msg = e.localizedMessage ?: "Connection error"
            Result.failure(IOException(msg))
        } catch (e: Exception) {
            Result.failure(Exception("Login failed: ${e.localizedMessage ?: "Unknown error"}"))
        }
    }

    fun getBarcode(credentials: UserCredentials): Result<BarcodeResponse> {
        return try {
            val path = String.format(BARCODE_PATH, credentials.uuid)
            val request = Request.Builder().url("$baseUrl$path").get().build()
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: throw IOException("Empty response")
            if (!response.isSuccessful) {
                return Result.failure(IOException("Barcode fetch failed: ${response.code}"))
            }
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
            if (!response.isSuccessful) {
                return Result.failure(IOException("Google Pay barcode fetch failed: ${response.code}"))
            }
            Result.success(body.trim().removeSurrounding("\""))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

class CrunchAuthException(
    val httpCode: Int,
    val apiMessage: String,
    val apiCause: String
) : Exception("Login failed ($httpCode): $apiMessage")
