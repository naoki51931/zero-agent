package com.zeroagent.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Device-local, bounded cache of publicly visible X posts. Never stores credentials. */
class PopularPostMemory(context: Context) {
    private val file = File(context.filesDir, "popular_x_posts.json")
    private val maxPosts = 500

    data class Post(
        val id: String,
        val url: String,
        val author: String,
        val text: String,
        val likes: Long,
        val collectedAt: Long = System.currentTimeMillis()
    )

    @Synchronized
    fun save(post: Post): Boolean {
        if (!post.id.matches(Regex("[0-9]{5,25}")) || post.likes < 0 ||
            !isPublicStatusUrl(post.url, post.id)) return false
        val posts = read().associateBy { it.id }.toMutableMap()
        posts[post.id] = post.copy(
            author = post.author.take(120),
            text = post.text.take(2000),
            url = post.url.take(300)
        )
        persist(posts.values.sortedByDescending { it.collectedAt }.take(maxPosts))
        return true
    }

    @Synchronized
    fun saveAll(posts: List<Post>): Int = posts.count { save(it) }

    @Synchronized
    fun popular(minLikes: Long = 0, limit: Int = 50): List<Post> =
        read().filter { it.likes >= minLikes }
            .sortedWith(compareByDescending<Post> { it.likes }.thenByDescending { it.collectedAt })
            .take(limit.coerceIn(0, maxPosts))

    @Synchronized
    fun delete(id: String) = persist(read().filterNot { it.id == id })

    @Synchronized
    fun clear() { file.delete() }

    private fun isPublicStatusUrl(url: String, id: String): Boolean = try {
        val uri = android.net.Uri.parse(url)
        uri.scheme == "https" &&
            uri.host?.lowercase() in setOf("x.com", "www.x.com", "twitter.com", "www.twitter.com") &&
            uri.pathSegments.size >= 3 &&
            uri.pathSegments[uri.pathSegments.size - 2] == "status" &&
            uri.pathSegments.last() == id
    } catch (_: Exception) { false }

    private fun read(): List<Post> = try {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id")
            val url = o.optString("url")
            if (!isPublicStatusUrl(url, id)) return@mapNotNull null
            Post(id, url, o.optString("author"), o.optString("text"),
                o.optLong("likes").coerceAtLeast(0), o.optLong("collectedAt"))
        }
    } catch (_: Exception) { emptyList() }

    private fun persist(posts: List<Post>) {
        val arr = JSONArray()
        posts.forEach { p ->
            arr.put(JSONObject().put("id", p.id).put("url", p.url)
                .put("author", p.author).put("text", p.text)
                .put("likes", p.likes).put("collectedAt", p.collectedAt))
        }
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(arr.toString())
        if (!temp.renameTo(file)) {
            file.writeText(arr.toString())
            temp.delete()
        }
    }
}
