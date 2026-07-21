package com.crunchbarcode.app.data.api

import android.content.Context
import com.crunchbarcode.app.data.model.LoginResponse
import com.crunchbarcode.app.data.model.UserCredentials
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ApiConfig(val baseUrl: String, val path: String, val userKey: String)

class CrunchApi private constructor() {

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

        @Volatile private var instance: CrunchApi? = null
        fun get() = instance ?: synchronized(this) { CrunchApi().also { instance = it } }
    }

    private val cookieStore = mutableMapOf<String, List<Cookie>>()
    var sessionId: String? = null; private set
    var workingConfig: ApiConfig? = null; private set
    var savedUsername: String? = null; private set
    var savedPassword: String? = null; private set

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
                .addHeader("X-NP-User-Agent",
                    "clientType=MOBILE_DEVICE; devicePlatform=ANDROID; " +
                    "deviceUid=${UUID.randomUUID()}::emulator; " +
                    "applicationName=Crunch; applicationVersion=5.15.2; applicationVersionCode=559")
                .build())
        }
        .build()

    fun login(user: String, pass: String): Result<LoginResponse> {
        savedUsername = user; savedPassword = pass
        val cached = workingConfig
        if (cached != null) { val r = tryLogin(user, pass, cached); if (r.isSuccess) return r }
        for (cfg in COMBOS) { if (cfg == cached) continue
            val r = tryLogin(user, pass, cfg); if (r.isSuccess) { workingConfig = cfg; return r } }
        return tryLogin(user, pass, ApiConfig(BASE_URL, "/np/login", "login"))
    }

    fun relogin(): Result<LoginResponse> {
        val u = savedUsername ?: return Result.failure(Exception("No saved creds"))
        val p = savedPassword ?: return Result.failure(Exception("No saved creds"))
        sessionId = null; cookieStore.clear()
        return login(u, p)
    }

    fun getBarcode(creds: UserCredentials): Result<Pair<String, ByteArray>> {
        return try {
            val base = (workingConfig ?: COMBOS[0]).baseUrl.trimEnd('/')
            val resp = client.newCall(Request.Builder().url("$base/np/exerciser/${creds.uuid}/membership-barcode").get().build()).execute()
            if (resp.code == 401) return Result.failure(SessionExpiredException())
            val body = resp.body?.string() ?: return Result.failure(Exception("Empty"))
            if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
            val json = JSONObject(body)
            val barcode = json.optString("barcode", "")
            if (barcode.isEmpty()) return Result.failure(Exception(json.optString("errorMessage", "No barcode")))
            Result.success(Pair(barcode, ByteArray(0)))
        } catch (e: SessionExpiredException) { throw e
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getGooglePayJwt(creds: UserCredentials): Result<String> {
        return try {
            val base = (workingConfig ?: COMBOS[0]).baseUrl.trimEnd('/')
            val resp = client.newCall(Request.Builder().url("$base/np/exercisers/${creds.uuid}/google/pay/barcode?appVersion=1&backgroundColor=%23000000").get().build()).execute()
            if (resp.code == 401) return Result.failure(SessionExpiredException())
            val body = resp.body?.string() ?: return Result.failure(Exception("Empty"))
            if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
            Result.success(body.trim().removeSurrounding("\""))
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun tryLogin(user: String, pass: String, cfg: ApiConfig): Result<LoginResponse> = try {
        val base = cfg.baseUrl.trimEnd('/')
        val resp = client.newCall(Request.Builder().url("$base${cfg.path}")
            .post(FormBody.Builder().add(cfg.userKey, user).add("password", pass).build()).build()).execute()
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
}

class CrunchAuthException(val httpCode: Int, val apiMessage: String, val apiCause: String) : Exception("$httpCode: ${apiMessage.take(100)}")
class SessionExpiredException : Exception("Session expired")
