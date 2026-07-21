package com.crunchbarcode.app.widget

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.FileOutputStream

class BarcodeImageProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val file = context?.let { getBarcodeFile(it) }
            ?: throw FileNotFoundException("No context")
        if (!file.exists()) {
            FileOutputStream(file).use { fos ->
                val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String = "image/png"

    companion object {
        private const val BARCODE_IMAGE = "widget_barcode.png"

        fun getBarcodeFile(context: Context): File =
            File(context.filesDir, BARCODE_IMAGE)

        fun saveBarcodeBitmap(context: Context, bitmap: Bitmap) {
            val file = getBarcodeFile(context)
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 85, fos)
            }
        }

        fun getBarcodeUri(context: Context): Uri =
            Uri.parse("content://${context.packageName}.barcodeimageprovider/$BARCODE_IMAGE")
    }
}
