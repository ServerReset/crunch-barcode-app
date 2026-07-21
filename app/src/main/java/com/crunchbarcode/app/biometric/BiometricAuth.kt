package com.crunchbarcode.app.biometric

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.biometric.BiometricPrompt.PromptInfo
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import java.util.concurrent.Executors

sealed class BiometricState {
    data object Unavailable : BiometricState()
    data object Locked : BiometricState()
    data object Unlocked : BiometricState()
    data object Authenticating : BiometricState()
}

@Composable
fun rememberBiometricState(activity: FragmentActivity): BiometricState {
    val biometricManager = BiometricManager.from(activity)
    val canAuthenticate = biometricManager.canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
    )

    var state by remember(canAuthenticate) {
        mutableStateOf(
            when {
                canAuthenticate == BiometricManager.BIOMETRIC_SUCCESS -> BiometricState.Locked
                else -> BiometricState.Unavailable
            }
        )
    }

    val executor = remember { Executors.newSingleThreadExecutor() }

    if (state == BiometricState.Locked) {
        LaunchedEffect(Unit) {
            val promptInfo = PromptInfo.Builder()
                .setTitle("Crunch Barcode")
                .setSubtitle("Authenticate to view your barcode")
                .setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL
                )
                .build()

            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    state = BiometricState.Unlocked
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        state = BiometricState.Locked
                    } else {
                        state = BiometricState.Unavailable
                    }
                }
                override fun onAuthenticationFailed() {
                    state = BiometricState.Locked
                }
            }

            val prompt = BiometricPrompt(activity, executor, callback)
            prompt.authenticate(promptInfo)
        }
    }

    return state
}
