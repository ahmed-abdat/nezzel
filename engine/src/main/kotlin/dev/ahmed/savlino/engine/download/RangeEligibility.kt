package dev.ahmed.savlino.engine.download

import dev.ahmed.savlino.domain.*
import com.fasterxml.jackson.databind.ObjectMapper
import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy

internal object RangeEligibility {
    fun select(site: Host, row: Format, json: String, allows: (String) -> Boolean): DirectRequest? = try {
        if (site !in setOf(Host.TIKTOK, Host.FACEBOOK, Host.INSTAGRAM, Host.X) || row.container != "mp4" ||
            !row.hasVideo || row.requiresMerge || row.conversion != null) null
        else {
            val root = ObjectMapper().readTree(json)
            val candidates = root.path("formats").takeIf { it.isArray }?.toList() ?: listOf(root)
            val selected = candidates.singleOrNull { it.path("format_id").asText() == row.sourceFormatId }
            val url = selected?.path("url")?.asText()
            if (selected == null || url == null || MediaHttpPolicy.parse(url) == null || !allows(url) ||
                selected.path("protocol").asText() != "https" || selected.path("ext").asText() != "mp4" ||
                selected.has("fragments") || selected.has("manifest_url") || selected.path("vcodec").asText() == "none" ||
                (row.hasAudio && selected.path("acodec").asText() == "none")) null
            else {
                val headers = linkedMapOf<String, String>()
                for (node in listOf(root.path("http_headers"), selected.path("http_headers"))) {
                    if (node.isObject) node.fields().forEach { (name, value) ->
                        if (!value.isTextual || name.any { it <= ' ' || it >= '\u007f' } ||
                            value.asText().any { it < ' ' || it == '\u007f' }) throw IllegalArgumentException()
                        headers.keys.firstOrNull { it.equals(name, true) }?.let { headers.remove(it) }
                        headers[name] = value.asText()
                    }
                }
                val cookies = selected.get("cookies") ?: root.get("cookies")
                if (cookies != null) {
                    if (!cookies.isTextual) throw IllegalArgumentException()
                    val cookieHeader = InfoCookies.header(cookies.asText(), requireNotNull(MediaHttpPolicy.parse(url)))
                        ?: throw IllegalArgumentException()
                    headers.keys.firstOrNull { it.equals("Cookie", true) }?.let { headers.remove(it) }
                    if (cookieHeader.isNotEmpty()) headers["Cookie"] = cookieHeader
                }
                DirectRequest(url, headers)
            }
        }
    } catch (_: Exception) { null }
}
