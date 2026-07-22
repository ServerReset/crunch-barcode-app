package com.crunchbarcode.app.data.api

import com.crunchbarcode.app.data.model.LoginResponse
import com.crunchbarcode.app.data.model.UserCredentials
import okhttp3.*
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class ApiConfig(val baseUrl: String, val path: String, val userKey: String)

class CrunchApi private constructor() {

    companion object {
        const val BASE_URL = "https://crunch-fitness-container.netpulse.com"
        private val deviceUid = UUID.randomUUID().toString().take(16)
        private val COMBOS = listOf(
            ApiConfig(BASE_URL, "/np/login", "login"),
            ApiConfig(BASE_URL, "/np/exerciser/login", "login"),
            ApiConfig(BASE_URL, "/np/exerciser/login", "username"),
            ApiConfig("https://vollgas.netpulse.com", "/np/login", "login"),
            ApiConfig("https://vollgas.netpulse.com", "/np/exerciser/login", "login"),
            ApiConfig("https://vollgas.netpulse.com", "/np/exerciser/login", "username"),
            ApiConfig("https://api.netpulse.com", "/np/login", "login"),
            ApiConfig("https://api.netpulse.com", "/np/exerciser/login", "login"),
            ApiConfig("https://mobile-api.int.api.egym.com", "/np/exerciser/login", "login"),
            ApiConfig("https://mobile-api.int.api.egym.com", "/np/exerciser/login", "username"),
        )
        @Volatile private var instance: CrunchApi? = null
        fun get() = instance ?: synchronized(this) { CrunchApi().also { instance = it } }
    }

    private val cookieStore = mutableMapOf<String, List<Cookie>>()
    var sessionId: String? = null; private set
    var workingCfg: ApiConfig? = null; private set
    var savedUser: String? = null; private set
    var savedPass: String? = null; private set

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
                    "deviceUid=$deviceUid::${android.os.Build.MODEL}; " +
                    "applicationName=Crunch; applicationVersion=5.15.2; applicationVersionCode=559")
                .build())
        }
        .build()

    fun login(user: String, pass: String): Result<LoginResponse> {
        savedUser = user; savedPass = pass
        val cached = workingCfg
        if (cached != null) { val r = tryLogin(user, pass, cached); if (r.isSuccess) return r }
        for (cfg in COMBOS) { if (cfg == cached) continue
            val r = tryLogin(user, pass, cfg); if (r.isSuccess) { workingCfg = cfg; return r } }
        return tryLogin(user, pass, ApiConfig(BASE_URL, "/np/login", "login"))
    }

    fun oauth2Login(authCode: String): Result<LoginResponse> = try {
        val base = BASE_URL
        val resp = client.newCall(Request.Builder().url("$base/np/exerciser/oauth2/login")
            .post(FormBody.Builder().add("authCode", authCode).add("redirectUrl", "crunchbarcode://oauth2")
                .add("guestUuid", "").add("referrerId", "").add("locale", "en").build()).build()).execute()
        val code = resp.code; val body = resp.body?.string() ?: ""
        if (code == 200) {
            val json = JSONObject(body)
            val uuid = json.optString("uuid", "")
            if (uuid.isEmpty()) Result.failure(Exception("Missing uuid"))
            else Result.success(LoginResponse.fromJson(json, sessionId ?: ""))
        } else {
            val msg = try { JSONObject(body).optString("message", "") } catch (_: Exception) { "" }
            Result.failure(Exception("OAuth2 login failed: $code $msg"))
        }
    } catch (e: Exception) { Result.failure(e) }

    fun relogin(): Result<LoginResponse> {
        val u = savedUser ?: return Result.failure(Exception("No saved creds"))
        val p = savedPass ?: return Result.failure(Exception("No saved creds"))
        sessionId = null; cookieStore.clear()
        return login(u, p)
    }

    fun getBarcode(creds: UserCredentials): Result<String> {
        val base = (workingCfg ?: COMBOS[0]).baseUrl.trimEnd('/')
        return try {
            val resp = client.newCall(Request.Builder()
                .url("$base/np/exerciser/${creds.uuid}/membership-barcode").get().build()).execute()
            if (resp.code == 401) return Result.failure(SessionExpired())
            val body = resp.body?.string() ?: return Result.failure(Exception("Empty response"))
            if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
            val barcode = JSONObject(body).optString("barcode", "")
            if (barcode.isEmpty()) return Result.failure(Exception("No barcode"))
            Result.success(barcode)
        } catch (_: SessionExpired) { throw SessionExpired()
        } catch (e: Exception) { Result.failure(e) }
    }

    fun getGooglePayJwt(creds: UserCredentials): Result<String> {
        val base = (workingCfg ?: COMBOS[0]).baseUrl.trimEnd('/')
        return try {
            val resp = client.newCall(Request.Builder()
                .url("$base/np/exercisers/${creds.uuid}/google/pay/barcode?appVersion=1&backgroundColor=%23000000")
                .get().build()).execute()
            if (resp.code == 401) return Result.failure(SessionExpired())
            val body = resp.body?.string() ?: return Result.failure(Exception("Empty"))
            if (!resp.isSuccessful) return Result.failure(Exception("HTTP ${resp.code}"))
            Result.success(body.trim().removeSurrounding("\""))
        } catch (e: Exception) { Result.failure(e) }
    }

    private fun tryLogin(user: String, pass: String, cfg: ApiConfig): Result<LoginResponse> = try {
        val resp = client.newCall(Request.Builder().url("${cfg.baseUrl.trimEnd('/')}${cfg.path}")
            .post(FormBody.Builder().add(cfg.userKey, user).add("password", pass).build()).build()).execute()
        val code = resp.code; val body = resp.body?.string() ?: ""
        if (code == 200) {
            val json = JSONObject(body)
            val uuid = json.optString("uuid", "")
            if (uuid.isEmpty()) Result.failure(Exception("Missing uuid"))
            else Result.success(LoginResponse.fromJson(json, sessionId ?: ""))
        } else {
            val msg = try { JSONObject(body).optString("message", "") } catch (_: Exception) { "" }
            val cause = try { JSONObject(body).optString("cause", "") } catch (_: Exception) { "" }
            Result.failure(CrunchAuthException(code, msg, cause))
        }
    } catch (_: Exception) { Result.failure(Exception("Connection failed")) }
}

class CrunchAuthException(val httpCode: Int, val apiMessage: String, val apiCause: String) : Exception("$httpCode: ${apiMessage.take(80)}")
class SessionExpired : Exception("Session expired")
