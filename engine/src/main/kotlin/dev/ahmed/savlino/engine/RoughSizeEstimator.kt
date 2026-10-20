package dev.ahmed.savlino.engine

import com.fasterxml.jackson.databind.JsonNode
import dev.ahmed.savlino.domain.*

/** Presentation only: manifest bandwidth may be peak, not average. Never a transfer budget. */
internal object RoughSizeEstimator {
    fun estimate(root: JsonNode, sources: Map<String, Pair<Format, JsonNode>>, rows: List<Format>): Map<String, Long> {
        if (root.path("is_live").asBoolean(false) || root.path("is_upcoming").asBoolean(false) ||
            root.path("live_status").asText() in setOf("is_live", "is_upcoming", "post_live")) return emptyMap()
        val duration = root.positiveNumber("duration")
            ?.takeIf { it <= PreviewMetadata.MAX_DURATION_SECONDS } ?: return emptyMap()
        fun fromBitrate(rate: Double?): Long? {
            val bytes = rate?.let { duration * it * 125 } ?: return null
            return bytes.takeIf { it.isFinite() && it >= 1 && it < Long.MAX_VALUE.toDouble() }?.toLong()
        }
        fun component(id: String): Long? {
            val (format, metadata) = sources[id] ?: return null
            return when (val size = format.size) {
                is Size.Exact -> size.bytes
                is Size.Estimated -> size.bytes
                Size.Unknown -> fromBitrate(metadata.positiveNumber("tbr")
                    ?: if (!format.hasVideo) metadata.positiveNumber("abr") else null)
            }
        }
        return rows.mapNotNull { row ->
            if (row.size != Size.Unknown) return@mapNotNull null
            val conversion = row.conversion
            val bytes = when {
                conversion != null -> fromBitrate(conversion.bitrateKbps.toDouble())
                !row.hasVideo && row.container == "m4a" && row.sourceFormatId != row.id ->
                    fromBitrate(sources[row.sourceFormatId]?.second?.positiveNumber("abr"))
                row.requiresMerge -> {
                    val video = component(row.sourceFormatId)
                    val audio = row.audioFormatId?.let(::component)
                    if (video == null || audio == null || video > Long.MAX_VALUE - audio) null else video + audio
                }
                else -> component(row.id)
            }
            bytes?.takeIf { it > 0 }?.let { row.id to it }
        }.toMap()
    }

    private fun JsonNode.positiveNumber(key: String) = get(key)?.takeIf { it.isNumber }
        ?.doubleValue()?.takeIf { it.isFinite() && it > 0 }
}
