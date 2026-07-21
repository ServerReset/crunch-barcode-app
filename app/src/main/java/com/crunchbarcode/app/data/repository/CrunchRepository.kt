package com.crunchbarcode.app.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.crunchbarcode.app.data.api.CrunchApi
import com.crunchbarcode.app.data.api.SessionExpired
import com.crunchbarcode.app.data.model.LoginResponse
import com.crunchbarcode.app.data.model.UserCredentials
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import java.io.File
import java.io.FileOutputStream

class CrunchRepository private constructor(ctx: Context) {

    private val prefs = EncryptedSharedPreferences.create(ctx, "crunch",
        MasterKey.Builder(ctx).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)

    private val cacheDir = File(ctx.cacheDir, "barcode")
    private val cacheFile = File(cacheDir, "barcode.png")
    private val api get() = CrunchApi.get()

    var savedCredentials: UserCredentials?
        get() {
            val l = prefs.getString("login", null) ?: return null
            val p = prefs.getString("pass", null) ?: return null
            val u = prefs.getString("uuid", null) ?: return null
            val s = prefs.getString("sid", null) ?: return null
            return UserCredentials(l, p, u, s, prefs.getString("fn", null), prefs.getString("ln", null))
        }
        private set(v) {
            if (v == null) prefs.edit().clear().apply()
            else prefs.edit().putString("login", v.login).putString("pass", v.password)
                .putString("uuid", v.uuid).putString("sid", v.sessionId)
                .putString("fn", v.firstName).putString("ln", v.lastName).apply()
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
        var result = api.getBarcode(c)
        if (result.isFailure && result.exceptionOrNull() is SessionExpired) {
            val relogin = api.relogin()
            if (relogin.isSuccess) {
                val lr = relogin.getOrThrow()
                savedCredentials = c.copy(uuid = lr.uuid, sessionId = lr.sessionId)
                result = api.getBarcode(savedCredentials!!)
            }
        }
        return result.fold(
            onSuccess = { value ->
                try { Result.success(BarcodeResult(value, renderBarcode(value))) }
                catch (e: Exception) { Result.failure(e) }
            },
            onFailure = { Result.failure(it) }
        )
    }

    private fun renderBarcode(value: String): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "ISO-8859-1")
        val writer = MultiFormatWriter()
        val mx = try { writer.encode(value, BarcodeFormat.QR_CODE, 512, 512, hints) }
        catch (_: Exception) { writer.encode(value, BarcodeFormat.CODE_128, 1024, 256, hints) }
        val bmp = Bitmap.createBitmap(mx.width, mx.height, Bitmap.Config.ARGB_8888)
        val px = IntArray(mx.width * mx.height)
        for (y in 0 until mx.height) for (x in 0 until mx.width)
            px[y * mx.width + x] = if (mx[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        bmp.setPixels(px, 0, mx.width, 0, 0, mx.width, mx.height)
        cacheDir.mkdirs()
        FileOutputStream(cacheFile).use { bmp.compress(Bitmap.CompressFormat.PNG, 85, it) }
        return bmp
    }

    fun getCachedBitmap(): Bitmap? = try {
        if (cacheFile.exists()) BitmapFactory.decodeFile(cacheFile.path) else null
    } catch (_: Exception) { null }

    fun getCachedValue(): String? = try {
        if (cacheFile.exists()) prefs.getString("cached_val", null) else null
    } catch (_: Exception) { null }

    fun getGooglePayJwt(): Result<String> {
        val c = savedCredentials ?: return Result.failure(Exception("Not logged in"))
        return api.getGooglePayJwt(c)
    }

    fun logout() { savedCredentials = null; cacheFile.delete() }

    data class BarcodeResult(val value: String, val bitmap: Bitmap)

    companion object {
        @Volatile private var i: CrunchRepository? = null
        fun getInstance(ctx: Context) = i ?: synchronized(this) {
            CrunchRepository(ctx.applicationContext).also { i = it }
        }
    }
}
