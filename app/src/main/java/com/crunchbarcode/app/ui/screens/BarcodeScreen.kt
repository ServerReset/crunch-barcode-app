package com.crunchbarcode.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeScreen(
    viewModel: BarcodeViewModel,
    onLogout: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.googlePayJwt) {
        state.googlePayJwt?.let { jwt ->
            try {
                uriHandler.openUri("https://pay.google.com/gp/p/ui/pay?jwt=$jwt")
            } catch (_: Exception) {
                snackbarHostState.showSnackbar("Failed to open Google Wallet")
            }
        }
    }

    if (state.installPrompt) {
        InstallDialog(
            onInstall = { viewModel.launchInstall() },
            onDismiss = { viewModel.dismissInstallPrompt() }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("My Barcode") },
                actions = {
                    IconButton(onClick = {
                        viewModel.logout()
                        onLogout()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sign out")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val updateState = state.update
            if (!state.isUpdateChecking && updateState != null) {
                UpdateBanner(
                    version = updateState.latestVersion,
                    isDownloading = state.isDownloading,
                    progress = state.downloadProgress,
                    onUpdate = { viewModel.downloadAndInstall() }
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else if (state.error != null && state.barcodeBitmap == null) {
                    Spacer(modifier = Modifier.height(48.dp))
                    Icon(
                        Icons.Default.ErrorOutline, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = state.error ?: "Error loading barcode",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    OutlinedButton(onClick = viewModel::loadBarcode) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Retry")
                    }
                } else {
                    state.barcodeBitmap?.let { bitmap ->
                        Spacer(modifier = Modifier.height(16.dp))
                        BarcodeImage(bitmap = bitmap)

                        Spacer(modifier = Modifier.height(16.dp))

                        state.barcodeValue?.let { value ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            ) {
                                Text(
                                    text = value,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(24.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = viewModel::loadBarcode,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Refresh")
                            }

                            Button(
                                onClick = { viewModel.loadGooglePayJwt() },
                                enabled = !state.isGooglePayLoading,
                                modifier = Modifier.weight(1f)
                            ) {
                                if (state.isGooglePayLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Icon(Icons.Default.AccountBalanceWallet, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Save to Wallet")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun BarcodeImage(bitmap: Bitmap) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Member barcode",
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            contentScale = ContentScale.Fit
        )
    }
}

@Composable
private fun UpdateBanner(
    version: String,
    isDownloading: Boolean,
    progress: Float,
    onUpdate: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.SystemUpdateAlt, contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Update v$version available",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (isDownloading) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(
                onClick = onUpdate,
                enabled = !isDownloading
            ) {
                Text(if (isDownloading) "..." else "Update")
            }
        }
    }
}

@Composable
private fun InstallDialog(
    onInstall: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(Icons.Default.Warning, contentDescription = null)
        },
        title = {
            Text("Install Update")
        },
        text = {
            Column {
                Text(
                    "Your device may ask you to scan the app before installing."

                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "If it does, tap More Details → Install Anyway " +
                            "and then use your biometrics or PIN to confirm."
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "The app will not read any of your personal data. " +
                            "The source is the same GitHub release you just downloaded from.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onInstall) {
                Text("Install")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Later")
            }
        }
    )
}
