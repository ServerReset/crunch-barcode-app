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
fun BarcodeScreen(vm: BarcodeViewModel, onLogout: () -> Unit) {
    val s by vm.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current; val sb = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.loadHealthData() }

    LaunchedEffect(s.googlePayJwt) { s.googlePayJwt?.let { try { uriHandler.openUri("https://pay.google.com/gp/p/ui/pay?jwt=$it") } catch (_: Exception) {} } }
    LaunchedEffect(s.countdownSeconds) { if (s.countdownSeconds <= 0 && s.barcodeValue != null) vm.startCountdown() }
    LaunchedEffect(s.justCopied) { if (s.justCopied) { haptics.performHapticFeedback(HapticFeedbackType.LongPress); sb.showSnackbar("Copied") } }

    if (s.installPrompt) InstallDialog({ vm.launchInstall() }, { vm.dismissInstallPrompt() })

    Scaffold(snackbarHost = { SnackbarHost(sb) }, topBar = {
        TopAppBar(title = {
            Column { Text("Crunch", fontWeight = FontWeight.Bold); Text("Welcome${s.memberFirstName?.let { ", $it" } ?: ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }, actions = { IconButton(onClick = { vm.logout(); onLogout() }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Sign out") } }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface))
    }) { p ->
        if (s.isLoading && s.barcodeBitmap == null) Box(Modifier.fillMaxSize().padding(p), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else Column(Modifier.fillMaxSize().padding(p).padding(horizontal = 16.dp), verticalArrangement = Arrangement.SpaceEvenly) {
            HealthCard(s.healthData, vm, permLauncher)
            Spacer(Modifier.height(4.dp))
            val bmp = s.barcodeBitmap
            if (bmp != null) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { BarcodeCard(bmp, s.barcodeValue, vm) }
            else ErrorContent(s.error ?: "No barcode", vm::loadBarcode)
            Spacer(Modifier.height(4.dp))
            ControlsRow(s, vm)
            val u = s.update
            if (!s.isUpdateChecking && u != null) UpdateBanner(u.latestVersion, s.isDownloading, s.downloadProgress, vm::downloadAndInstall)
        }
    }
}

@Composable
private fun BarcodeCard(bmp: Bitmap, value: String?, vm: BarcodeViewModel) {
    val pulse = rememberInfiniteTransition().animateFloat(0.88f, 1f, infiniteRepeatable(tween(1500, easing = EaseInOutCubic), RepeatMode.Reverse), label = "p")
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), elevation = CardDefaults.cardElevation(4.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(bitmap = bmp.asImageBitmap(), null, Modifier.fillMaxWidth().padding(16.dp).graphicsLayer(alpha = pulse.value), contentScale = ContentScale.Fit)
            value?.let { v ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    Text(v, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = vm::copyBarcodeToClipboard, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun HealthCard(d: HealthData, vm: BarcodeViewModel, launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        if (d.isLoading) Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        else if (d.hasData) Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Stat(Icons.Default.DirectionsWalk, formatNum(d.stepsToday), "Today")
            Stat(Icons.Default.Speed, "${d.workoutsThisWeek}", "Workouts")
            Stat(Icons.Default.LocalFireDepartment, "${d.caloriesToday.toInt()}", "Cal")
            Stat(Icons.Default.FitnessCenter, d.lastWorkoutName?.take(8) ?: "OK", d.lastWorkoutDate?.take(5) ?: "Ready")
        }
        else if (d.isAvailable && !d.isAuthorized) Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.MonitorHeart, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp)); Text("Health Connect", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            FilledTonalButton(onClick = { vm.getHealthPermissionIntent()?.let { launcher.launch(Intent.createChooser(it, "Health Connect")) } }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("Connect", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, v: String, l: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Text(v, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ControlsRow(s: BarcodeUiState, vm: BarcodeViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Refresh", Icons.Default.Refresh) { vm.loadBarcode() }
            Chip("Copy", Icons.Default.ContentCopy) { vm.copyBarcodeToClipboard() }
            Chip("Save", Icons.Default.SaveAlt) { vm.saveBarcodeToGallery() }
            Chip("Share", Icons.Default.Share) { vm.shareBarcode() }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("Google Wallet", Icons.Default.AccountBalanceWallet, !s.isGooglePayLoading) { vm.loadGooglePayJwt() }
            Chip("Samsung Wallet", Icons.Default.PhoneAndroid) { vm.trySamsungWallet() }
            if (s.countdownSeconds > 0) {
                val urgent = s.countdownSeconds < 60
                SuggestionChip(onClick = {}, colors = SuggestionChipDefaults.suggestionChipColors(containerColor = if (urgent) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant),
                    label = { Text("${s.countdownSeconds / 60}m ${s.countdownSeconds % 60}s", style = MaterialTheme.typography.labelSmall, color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
        s.lastRefreshed?.let { t ->
            Text("Updated $t", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
                val urgent = s.countdownSeconds < 60
                SuggestionChip(onClick = {}, colors = SuggestionChipDefaults.suggestionChipColors(containerColor = if (urgent) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surfaceVariant),
                    label = { Text("${s.countdownSeconds / 60}m ${s.countdownSeconds % 60}s", style = MaterialTheme.typography.labelSmall, color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) })
            }
        }
        val lastRef = s.lastRefreshed
        if (lastRef != null) {
            Text("Updated $lastRef", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Chip(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    SuggestionChip(onClick = onClick, enabled = enabled, icon = { Icon(icon, null, Modifier.size(16.dp)) }, label = { Text(label, style = MaterialTheme.typography.labelSmall) })
}

@Composable
private fun ErrorContent(e: String, retry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Default.ErrorOutline, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp)); Text(e, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp)); FilledTonalButton(onClick = retry) { Text("Retry") }
    }
}

@Composable
private fun UpdateBanner(v: String, d: Boolean, p: Float, onUpdate: () -> Unit) {
    Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.SystemUpdateAlt, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(8.dp)); Text("v$v", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (d) LinearProgressIndicator({ p }, Modifier.width(60.dp).height(4.dp).clip(RoundedCornerShape(2.dp)))
            else FilledTonalButton(onClick = onUpdate, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)) { Text("Get", style = MaterialTheme.typography.labelSmall) }
        }
    }
}

@Composable
private fun InstallDialog(onInstall: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Default.Security, null) }, title = { Text("Install") },
        text = { Text("Tap More Details → Install Anyway, then confirm with biometrics.") },
        confirmButton = { Button(onClick = onInstall) { Text("Install") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Later") } })
}

private fun formatNum(n: Int) = if (n >= 1000) "${n / 1000}.${(n % 1000) / 100}k" else n.toString()
