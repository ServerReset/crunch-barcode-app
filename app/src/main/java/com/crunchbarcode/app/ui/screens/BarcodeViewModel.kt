package com.crunchbarcode.app.ui.screens

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crunchbarcode.app.BuildConfig
import com.crunchbarcode.app.data.repository.CrunchRepository
import com.crunchbarcode.app.health.HealthConnectManager
import com.crunchbarcode.app.health.HealthData
import com.crunchbarcode.app.update.AppUpdate
import com.crunchbarcode.app.update.UpdateChecker
import com.crunchbarcode.app.widget.BarcodeWidgetProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class BarcodeUiState(
    val barcodeValue: String? = null,
    val barcodeBitmap: Bitmap? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val googlePayJwt: String? = null,
    val isGooglePayLoading: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateChecking: Boolean = true,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val installPrompt: Boolean = false,
    val installUri: Uri? = null,
    val countdownSeconds: Int = 0,
    val justCopied: Boolean = false,
    val memberFirstName: String? = null,
    val healthData: HealthData = HealthData(),
    val healthLoading: Boolean = false
)

class BarcodeViewModel(
    private val application: Application,
    private val repository: CrunchRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BarcodeUiState())
    val uiState: StateFlow<BarcodeUiState> = _uiState.asStateFlow()

    private val writer = MultiFormatWriter()
    private val updateChecker = UpdateChecker(BuildConfig.VERSION_NAME)
    private val healthManager = HealthConnectManager(application)
    private var countdownJob: Job? = null

    private val refreshThresholdMs = 5 * 60 * 1000L

    init {
        val creds = repository.savedCredentials
        if (creds != null) {
            _uiState.value = _uiState.value.copy(memberFirstName = creds.firstName)
        }
        loadBarcode()
        checkForUpdate()
        loadHealthData()
    }

    fun loadBarcode() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = withContext(Dispatchers.IO) { repository.refreshBarcode() }
            result.fold(
                onSuccess = { barcodeResp ->
                    val value = barcodeResp.barcode
                    if (value != null && value.isNotEmpty()) {
                        generateBitmap(value)
                    } else {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            error = barcodeResp.errorMessage ?: "Empty barcode value"
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = e.localizedMessage ?: "Failed to load barcode"
                    )
                }
            )
        }
    }

    private suspend fun generateBitmap(value: String) = withContext(Dispatchers.Default) {
        try {
            val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "ISO-8859-1")
            val bitMatrix = try {
                writer.encode(value, BarcodeFormat.QR_CODE, 512, 512, hints)
            } catch (_: Exception) {
                writer.encode(value, BarcodeFormat.CODE_128, 1024, 256, hints)
            }
            val w = bitMatrix.width; val h = bitMatrix.height
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val px = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w)
                px[y * w + x] = if (bitMatrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            bmp.setPixels(px, 0, w, 0, 0, w, h)

            BarcodeWidgetProvider.pushBarcodeUpdate(application, bmp)

            _uiState.value = _uiState.value.copy(
                barcodeValue = value, barcodeBitmap = bmp, isLoading = false, error = null
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false, error = "Barcode render failed: ${e.localizedMessage}"
            )
        }
    }

    fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var remaining = (refreshThresholdMs / 1000).toInt()
            while (isActive && remaining > 0) {
                _uiState.value = _uiState.value.copy(countdownSeconds = remaining)
                delay(1000); remaining--
            }
            if (remaining <= 0) {
                _uiState.value = _uiState.value.copy(countdownSeconds = 0, isUpdateChecking = false)
                loadBarcode()
            }
        }
    }

    fun loadGooglePayJwt() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGooglePayLoading = true)
            withContext(Dispatchers.IO) { repository.getGooglePayJwt() }.fold(
                onSuccess = { jwt -> _uiState.value = _uiState.value.copy(googlePayJwt = jwt, isGooglePayLoading = false) },
                onFailure = { e -> _uiState.value = _uiState.value.copy(isGooglePayLoading = false, error = "Google Wallet: ${e.localizedMessage}") }
            )
        }
    }

    fun copyBarcodeToClipboard() {
        val value = _uiState.value.barcodeValue ?: return
        (application.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Crunch Barcode", value))
        _uiState.value = _uiState.value.copy(justCopied = true)
        viewModelScope.launch { delay(2000); _uiState.value = _uiState.value.copy(justCopied = false) }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUpdateChecking = true)
            updateChecker.checkForUpdate().fold(
                onSuccess = { upd -> _uiState.value = _uiState.value.copy(update = if (upd.isNewer) upd else null, isUpdateChecking = false) },
                onFailure = { _uiState.value = _uiState.value.copy(isUpdateChecking = false) }
            )
        }
    }

    fun downloadAndInstall() {
        val update = _uiState.value.update ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDownloading = true, downloadProgress = 0f)
            withContext(Dispatchers.IO) { downloadApk(update.downloadUrl) }.fold(
                onSuccess = { uri -> _uiState.value = _uiState.value.copy(isDownloading = false, installUri = uri, installPrompt = true) },
                onFailure = { e -> _uiState.value = _uiState.value.copy(isDownloading = false, error = "Download failed: ${e.localizedMessage}") }
            )
        }
    }

    private fun downloadApk(downloadUrl: String): Result<Uri> = try {
        val dir = File(application.cacheDir, "updates").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "crunch-barcode-update.apk")
        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000; conn.readTimeout = 30000; conn.connect()
        FileOutputStream(file).use { out ->
            val buf = ByteArray(8192); var read: Int; var total = 0L; val size = conn.contentLengthLong
            while (conn.inputStream.read(buf).also { read = it } != -1) {
                out.write(buf, 0, read); total += read
                if (size > 0) _uiState.value = _uiState.value.copy(downloadProgress = total.toFloat() / size.toFloat())
            }
        }
        conn.inputStream.close()
        Result.success(FileProvider.getUriForFile(application, "${application.packageName}.fileprovider", file))
    } catch (e: Exception) { Result.failure(e) }

    fun launchInstall() {
        val uri = _uiState.value.installUri ?: return
        application.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        })
    }

    fun dismissInstallPrompt() { _uiState.value = _uiState.value.copy(installPrompt = false) }

    fun loadHealthData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(healthLoading = true)
            val data = withContext(Dispatchers.IO) { healthManager.loadHealthData() }
            _uiState.value = _uiState.value.copy(healthData = data, healthLoading = false)
        }
    }

    fun getHealthPermissionIntent() = healthManager.getPermissionIntent()

    fun logout() {
        countdownJob?.cancel()
        repository.logout()
    }

    class Factory(private val application: Application, private val repository: CrunchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BarcodeViewModel(application, repository) as T
    }
}
