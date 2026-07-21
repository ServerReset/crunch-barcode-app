package com.crunchbarcode.app.ui.screens

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crunchbarcode.app.BuildConfig
import com.crunchbarcode.app.data.repository.CrunchRepository
import com.crunchbarcode.app.update.AppUpdate
import com.crunchbarcode.app.update.UpdateChecker
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class BarcodeUiState(
    val barcodeValue: String? = null,
    val barcodeBitmap: Bitmap? = null,
    val barcodeFormat: BarcodeFormat = BarcodeFormat.CODE_128,
    val isLoading: Boolean = true,
    val error: String? = null,
    val googlePayJwt: String? = null,
    val isGooglePayLoading: Boolean = false,
    val update: AppUpdate? = null,
    val isUpdateChecking: Boolean = true,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val installPrompt: Boolean = false,
    val installUri: Uri? = null
)

class BarcodeViewModel(
    private val application: Application,
    private val repository: CrunchRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BarcodeUiState())
    val uiState: StateFlow<BarcodeUiState> = _uiState.asStateFlow()

    private val writer = MultiFormatWriter()
    private val updateChecker = UpdateChecker(BuildConfig.VERSION_NAME)

    init {
        loadBarcode()
        checkForUpdate()
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
                        error = "Failed to load barcode: ${e.localizedMessage}"
                    )
                }
            )
        }
    }

    private suspend fun generateBitmap(value: String) = withContext(Dispatchers.Default) {
        try {
            val hints = mapOf(EncodeHintType.MARGIN to 0)
            val bitMatrix: BitMatrix = try {
                writer.encode(value, BarcodeFormat.QR_CODE, 512, 512, hints)
            } catch (_: Exception) {
                writer.encode(value, BarcodeFormat.CODE_128, 1024, 256, hints)
            }
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    pixels[y * width + x] = if (bitMatrix[x, y]) {
                        0xFF000000.toInt()
                    } else {
                        0xFFFFFFFF.toInt()
                    }
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            _uiState.value = _uiState.value.copy(
                barcodeValue = value,
                barcodeBitmap = bitmap,
                isLoading = false,
                error = null
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                error = "Barcode render failed: ${e.localizedMessage}"
            )
        }
    }

    fun loadGooglePayJwt() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isGooglePayLoading = true)
            val result = withContext(Dispatchers.IO) { repository.getGooglePayJwt() }
            result.fold(
                onSuccess = { jwt ->
                    _uiState.value = _uiState.value.copy(googlePayJwt = jwt, isGooglePayLoading = false)
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isGooglePayLoading = false,
                        error = "Google Wallet: ${e.localizedMessage}"
                    )
                }
            )
        }
    }

    private fun checkForUpdate() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isUpdateChecking = true)
            val result = updateChecker.checkForUpdate()
            result.fold(
                onSuccess = { update ->
                    _uiState.value = _uiState.value.copy(
                        update = if (update.isNewer) update else null,
                        isUpdateChecking = false
                    )
                },
                onFailure = {
                    _uiState.value = _uiState.value.copy(isUpdateChecking = false)
                }
            )
        }
    }

    fun downloadAndInstall() {
        val update = _uiState.value.update ?: return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isDownloading = true, downloadProgress = 0f)
            val result = withContext(Dispatchers.IO) { downloadApk(update.downloadUrl) }
            result.fold(
                onSuccess = { uri ->
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        installUri = uri,
                        installPrompt = true
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isDownloading = false,
                        error = "Download failed: ${e.localizedMessage}"
                    )
                }
            )
        }
    }

    private fun downloadApk(downloadUrl: String): Result<Uri> {
        return try {
            val dir = File(application.cacheDir, "updates")
            dir.mkdirs()
            dir.listFiles()?.forEach { it.delete() }

            val file = File(dir, "crunch-barcode-update.apk")
            val url = URL(downloadUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 30000
            conn.connect()

            val totalSize = conn.contentLengthLong
            val input = conn.inputStream
            val output = FileOutputStream(file)
            val buffer = ByteArray(8192)
            var bytesRead: Int
            var totalRead = 0L

            while (input.read(buffer).also { bytesRead = it } != -1) {
                output.write(buffer, 0, bytesRead)
                totalRead += bytesRead
                if (totalSize > 0) {
                    val progress = totalRead.toFloat() / totalSize.toFloat()
                    _uiState.value = _uiState.value.copy(downloadProgress = progress)
                }
            }

            output.close()
            input.close()

            val uri = FileProvider.getUriForFile(
                application,
                "${application.packageName}.fileprovider",
                file
            )
            Result.success(uri)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun launchInstall() {
        val uri = _uiState.value.installUri ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        try {
            application.startActivity(intent)
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(error = "Could not start installer: ${e.localizedMessage}")
        }
    }

    fun dismissInstallPrompt() {
        _uiState.value = _uiState.value.copy(installPrompt = false)
    }

    fun logout() {
        repository.logout()
    }

    class Factory(private val application: Application, private val repository: CrunchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BarcodeViewModel(application, repository) as T
        }
    }
}
