package com.crunchbarcode.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.crunchbarcode.app.data.api.CrunchApi
import com.crunchbarcode.app.data.api.SessionExpiredException
import com.crunchbarcode.app.data.model.LoginResponse
import com.crunchbarcode.app.data.model.UserCredentials
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.io.File
import java.io.FileOutputStream

class CrunchRepository private constructor(ctx: Context) {

    private val prefs = EncryptedSharedPreferences.create(ctx,
        "crunch_secure", MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    private val barcodeCache = File(ctx.cacheDir, "barcode_cache.png")
    private val api get() = CrunchApi.get()

    var savedCredentials: UserCredentials?
        get() {
            val l = prefs.getString("login", null) ?: return null
            val p = prefs.getString("password", null) ?: return null
            val u = prefs.getString("uuid", null) ?: return null
            val s = prefs.getString("session", null) ?: return null
            return UserCredentials(l, p, u, s, prefs.getString("first", null), prefs.getString("last", null))
        }
        private set(v) {
            if (v == null) prefs.edit().clear().apply()
            else prefs.edit().putString("login", v.login).putString("password", v.password)
                .putString("uuid", v.uuid).putString("session", v.sessionId)
                .putString("first", v.firstName).putString("last", v.lastName).apply()
        }

    val isLoggedIn: Boolean get() = savedCredentials != null

    fun login(user: String, pass: String): Result<LoginResponse> {
        val r = api.login(user, pass)
        if (r.isSuccess) {
            val lr = r.getOrThrow()
            savedCredentials = UserCredentials(user, pass, lr.uuid, lr.sessionId, lr.firstName, lr.lastName)
        }
        return r
    }

    fun loadBarcode(): Result<BarcodeResult> {
        val c = savedCredentials ?: return Result.failure(Exception("Not logged in"))
        return try {
            val result = api.getBarcode(c)
            if (result.isFailure && result.exceptionOrNull() is SessionExpiredException) {
                val relogin = api.relogin()
                if (relogin.isSuccess) {
                    val lr = relogin.getOrThrow()
                    savedCredentials = c.copy(uuid = lr.uuid, sessionId = lr.sessionId)
                    api.getBarcode(savedCredentials!!)
                } else result
            } else result
        }.fold(
            onSuccess = { (value, _) -> genBitmap(value).fold(
                { bmp -> Result.success(BarcodeResult(value, bmp)) },
                { Result.failure(it) }
            )},
            onFailure = { Result.failure(it) }
        )
    }

    private fun genBitmap(value: String): Result<Bitmap> = try {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "ISO-8859-1")
        val w = MultiFormatWriter()
        val mx = try { w.encode(value, BarcodeFormat.QR_CODE, 512, 512, hints) }
        catch (_: Exception) { w.encode(value, BarcodeFormat.CODE_128, 1024, 256, hints) }
        val bmp = Bitmap.createBitmap(mx.width, mx.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(mx.width * mx.height)
        for (y in 0 until mx.height) for (x in 0 until mx.width)
            px[y * mx.width + x] = if (mx[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        bmp.setPixels(px, 0, mx.width, 0, 0, mx.width, mx.height)
        FileOutputStream(barcodeCache).use { bmp.compress(Bitmap.CompressFormat.PNG, 85, it) }
        Result.success(bmp)
    } catch (e: Exception) { Result.failure(e) }

    fun getCachedBarcodeBitmap(): Bitmap? = try {
        if (barcodeCache.exists()) BitmapFactory.decodeFile(barcodeCache.absolutePath) else null
    } catch (_: Exception) { null }

    fun getCachedBarcodeValue(): String? = try {
        if (barcodeCache.exists()) prefs.getString("cached_value", null) else null
    } catch (_: Exception) { null }

    fun getGooglePayJwt(): Result<String> {
        val c = savedCredentials ?: return Result.failure(Exception("Not logged in"))
        return api.getGooglePayJwt(c)
    }

    fun logout() { savedCredentials = null; barcodeCache.delete() }

    data class BarcodeResult(val value: String, val bitmap: Bitmap)

    companion object {
        @Volatile private var i: CrunchRepository? = null
        fun getInstance(ctx: Context) = i ?: synchronized(this) {
            CrunchRepository(ctx.applicationContext).also { i = it }
        }
    }
}
