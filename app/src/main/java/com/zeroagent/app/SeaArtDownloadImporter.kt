package com.zeroagent.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/** Uses a user-granted SAF directory, never broad storage permissions. */
object SeaArtDownloadImporter {
    data class Result(val status: String, val detail: String = "")

    suspend fun scan(context: Context): Result = withContext(Dispatchers.IO) {
        val settings = LocalSettings(context)
        val id = settings.get("seaartPendingId")
        val state = settings.get("seaartPendingState")
        val since = settings.get("seaartPendingSince").toLongOrNull() ?: 0L
        if (id.isBlank() || state != "download_requested" || since <= 0L)
            return@withContext Result("not_pending")
        val rawTree = settings.get("seaartDownloadTree")
        if (rawTree.isBlank()) return@withContext Result("permission_needed")
        val tree = Uri.parse(rawTree)
        if (context.contentResolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission })
            return@withContext Result("permission_needed")
        val resolver = context.contentResolver
        try {
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
            val matches = mutableListOf<Pair<Uri, String>>()
            resolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_SIZE
            ), null, null, null)?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val timeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex) ?: continue
                    val mime = cursor.getString(mimeIndex) ?: ""
                    val modified = if (cursor.isNull(timeIndex)) 0L else cursor.getLong(timeIndex)
                    val size = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex)
                    val lower = name.lowercase(Locale.ROOT)
                    val looksSeaArt = lower.contains("seaart")
                    val image = mime in setOf("image/png", "image/jpeg", "image/webp") &&
                        (lower.endsWith(".png") || lower.endsWith(".jpg") ||
                            lower.endsWith(".jpeg") || lower.endsWith(".webp"))
                    // Require both a recognizable SeaArt filename and a fresh modification time.
                    if (!looksSeaArt || !image || modified < since - 5_000L ||
                        modified > System.currentTimeMillis() + 60_000L || size !in 8L..20_000_000L) continue
                    val document = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idIndex))
                    matches.add(document to name)
                }
            } ?: return@withContext Result("unavailable")
            if (matches.isEmpty()) return@withContext Result("not_found")
            if (matches.size != 1) return@withContext Result("ambiguous", matches.size.toString())
            val (uri, name) = matches.single()
            val store = CharacterDraftStore(context)
            if (store.list().let { rows -> (0 until rows.length()).none { i ->
                    rows.optJSONObject(i)?.optString("id") == id &&
                        rows.optJSONObject(i)?.optString("status") == "ready_for_post_review"
                } }) return@withContext Result("already_imported")
            if (!store.attachImage(id, uri)) return@withContext Result("copy_failed")
            settings.put("seaartPendingState", "imported")
            settings.put("seaartImportedFilename", name)
            Result("imported", name)
        } catch (e: SecurityException) {
            Result("permission_needed")
        } catch (e: Exception) {
            Result("error", e.message.orEmpty().take(100))
        }
    }
}
