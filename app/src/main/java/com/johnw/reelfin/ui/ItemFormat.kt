package com.johnw.reelfin.ui

import com.johnw.reelfin.data.ImageRef
import com.johnw.reelfin.data.ImageUrls
import com.johnw.reelfin.playback.AudioTrackPolicy
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaStreamType

/** Turns SDK items into the strings and art the browse screens show. */
object ItemFormat {
    fun ImageRef?.art(): Art? = this?.let { Art(it.url, it.blurHash) }

    fun landscape(api: ApiClient, item: BaseItemDto, large: Boolean = false): Art? =
        ImageUrls.landscape(api, item, if (large) ImageUrls.Width.LANDSCAPE_LARGE else ImageUrls.Width.LANDSCAPE).art()
    fun poster(api: ApiClient, item: BaseItemDto): Art? = ImageUrls.poster(api, item).art()
    fun backdrop(api: ApiClient, item: BaseItemDto): Art? = ImageUrls.backdrop(api, item).art()
    fun logo(api: ApiClient, item: BaseItemDto): Art? = ImageUrls.logo(api, item).art()

    /** Tile caption: the series name for episodes, otherwise the item name. */
    fun title(item: BaseItemDto): String = when (item.type) {
        BaseItemKind.EPISODE -> item.seriesName ?: item.name.orEmpty()
        else -> item.name.orEmpty()
    }

    /** "S2:E5 · Episode name" for episodes, null otherwise. */
    fun episodeLine(item: BaseItemDto): String? {
        if (item.type != BaseItemKind.EPISODE) return null
        val s = item.parentIndexNumber
        val e = item.indexNumber
        val code = if (s != null && e != null) "S$s:E$e" else null
        return listOfNotNull(code, item.name).joinToString("  ·  ").ifEmpty { null }
    }

    fun progress(item: BaseItemDto): Float? =
        item.userData?.playedPercentage?.takeIf { it > 0.5 && it < 99.5 }?.let { (it / 100).toFloat() }

    fun played(item: BaseItemDto): Boolean = item.userData?.played == true

    fun runtime(ticks: Long?): String? {
        val minutes = ((ticks ?: return null) / 600_000_000L).toInt()
        if (minutes <= 0) return null
        val h = minutes / 60
        val m = minutes % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    /** Minutes left for something in progress: "34m left". */
    fun remaining(item: BaseItemDto): String? {
        val total = item.runTimeTicks ?: return null
        val pos = item.userData?.playbackPositionTicks ?: return null
        if (pos <= 0) return null
        return runtime(total - pos)?.let { "$it left" }
    }

    /** "2019 · PG-13 · 2h 14m" (or "3 Seasons" for a series). */
    fun metaParts(item: BaseItemDto): List<String> = buildList {
        item.productionYear?.let { add(it.toString()) }
        item.officialRating?.takeIf { it.isNotBlank() }?.let { add(it) }
        when (item.type) {
            BaseItemKind.SERIES -> item.childCount?.takeIf { it > 0 }?.let { add(if (it == 1) "1 Season" else "$it Seasons") }
            else -> runtime(item.runTimeTicks)?.let { add(it) }
        }
        item.communityRating?.let { add("★ %.1f".format(it)) }
    }

    /**
     * Quality badges from the first media source: 4K/HD, Dolby Vision / HDR10+ / HDR10 / HLG,
     * Atmos, 5.1 and CC. TrueHD/DTS tracks are ignored (Reelfin never plays them).
     */
    fun badges(item: BaseItemDto): List<String> {
        val streams = item.mediaSources?.firstOrNull()?.mediaStreams ?: item.mediaStreams ?: return emptyList()
        val video = streams.firstOrNull { it.type == MediaStreamType.VIDEO }
        val audio = streams.filter { it.type == MediaStreamType.AUDIO && !AudioTrackPolicy.isBanned(it.codec) }
        return buildList {
            val w = video?.width ?: 0
            if (w >= 3200) add("4K") else if (w >= 1200) add("HD")
            when (val range = video?.videoRangeType?.toString().orEmpty()) {
                "DOVI", "DOVIWithHDR10", "DOVIWithHLG", "DOVIWithSDR", "DOVIWithHDR10Plus" -> add("Dolby Vision")
                "HDR10Plus" -> add("HDR10+")
                "HDR10", "DOVIWithEL", "DOVIWithELHDR10Plus", "DOVIInvalid" -> add("HDR10")
                "HLG" -> add("HLG")
                else -> if (range.startsWith("DOVI")) add("HDR10")
            }
            val atmos = audio.any { a ->
                listOf(a.profile, a.title, a.displayTitle).any { it?.contains("Atmos", ignoreCase = true) == true }
            }
            if (atmos) add("Atmos")
            if (audio.any { (it.channels ?: 0) >= 6 }) add("5.1")
            if (streams.any { it.type == MediaStreamType.SUBTITLE }) add("CC")
        }
    }
}
