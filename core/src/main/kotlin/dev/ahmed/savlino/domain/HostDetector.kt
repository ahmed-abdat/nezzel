package dev.ahmed.savlino.domain

import java.util.Locale

object HostDetector {
    /** Syntactic intake check, not DNS resolution or a guarantee against DNS rebinding. */
    internal fun isPublicDnsHost(host: String): Boolean {
        val value = host.lowercase(Locale.ROOT)
        if (value.length > 253 || value.endsWith('.')) return false
        val labels = value.split('.')
        if (labels.size < 2 || labels.last() in setOf("local", "localhost", "internal", "lan", "home", "localdomain", "invalid", "test")) return false
        // MVP accepts ASCII domains only; IDN/Arabic hosts (including punycode) remain rejected.
        if (labels.any { it.startsWith("xn--") || !it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) }) return false
        return labels.last().any { it in 'a'..'z' }
    }

    fun detect(host: String): Host {
        val value = host.lowercase(Locale.ROOT)
        val domains = listOf("youtube.com" to Host.YOUTUBE, "youtu.be" to Host.YOUTUBE,
            "youtube-nocookie.com" to Host.YOUTUBE,
            "tiktok.com" to Host.TIKTOK, "instagram.com" to Host.INSTAGRAM,
            "facebook.com" to Host.FACEBOOK, "fb.watch" to Host.FACEBOOK,
            "x.com" to Host.X, "twitter.com" to Host.X, "linkedin.com" to Host.LINKEDIN)
        return domains.firstOrNull { (domain, _) -> value == domain || value.endsWith(".$domain") }?.second ?: Host.OTHER
    }
}
