package com.crunchbarcode.app.data.api

import com.crunchbarcode.app.data.model.BarcodeResponse
import com.crunchbarcode.app.data.model.LoginResponse
import com.crunchbarcode.app.data.model.UserCredentials
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class CrunchApi(baseUrl: String = BASE_URL) {

    companion object {
        const val BASE_URL = "https://crunch-fitness-container.netpulse.com"
        private const val LOGIN_NEW = "/np/exerciser/login"
        private const val LOGIN_LEGACY = "/np/login"
        private const val BARCODE = "/np/exerciser/%s/membership-barcode"
        const val GOOGLE_PAY = "/np/exercisers/%s/google/pay/barcode"
    }

    private var base = baseUrl.trimEnd('/')

    private val cookieStore = mutableMapOf<String, List<Cookie>>()
    var sessionId: String? = null; private set

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
                cookies.find { it.name == "JSESSIONID" }?.let { sessionId = it.value }
            }
            override fun loadForRequest(url: HttpUrl) = cookieStore[url.host] ?: emptyList()
        })
        .addInterceptor { chain ->
            val r = chain.request().newBuilder()
                .addHeader("Accept", "application/json")
                .addHeader("User-Agent", "CrunchBarcode/1.0")
                .addHeader("X-NP-API-Version", "1.5")
                .addHeader("X-NP-APP-Version", "1")
                .addHeader("X-NP-User-Agent", "clientType=MOBILE_DEVICE; devicePlatform=ANDROID; deviceUid=${UUID.randomUUID()}::emulator; applicationName=Crunch; applicationVersion=5.15.2; applicationVersionCode=559")
                .build()
            chain.proceed(r)
        }
        .build()

    fun login(username: String, password: String): Result<LoginResponse> {
        val legacy = tryLogin(username, password, LOGIN_LEGACY, "login")
        if (legacy.isSuccess) return legacy
        val modern = tryLogin(username, password, LOGIN_NEW, "username")
        if (modern.isSuccess) return modern
        return legacy
    }

    private fun tryLogin(username: String, password: String, path: String, userKey: String): Result<LoginResponse> = try {
        val resp = client.newCall(Request.Builder()
            .url("$base$path")
            .post(FormBody.Builder().add(userKey, username).add("password", password).build())
            .build()).execute()
        val code = resp.code; val body = resp.body?.string() ?: ""
        when {
            code == 200 -> JSONObject(body).let { json ->
                val uuid = json.optString("uuid", "")
                if (uuid.isEmpty()) Result.failure(Exception("Bad response: ${body.take(200)}"))
                else Result.success(LoginResponse.fromJson(json, sessionId ?: ""))
            }
            code == 401 -> {
                val msg = try { JSONObject(body).optString("message", "") } catch (_: Exception) { "" }
                val cause = try { JSONObject(body).optString("cause", "") } catch (_: Exception) { "" }
                Result.failure(CrunchAuthException(code, msg, cause, body.take(150)))
            }
            else -> Result.failure(CrunchAuthException(code, "", "", body.take(200)))
        }
    } catch (e: IOException) { Result.failure(Exception("Network error: ${e.message}"))
    } catch (e: Exception) { Result.failure(Exception("Error: ${e.message ?: e.javaClass.simpleName}")) }

    fun getBarcode(c: UserCredentials) = apiGet(String.format(BARCODE, c.uuid)) { BarcodeResponse.fromJson(JSONObject(it)) }
    fun getGooglePayJwt(c: UserCredentials) = apiGet("${String.format(GOOGLE_PAY, c.uuid)}?appVersion=1&backgroundColor=%23000000") { it.trim().removeSurrounding("\"") }

    private inline fun <T> apiGet(path: String, crossinline parse: (String) -> T): Result<T> = try {
        val resp = client.newCall(Request.Builder().url("$base$path").get().build()).execute()
        val body = resp.body?.string() ?: ""
        if (!resp.isSuccessful) Result.failure(IOException("$path failed (${resp.code})"))
        else Result.success(parse(body))
    } catch (e: Exception) { Result.failure(e) }
}

class CrunchAuthException(val httpCode: Int, val apiMessage: String, val apiCause: String, val hint: String = ""
) : Exception("$httpCode: ${apiMessage.ifEmpty { hint.take(100) }}")
