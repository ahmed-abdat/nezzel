package dev.ahmed.savlino.engine.telegram

import dev.ahmed.savlino.engine.mediahttp.MediaHttpPolicy
import dev.ahmed.savlino.engine.mediahttp.MediaPageLimits
import dev.ahmed.savlino.engine.mediaimage.*
import org.jsoup.Jsoup

sealed interface TelegramParseResult {
    class Success(val candidates: List<String>, val discovery: ImageDiscovery, val title: String?) : TelegramParseResult {
        override fun toString() = "TelegramParseResult.Success(redacted)"
    }
    data object Video : TelegramParseResult
    data class Failure(val reason: ImageFailure, val phase: ImagePhase = ImagePhase.PAGE) : TelegramParseResult
}

/** Only the requested widget's own attachments count; link previews, replies and avatars do not. */
class TelegramPostParser {
    fun parse(body: ByteArray, identity: TelegramPostIdentity, length: Int = body.size): TelegramParseResult {
        if (length !in 0..body.size || length > MediaPageLimits.TELEGRAM)
            return TelegramParseResult.Failure(ImageFailure.BUDGET)
        val document = Jsoup.parse(String(body, 0, length, Charsets.UTF_8))
        if (document.selectFirst(".tgme_widget_message_error") != null)
            return TelegramParseResult.Failure(ImageFailure.PRIVATE)
        val message = document.select(".tgme_widget_message[data-post]").singleOrNull {
            it.attr("data-post").equals(identity.postKey, ignoreCase = true)
        } ?: return TelegramParseResult.Failure(ImageFailure.SOURCE_REFUSED)
        fun own(element: org.jsoup.nodes.Element): Boolean = element.closest(".tgme_widget_message") === message &&
            element.closest(".tgme_widget_message_link_preview, .tgme_widget_message_reply, .tgme_widget_message_reply_to") == null
        val video = message.getAllElements().any { element -> own(element) && element.classNames().any { name ->
            listOf("video", "roundvideo", "document", "audio", "voice").any { kind ->
                name == "tgme_widget_message_$kind" || name.startsWith("tgme_widget_message_${kind}_")
            }
        } }
        val photos = message.select("a.tgme_widget_message_photo_wrap").filter(::own)
        // A video Telegram won't play on the web ("Media is too big"): its player is marked not_supported, with no file.
        val appOnly = message.select("a.tgme_widget_message_video_player.not_supported").any(::own) &&
            message.select("video[src]").none(::own)
        if (photos.isEmpty()) return if (appOnly) TelegramParseResult.Failure(ImageFailure.APP_ONLY)
            else if (video) TelegramParseResult.Video else TelegramParseResult.Failure(ImageFailure.NO_MEDIA)
        val urls = photos.take(20).mapNotNull { photo ->
            val raw = background.find(photo.attr("style"))?.groupValues?.get(2) ?: return@mapNotNull null
            MediaHttpPolicy.parse(raw)?.takeIf(MediaHttpPolicy::isTelegramImage)?.toString()
        }.distinct()
        if (urls.isEmpty()) return if (video) TelegramParseResult.Video
            else TelegramParseResult.Failure(ImageFailure.SOURCE_REFUSED, ImagePhase.IMAGE)
        val unavailable = minOf(photos.size, 20) - urls.size
        val capped = (photos.size - 20).coerceAtLeast(0)
        return TelegramParseResult.Success(urls, ImageDiscovery(urls.size,
            if (unavailable + capped == 0) DiscoveryCompleteness.COMPLETE else DiscoveryCompleteness.PARTIAL,
            unavailableCount = unavailable, cappedCount = capped, hasVideo = video),
            PostText.firstLine(message.selectFirst(".tgme_widget_message_text")?.wholeText()))
    }
    private companion object {
        val background = Regex("""background-image\s*:\s*url\(\s*(['"]?)(https://[^\s'"()]+)\1\s*\)""", RegexOption.IGNORE_CASE)
    }
}
