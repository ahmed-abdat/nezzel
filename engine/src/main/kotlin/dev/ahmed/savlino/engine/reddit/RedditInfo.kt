package dev.ahmed.savlino.engine.reddit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * yt-dlp reads the manifest as a generic page, titled "DASHPlaylist" with no picture; the post page names both.
 * Formats stay exactly as yt-dlp returned them. Anything but one JSON object is passed through untouched.
 */
internal object RedditInfo {
    private val json = ObjectMapper()

    fun patch(info: String, video: RedditPost.Video): String {
        val root = try { json.readTree(info) as? ObjectNode } catch (_: Exception) { null } ?: return info
        root.put("id", video.videoId)
        root.put("display_id", video.videoId)
        video.title?.let { root.put("title", it); root.put("fulltitle", it) }
        video.author?.let { root.put("uploader", it) }
        video.thumbnailUrl?.let { root.put("thumbnail", it) }
        if (root.path("duration").let { !it.isNumber || it.doubleValue() <= 0 }) video.durationSeconds?.let { root.put("duration", it) }
        return json.writeValueAsString(root)
    }
}
