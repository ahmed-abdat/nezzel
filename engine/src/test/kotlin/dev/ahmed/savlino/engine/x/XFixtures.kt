package dev.ahmed.savlino.engine.x

/** Synthetic ids and names only; shapes follow the public syndication JSON (no real post, user or signed URL). */
internal const val X_ID = "1790000000000000001"
internal const val X_POST = "https://x.com/someone/status/$X_ID"
internal const val X_SYNDICATION_TARGET = "/tweet-result?id=$X_ID&token=4c7g8auqyik"

internal fun xPhoto(name: String = "SyntheticPhoto1", ext: String = "jpg", width: Int = 1200, height: Int = 900) =
    """{"display_url":"pic.x.com/Synthetic","expanded_url":"https://x.com/someone/status/$X_ID/photo/1",""" +
        """"ext_media_availability":{"status":"Available"},"indices":[20,43],""" +
        """"media_url_https":"https://pbs.twimg.com/media/$name.$ext",""" +
        """"original_info":{"focus_rects":[],"height":$height,"width":$width},""" +
        """"sizes":{"large":{"h":$height,"resize":"fit","w":$width}},"type":"photo","url":"https://t.co/Synthetic"}"""

internal fun xVideo(type: String = "video") =
    """{"media_url_https":"https://pbs.twimg.com/ext_tw_video_thumb/1/pu/img/SyntheticThumb.jpg",""" +
        """"original_info":{"height":720,"width":1280},"type":"$type",""" +
        """"video_info":{"aspect_ratio":[16,9],"variants":[{"bitrate":${if (type == "animated_gif") 0 else 832000},"content_type":"video/mp4",""" +
        """"url":"https://video.twimg.com/ext_tw_video/1/pu/vid/1280x720/Synthetic.mp4"}]}}"""

internal fun xTweet(media: List<String>? = listOf(xPhoto()), id: String = X_ID, typename: String = "Tweet",
    text: String = "Synthetic caption line\nsecond line https://t.co/Synthetic", extra: String = ""): ByteArray =
    ("""{"__typename":"$typename","lang":"en","id_str":"$id","text":${quote(text)},""" +
        """"user":{"id_str":"42","name":"Synthetic Author","screen_name":"someone",""" +
        """"profile_image_url_https":"https://pbs.twimg.com/profile_images/42/Synthetic_normal.jpg"}""" +
        (media?.let { ""","mediaDetails":[${it.joinToString(",")}]""" } ?: "") + extra + "}").toByteArray()

/** A text post that links out: the card's own image and a quoted post's photo are not this post's media. */
internal fun xTextWithCard(): ByteArray = xTweet(media = null, text = "Read this https://t.co/Synthetic", extra =
    ""","card":{"name":"summary_large_image","binding_values":{"thumbnail_image_large":{"image_value":""" +
        """{"url":"https://pbs.twimg.com/card_img/1/Synthetic?format=jpg&name=800x419"}}}},""" +
        """"quoted_tweet":{"__typename":"Tweet","id_str":"1790000000000000009","mediaDetails":[${xPhoto("QuotedPhoto")}]}""")

internal fun xTombstone(): ByteArray = """{"__typename":"TweetTombstone","tombstone":{"text":{"text":"Synthetic"}}}""".toByteArray()

private fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
