package dev.ahmed.savlino.engine.youtube

import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.*
import java.util.Locale

internal object NpeFixtures {
    fun url(itag: Int) = "https://rr1---sn-test.googlevideo.com/videoplayback?itag=$itag"

    fun video(itag: Int = 137, height: Int = 1080, codec: String? = "avc1.640028",
        only: Boolean = true, method: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        url: String = url(itag), length: Long = 0, bitrate: Int = 800_000,
        durationMs: Long = 0, format: MediaFormat = MediaFormat.MPEG_4): VideoStream {
        val item = ItagItem(itag, if (only) ItagItem.ItagType.VIDEO_ONLY else ItagItem.ItagType.VIDEO,
            format, "${height}p", 30).apply {
            setWidth(height * 16 / 9); setHeight(height); setCodec(codec); setBitrate(bitrate)
            setContentLength(length); setApproxDurationMs(durationMs)
        }
        return VideoStream.Builder().setId("$itag").setContent(url, true).setMediaFormat(format)
            .setDeliveryMethod(method).setIsVideoOnly(only).setResolution("${height}p").setItagItem(item).build()
    }

    fun audio(itag: Int = 140, track: String? = null, type: AudioTrackType? = null, drc: Boolean = false,
        codec: String? = "mp4a.40.2", method: DeliveryMethod = DeliveryMethod.PROGRESSIVE_HTTP,
        url: String = url(itag), length: Long = 0, bitrate: Int = 128_000, average: Int = 128,
        locale: Locale? = null, format: MediaFormat = MediaFormat.M4A): AudioStream {
        val item = ItagItem(itag, ItagItem.ItagType.AUDIO, format, average).apply {
            setCodec(codec); setBitrate(bitrate); setContentLength(length); setIsDrc(drc)
        }
        return AudioStream.Builder().setId("$itag").setContent(url, true).setMediaFormat(format)
            .setDeliveryMethod(method).setAverageBitrate(average).setAudioTrackId(track)
            .setAudioTrackType(type).setAudioLocale(locale).setItagItem(item).build()
    }

    fun info(videos: List<VideoStream> = listOf(video()), audios: List<AudioStream> = listOf(audio()),
        type: StreamType = StreamType.VIDEO_STREAM, duration: Long = 10): StreamInfo =
        StreamInfo(0, "https://www.youtube.com/watch?v=fixture-only", "https://www.youtube.com/watch?v=fixture-only",
            type, "fixture-only", "Fixture title", 0).apply {
            setDuration(duration); setUploaderName("Fixture uploader")
            setVideoOnlyStreams(videos.filter { it.isVideoOnly }); setVideoStreams(videos.filterNot { it.isVideoOnly })
            setAudioStreams(audios)
            setThumbnails(listOf(Image("https://images.test/small.jpg", 90, 160, Image.ResolutionLevel.LOW),
                Image("https://images.test/large.jpg", 720, 1280, Image.ResolutionLevel.HIGH)))
        }

    fun golden() = info(videos = listOf(video(), video(136, 720)), audios = listOf(
        audio(), audio(drc = true),
        audio(251, "en.4", AudioTrackType.ORIGINAL, codec = "opus", format = MediaFormat.WEBMA_OPUS),
        audio(251, "en.4", AudioTrackType.ORIGINAL, drc = true, codec = "opus", format = MediaFormat.WEBMA_OPUS),
        audio(251, "sr", AudioTrackType.DUBBED, codec = "opus", format = MediaFormat.WEBMA_OPUS),
        audio(251, "2", AudioTrackType.SECONDARY, drc = true, codec = "opus", format = MediaFormat.WEBMA_OPUS)))
}
