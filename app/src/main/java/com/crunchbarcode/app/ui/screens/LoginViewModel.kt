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
import org.json.JSONObject
import java.net.URL

data class LoginUiState(
    val login: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    val isPasswordVisible: Boolean = false,
    val serverUrl: String = CrunchApi.BASE_URL,
    val showServerSettings: Boolean = false,
    val isResolving: Boolean = false
)

class LoginViewModel(
    private val application: Application,
    private val repository: CrunchRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val prefs = application.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)

    init {
        val savedUrl = prefs.getString("server_url", CrunchApi.BASE_URL) ?: CrunchApi.BASE_URL
        if (savedUrl != CrunchApi.BASE_URL) {
            _uiState.value = _uiState.value.copy(serverUrl = savedUrl)
        }
        if (repository.isLoggedIn) {
            _uiState.value = _uiState.value.copy(isLoggedIn = true)
        }
    }

    fun onLoginChanged(value: String) {
        _uiState.value = _uiState.value.copy(login = value, error = null)
    }

    fun onPasswordChanged(value: String) {
        _uiState.value = _uiState.value.copy(password = value, error = null)
    }

    fun onPasswordVisibilityToggle() {
        _uiState.value = _uiState.value.copy(isPasswordVisible = !_uiState.value.isPasswordVisible)
    }

    fun onServerUrlChanged(value: String) {
        _uiState.value = _uiState.value.copy(serverUrl = value, error = null)
    }

    fun toggleServerSettings() {
        _uiState.value = _uiState.value.copy(showServerSettings = !_uiState.value.showServerSettings)
    }

    fun resolveRegion(keyword: String) {
        if (keyword.isBlank()) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isResolving = true, error = null)
            val result = withContext(Dispatchers.IO) {
                try {
                    val base = _uiState.value.serverUrl
                    val url = "$base/np/nfa/resolveContainer?keyword=${java.net.URLEncoder.encode(keyword, "UTF-8")}&containerAppVersion=559"
                    val conn = URL(url).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("Accept", "application/json")
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    val code = conn.responseCode
                    if (code == 200) {
                        val body = conn.inputStream.bufferedReader().readText()
                        val json = JSONObject(body)
                        val brandId = json.optString("brandIdentifier", "")
                        val resourceType = json.optString("resourceType", "prod")
                        "brandId=$brandId&resourceType=$resourceType"
                    } else {
                        "Server returned HTTP $code"
                    }
                } catch (e: Exception) {
                    e.localizedMessage ?: "Connection failed"
                }
            }
            _uiState.value = _uiState.value.copy(isResolving = false,
                error = "Resolve result: $result")
        }
    }

    fun login() {
        val state = _uiState.value
        if (state.login.isBlank()) {
            _uiState.value = state.copy(error = "Please enter your email or member ID")
            return
        }
        if (state.password.isBlank()) {
            _uiState.value = state.copy(error = "Please enter your password")
            return
        }

        val url = state.serverUrl.trim().trimEnd('/')
        prefs.edit().putString("server_url", url).apply()

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = withContext(Dispatchers.IO) { repository.login(state.login.trim(), state.password, url) }
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isLoading = false, isLoggedIn = true)
                },
                onFailure = { e ->
                    val msg = when (e) {
                        is CrunchAuthException -> when {
                            e.httpCode == 401 && e.apiCause == "loginFailureAttemptsExceeded" ->
                                "Account locked. Too many failed attempts. Try again later."
                            e.httpCode == 401 && e.apiCause == "userAccountTemporarilyLocked" ->
                                "Account temporarily locked. Please wait."
                            e.httpCode == 401 -> "Invalid email or password."
                            e.httpCode == 400 -> "Please check your information and try again."
                            e.httpCode == 403 -> "Account requires migration. Contact support."
                            else -> "Login failed (${e.httpCode}): ${e.apiMessage}"
                        }
                        is java.net.UnknownHostException -> "No internet connection."
                        is java.net.SocketTimeoutException -> "Connection timed out."
                        else -> e.localizedMessage ?: "Login failed. Check your server URL or credentials."
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false, error = msg)
                }
            )
        }
    }

    class Factory(private val application: Application, private val repository: CrunchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LoginViewModel(application, repository) as T
    }
}
