package com.crunchbarcode.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BarcodeScreen(
    viewModel: BarcodeViewModel,
    onLogout: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val uriHandler = LocalUriHandler.current
    val snackbarHostState = remember { SnackbarHostState() }
    val haptics = LocalHapticFeedback.current

    val pullToRefreshState = rememberPullToRefreshState()

    LaunchedEffect(state.googlePayJwt) {
        state.googlePayJwt?.let { jwt ->
            try { uriHandler.openUri("https://pay.google.com/gp/p/ui/pay?jwt=$jwt") }
            catch (_: Exception) { snackbarHostState.showSnackbar("Failed to open Google Wallet") }
        }
    }

    LaunchedEffect(state.countdownSeconds) {
        if (state.countdownSeconds <= 0 && state.barcodeValue != null) {
            viewModel.startCountdown()
        }
    }

    LaunchedEffect(state.justCopied) {
        if (state.justCopied) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            snackbarHostState.showSnackbar("Barcode copied to clipboard")
            delay(1500)
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
                title = {
                    Column {
                        Text("My Barcode", style = MaterialTheme.typography.titleLarge)
                        state.memberFirstName?.let {
                            Text("Welcome back, $it", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.logout(); onLogout() }) {
                        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sign out")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(pullToRefreshState.nestedScrollConnection)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (state.isLoading && state.barcodeBitmap == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .weight(1f),
                        contentAlignment = Alignment.Center
                    ) {
                        ShimmerLoading()
                    }
                } else {
                    AnimatedContent(
                        targetState = state.error != null && state.barcodeBitmap == null,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "barcode_content"
                    ) { isError ->
                        if (isError) {
                            ErrorContent(state.error ?: "", viewModel::loadBarcode)
                        } else {
                            BarcodeContent(state, viewModel)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (!state.isUpdateChecking && state.update != null) {
                    val updateState = state.update
                    UpdateBanner(
                        version = updateState.latestVersion,
                        isDownloading = state.isDownloading,
                        progress = state.downloadProgress,
                        onUpdate = viewModel::downloadAndInstall
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            if (pullToRefreshState.isRefreshing) {
                LaunchedEffect(true) {
                    viewModel.loadBarcode()
                    delay(1000)
                    pullToRefreshState.endRefresh()
                }
            }

            PullToRefreshContainer(
                state = pullToRefreshState,
                modifier = Modifier.align(Alignment.TopCenter),
                shape = RoundedCornerShape(16.dp)
            )
        }
    }
}

@Composable
private fun BarcodeContent(
    state: BarcodeUiState,
    viewModel: BarcodeViewModel
) {
    Spacer(modifier = Modifier.height(12.dp))

    state.barcodeBitmap?.let { bitmap ->
        BarcodeImageCard(bitmap = bitmap)

        Spacer(modifier = Modifier.height(12.dp))

        state.barcodeValue?.let { value ->
            BarcodeValueCard(value = value)

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CountdownChip(seconds = state.countdownSeconds)
                CopyChip(
                    onCopy = viewModel::copyBarcodeToClipboard
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        ActionButtons(
            onRefresh = viewModel::loadBarcode,
            onGooglePay = viewModel::loadGooglePayJwt,
            isGooglePayLoading = state.isGooglePayLoading
        )
    }
}

@Composable
private fun BarcodeImageCard(bitmap: Bitmap) {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutCubic),
            repeatMode = RepeatMode.Reverse
        ),
        label = "barcode_pulse"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Member barcode",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .graphicsLayer(alpha = alpha),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun BarcodeValueCard(value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.weight(1f),
                letterSpacing = 2.sp
            )
        }
    }
}

@Composable
private fun CountdownChip(seconds: Int) {
    val minutes = seconds / 60
    val secs = seconds % 60
    val isUrgent = seconds < 60

    SuggestionChip(
        onClick = {},
        icon = {
            Icon(
                if (isUrgent) Icons.Default.Timer else Icons.Default.TimerOutlined,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (isUrgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        label = {
            Text(
                if (seconds > 0) "Auto-refresh in ${minutes}m ${secs}s" else "Refreshing...",
                style = MaterialTheme.typography.labelSmall
            )
        },
        colors = SuggestionChipDefaults.suggestionChipColors(
            containerColor = if (isUrgent)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    )
}

@Composable
private fun CopyChip(onCopy: () -> Unit) {
    SuggestionChip(
        onClick = onCopy,
        icon = {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        label = { Text("Copy barcode", style = MaterialTheme.typography.labelSmall) }
    )
}

@Composable
private fun ActionButtons(
    onRefresh: () -> Unit,
    onGooglePay: () -> Unit,
    isGooglePayLoading: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Refresh")
        }

        Button(
            onClick = onGooglePay,
            enabled = !isGooglePayLoading,
            modifier = Modifier
                .weight(1f)
                .height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            if (isGooglePayLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.AccountBalanceWallet, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Save to Wallet")
            }
        }
    }
}

@Composable
private fun ErrorContent(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.ErrorOutline, contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        FilledTonalButton(onClick = onRetry, shape = RoundedCornerShape(16.dp)) {
            Icon(Icons.Default.Refresh, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Retry")
        }
    }
}

@Composable
private fun ShimmerLoading() {
    val infiniteTransition = rememberInfiniteTransition()
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f, targetValue = 0.7f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "shimmer"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
        )
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .height(24.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = alpha))
        )
        Spacer(Modifier.height(32.dp))
        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
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
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.SystemUpdateAlt, contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Update v$version available",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold)
                if (isDownloading) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(progress = { progress },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)))
                }
            }
            Spacer(Modifier.width(8.dp))
            FilledTonalButton(onClick = onUpdate, enabled = !isDownloading) {
                Text(if (isDownloading) "..." else "Update")
            }
        }
    }
}

@Composable
private fun InstallDialog(onInstall: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Security, contentDescription = null) },
        title = { Text("Install Update") },
        text = {
            Column {
                Text("Your device may ask you to scan the app before installing. Tap More Details → Install Anyway, then confirm with biometrics or device PIN.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "This APK is built from the same source code available on GitHub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(onClick = onInstall) { Text("Install") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Later") }
        }
    )
}
