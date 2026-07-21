package com.crunchbarcode.app.ui.screens

import android.app.Application
import android.content.*
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import com.crunchbarcode.app.BuildConfig
import com.crunchbarcode.app.data.repository.CrunchRepository
import com.crunchbarcode.app.health.HealthConnectManager
import com.crunchbarcode.app.health.HealthData
import com.crunchbarcode.app.update.UpdateChecker
import com.crunchbarcode.app.widget.BarcodeWidgetProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class BarcodeUiState(
    val barcodeValue: String? = null, val barcodeBitmap: Bitmap? = null,
    val isLoading: Boolean = true, val error: String? = null,
    val googlePayJwt: String? = null, val isGooglePayLoading: Boolean = false,
    val update: com.crunchbarcode.app.update.AppUpdate? = null, val isUpdateChecking: Boolean = true,
    val isDownloading: Boolean = false, val downloadProgress: Float = 0f,
    val installPrompt: Boolean = false, val installUri: Uri? = null,
    val countdownSeconds: Int = 0, val justCopied: Boolean = false,
    val memberFirstName: String? = null, val healthData: HealthData = HealthData(), val healthLoading: Boolean = false
)

class BarcodeViewModel(private val app: Application, private val repo: CrunchRepository) : ViewModel() {
    private val _s = MutableStateFlow(BarcodeUiState()); val uiState = _s.asStateFlow()
    private val w = MultiFormatWriter()
    private val healthManager = HealthConnectManager(app)
    private var countdownJob: Job? = null
    private val refreshMs = 5 * 60 * 1000L

    init { _s.value = _s.value.copy(memberFirstName = repo.savedCredentials?.firstName); loadBarcode(); checkForUpdate(); loadHealthData() }

    fun loadBarcode() { viewModelScope.launch {
        _s.value = _s.value.copy(isLoading = true, error = null)
        withContext(Dispatchers.IO) { repo.refreshBarcode() }.fold(
            { v -> if (!v.barcode.isNullOrEmpty()) genBitmap(v.barcode) else _s.value = _s.value.copy(isLoading = false, error = v.errorMessage ?: "Empty") },
            { e -> _s.value = _s.value.copy(isLoading = false, error = e.localizedMessage ?: "Failed") })
    }}

    private suspend fun genBitmap(value: String) = withContext(Dispatchers.Default) { try {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "ISO-8859-1")
        val mx = try { w.encode(value, BarcodeFormat.QR_CODE, 512, 512, hints) } catch (_: Exception) { w.encode(value, BarcodeFormat.CODE_128, 1024, 256, hints) }
        val b = Bitmap.createBitmap(mx.width, mx.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(mx.width * mx.height); for (y in 0 until mx.height) for (x in 0 until mx.width) px[y * mx.width + x] = if (mx[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        b.setPixels(px, 0, mx.width, 0, 0, mx.width, mx.height)
        BarcodeWidgetProvider.pushBarcodeUpdate(app, b)
        _s.value = _s.value.copy(barcodeValue = value, barcodeBitmap = b, isLoading = false, error = null)
    } catch (e: Exception) { _s.value = _s.value.copy(isLoading = false, error = "Render failed: ${e.localizedMessage}") }}

    fun startCountdown() { countdownJob?.cancel(); countdownJob = viewModelScope.launch {
        var r = (refreshMs / 1000).toInt(); while (isActive && r > 0) { _s.value = _s.value.copy(countdownSeconds = r); delay(1000); r-- }
        if (r <= 0) { _s.value = _s.value.copy(countdownSeconds = 0); loadBarcode() }
    }}

    fun loadGooglePayJwt() { viewModelScope.launch {
        _s.value = _s.value.copy(isGooglePayLoading = true)
        withContext(Dispatchers.IO) { repo.getGooglePayJwt() }.fold({ _s.value = _s.value.copy(googlePayJwt = it, isGooglePayLoading = false) },
            { _s.value = _s.value.copy(isGooglePayLoading = false, error = "Wallet: ${it.localizedMessage}") })
    }}

    fun copyBarcodeToClipboard() { _s.value.barcodeValue?.let {
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Crunch Barcode", it))
        _s.value = _s.value.copy(justCopied = true); viewModelScope.launch { delay(2000); _s.value = _s.value.copy(justCopied = false) }
    }}

    fun saveBarcodeToGallery() {
        val bmp = _s.value.barcodeBitmap ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val cv = ContentValues()
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, "CrunchBarcode_${System.currentTimeMillis()}.png")
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    if (Build.VERSION.SDK_INT >= 29) {
                        cv.put(MediaStore.Images.Media.IS_PENDING, 1)
                        cv.put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CrunchBarcode")
                    }
                    val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
                        ?: throw Exception("No URI")
                    app.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        ?: throw Exception("No stream")
                    if (Build.VERSION.SDK_INT >= 29) {
                        cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                        app.contentResolver.update(uri, cv, null, null)
                    }
                    Result.success(Unit)
                } catch (e: Exception) { Result.failure(e) }
            }
            result.fold(onSuccess = {}, onFailure = { e ->
                _s.value = _s.value.copy(error = "Save failed: ${e.localizedMessage}")
            })
        }
    }

    fun shareBarcode() {
        val bmp = _s.value.barcodeBitmap ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val dir = File(app.cacheDir, "shared_barcodes").also { it.mkdirs() }
                    val file = File(dir, "crunch_barcode.png")
                    FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    app.startActivity(Intent.createChooser(intent, "Share barcode").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                } catch (_: Exception) {}
            }
        }
    }

    fun trySamsungWallet() { try { app.startActivity(Intent(Intent.ACTION_VIEW).apply { data = Uri.parse("samsungwallet://addPass"); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }) } catch (_: Exception) { shareBarcode() } }

    private fun checkForUpdate() { viewModelScope.launch {
        _s.value = _s.value.copy(isUpdateChecking = true)
        val result = UpdateChecker(BuildConfig.VERSION_NAME).checkForUpdate()
        result.fold(
            onSuccess = { upd -> _s.value = _s.value.copy(update = if (upd.isNewer) upd else null, isUpdateChecking = false) },
            onFailure = { _s.value = _s.value.copy(isUpdateChecking = false) }
        )
    }}

    fun downloadAndInstall() {
        val update = _s.value.update ?: return
        viewModelScope.launch {
            _s.value = _s.value.copy(isDownloading = true, downloadProgress = 0f)
            val result = withContext(Dispatchers.IO) { downloadApk(update.downloadUrl) }
            result.fold(
                onSuccess = { uri -> _s.value = _s.value.copy(isDownloading = false, installUri = uri, installPrompt = true) },
                onFailure = { e -> _s.value = _s.value.copy(isDownloading = false, error = "Download: ${e.localizedMessage}") }
            )
        }
    }

    private fun downloadApk(downloadUrl: String): Result<Uri> = try {
        val dir = File(app.cacheDir, "updates").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "crunch-barcode-update.apk")
        val conn = URL(downloadUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000; conn.readTimeout = 30000; conn.connect()
        FileOutputStream(file).use { out ->
            val buf = ByteArray(8192); var r: Int; var total = 0L; val size = conn.contentLengthLong
            while (conn.inputStream.read(buf).also { r = it } != -1) {
                out.write(buf, 0, r); total += r
                if (size > 0) _s.value = _s.value.copy(downloadProgress = total.toFloat() / size.toFloat())
            }
        }
        conn.inputStream.close()
        Result.success(FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file))
    } catch (e: Exception) { Result.failure(e) }

    fun launchInstall() {
        val uri = _s.value.installUri ?: return
        app.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        })
    }
    fun dismissInstallPrompt() { _s.value = _s.value.copy(installPrompt = false) }
    fun loadHealthData() { viewModelScope.launch {
        _s.value = _s.value.copy(healthLoading = true)
        val data = withContext(Dispatchers.IO) { healthManager.loadHealthData() }
        _s.value = _s.value.copy(healthData = data, healthLoading = false)
    }}
    fun getHealthPermissionIntent() = healthManager.getPermissionIntent()
    fun logout() { countdownJob?.cancel(); repo.logout() }

    class Factory(private val a: Application, private val r: CrunchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(c: Class<T>): T = BarcodeViewModel(a, r) as T
    }
}
