package com.crunchbarcode.app.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.crunchbarcode.app.health.HealthData
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeScreen(viewModel: BarcodeViewModel, onLogout: () -> Unit) {
    val state by viewModel.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current

    val healthPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { viewModel.loadHealthData() }

    LaunchedEffect(state.googlePayJwt) {
        state.googlePayJwt?.let { jwt ->
            try { uriHandler.openUri("https://pay.google.com/gp/p/ui/pay?jwt=$jwt") }
            catch (_: Exception) { snackbarHostState.showSnackbar("Failed to open Google Wallet") }
        }
    }

    LaunchedEffect(state.countdownSeconds) {
        if (state.countdownSeconds <= 0 && state.barcodeValue != null) viewModel.startCountdown()
    }

    LaunchedEffect(state.justCopied) {
        if (state.justCopied) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            snackbarHostState.showSnackbar("Barcode copied")
        }
    }

    if (state.installPrompt) {
        InstallDialog(onInstall = { viewModel.launchInstall() }, onDismiss = { viewModel.dismissInstallPrompt() })
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Crunch", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("Welcome${state.memberFirstName?.let { ", $it" } ?: ""}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.logout(); onLogout() }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sign out")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        }
    ) { padding ->
        if (state.isLoading && state.barcodeBitmap == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                HealthCard(state.healthData, viewModel, healthPermissionLauncher)

                Spacer(Modifier.height(4.dp))

                val bmp = state.barcodeBitmap
                if (bmp != null) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        BarcodeImageCard(bmp, state.barcodeValue, viewModel)
                    }
                } else {
                    ErrorContent(state.error ?: "No barcode", viewModel::loadBarcode)
                }

                Spacer(Modifier.height(4.dp))

                ControlsRow(state, viewModel)

                val up = state.update
                if (!state.isUpdateChecking && up != null) {
                    Spacer(Modifier.height(4.dp))
                    UpdateBanner(up.latestVersion, state.isDownloading, state.downloadProgress, viewModel::downloadAndInstall)
                }
            }
        }
    }
}

@Composable
private fun BarcodeImageCard(bmp: Bitmap, value: String?, vm: BarcodeViewModel) {
    val pulse = rememberInfiniteTransition().animateFloat(0.88f, 1f,
        infiniteRepeatable(tween(1500, easing = EaseInOutCubic), RepeatMode.Reverse), label = "pulse")
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = "Barcode",
                modifier = Modifier.fillMaxWidth().padding(16.dp).graphicsLayer(alpha = pulse.value),
                contentScale = ContentScale.Fit)
            value?.let { v ->
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Text(v, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::copyBarcodeToClipboard, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthCard(data: HealthData, vm: BarcodeViewModel, launcher: Any) {
    @Suppress("UNCHECKED_CAST")
    val permLauncher = launcher as androidx.activity.result.ActivityResultLauncher<Intent>

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        if (data.isLoading) {
            Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else if (data.hasData) {
            Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                StatItem(Icons.Default.DirectionsWalk, "${formatNum(data.stepsToday)}", "Today")
                StatItem(Icons.Default.Speed, "${data.workoutsThisWeek}", "Workouts")
                StatItem(Icons.Default.LocalFireDepartment, "${data.caloriesToday.toInt()}", "Cal")
                data.lastWorkoutName?.let {
                    StatItem(Icons.Default.FitnessCenter, it.take(8), data.lastWorkoutDate?.take(5) ?: "")
                }
                if (data.lastWorkoutName == null) {
                    StatItem(Icons.Default.Favorite, "OK", "Ready")
                }
            }
        } else if (data.isAvailable && !data.isAuthorized) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.MonitorHeart, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Connect Health", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = {
                    vm.getHealthPermissionIntent()?.let { intent ->
                        permLauncher.launch(Intent.createChooser(intent, "Health Connect"))
                    }
                }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Text("Connect", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun StatItem(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ControlsRow(state: BarcodeUiState, vm: BarcodeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SuggestionChip(onClick = vm::loadBarcode, icon = {
                Icon(Icons.Default.Refresh, null, Modifier.size(16.dp))
            }, label = { Text("Refresh", style = MaterialTheme.typography.labelSmall) })

            SuggestionChip(onClick = vm::copyBarcodeToClipboard, icon = {
                Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
            }, label = { Text("Copy", style = MaterialTheme.typography.labelSmall) })

            SuggestionChip(onClick = vm::saveBarcodeToGallery, icon = {
                Icon(Icons.Default.SaveAlt, null, Modifier.size(16.dp))
            }, label = { Text("Save", style = MaterialTheme.typography.labelSmall) })

            SuggestionChip(onClick = vm::shareBarcode, icon = {
                Icon(Icons.Default.Share, null, Modifier.size(16.dp))
            }, label = { Text("Share", style = MaterialTheme.typography.labelSmall) })
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SuggestionChip(onClick = vm::loadGooglePayJwt, enabled = !state.isGooglePayLoading, icon = {
                if (state.isGooglePayLoading) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.AccountBalanceWallet, null, Modifier.size(16.dp))
            }, label = { Text("Google Wallet", style = MaterialTheme.typography.labelSmall) })

            SuggestionChip(onClick = vm::trySamsungWallet, icon = {
                Icon(Icons.Default.PhoneAndroid, null, Modifier.size(16.dp))
            }, label = { Text("Samsung Wallet", style = MaterialTheme.typography.labelSmall) })

            val secs = state.countdownSeconds; val urgent = secs < 60
            if (secs > 0) {
                SuggestionChip(onClick = {},
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (urgent) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant),
                    label = {
                        Text("${secs / 60}m ${secs % 60}s", style = MaterialTheme.typography.labelSmall,
                            color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )
            }
        }
    }
}

@Composable
private fun ErrorContent(error: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        FilledTonalButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun UpdateBanner(v: String, downloading: Boolean, progress: Float, onUpdate: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdateAlt, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(8.dp))
            Text("v$v available", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (downloading) LinearProgressIndicator({ progress }, Modifier.width(60.dp).height(4.dp).clip(RoundedCornerShape(2.dp)))
            else FilledTonalButton(onClick = onUpdate, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) {
                Text("Get", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun InstallDialog(onInstall: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Security, null) },
        title = { Text("Install") },
        text = { Text("Tap More Details → Install Anyway, then confirm with biometrics.") },
        confirmButton = { Button(onClick = onInstall) { Text("Install") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } }
    )
}

private fun formatNum(n: Int): String = if (n >= 1000) "${n / 1000}.${(n % 1000) / 100}k" else n.toString()
