package dev.ahmed.savlino.engine.x

import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy

/**
 * A public X/Twitter post link: `x.com|twitter.com|mobile.twitter.com/<user>/status/<id>` or `/i/status/<id>`,
 * optionally with the viewer's `/photo/<n>` or `/video/<n>` suffix (or a mirror's `/<lang>` translation suffix). Only the numeric post id is kept; the
 * user segment and any query (share tracking) are never used for a request.
 */
class XPostIdentity private constructor(val postId: String, val legacyUrl: String?, val mediaIndex: Int?) {
    /** The public syndication read of this exact post (no auth, no cookies). */
    val syndicationUrl: String get() = "https://$SYNDICATION_HOST$SYNDICATION_PATH?id=$postId&token=${token(postId)}"

    override fun toString() = "XPostIdentity(redacted)"

    companion object {
        const val SYNDICATION_HOST = "cdn.syndication.twimg.com"
        const val SYNDICATION_PATH = "/tweet-result"
        private val mirrors = setOf("fxtwitter.com", "vxtwitter.com", "fixupx.com", "fixvx.com", "d.fxtwitter.com")
        private val hosts = setOf("x.com", "www.x.com", "mobile.x.com", "m.x.com", "twitter.com", "www.twitter.com",
            "mobile.twitter.com", "m.twitter.com", "d.fxtwitter.com", "fxtwitter.com", "www.fxtwitter.com", "vxtwitter.com", "www.vxtwitter.com",
            "fixupx.com", "www.fixupx.com", "fixvx.com", "www.fixvx.com")
        private val path = Regex("/(?:i(?:/web)?|[A-Za-z0-9_]{1,15})/status/([1-9][0-9]{0,18})(?:/(?:photo|video)/([1-4]))?/?")
        /** fxtwitter's translated view, `/status/<id>/<lang>`; only on a mirror. */
        private val translated = Regex("(/(?:i(?:/web)?|[A-Za-z0-9_]{1,15})/status/[1-9][0-9]{0,18})/[a-z]{2}/?")
        private val query = Regex("id=([1-9][0-9]{0,18})&token=([0-9a-z]{1,32})")

        fun parse(url: String): XPostIdentity? {
            val parsed = MediaHttpPolicy.parse(url) ?: return null
            if (parsed.host.lowercase() !in hosts) return null
            val mirror = parsed.host.lowercase().removePrefix("www.") in mirrors
            val encodedPath = if (mirror) translated.matchEntire(parsed.encodedPath)?.groupValues?.get(1) ?: parsed.encodedPath
                else parsed.encodedPath
            val match = path.matchEntire(encodedPath) ?: return null
            val id = match.groupValues[1]
            // yt-dlp reads the user's own link, suffix included; a mirror becomes x.com/i/status/<id> without it.
            return id.toLongOrNull()?.let {
                XPostIdentity(id, if (mirror) "https://x.com/i/status/$id" else null,
                    if (mirror) null else match.groupValues[2].toIntOrNull())
            }
        }

        /** True only for this reader's own syndication request shape, token included. */
        internal fun isSyndicationRequest(host: String, encodedPath: String, encodedQuery: String?): Boolean {
            if (host != SYNDICATION_HOST || encodedPath != SYNDICATION_PATH) return false
            val match = query.matchEntire(encodedQuery ?: return false) ?: return false
            val id = match.groupValues[1]
            return id.toLongOrNull() != null && match.groupValues[2] == token(id)
        }

        /**
         * The syndication token: `(id / 1e15 * π)` written in base 36 exactly as JavaScript's
         * `Number.prototype.toString(36)` does (V8's shortest-digits radix algorithm), then every `0` and `.` removed.
         */
        internal fun token(id: String): String =
            jsRadixString(id.toDouble() / 1e15 * Math.PI, 36).replace("0", "").replace(".", "")

        /** Port of V8 `DoubleToRadixCString` for finite non-negative values. */
        internal fun jsRadixString(input: Double, radix: Int): String {
            require(input.isFinite() && input >= 0 && radix in 2..36)
            val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
            var integer = Math.floor(input)
            var fraction = input - integer
            var delta = maxOf(0.5 * (Math.nextUp(input) - input), Math.nextUp(0.0))
            val digits = StringBuilder()
            if (fraction >= delta) {
                do {
                    fraction *= radix
                    delta *= radix
                    val digit = fraction.toInt()
                    digits.append(chars[digit])
                    fraction -= digit
                    if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) == 1)) {
                        if (fraction + delta > 1) {
                            // Round up, propagating the carry back through the written digits.
                            while (true) {
                                if (digits.isEmpty()) { integer += 1; break }
                                val last = chars.indexOf(digits[digits.length - 1])
                                digits.setLength(digits.length - 1)
                                if (last + 1 < radix) { digits.append(chars[last + 1]); break }
                            }
                            break
                        }
                    }
                } while (fraction >= delta)
            }
            val whole = StringBuilder()
            // V8 `Double::Exponent() > 0` (value ≥ 2^53): unrepresented low digits are written as zero.
            while (Math.getExponent(integer / radix) - 52 > 0) {
                integer /= radix
                whole.append('0')
            }
            do {
                val remainder = integer % radix
                whole.append(chars[remainder.toInt()])
                integer = (integer - remainder) / radix
            } while (integer > 0)
            whole.reverse()
            return if (digits.isEmpty()) whole.toString() else "$whole.$digits"
        }
    }
}
