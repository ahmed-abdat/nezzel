package dev.ahmed.savlino.engine.instagram

/**
 * Synthetic codes, names and CDN paths only; the page shape follows logged-out `www.instagram.com/p/<code>/` pages
 * (`<script type="application/json" data-sjs>` → ScheduledServerJS → RelayPrefetchedStreamCache → `__bbox.result.data
 * .xig_polaris_media`). No real post, user or signed URL parameter.
 */
internal const val IG_CODE = "SynthPost01"
internal const val IG_POST = "https://www.instagram.com/p/$IG_CODE/"
internal const val IG_PAGE_TARGET = "/p/$IG_CODE/"
internal const val IG_CDN = "instagram.fxyz1-1.fna.fbcdn.net"

internal fun igImagePath(n: Int, size: String = "", ext: String = "jpg") =
    "/v/t51.82787-15/100000000${n}_18000000000000001_100000000000000000${n}_n.$ext?stp=dst-jpg_e35${size}_tt6&_nc_ht=synthetic"

internal fun igImageUrl(n: Int, size: String = "", host: String = IG_CDN, ext: String = "jpg") =
    "https://$host${igImagePath(n, size, ext)}"

/** image_versions2: the original first (photos carry no width keys), then resized copies. */
internal fun igCandidates(n: Int = 1, host: String = IG_CDN, sized: Boolean = false, ext: String = "jpg") =
    """"image_versions2":{"candidates":[""" +
        (if (sized) """{"url":${j(igImageUrl(n, "_s640x640", host))},"width":640,"height":427},""" +
            """{"url":${j(igImageUrl(n, "", host))},"width":1440,"height":960}"""
        else """{"url":${j(igImageUrl(n, "", host, ext))}},{"url":${j(igImageUrl(n, "_s1080x1080", host))}},""" +
            """{"url":${j(igImageUrl(n, "_s640x640", host))}}""") + "]}"

/** A carousel child photo. */
internal fun igPhoto(n: Int = 1, host: String = IG_CDN, sized: Boolean = false) =
    """{"__typename":"XIGPolarisImageMedia","code":"SynthItem0$n","id":"30000000000000000${n}_1","media_type":1,""" +
        """"original_width":1440,"original_height":960,"accessibility_caption":"Synthetic",${igCandidates(n, host, sized)}}"""

/** A carousel child video (its poster image is never this reader's photo). */
internal fun igVideo(n: Int = 9) =
    """{"__typename":"XIGPolarisVideoMedia","code":"SynthVideo$n","id":"30000000000000000${n}_1","media_type":2,""" +
        """"original_width":640,"original_height":640,"has_audio":true,"image_versions2":{"candidates":[""" +
        """{"url":${j(igImageUrl(n))},"width":640,"height":640}]},"video_versions":[{"type":101,""" +
        """"url":"https:\/\/$IG_CDN\/o1\/v\/t16\/f2\/m86\/Synthetic.mp4?efg=synthetic","width":640,"height":640}]}"""

/** The requested post's polaris media: photo (1), video (2) or carousel (8) with [children]. */
internal fun igMedia(type: Int = 1, code: String = IG_CODE, children: List<String>? = null, n: Int = 1,
    candidates: String = igCandidates(n), caption: String = "Synthetic caption line\nsecond line https://example.com/x",
    fullName: String = "Synthetic Author") =
    """{"__typename":"XIGPolarisMedia","code":"$code","id":"3000000000000000001_1","media_type":$type,""" +
        """"product_type":"${if (type == 8) "carousel_container" else "feed"}",""" +
        """"caption":{"pk":"1","text":${j(caption)},"created_at":1700000000},""" +
        """"user":{"pk":"42","username":"synthetic_user","full_name":${j(fullName)},"is_private":false,""" +
        """"profile_pic_url":"https:\/\/$IG_CDN\/v\/t51.2885-19\/1_n.jpg?stp=dst-jpg_s150x150"},""" +
        (children?.let { """"carousel_media":[${it.joinToString(",")}],""" } ?: "") +
        """"original_width":1440,"original_height":960,$candidates}"""

/** One relay result naming [code], with [media] logged-out (null → gated) and an optional [gating] ruling. */
internal fun igPolaris(code: String = IG_CODE, media: String? = igMedia(code = code), gating: String = "null") =
    """{"__bbox":{"complete":true,"result":{"data":{"xig_polaris_media":{"__typename":"XIGPolarisMedia","pk":"1",""" +
        """"code":"$code","if_not_gated_logged_out":${media ?: "null"},"gating_ruling":$gating,"id":"1",""" +
        """"comments_connection":{"edges":[{"node":{"text":"commenter","user":{"username":"other"}}}]}}},""" +
        """"extensions":{"is_final":true}}}}"""

/** A full page: decoy blocks first (other post's relay data, config), then the post's own relay result(s). */
internal fun igPage(vararg results: String = arrayOf(igPolaris()), decoys: Boolean = true): ByteArray {
    fun block(json: String) = """<script type="application/json" nonce="n" data-content-len="${json.length}" data-sjs>$json</script>"""
    fun scheduled(entries: List<String>) = """{"require":[["ScheduledServerJS","handle",null,[{"__bbox":{"require":[""" +
        entries.joinToString(",") + "]}}]]]}"
    fun relay(result: String) = """["RelayPrefetchedStreamCache","next",[],["adp_PolarisLoggedOutDesktopWWWPostRootContentQueryRelayPreloader_1",$result]]"""
    val decoy = if (!decoys) "" else block("""{"require":[["PolarisConfig","set",null,[{"app_id":"synthetic"}]]]}""") +
        // A related post's relay data (another code) must never become this post's media.
        block(scheduled(listOf(relay(igPolaris("SynthOther1", igMedia(code = "SynthOther1", n = 7)))))) +
        """<script type="application/json" id="deferred-scripts">{}</script>"""
    val own = block(scheduled(listOf("""["Bootloader","markComponentsAsImmediate",[],[["PolarisPost"]]]""") + results.map(::relay)))
    return ("""<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><title>Instagram</title>""" +
        """<meta property="og:image" content="https://scontent.example.cdninstagram.com/v/t51.2885-15/og_n.jpg">""" +
        "</head><body><div id=\"mount\"></div>$decoy$own</body></html>").toByteArray()
}

/** A logged-out shell with no relay data for the post (missing post, changed page or a 200 login wall). */
internal fun igShell(): ByteArray = igPage(results = emptyArray())

internal fun j(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
    .replace("/", "\\/") + "\""
