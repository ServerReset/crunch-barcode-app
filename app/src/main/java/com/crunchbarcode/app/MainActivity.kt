package com.crunchbarcode.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.crunchbarcode.app.ui.screens.BarcodeScreen
import com.crunchbarcode.app.ui.screens.BarcodeViewModel
import com.crunchbarcode.app.ui.screens.LoginScreen
import com.crunchbarcode.app.ui.screens.LoginViewModel
import com.crunchbarcode.app.ui.theme.CrunchBarcodeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as CrunchApp

        setContent {
            CrunchBarcodeTheme {
                val navController = rememberNavController()

                NavHost(navController = navController, startDestination = "login") {
                    composable("login") {
                        val viewModel: LoginViewModel = viewModel(
                            factory = LoginViewModel.Factory(app.repository)
                        )
                        LoginScreen(
                            viewModel = viewModel,
                            onLoginSuccess = {
                                navController.navigate("barcode") {
                                    popUpTo("login") { inclusive = true }
                                }
                            }
                        )
                    }
                    composable("barcode") {
                        val viewModel: BarcodeViewModel = viewModel(
                            factory = BarcodeViewModel.Factory(app.repository)
                        )
                        BarcodeScreen(
                            viewModel = viewModel,
                            onLogout = {
                                navController.navigate("login") {
                                    popUpTo("barcode") { inclusive = true }
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}
