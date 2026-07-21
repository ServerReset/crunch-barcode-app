package com.crunchbarcode.app.data.repository

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.crunchbarcode.app.data.api.CrunchApi
import com.crunchbarcode.app.data.model.BarcodeResponse
import com.crunchbarcode.app.data.model.LoginResponse
import com.crunchbarcode.app.data.model.UserCredentials

class CrunchRepository private constructor(ctx: Context) {

    private val prefs = EncryptedSharedPreferences.create(ctx,
        "crunch_secure", MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    var savedCredentials: UserCredentials?
        get() {
            val l = prefs.getString("login", null) ?: return null
            val p = prefs.getString("password", null) ?: return null
            val u = prefs.getString("uuid", null) ?: return null
            val s = prefs.getString("session", null) ?: return null
            return UserCredentials(l, p, u, s, prefs.getString("first", null), prefs.getString("last", null))
        }
        private set(v) {
            if (v == null) prefs.edit().clear().apply()
            else prefs.edit().putString("login", v.login).putString("password", v.password)
                .putString("uuid", v.uuid).putString("session", v.sessionId)
                .putString("first", v.firstName).putString("last", v.lastName).apply()
        }

    val isLoggedIn: Boolean get() = savedCredentials != null

    fun login(user: String, pass: String, serverUrl: String = CrunchApi.BASE_URL): Result<LoginResponse> {
        val api = CrunchApi()
        val r = api.login(user, pass)
        if (r.isSuccess) {
            val lr = r.getOrThrow()
            savedCredentials = UserCredentials(user, pass, lr.uuid, lr.sessionId, lr.firstName, lr.lastName)
        }
        return r
    }

    fun refreshBarcode(): Result<BarcodeResponse> {
        val c = savedCredentials ?: return Result.failure(Exception("Not logged in"))
        return CrunchApi().getBarcode(c)
    }

    fun getGooglePayJwt(): Result<String> {
        val c = savedCredentials ?: return Result.failure(Exception("Not logged in"))
        return CrunchApi().getGooglePayJwt(c)
    }

    fun logout() { savedCredentials = null }

    companion object {
        @Volatile private var i: CrunchRepository? = null
        fun getInstance(ctx: Context) = i ?: synchronized(this) { CrunchRepository(ctx.applicationContext).also { i = it } }
    }
}
