package com.jn90z.openskylight

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Locale

internal class PhotoLibrary(private val context: Context) {
    @Volatile private var allowed = emptySet<String>()

    fun list(folder: String): List<String> {
        val tree = Uri.parse(folder)
        val found = runCatching {
            val root = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, root)
            val columns = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            val entries = mutableListOf<Pair<String, String>>()
            context.contentResolver.query(children, columns, null, null, null)?.use { cursor ->
                while (cursor.moveToNext() && entries.size < 1000) {
                    if (cursor.getString(2)?.startsWith("image/") != true) continue
                    entries += cursor.getString(1).orEmpty() to DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0)).toString()
                }
            }
            entries.sortedBy { it.first.lowercase(Locale.ROOT) }.map { it.second }
        }.getOrDefault(emptyList())
        allowed = found.toSet()
        return found.map { "https://localhost/_osl/photo/" + Uri.encode(it) }
    }

    fun open(document: String): WebResourceResponse? {
        if (document !in allowed) return null
        return runCatching {
            val uri = Uri.parse(document)
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            if (options.outWidth <= 0 || options.outHeight <= 0) return null
            if (options.outWidth <= 2048 && options.outHeight <= 2048) {
                val input = context.contentResolver.openInputStream(uri) ?: return null
                return WebResourceResponse(context.contentResolver.getType(uri), null, input)
            }
            var sample = 1
            while (options.outWidth / sample > 2048 || options.outHeight / sample > 2048) sample *= 2
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val output = ByteArrayOutputStream()
            try { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output) } finally { bitmap.recycle() }
            WebResourceResponse("image/jpeg", null, ByteArrayInputStream(output.toByteArray()))
        }.getOrNull()
    }
}
