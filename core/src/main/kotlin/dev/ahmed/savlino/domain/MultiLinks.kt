package dev.ahmed.savlino.domain

import java.net.URI

/** Paste/Share text holding several links: one "Download all" sheet instead of the one-by-one queue. */
object MultiLinks {
    const val MAX = 50

    /** 2..[MAX] distinct links in source order (extras beyond [MAX] are dropped), else null: one link keeps the single flow. */
    fun from(text: String?): Found? {
        val candidates = LinkInput.candidates(text)
        if (candidates.size < 2) return null
        return Found(candidates.take(MAX), candidates.size > MAX)
    }

    class Found(val links: List<ValidatedLink>, val truncated: Boolean) {
        override fun toString() = "MultiLinks(${links.size}, truncated=$truncated)"
    }
}

/** Display-only labels; nothing here is logged, and only [host] is ever persisted. */
object MultiLinkLabel {
    fun host(link: ValidatedLink): String = uri(link)?.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.").orEmpty()

    /** Host plus a short path (no query or fragment), e.g. "tiktok.com/@name/video/…". */
    fun short(link: ValidatedLink, max: Int = 32): String {
        val path = uri(link)?.rawPath.orEmpty().trimEnd('/')
        val label = host(link) + path
        return if (label.length <= max) label else label.take(max - 1) + "…"
    }

    private fun uri(link: ValidatedLink) = try { URI(link.rawUrl) } catch (_: Exception) { null }
}
