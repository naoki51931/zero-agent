package com.zeroagent.app

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Portable, user-selected ZIP. Contains sensitive screenshot images; never API keys. */
object ResearchArchive {
    private const val MAX_IMAGE = 15 * 1024 * 1024
    private const val MAX_ARCHIVE = 200 * 1024 * 1024

    fun export(context: Context, destination: Uri): Int {
        val evidence = ResearchEvidenceStore(context).exportJson()
        val queries = ResearchQueryStore(context).exportJson()
        val records = evidence.getJSONArray("records")
        val uniqueUris = (0 until records.length()).mapNotNull { i ->
            records.optJSONObject(i)?.optString("screenshot_uri")?.takeIf { it.startsWith("content://") }
        }.distinct()
        val mapping = JSONObject()
        val images = mutableListOf<Pair<String, ByteArray>>()
        var total = 0
        for ((index, uri) in uniqueUris.withIndex()) {
            val bytes = context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_IMAGE) { "Screenshot too large" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: error("Screenshot missing: $uri")
            total += bytes.size
            require(total <= MAX_ARCHIVE) { "Archive too large" }
            val entry = "images/$index.png"
            mapping.put(uri, entry)
            images.add(entry to bytes)
        }
        val manifest = JSONObject()
            .put("format", "zero-agent-research-archive-v1")
            .put("version", 1)
            .put("queries", queries)
            .put("evidence", records)
            .put("images", mapping)
        context.contentResolver.openOutputStream(destination, "wt")?.use { stream ->
            ZipOutputStream(stream).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                for ((name, bytes) in images) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        } ?: error("Cannot open backup destination")
        return images.size
    }

    fun restore(context: Context, source: Uri): Pair<Int, Int> {
        val entries = mutableMapOf<String, ByteArray>()
        var total = 0
        context.contentResolver.openInputStream(source)?.use { stream ->
            ZipInputStream(stream).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    require(name == "manifest.json" ||
                        Regex("""images/\\d+\\.png""").matches(name)) { "Unexpected ZIP entry" }
                    require(!entries.containsKey(name) && entries.size < 10001) { "Duplicate/too many ZIP entries" }
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_ARCHIVE && output.size() + count <= MAX_IMAGE) {
                            "Backup exceeds size limits"
                        }
                        output.write(buffer, 0, count)
                    }
                    entries[name] = output.toByteArray()
                    zip.closeEntry()
                }
            }
        } ?: error("Cannot open backup")
        val manifest = JSONObject(String(entries["manifest.json"] ?: error("Missing manifest"), Charsets.UTF_8))
        require(manifest.optString("format") == "zero-agent-research-archive-v1" &&
            manifest.optInt("version") == 1) { "Unsupported archive" }
        val imageMap = manifest.getJSONObject("images")
        val evidence = manifest.getJSONArray("evidence")
        require(evidence.length() <= 10000 && imageMap.length() <= 10000) { "Too many records" }
        val restoredUris = mutableMapOf<String, String>()
        val created = mutableListOf<Uri>()
        try {
            for (oldUri in imageMap.keys()) {
                require(oldUri.startsWith("content://")) { "Invalid source URI" }
                val imageName = imageMap.getString(oldUri)
                val bytes = entries[imageName] ?: error("Missing image: $imageName")
                require(bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10))) { "Invalid PNG" }
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "zero_agent_restored_" +
                        System.currentTimeMillis() + "_" + created.size + ".png")
                    put(MediaStore.Downloads.MIME_TYPE, "image/png")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/ZeroAgent")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Cannot restore image")
                created.add(uri)
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: error("Cannot write restored image")
                context.contentResolver.update(uri, ContentValues().apply {
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                restoredUris[oldUri] = uri.toString()
            }
            val queryCount = ResearchQueryStore(context).importJson(manifest.getJSONObject("queries"))
            val evidenceCount = ResearchEvidenceStore(context).importCaptured(evidence, restoredUris)
            return queryCount to evidenceCount
        } catch (e: Exception) {
            created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
            throw e
        }
    }
}
