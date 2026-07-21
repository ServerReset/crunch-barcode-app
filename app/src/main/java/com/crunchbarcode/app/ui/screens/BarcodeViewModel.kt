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

data class UiState(
    val barcodeValue: String? = null, val barcodeBitmap: Bitmap? = null,
    val isLoading: Boolean = false, val error: String? = null,
    val googlePayJwt: String? = null, val isWalletLoading: Boolean = false,
    val update: com.crunchbarcode.app.update.AppUpdate? = null,
    val isDownloading: Boolean = false, val downloadProgress: Float = 0f,
    val installPrompt: Boolean = false, val installUri: Uri? = null,
    val countdownSec: Int = 300, val justCopied: Boolean = false,
    val firstName: String? = null, val lastRefreshed: String? = null
)

class BarcodeViewModel(private val app: Application, private val repo: CrunchRepository) : ViewModel() {
    private val _s = MutableStateFlow(UiState()); val ui = _s.asStateFlow()
    private var countdownJob: Job? = null
    private val refreshMs = 5 * 60 * 1000L

    init {
        repo.getCachedBitmap()?.let { bmp ->
            _s.value = _s.value.copy(barcodeBitmap = bmp, barcodeValue = repo.getCachedValue(),
                countdownSec = (refreshMs / 1000).toInt())
        }
        _s.value = _s.value.copy(firstName = repo.savedCredentials?.firstName)
        loadBarcode(); checkUpdate()
    }

    fun loadBarcode() { viewModelScope.launch {
        _s.value = _s.value.copy(isLoading = true, error = null)
        withContext(Dispatchers.IO) { repo.loadBarcode() }.fold(
            onSuccess = { r ->
                BarcodeWidgetProvider.pushBarcodeUpdate(app, r.bitmap)
                _s.value = _s.value.copy(barcodeValue = r.value, barcodeBitmap = r.bitmap,
                    isLoading = false, error = null, countdownSec = (refreshMs / 1000).toInt(),
                    lastRefreshed = SimpleDateFormat("h:mm a", Locale.US).format(Date()))
                startCountdown()
            },
            onFailure = { e ->
                if (_s.value.barcodeBitmap == null)
                    _s.value = _s.value.copy(isLoading = false, error = e.localizedMessage ?: "Failed")
                else _s.value = _s.value.copy(isLoading = false, error = "Refresh failed")
            }
        )
    }}

    fun startCountdown() { countdownJob?.cancel(); countdownJob = viewModelScope.launch {
        var r = (refreshMs / 1000).toInt(); while (isActive && r > 0) { _s.value = _s.value.copy(countdownSec = r); delay(1000); r-- }
        if (r <= 0) { _s.value = _s.value.copy(countdownSec = 0); loadBarcode() }
    }}

    fun loadWallet() { viewModelScope.launch {
        _s.value = _s.value.copy(isWalletLoading = true)
        withContext(Dispatchers.IO) { repo.getGooglePayJwt() }.fold(
            { _s.value = _s.value.copy(googlePayJwt = it, isWalletLoading = false) },
            { _s.value = _s.value.copy(isWalletLoading = false, error = it.localizedMessage) })
    }}

    fun copy() { _s.value.barcodeValue?.let {
        (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Crunch", it))
        _s.value = _s.value.copy(justCopied = true)
        viewModelScope.launch { delay(2000); _s.value = _s.value.copy(justCopied = false) }
    }}

    fun saveToGallery() { _s.value.barcodeBitmap?.let { bmp -> viewModelScope.launch {
        withContext(Dispatchers.IO) { try {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Crunch_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Images.Media.IS_PENDING, 1)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/CrunchBarcode") }
            }
            val uri = app.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv) ?: throw Exception("No URI")
            app.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } ?: throw Exception("No stream")
            if (Build.VERSION.SDK_INT >= 29) { cv.clear(); cv.put(MediaStore.Images.Media.IS_PENDING, 0); app.contentResolver.update(uri, cv, null, null) }
        } catch (_: Exception) { _s.value = _s.value.copy(error = "Save failed") } }
    } }}

    fun share() { _s.value.barcodeBitmap?.let { bmp -> viewModelScope.launch {
        withContext(Dispatchers.IO) { try {
            val dir = File(app.cacheDir, "shared").also { it.mkdirs() }; val f = File(dir, "barcode.png")
            FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f)
            app.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share").apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (_: Exception) {} }
    } }}

    fun tryWallet() {
        for (scheme in listOf("samsungwallet://addPass", "samsungpay://", "wallet://")) {
            try { app.startActivity(Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(scheme); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }); return } catch (_: Exception) {}
        }
        share()
    }

    private fun checkUpdate() { viewModelScope.launch {
        UpdateChecker(BuildConfig.VERSION_NAME).checkForUpdate().fold(
            { upd -> _s.value = _s.value.copy(update = if (upd.isNewer) upd else null) },
            { _s.value = _s.value.copy(update = null) })
    }}

    fun downloadAndInstall() { _s.value.update?.let { u -> viewModelScope.launch {
        _s.value = _s.value.copy(isDownloading = true, downloadProgress = 0f)
        withContext(Dispatchers.IO) { downloadApk(u.downloadUrl) }
            .fold({ _s.value = _s.value.copy(isDownloading = false, installUri = it, installPrompt = true) },
                  { _s.value = _s.value.copy(isDownloading = false, error = "Download failed") })
    } }}

    private fun downloadApk(url: String): Result<Uri> = try {
        val dir = File(app.cacheDir, "updates").also { it.mkdirs() }; dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "update.apk")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30000; conn.readTimeout = 30000; conn.connect()
        FileOutputStream(f).use { out ->
            val buf = ByteArray(8192); var r: Int; var t = 0L; val s = conn.contentLengthLong
            while (conn.inputStream.read(buf).also { r = it } != -1) { out.write(buf, 0, r); t += r
                if (s > 0) _s.value = _s.value.copy(downloadProgress = t.toFloat() / s.toFloat()) }
        }
        conn.inputStream.close()
        Result.success(FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", f))
    } catch (e: Exception) { Result.failure(e) }

    fun launchInstall() { _s.value.installUri?.let {
        app.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(it, "application/vnd.android.package-archive")
            flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true) })
    }}
    fun dismissInstall() { _s.value = _s.value.copy(installPrompt = false) }
    fun logout() { countdownJob?.cancel(); repo.logout() }

    class Factory(private val a: Application, private val r: CrunchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(c: Class<T>): T = BarcodeViewModel(a, r) as T
    }
}
