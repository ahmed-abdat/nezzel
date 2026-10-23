package dev.ahmed.savlino.engine.mediaimage

import dev.ahmed.savlino.domain.*
import dev.ahmed.savlino.engine.mediahttp.*
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import javax.imageio.ImageIO
import javax.net.ssl.SSLSocket
import org.junit.AfterClass
import org.junit.BeforeClass
import kotlin.test.*

internal const val POST = "https://www.linkedin.com/posts/synthetic-activity-123"
internal const val ACTIVITY = "https://www.linkedin.com/feed/update/urn:li:activity:123"
internal const val ASSET = "https://media.licdn.com/asset-one?sig=Ab%2fCD%2B%3d"
internal const val SECOND_ASSET = "https://media.licdn.com/asset-two?sig=synthetic"
internal const val AVATAR = "https://media.licdn.com/avatar?sig=synthetic"
internal val IMAGE_IDENTITY = MediaAdapterIdentity("public-image", 1, "android-image-1", 1)
internal fun imageSource(url: String = "https://media.licdn.com/opaque?sig=synthetic", revision: Long = 1) =
    LinkRevision(assertNotNull(LinkInput.parse(url)), revision)
internal fun imageDigest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it.toInt() and 255) }

/** Real generated synthetic raster, never a user's attachment. */
internal fun raster(width: Int = 32, height: Int = 24, format: String = "png", color: Int = 0x37a190): ByteArray {
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    try {
        val graphics = image.createGraphics()
        try { graphics.color = java.awt.Color(color); graphics.fillRect(0, 0, width, height) }
        finally { graphics.dispose() }
        return ByteArrayOutputStream().also { assertTrue(ImageIO.write(image, format, it)) }.toByteArray()
    } finally { image.flush() }
}

internal fun pngChunk(type: String, body: ByteArray): ByteArray {
    val name = type.toByteArray(Charsets.US_ASCII)
    val crc = CRC32().apply { update(name); update(body) }.value.toInt()
    return ByteBuffer.allocate(body.size + 12).putInt(body.size).put(name).put(body).putInt(crc).array()
}
internal fun bombPng(width: Int, height: Int): ByteArray =
    byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) +
        pngChunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(byteArrayOf(8, 2, 0, 0, 0)).array()) +
        pngChunk("IEND", byteArrayOf())
/** Complete, real, highly compressed grayscale PNG (valid IDAT), not a header-only fixture. */
internal fun compressedGrayPng(width: Int, height: Int, bitDepth: Int = 8, value: Int = 151): ByteArray {
    val row = ByteArray(1 + ((width.toLong() * bitDepth + 7) / 8).toInt()) { if (it == 0) 0 else value.toByte() }
    val pixels = ByteArrayOutputStream()
    java.util.zip.DeflaterOutputStream(pixels, java.util.zip.Deflater(java.util.zip.Deflater.BEST_SPEED)).use { out ->
        repeat(height) { out.write(row) }
    }
    return byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) +
        pngChunk("IHDR", ByteBuffer.allocate(13).putInt(width).putInt(height).put(byteArrayOf(bitDepth.toByte(), 0, 0, 0, 0)).array()) +
        pngChunk("IDAT", pixels.toByteArray()) + pngChunk("IEND", byteArrayOf())
}
internal fun animatedPng(): ByteArray {
    val bytes = raster()
    return bytes.copyOfRange(0, 33) + pngChunk("acTL", ByteBuffer.allocate(8).putInt(2).putInt(0).array()) +
        bytes.copyOfRange(33, bytes.size)
}
internal fun webpHeader(width: Int = 16385, height: Int = 1, animated: Boolean = false): ByteArray {
    val body = ByteArray(10)
    body[0] = if (animated) 2 else 0
    for ((offset, value) in listOf(4 to (width - 1), 7 to (height - 1))) {
        repeat(3) { body[offset + it] = (value ushr (8 * it)).toByte() }
    }
    return "RIFF".toByteArray() + ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(22).array() +
        "WEBPVP8X".toByteArray() + byteArrayOf(10, 0, 0, 0) + body
}

/** Exact post-owned DOM plus matching JSON-LD. Claimed count and decoy roots are deliberately independent. */
internal fun linkedInHtml(assets: List<String> = listOf(ASSET), domAssets: List<String> = assets,
    canonical: String = POST, jsonPost: String = POST, og: String? = assets.firstOrNull(),
    claimed: Int? = null, extra: String = "", author: String = AVATAR): ByteArray {
    fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    fun attr(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;")
    val images = assets.joinToString(",") { "{\"@type\":\"ImageObject\",\"contentUrl\":${json(it)}}" }
    return ("<!doctype html><html><head><link rel=canonical href=\"${attr(canonical)}\">" +
        (og?.let { "<meta property=\"og:image\" content=\"${attr(it)}\">" } ?: "") +
        "<script type=\"application/ld+json\">{\"@context\":\"https://schema.org\",\"@type\":\"SocialMediaPosting\"," +
        "\"url\":${json(jsonPost)},\"identifier\":\"urn:li:activity:123\",\"headline\":\"Synthetic image post\"," +
        "\"author\":{\"@type\":\"Person\",\"image\":${json(author)}},\"image\":[$images]}</script></head>" +
        "<body><main><article data-activity-urn=\"urn:li:activity:123\" data-post-url=\"${attr(POST)}\">" +
        "<img class=avatar src=\"${attr(author)}\"><ul class=\"feed-images-content\"" +
        (claimed?.let { " data-num-images=\"$it\"" } ?: "") + ">" +
        domAssets.joinToString("") { "<li><img data-delayed-url=\"${attr(it)}\"></li>" } +
        "</ul></article></main>$extra</body></html>").toByteArray()
}

/** Engine JVM tests decode real PNG/JPEG via installed JDK; Android behavior is tested separately in app. */
internal open class JdkPreviewDecoder : ImagePreviewDecoder {
    val previews = mutableListOf<JdkOwnedPreview>()
    override fun decode(bytes: ByteArray, mimeType: String, isActive: () -> Boolean, length: Int): ImageDecodeResult {
        if (!isActive()) return ImageDecodeResult.Failure(ImageFailure.CANCELLED)
        val original = ImageIO.read(ByteArrayInputStream(bytes, 0, length)) ?: return ImageDecodeResult.Failure(ImageFailure.DECODE)
        try {
            val scale = minOf(1.0, 1024.0 / maxOf(original.width, original.height))
            val image = BufferedImage(maxOf(1, (original.width * scale).toInt()),
                maxOf(1, (original.height * scale).toInt()), BufferedImage.TYPE_INT_ARGB)
            val graphics = image.createGraphics()
            try { graphics.drawImage(original, 0, 0, image.width, image.height, null) } finally { graphics.dispose() }
            val preview = JdkOwnedPreview(image)
            previews += preview
            return ImageDecodeResult.Success(preview)
        } finally { original.flush() }
    }
}
internal class JdkOwnedPreview(private val image: BufferedImage) : OwnedImagePreview {
    override val width = image.width
    override val height = image.height
    override val allocationBytes = width.toLong() * height * 4
    val closeCount = AtomicInteger()
    override val isClosed get() = closeCount.get() > 0
    override fun close() { if (closeCount.compareAndSet(0, 1)) image.flush() }
    override fun toString() = "JdkOwnedPreview(redacted)"
}

internal class PublicImageFixture(identity: MediaHttpTlsIdentity,
    decoder: ImagePreviewDecoder = JdkPreviewDecoder(),
    respond: (RecordedMediaRequest, SSLSocket) -> Unit = { _, socket -> socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png")) },
    resolver: (String) -> List<java.net.InetAddress> = { listOf(PUBLIC_V4) }) : Closeable {
    // A page plus three parallel carousel bodies, each on its own connection.
    val server = MediaHttpServer(identity, handlerThreads = 4) { request, socket ->
        if (request.target == "/control") socket.reply() else respond(request, socket)
    }
    val http = MediaHttpHarness(identity, server, resolver)
    val store = MediaReferenceStore()
    /** X video info handed out before a redirect: post id + yt-dlp-shaped JSON. */
    val xVideoInfos = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
    val inspector = PublicImageInspector(http.client, decoder, store) { post, info -> xVideoInfos += post.postId to info }
    val saver = ImageSaveTransfer(http.client, store)
    override fun close() { inspector.close(); store.close(); http.close(); server.close() }
}

abstract class PublicImageTestBase {
    companion object {
        private lateinit var tls: MediaHttpTlsIdentity
        @JvmStatic @BeforeClass fun imageTlsIdentity() { tls = MediaHttpTlsIdentity() }
        @JvmStatic @AfterClass fun closeImageTlsIdentity() { if (::tls.isInitialized) tls.close() }
    }
    internal fun fixture(decoder: ImagePreviewDecoder = JdkPreviewDecoder(),
        respond: (RecordedMediaRequest, SSLSocket) -> Unit = { _, socket -> socket.reply(body = raster(), headers = mapOf("Content-Type" to "image/png")) },
        resolver: (String) -> List<java.net.InetAddress> = { listOf(PUBLIC_V4) },
        test: (PublicImageFixture) -> Unit) = PublicImageFixture(tls, decoder, respond, resolver).use(test)
}
internal fun inspected(result: MediaInspectResult): MediaInspectResult.Success = assertIs<MediaInspectResult.Success>(result)
internal fun failed(result: MediaInspectResult, reason: ImageFailure, phase: ImagePhase? = null): MediaInspectResult.Failure {
    val failure = assertIs<MediaInspectResult.Failure>(result)
    assertEquals(reason, failure.reason)
    if (phase != null) assertEquals(phase, failure.phase)
    return failure
}
internal fun firstRendition(result: MediaInspectResult.Success) =
    assertIs<MediaRendition.Image>(result.inspection.items.first().renditions.single())

/** Two 1×1 frames (red, blue), with real LZW data and a looping application extension. */
internal fun animatedGif(version: String = "89a"): ByteArray {
    val hex = "01000100800000ff00000000ff" +
        "21ff0b4e45545343415045322e300301000000" +
        "21f904000a0000002c0000000001000100000202440100" +
        "21f904000a0000002c00000000010001000002024c01003b"
    return ("GIF" + version).toByteArray() + hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
