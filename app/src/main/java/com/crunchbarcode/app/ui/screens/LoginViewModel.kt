package com.crunchbarcode.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crunchbarcode.app.data.api.CrunchAuthException
import com.crunchbarcode.app.data.repository.CrunchRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class LoginUiState(
    val login: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isLoggedIn: Boolean = false,
    val isPasswordVisible: Boolean = false
)

class LoginViewModel(
    private val repository: CrunchRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    init {
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
        _uiState.value = _uiState.value.copy(
            isPasswordVisible = !_uiState.value.isPasswordVisible
        )
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

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = repository.login(state.login.trim(), state.password)
            result.fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(isLoading = false, isLoggedIn = true)
                },
                onFailure = { e ->
                    val msg = when (e) {
                        is CrunchAuthException -> when {
                            e.httpCode == 401 && e.apiCause == "loginFailureAttemptsExceeded" ->
                                "Account temporarily locked due to too many attempts. Try again later."
                            e.httpCode == 401 && e.apiCause == "userAccountTemporarilyLocked" ->
                                "Account locked. Please wait before trying again."
                            e.httpCode == 401 -> "Invalid email or password. Please try again."
                            e.httpCode == 400 -> "Please check your information and try again."
                            e.httpCode == 403 -> "Account requires migration. Please contact support."
                            else -> "Login failed (${e.httpCode}): ${e.apiMessage}"
                        }
                        is java.net.UnknownHostException -> "No internet connection. Check your network."
                        is java.net.SocketTimeoutException -> "Connection timed out. Please try again."
                        else -> e.localizedMessage ?: "An unexpected error occurred"
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false, error = msg)
                }
            )
        }
    }

    class Factory(private val repository: CrunchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return LoginViewModel(repository) as T
        }
    }
}
