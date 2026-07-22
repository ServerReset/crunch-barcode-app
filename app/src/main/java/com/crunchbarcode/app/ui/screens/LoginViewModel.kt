package com.crunchbarcode.app.ui.screens

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crunchbarcode.app.data.api.CrunchAuthException
import com.crunchbarcode.app.data.api.CrunchApi
import com.crunchbarcode.app.data.repository.CrunchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import javax.net.ssl.HttpsURLConnection

data class LoginUiState(
    val login: String = "", val password: String = "",
    val isLoading: Boolean = false, val isTesting: Boolean = false,
    val error: String? = null, val isLoggedIn: Boolean = false,
    val isPasswordVisible: Boolean = false,
    val serverUrl: String = CrunchApi.BASE_URL,
    val showServerSettings: Boolean = false
)

class LoginViewModel(app: Application, private val repo: CrunchRepository) : ViewModel() {

    private val _s = MutableStateFlow(LoginUiState()); val uiState = _s.asStateFlow()
    private val prefs = app.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)

    init {
        val saved = prefs.getString("server_url", CrunchApi.BASE_URL) ?: CrunchApi.BASE_URL
        if (saved != CrunchApi.BASE_URL) _s.value = _s.value.copy(serverUrl = saved)
        if (repo.isLoggedIn) _s.value = _s.value.copy(isLoggedIn = true)
    }

    fun onLoginChanged(v: String) { _s.value = _s.value.copy(login = v, error = null) }
    fun onPasswordChanged(v: String) { _s.value = _s.value.copy(password = v, error = null) }
    fun onPasswordVisibilityToggle() { _s.value = _s.value.copy(isPasswordVisible = !_s.value.isPasswordVisible) }
    fun onServerUrlChanged(v: String) { _s.value = _s.value.copy(serverUrl = v, error = null) }
    fun toggleServerSettings() { _s.value = _s.value.copy(showServerSettings = !_s.value.showServerSettings) }

    fun testConnection() { viewModelScope.launch {
        _s.value = _s.value.copy(isTesting = true, error = null)
        val url = _s.value.serverUrl.trimEnd('/')
        val result = withContext(Dispatchers.IO) { try {
            val conn = URL("$url/np/login").openConnection() as HttpsURLConnection
            conn.requestMethod = "POST"; conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("X-NP-API-Version", "1.5"); conn.doOutput = true
            conn.connectTimeout = 8000; conn.readTimeout = 8000; conn.connect()
            "Server: HTTP ${conn.responseCode}"
        } catch (e: Exception) { "Failed: ${e.localizedMessage ?: e.javaClass.simpleName}" }}
        _s.value = _s.value.copy(isTesting = false, error = "Test result: $result")
    }}

    fun webLogin(code: String) { viewModelScope.launch {
        _s.value = _s.value.copy(isLoading = true, error = null)
        val result = withContext(Dispatchers.IO) { repo.oauth2Login(code) }
        result.fold(
            onSuccess = { _s.value = _s.value.copy(isLoading = false, isLoggedIn = true) },
            onFailure = { _s.value = _s.value.copy(isLoading = false, error = "Web login failed: ${it.localizedMessage ?: "Try direct login instead"}") }
        )
    }}

    fun login() {
        val s = _s.value
        if (s.login.isBlank()) { _s.value = s.copy(error = "Enter your email or member ID"); return }
        if (s.password.isBlank()) { _s.value = s.copy(error = "Enter your password"); return }

        prefs.edit().putString("server_url", s.serverUrl.trimEnd('/')).apply()

        viewModelScope.launch {
            _s.value = _s.value.copy(isLoading = true, error = null)
            val result = withContext(Dispatchers.IO) { repo.login(s.login.trim(), s.password) }
            result.fold(
                onSuccess = { _s.value = _s.value.copy(isLoading = false, isLoggedIn = true) },
                onFailure = { e ->
                    val msg = when (e) {
                        is CrunchAuthException -> {
                            val hint = when {
                                e.httpCode == 401 && e.apiCause.contains("locked", true) ->
                                    "Account locked. Wait 15-30 min."
                                e.httpCode == 401 -> "Invalid email or password. Try a different server URL (tap Server)."
                                e.httpCode == 400 -> "Check your information."
                                e.httpCode == 403 -> "Account needs migration. Contact Crunch."
                                e.httpCode == 404 -> "Wrong server URL. Try another preset."
                                else -> "Server: HTTP ${e.httpCode}. Try a different server URL."
                            }
                            "$hint (${e.apiMessage.take(60)})"
                        }
                        is java.net.UnknownHostException -> "Server not found. Wrong URL?"
                        is java.net.SocketTimeoutException -> "Server timed out. Wrong URL?"
                        else -> e.localizedMessage ?: "Failed. Try a different server URL."
                    }
                    _s.value = _s.value.copy(isLoading = false, error = msg)
                }
            )
        }
    }

    class Factory(private val a: Application, private val r: CrunchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(c: Class<T>): T = LoginViewModel(a, r) as T
    }
}
