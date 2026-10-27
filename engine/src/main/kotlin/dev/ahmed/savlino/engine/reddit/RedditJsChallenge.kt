package dev.ahmed.savlino.engine.reddit

import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import okhttp3.HttpUrl
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Reddit's logged-out page check (2026-10-27): an ~8 KB page whose script doubles one hex string,
 * `await(async e=>e+e)("<hex>")`, puts it in a hidden GET form's `solution` field and submits the form to the
 * same page. The refetch needs no cookie. Anything but that exact shape (another function, another target, a
 * missing field) is not answered, so the read fails as it did before the check existed.
 */
internal object RedditJsChallenge {
    /** The real check page is ~8 KB; a post page (~1 MB) is never parsed twice. */
    private const val MAX_PAGE = 32 * 1024
    private val doubled = Regex("""await\s*\(\s*async\s+(\w+)\s*=>\s*\1\s*\+\s*\1\s*\)\s*\(\s*"([0-9a-f]{8,256})"\s*\)""")
    private val fieldName = Regex("[a-z_]{1,32}")
    private val fieldValue = Regex("[A-Za-z0-9_-]{0,512}")

    /** The page URL that answers [html]'s check for [page], or null when it isn't one or can't be answered. */
    fun answer(html: ByteArray, length: Int, page: HttpUrl): HttpUrl? {
        if (length !in 1..minOf(MAX_PAGE, html.size)) return null
        return try {
            val document = Jsoup.parse(String(html, 0, length, Charsets.UTF_8), "", Parser.htmlParser().setMaxDepth(32))
            val seeds = document.select("script").mapNotNull { doubled.find(it.data())?.groupValues?.get(2) }
            val seed = seeds.singleOrNull() ?: return null
            val form = document.select("form").singleOrNull() ?: return null
            if (!form.attr("method").equals("get", ignoreCase = true)) return null
            val action = MediaHttpPolicy.resolve(page, form.attr("action")) ?: return null
            // The form goes back to the page that was asked for, never anywhere else.
            if (action.host != page.host || action.encodedPath != page.encodedPath || action.encodedQuery != null) return null
            val inputs = form.select("input")
            if (inputs.isEmpty() || inputs.size > 8) return null
            val names = inputs.map { it.attr("name") }
            if ("solution" !in names || "jsc_token" !in names || names.toSet().size != names.size) return null
            val builder = page.newBuilder().query(null)
            inputs.forEach { input ->
                val name = input.attr("name").takeIf(fieldName::matches) ?: return null
                val value = if (name == "solution") seed + seed else input.attr("value").takeIf(fieldValue::matches) ?: return null
                builder.addQueryParameter(name, value)
            }
            builder.build()
        } catch (_: IllegalArgumentException) { null }
    }
}
