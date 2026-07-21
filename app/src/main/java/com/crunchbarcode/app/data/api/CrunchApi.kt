package com.crunchbarcode.app.data.api

import com.crunchbarcode.app.data.model.BarcodeResponse
import com.crunchbarcode.app.data.model.LoginResponse
import com.crunchbarcode.app.data.model.UserCredentials
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ApiConfig(val baseUrl: String, val path: String, val userKey: String)

class CrunchApi {

    companion object {
        const val BASE_URL = "https://crunch-fitness-container.netpulse.com"
        val COMBOS = listOf(
            ApiConfig(BASE_URL, "/np/login", "login"),
            ApiConfig(BASE_URL, "/np/exerciser/login", "username"),
            ApiConfig("https://vollgas.netpulse.com", "/np/login", "login"),
            ApiConfig("https://vollgas.netpulse.com", "/np/exerciser/login", "username"),
            ApiConfig("https://api.netpulse.com", "/np/login", "login"),
            ApiConfig("https://mobile-api.int.api.egym.com", "/np/login", "login"),
        )
    }

    private val cookieStore = mutableMapOf<String, List<Cookie>>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
                cookies.find { it.name == "JSESSIONID" }?.let { sessionId = it.value }
            }
            override fun loadForRequest(url: HttpUrl) = cookieStore[url.host] ?: emptyList()
        })
        .addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder()
                .addHeader("Accept", "application/json")
                .addHeader("X-NP-API-Version", "1.5")
                .addHeader("X-NP-APP-Version", "1")
                .addHeader("X-NP-User-Agent", "clientType=MOBILE_DEVICE; devicePlatform=ANDROID; deviceUid=${UUID.randomUUID()}::emulator; applicationName=Crunch; applicationVersion=5.15.2; applicationVersionCode=559")
                .build())
        }
        .build()

    var sessionId: String? = null; private set
    var workingConfig: ApiConfig? = null; private set

    fun login(user: String, pass: String): Result<LoginResponse> {
        val cached = workingConfig
        if (cached != null) {
            val r = tryLogin(user, pass, cached)
            if (r.isSuccess) return r
        }
        for (cfg in COMBOS) {
            if (cfg == cached) continue
            val r = tryLogin(user, pass, cfg)
            if (r.isSuccess) { workingConfig = cfg; return r }
        }
        return tryLogin(user, pass, ApiConfig(BASE_URL, "/np/login", "login"))
    }

    private fun tryLogin(user: String, pass: String, cfg: ApiConfig): Result<LoginResponse> = try {
        val resp = client.newCall(Request.Builder()
            .url("${cfg.baseUrl.trimEnd('/')}${cfg.path}")
            .post(FormBody.Builder().add(cfg.userKey, user).add("password", pass).build())
            .build()).execute()
        val code = resp.code; val body = resp.body?.string() ?: ""
        when {
            code == 200 -> JSONObject(body).let { json ->
                val uuid = json.optString("uuid", "")
                if (uuid.isEmpty()) Result.failure(Exception("Missing uuid"))
                else Result.success(LoginResponse.fromJson(json, sessionId ?: ""))
            }
            code == 401 -> {
                val msg = try { JSONObject(body).optString("message", "") } catch (_: Exception) { "" }
                val cause = try { JSONObject(body).optString("cause", "") } catch (_: Exception) { "" }
                Result.failure(CrunchAuthException(code, msg, cause))
            }
            else -> Result.failure(CrunchAuthException(code, "", ""))
        }
    } catch (_: Exception) { Result.failure(Exception("Connection failed")) }

    fun getBarcode(c: UserCredentials) = apiGet("/np/exerciser/${c.uuid}/membership-barcode") { BarcodeResponse.fromJson(JSONObject(it)) }
    fun getGooglePayJwt(c: UserCredentials) = apiGet("/np/exercisers/${c.uuid}/google/pay/barcode?appVersion=1&backgroundColor=%23000000") { it.trim().removeSurrounding("\"") }

    private inline fun <T> apiGet(path: String, crossinline parse: (String) -> T): Result<T> = try {
        val resp = client.newCall(Request.Builder().url("${(workingConfig ?: COMBOS[0]).baseUrl.trimEnd('/')}$path").get().build()).execute()
        val body = resp.body?.string() ?: ""
        if (!resp.isSuccessful) Result.failure(IOException("HTTP ${resp.code}"))
        else Result.success(parse(body))
    } catch (e: Exception) { Result.failure(e) }
}

class CrunchAuthException(val httpCode: Int, val apiMessage: String, val apiCause: String) : Exception("$httpCode: ${apiMessage.take(100)}")
