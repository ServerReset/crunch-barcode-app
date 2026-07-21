package com.crunchbarcode.app.ui.screens

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crunchbarcode.app.data.repository.CrunchRepository
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

data class BarcodeUiState(
    val barcodeValue: String? = null,
    val barcodeBitmap: Bitmap? = null,
    val barcodeFormat: BarcodeFormat = BarcodeFormat.CODE_128,
    val isLoading: Boolean = true,
    val error: String? = null,
    val googlePayJwt: String? = null,
    val isGooglePayLoading: Boolean = false
)

class BarcodeViewModel(
    private val repository: CrunchRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BarcodeUiState())
    val uiState: StateFlow<BarcodeUiState> = _uiState.asStateFlow()

    private val writer = MultiFormatWriter()

    init {
        loadBarcode()
    }

    fun loadBarcode() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            val result = withContext(Dispatchers.IO) {
                repository.refreshBarcode()
            }
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
            val result = withContext(Dispatchers.IO) {
                repository.getGooglePayJwt()
            }
            result.fold(
                onSuccess = { jwt ->
                    _uiState.value = _uiState.value.copy(
                        googlePayJwt = jwt,
                        isGooglePayLoading = false
                    )
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

    fun logout() {
        repository.logout()
    }

    class Factory(private val repository: CrunchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return BarcodeViewModel(repository) as T
        }
    }
}
