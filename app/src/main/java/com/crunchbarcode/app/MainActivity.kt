package com.crunchbarcode.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.crunchbarcode.app.biometric.BiometricState
import com.crunchbarcode.app.biometric.rememberBiometricState
import com.crunchbarcode.app.ui.screens.BarcodeScreen
import com.crunchbarcode.app.ui.screens.BarcodeViewModel
import com.crunchbarcode.app.ui.screens.LoginScreen
import com.crunchbarcode.app.ui.screens.LoginViewModel
import com.crunchbarcode.app.ui.theme.CrunchBarcodeTheme

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen(); super.onCreate(savedInstanceState); enableEdgeToEdge()
        val app = application as CrunchApp
        setContent {
            CrunchBarcodeTheme {
                var ready by remember { mutableStateOf(false) }
                val navController = rememberNavController()
                val bioState = rememberBiometricState(this)

                LaunchedEffect(bioState) {
                    if (bioState == BiometricState.Unlocked || bioState == BiometricState.Unavailable) ready = true
                }

                AnimatedContent(targetState = ready, transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(200)) }, label = "") { r ->
                    if (r) {
                        NavHost(navController, "login") {
                            composable("login") {
                                val vm: LoginViewModel = viewModel(factory = LoginViewModel.Factory(application, app.repository))
                                LoginScreen(vm) { navController.navigate("barcode") { popUpTo("login") { inclusive = true } } }
                            }
                            composable("barcode") {
                                val vm: BarcodeViewModel = viewModel(factory = BarcodeViewModel.Factory(application, app.repository))
                                BarcodeScreen(vm) { navController.navigate("login") { popUpTo("barcode") { inclusive = true } } }
                            }
                        }
                    } else if (bioState == BiometricState.Locked) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text("🔒", style = MaterialTheme.typography.displayLarge)
                                Spacer(Modifier.height(16.dp))
                                Text("Crunch Barcode", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(8.dp))
                                Text("Authenticate to view your barcode", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(24.dp)); CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
