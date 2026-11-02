package com.zeroagent.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.io.IOException

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
        if (context.contentResolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission && it.isWritePermission })
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
                    val looksSeaArt = lower.contains("seaart") && !lower.startsWith("post_seaart_")
                    val image = mime in setOf("image/png", "image/jpeg", "image/webp") &&
                        (lower.endsWith(".png") || lower.endsWith(".jpg") ||
                            lower.endsWith(".jpeg") || lower.endsWith(".webp"))
                    // Require both a recognizable SeaArt filename and a fresh modification time.
                    if (!looksSeaArt || !image || modified < since ||
                        modified > System.currentTimeMillis() + 60_000L || size !in 8L..20_000_000L) continue
                    val document = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idIndex))
                    matches.add(document to name)
                }
            } ?: return@withContext Result("unavailable")
            if (matches.isEmpty()) return@withContext Result("not_found")
            val used = settings.get("seaartImportedNames").split("\\n").filter { it.isNotBlank() }.toSet()
            val fresh = matches.filter { it.second !in used }
            if (fresh.isEmpty()) return@withContext Result("not_found")
            if (fresh.size != 1) return@withContext Result("ambiguous", fresh.size.toString())
            val (uri, name) = fresh.single()
            // Keep the original download. Write a separately named post-ready
            // copy into the user-selected SAF folder (never overwrite files).
            val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
            val mime = when (extension) {
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "webp" -> "image/webp"
                else -> return@withContext Result("unsupported_format")
            }
            val completedBefore = settings.get("seaartRepeatCompleted").toIntOrNull() ?: 0
            val safeId = id.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
            val copyName = "post_seaart_${safeId}_${completedBefore + 1}_${System.currentTimeMillis()}.$extension"
            val copyUri = DocumentsContract.createDocument(resolver,
                DocumentsContract.buildDocumentUriUsingTree(tree, rootId), mime, copyName)
                ?: return@withContext Result("copy_failed", "Could not create post-ready copy")
            try {
                val input = resolver.openInputStream(uri)
                    ?: throw IOException("Cannot read downloaded image")
                val output = resolver.openOutputStream(copyUri, "w")
                    ?: throw IOException("Cannot write post-ready copy")
                input.use { source -> output.use { dest -> source.copyTo(dest) } }
                val store = CharacterDraftStore(context)
                if (!store.attachImage(id, copyUri)) throw IOException("Cannot register post-ready copy")
            } catch (error: Exception) {
                try { DocumentsContract.deleteDocument(resolver, copyUri) } catch (_: Exception) {}
                return@withContext Result("copy_failed", error.message.orEmpty().take(100))
            }
            settings.put("seaartPostCopyName", copyName)
            val previousUris = settings.get("seaartPostCopyUris")
            settings.put("seaartPostCopyUris", listOf(previousUris, copyUri.toString()).filter { it.isNotBlank() }.joinToString("|"))
            settings.put("seaartImportedNames", (used + name).joinToString("\\n"))
            val completed = (settings.get("seaartRepeatCompleted").toIntOrNull() ?: 0) + 1
            val total = (settings.get("seaartRepeatTotal").toIntOrNull() ?: 1).coerceIn(1, 20)
            settings.put("seaartRepeatCompleted", completed.toString())
            settings.put("seaartPendingState", if (completed < total) "awaiting_generate" else "x_post_review_pending")
            if (completed >= total) settings.put("seaartXHandoffReady", id)
            settings.put("seaartImportedFilename", name)
            Result("imported", copyName)
        } catch (e: SecurityException) {
            Result("permission_needed")
        } catch (e: Exception) {
            Result("error", e.message.orEmpty().take(100))
        }
    }
}
