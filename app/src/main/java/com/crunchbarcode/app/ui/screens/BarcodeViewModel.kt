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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

data class BarcodeUiState(
    val barcodeValue: String? = null, val barcodeBitmap: Bitmap? = null,
    val isLoading: Boolean = true, val error: String? = null,
    val googlePayJwt: String? = null, val isGooglePayLoading: Boolean = false,
    val update: com.crunchbarcode.app.update.AppUpdate? = null, val isUpdateChecking: Boolean = true,
    val isDownloading: Boolean = false, val downloadProgress: Float = 0f,
    val installPrompt: Boolean = false, val installUri: Uri? = null,
    val countdownSeconds: Int = 300, val justCopied: Boolean = false,
    val memberFirstName: String? = null, val lastRefreshed: String? = null,
    val healthData: HealthData = HealthData(), val healthLoading: Boolean = false
)

class BarcodeViewModel(app: Application, private val repo: CrunchRepository) : ViewModel() {
    private val _s = MutableStateFlow(BarcodeUiState()); val uiState = _s.asStateFlow()
    private val healthManager = HealthConnectManager(app)
    private var countdownJob: Job? = null
    private val refreshMs = 5 * 60 * 1000L

    init {
        repo.getCachedBarcodeBitmap()?.let { bmp ->
            _s.value = _s.value.copy(barcodeBitmap = bmp, barcodeValue = repo.getCachedBarcodeValue(),
                isLoading = false, countdownSeconds = (refreshMs / 1000).toInt())
        }
        _s.value = _s.value.copy(memberFirstName = repo.savedCredentials?.firstName)
        loadBarcode(); checkForUpdate(); loadHealthData()
    }

    fun loadBarcode() { viewModelScope.launch {
        _s.value = _s.value.copy(isLoading = true, error = null)
        val result = withContext(Dispatchers.IO) { repo.loadBarcode() }
        result.fold(
            onSuccess = { r ->
                BarcodeWidgetProvider.pushBarcodeUpdate(app, r.bitmap)
                val t = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date())
                _s.value = _s.value.copy(barcodeValue = r.value, barcodeBitmap = r.bitmap,
                    isLoading = false, error = null, countdownSeconds = (refreshMs / 1000).toInt(), lastRefreshed = t)
                startCountdown()
            },
            onFailure = { e ->
                if (_s.value.barcodeBitmap == null)
                    _s.value = _s.value.copy(isLoading = false, error = e.localizedMessage ?: "Failed")
                else _s.value = _s.value.copy(isLoading = false,
                    error = "Refresh failed. ${e.localizedMessage ?: ""}")
            }
        )
    }}

    fun startCountdown() { countdownJob?.cancel(); countdownJob = viewModelScope.launch {
        var r = (refreshMs / 1000).toInt(); while (isActive && r > 0) { _s.value = _s.value.copy(countdownSeconds = r); delay(1000); r-- }
        if (r <= 0) { _s.value = _s.value.copy(countdownSeconds = 0); loadBarcode() }
    }}

    fun loadGooglePayJwt() { viewModelScope.launch {
        _s.value = _s.value.copy(isGooglePayLoading = true)
        withContext(Dispatchers.IO) { repo.getGooglePayJwt() }.fold(
            { _s.value = _s.value.copy(googlePayJwt = it, isGooglePayLoading = false) },
            { _s.value = _s.value.copy(isGooglePayLoading = false, error = "Wallet: ${it.localizedMessage}") })
    }}

    fun copyBarcodeToClipboard() { _s.value.barcodeValue?.let {
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Crunch", it))
        _s.value = _s.value.copy(justCopied = true)
        viewModelScope.launch { delay(2000); _s.value = _s.value.copy(justCopied = false) }
    }}

    fun saveToGallery() { _s.value.barcodeBitmap?.let { bmp -> viewModelScope.launch {
        withContext(Dispatchers.IO) { try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "CrunchBarcode_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Images.Media.IS_PENDING, 1)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CrunchBarcode") }
            }
            val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw Exception("No URI")
            app.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: throw Exception("No stream")
            if (Build.VERSION.SDK_INT >= 29) { cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0); app.contentResolver.update(uri, cv, null, null) }
        } catch (e: Exception) { _s.value = _s.value.copy(error = "Save failed") } }
    } }}

    fun share() { _s.value.barcodeBitmap?.let { bmp -> viewModelScope.launch { withContext(Dispatchers.IO) { try {
        val f = File(app.cacheDir, "shared_barcodes").also { it.mkdirs() }; val file = File(f, "crunch_barcode.png")
        FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
        app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }, "Share").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    } catch (_: Exception) {} } } } }

    fun tryWallet() {
        for (scheme in listOf("samsungwallet://addPass", "samsungpay://addPass", "wallet://addPass")) {
            try { app.startActivity(Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(scheme); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); return } catch (_: Exception) {}
        }
        share()
    }

    private fun checkForUpdate() { viewModelScope.launch {
        _s.value = _s.value.copy(isUpdateChecking = true)
        UpdateChecker(BuildConfig.VERSION_NAME).checkForUpdate().fold(
            { upd -> _s.value = _s.value.copy(update = if (upd.isNewer) upd else null, isUpdateChecking = false) },
            { _s.value = _s.value.copy(isUpdateChecking = false) })
    }}

    fun downloadAndInstall() { _s.value.update?.let { u -> viewModelScope.launch {
        _s.value = _s.value.copy(isDownloading = true, downloadProgress = 0f)
        withContext(Dispatchers.IO) { downloadApk(u.downloadUrl) }
            .fold({ _s.value = _s.value.copy(isDownloading = false, installUri = it, installPrompt = true) },
                { _s.value = _s.value.copy(isDownloading = false, error = "Download failed") })
    } }}

    private fun downloadApk(url: String): Result<Uri> = try {
        val dir = File(app.cacheDir, "updates").also { it.mkdirs() }; dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "update.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000; conn.readTimeout = 30000; conn.connect()
        FileOutputStream(file).use { out ->
            val buf = ByteArray(8192); var r: Int; var t = 0L; val s = conn.contentLengthLong
            while (conn.inputStream.read(buf).also { r = it } != -1) {
                out.write(buf, 0, r); t += r
                if (s > 0) _s.value = _s.value.copy(downloadProgress = t.toFloat() / s.toFloat())
            }
        }
        conn.inputStream.close()
        Result.success(FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file))
    } catch (e: Exception) { Result.failure(e) }

    fun launchInstall() { _s.value.installUri?.let {
        app.startActivity(Intent(Intent.ACTION_VIEW).apply { setDataAndType(it, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true) })
    }}
    fun dismissInstallPrompt() { _s.value = _s.value.copy(installPrompt = false) }
    fun loadHealthData() { viewModelScope.launch {
        _s.value = _s.value.copy(healthLoading = true)
        _s.value = _s.value.copy(healthData = withContext(Dispatchers.IO) { healthManager.loadHealthData() }, healthLoading = false)
    }}
    fun getHealthPermissionIntent() = healthManager.getPermissionIntent()
    fun logout() { countdownJob?.cancel(); repo.logout() }

    class Factory(private val a: Application, private val r: CrunchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(c: Class<T>): T = BarcodeViewModel(a, r) as T
    }
}
