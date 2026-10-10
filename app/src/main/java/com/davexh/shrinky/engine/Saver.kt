package com.davexh.shrinky.engine

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

fun queryMeta(cr: ContentResolver, uri: Uri): Pair<String, Long> {
    var name = "file"
    var size = 0L
    cr.query(uri, null, null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = c.getString(it) }
            c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { size = c.getLong(it) }
        }
    }
    return name to size
}

/** Where files go: a folder the user picked, or Downloads/Shrinky by default. */
object Saver {
    private var prefs: SharedPreferences? = null
    var folder by mutableStateOf<Uri?>(null); private set

    fun init(ctx: Context) {
        val p = ctx.getSharedPreferences("shrinky", Context.MODE_PRIVATE)
        prefs = p
        folder = p.getString("folder", null)?.let { Uri.parse(it) }
    }

    fun setFolder(ctx: Context, uri: Uri?) {
        uri ?: return
        runCatching {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        prefs?.edit()?.putString("folder", uri.toString())?.apply()
        folder = uri
    }

    val label: String
        get() {
            val f = folder ?: return "Downloads/Shrinky"
            val id = runCatching { DocumentsContract.getTreeDocumentId(f) }.getOrNull().orEmpty()
            val path = id.substringAfter(':', "")
            return if (path.isEmpty()) "Internal storage" else path
        }

    fun fileName(input: String, ext: String): String {
        var base = input.replace(Regex("[\\\\/:*?\"<>|]"), "").trim()
        if (base.endsWith(".$ext", ignoreCase = true)) base = base.dropLast(ext.length + 1).trim()
        return base.ifEmpty { "shrinky" } + "." + ext
    }

    fun save(cr: ContentResolver, name: String, mime: String, bytes: ByteArray) =
        save(cr, name, mime) { out -> out.write(bytes) }

    /** Streams a file to the destination, for results too large to hold in memory (video). */
    fun save(cr: ContentResolver, name: String, mime: String, file: java.io.File) =
        save(cr, name, mime) { out -> file.inputStream().use { it.copyTo(out) } }

    private fun save(cr: ContentResolver, name: String, mime: String, write: (java.io.OutputStream) -> Unit) {
        val tree = folder
        val created: Uri? = if (tree == null) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Shrinky")
            }
            cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        } else {
            val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            DocumentsContract.createDocument(cr, dir, mime, name)
        }
        val uri = created ?: error("Can't create the file in that location.")
        cr.openOutputStream(uri)!!.use(write)
    }
}
