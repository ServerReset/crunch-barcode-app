package com.crunchbarcode.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.crunchbarcode.app.data.api.CrunchApi
import com.crunchbarcode.app.data.api.CrunchAuthException
import com.crunchbarcode.app.data.model.BarcodeResponse
import com.crunchbarcode.app.data.model.LoginResponse
import com.crunchbarcode.app.data.model.UserCredentials

class CrunchRepository private constructor(context: Context) {

    private val api = CrunchApi()

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "crunch_secure_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    var savedCredentials: UserCredentials?
        get() {
            val login = prefs.getString(KEY_LOGIN, null) ?: return null
            val password = prefs.getString(KEY_PASSWORD, null) ?: return null
            val uuid = prefs.getString(KEY_UUID, null) ?: return null
            val sessionId = prefs.getString(KEY_SESSION_ID, null) ?: return null
            val firstName = prefs.getString(KEY_FIRST_NAME, null)
            val lastName = prefs.getString(KEY_LAST_NAME, null)
            return UserCredentials(login, password, uuid, sessionId, firstName, lastName)
        }
        private set(value) {
            if (value == null) {
                prefs.edit().clear().apply()
            } else {
                prefs.edit()
                    .putString(KEY_LOGIN, value.login)
                    .putString(KEY_PASSWORD, value.password)
                    .putString(KEY_UUID, value.uuid)
                    .putString(KEY_SESSION_ID, value.sessionId)
                    .putString(KEY_FIRST_NAME, value.firstName)
                    .putString(KEY_LAST_NAME, value.lastName)
                    .apply()
            }
        }

    val isLoggedIn: Boolean get() = savedCredentials != null

    fun login(username: String, password: String, serverUrl: String = CrunchApi.BASE_URL): Result<LoginResponse> {
        val api = if (serverUrl != CrunchApi.BASE_URL) CrunchApi(serverUrl) else this.api
        val result = api.login(username, password)
        if (result.isSuccess) {
            val loginResp = result.getOrThrow()
            savedCredentials = UserCredentials(
                login = username,
                password = password,
                uuid = loginResp.uuid,
                sessionId = loginResp.sessionId,
                firstName = loginResp.firstName,
                lastName = loginResp.lastName
            )
        }
        return result
    }

    fun refreshBarcode(): Result<BarcodeResponse> {
        val creds = savedCredentials ?: return Result.failure(Exception("Not logged in"))
        return api.getBarcode(creds)
    }

    fun getGooglePayJwt(): Result<String> {
        val creds = savedCredentials ?: return Result.failure(Exception("Not logged in"))
        return api.getGooglePayJwt(creds)
    }

    fun logout() {
        savedCredentials = null
    }

    companion object {
        private const val KEY_LOGIN = "login"
        private const val KEY_PASSWORD = "password"
        private const val KEY_UUID = "uuid"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_FIRST_NAME = "first_name"
        private const val KEY_LAST_NAME = "last_name"

        @Volatile
        private var instance: CrunchRepository? = null

        fun getInstance(context: Context): CrunchRepository {
            return instance ?: synchronized(this) {
                instance ?: CrunchRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
