package com.crunchbarcode.app.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebLoginScreen(
    startUrl: String = "https://www.egym.com/mvc/login?appName=Crunch",
    onAuthCode: (String) -> Unit,
    onBack: () -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Crunch Login") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } })
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                isLoading = true
                                url?.let { checkUrl(it, onAuthCode) }
                            }
                            override fun onPageFinished(view: WebView?, url: String?) {
                                isLoading = false
                                url?.let { checkUrl(it, onAuthCode) }
                            }
                            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                                error = "Loading failed: $description"
                                isLoading = false
                            }
                        }
                        loadUrl(startUrl)
                    }
                },
                update = { it.loadUrl(startUrl) }
            )

            if (isLoading) {
                CircularProgressIndicator(Modifier.align(Alignment.TopCenter).padding(top = 48.dp))
            }

            error?.let { err ->
                Card(Modifier.fillMaxWidth().padding(16.dp).align(Alignment.BottomCenter),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(err, modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}

private fun checkUrl(url: String, onCode: (String) -> Unit) {
    val uri = android.net.Uri.parse(url)
    val code = uri.getQueryParameter("code") ?: uri.getQueryParameter("authCode")
    val token = uri.getQueryParameter("token") ?: uri.getQueryParameter("accessToken")
    if (code != null) { onCode(code); return }
    if (token != null) { onCode(token); return }
    if (url.contains("passwordlessLogIn") && (code != null || token != null)) {
        onCode(code ?: token!!)
    }
}
