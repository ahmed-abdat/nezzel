package dev.ahmed.savlino.shortcuts

import android.appwidget.AppWidgetManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import dev.ahmed.savlino.R
import dev.ahmed.savlino.domain.Size
import dev.ahmed.savlino.downloads.SavedMedia
import dev.ahmed.savlino.jobs.DownloadNotifications
import dev.ahmed.savlino.jobs.DownloadPhase
import dev.ahmed.savlino.jobs.DownloadProgress
import dev.ahmed.savlino.jobs.DownloadWorker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class WidgetTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun job(id: String = "new", phase: DownloadPhase = DownloadPhase.DOWNLOADING, bytes: Long = 37) =
        DownloadProgress(id, "$id.mp4", phase, bytes, Size.Exact(100))
    private fun saved(id: Int) = SavedMedia(Uri.parse("content://media/external/downloads/$id"), "$id.mp4", "$id", "video/mp4",
        100, id.toLong(), null, null, null)
    private fun view(state: WidgetUiState, size: WidgetSize = WidgetSize.WIDE, language: String = "en", scale: Float = 1f,
        widthDp: Int = size.bounds.width.toInt(), heightDp: Int = maxOf(size.bounds.height.toInt(), 48)): View {
        val config = Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag(language)); fontScale = scale }
        val context = app.createConfigurationContext(config)
        return NezzelWidgetProvider.views(context, state, size, widthDp, heightDp).apply(context, FrameLayout(context))
    }
    private fun text(view: View, id: Int) = view.findViewById<TextView>(id).text.toString()

    @Test fun zeroToThreeJobsKeepTwoNewestLiveRowsAndCountTheRest() {
        assertTrue(WidgetUiState.from(emptyList()).jobs.isEmpty())
        val one = WidgetUiState.from(listOf(job()))
        assertEquals(listOf("new"), one.jobs.map { it.id })
        assertEquals(35, one.jobs.single().percent)
        assertEquals(0, one.more)
        val two = WidgetUiState.from(listOf(job("a"), job("b")))
        assertEquals(listOf("b", "a"), two.jobs.map { it.id })
        assertEquals(0, two.more)
        val three = WidgetUiState.from(listOf(job("a"), job("b"), job("c")))
        assertEquals(listOf("c", "b"), three.jobs.map { it.id })
        assertEquals(1, three.more)
        val rendered = view(three)
        assertEquals(View.VISIBLE, rendered.findViewById<View>(R.id.widget_job_second).visibility)
        assertEquals("⁦+1⁩", text(rendered, R.id.widget_more))
    }

    // A playlist hands out a few videos at a time; the rest still wait, so «+N» is not «+1» with 15 to go.
    @Test fun aPlaylistsVideosNotHandedOutYetCountInTheRest() {
        val state = WidgetUiState.from(listOf(job("a"), job("b")), unfed = 15)
        assertEquals(listOf("b", "a"), state.jobs.map { it.id })
        assertEquals(15, state.more)
        // One row shown: every unfed video is in the rest, none hides behind the empty second row.
        assertEquals(15, WidgetUiState.from(listOf(job("a")), unfed = 15).more)
        assertEquals(1, WidgetUiState.from(listOf(job("a")), unfed = 1).more)
    }

    @Test fun runningComesBeforePausedQueuedAndFailedAndFinishedFilesLeaveTheRows() {
        val state = WidgetUiState.from(listOf(job("live"), job("paused").copy(paused = true),
            job("failed", DownloadPhase.FAILED), job("done", DownloadPhase.COMPLETED), job("gone", DownloadPhase.CANCELLED)))
        assertEquals(listOf("live", "paused"), state.jobs.map { it.id })
        assertEquals(1, state.more)
        assertEquals("Paused", text(view(WidgetUiState.from(job().copy(paused = true))), R.id.widget_status))
        assertEquals("Failed", text(view(WidgetUiState.from(job(phase = DownloadPhase.FAILED))), R.id.widget_status))
        assertTrue(WidgetUiState.from(job(phase = DownloadPhase.COMPLETED)).jobs.isEmpty())
    }

    @Test fun idleShowsPasteAndSavedCountAndAtMostFourNewestRealFiles() {
        val empty = view(WidgetUiState())
        assertEquals("0 saved", text(empty, R.id.widget_saved_count))
        assertEquals(View.VISIBLE, empty.findViewById<View>(R.id.widget_paste).visibility)
        assertEquals(View.GONE, empty.findViewById<View>(R.id.widget_recents).visibility)
        val state = WidgetUiState.from(emptyList(), (1..5).map(::saved))
        assertEquals(5, state.savedCount)
        assertEquals(listOf("5.mp4", "4.mp4", "3.mp4", "2.mp4"), state.recents.map { it.media.filename })
        val filled = view(state)
        assertEquals("5 saved", text(filled, R.id.widget_saved_count))
        assertEquals(View.VISIBLE, filled.findViewById<View>(R.id.widget_recent_4).visibility)
        filled.findViewById<View>(R.id.widget_recent_1).performClick()
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(ShortcutActions.OPEN_SAVED, intent.action)
        assertEquals("content://media/external/downloads/5", intent.getStringExtra(ShortcutActions.SAVED_URI))
    }

    @Test fun pasteTargetsTheForegroundPasteEntryInEverySizeAndState() {
        for (size in WidgetSize.entries) for (state in listOf(WidgetUiState(), WidgetUiState.from(job()))) {
            val rendered = view(state, size)
            if (size == WidgetSize.SLIM && state.jobs.isNotEmpty()) {
                assertEquals(View.GONE, rendered.findViewById<View>(R.id.widget_paste_icon).visibility)
                continue
            }
            val target = rendered.findViewById<View>(if (state.jobs.isEmpty()) R.id.widget_paste else R.id.widget_paste_icon)
            assertEquals(View.VISIBLE, target.visibility)
            assertNotNull(target.contentDescription)
            target.performClick()
            val intent = shadowOf(app).nextStartedActivity
            assertEquals(ShortcutActions.PASTE_LINK, intent.action)
            assertTrue(ShortcutActions.isPasteEntry(intent))
        }
        assertTrue(shadowOf(ShortcutActions.pendingIntent(app, ShortcutActions.PASTE_LINK)).isImmutable)
    }

    @Test fun pausedBarsUseXmlAlphaAndResumeReplacesTheFrozenBar() {
        for (size in listOf(WidgetSize.SLIM, WidgetSize.WIDE)) {
            val jobs = listOf(job("a"), job("b", bytes = 62))
            val rendered = view(WidgetUiState.from(jobs.map { it.copy(paused = true) }), size)
            val activeId = if (size == WidgetSize.SLIM) R.id.widget_progress else R.id.widget_progress_second
            val pausedId = if (size == WidgetSize.SLIM) R.id.widget_progress_paused else R.id.widget_progress_paused_second
            val paused = rendered.findViewById<ProgressBar>(pausedId)
            assertEquals(View.GONE, rendered.findViewById<View>(activeId).visibility)
            assertEquals(View.VISIBLE, paused.visibility)
            assertEquals(0.5f, paused.alpha)
            assertFalse(paused.isIndeterminate)
            assertEquals(if (size == WidgetSize.SLIM) 60 else 35, paused.progress)
            NezzelWidgetProvider.views(rendered.context, WidgetUiState.from(jobs), size).reapply(rendered.context, rendered)
            assertEquals(View.GONE, paused.visibility)
            assertEquals(View.VISIBLE, rendered.findViewById<View>(activeId).visibility)
        }
    }

    @Test fun controlsUseExistingReceiverWithDistinctJobIdentities() {
        val pause = shadowOf(NezzelWidgetProvider.control(app, WidgetUiState.from(job("a")).jobs.single())).savedIntent
        val resume = shadowOf(NezzelWidgetProvider.control(app, WidgetUiState.from(job("b").copy(paused = true)).jobs.single())).savedIntent
        assertEquals(DownloadNotifications.PAUSE, pause.action)
        assertEquals(DownloadNotifications.RESUME, resume.action)
        assertEquals("a", pause.getStringExtra(DownloadWorker.JOB_ID))
        assertEquals("b", resume.getStringExtra(DownloadWorker.JOB_ID))
        assertNotEquals(pause.data, resume.data)
        assertTrue(pause.component!!.className.endsWith(".DownloadControlReceiver"))
    }

    @Test fun sizeFallbackUsesBothDimensionsAndSlimNeverSqueezesATitle() {
        fun bucket(w: Int, h: Int) = WidgetSize.from(Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, w)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, h)
        })
        assertEquals(WidgetSize.SLIM, bucket(110, 40))
        assertEquals(WidgetSize.SLIM, bucket(250, 109))
        assertEquals(WidgetSize.SQUARE, bucket(110, 110))
        assertEquals(WidgetSize.SQUARE, bucket(249, 150))
        assertEquals(WidgetSize.WIDE, bucket(250, 110))
        assertEquals(WidgetSize.WIDE, bucket(280, 150))
        for (size in WidgetSize.entries) assertEquals(size.layout, NezzelWidgetProvider.views(app, WidgetUiState(), size).layoutId)
        val slim = view(WidgetUiState.from(job()), WidgetSize.SLIM)
        assertEquals(View.GONE, slim.findViewById<View>(R.id.widget_title).visibility)
        assertEquals("35%", text(slim, R.id.widget_status))
        assertEquals(View.VISIBLE, slim.findViewById<View>(R.id.widget_control).visibility)
    }

    @Test @Config(sdk = [33]) fun android12UsesResponsiveRemoteViews() {
        val views = NezzelWidgetProvider.responsiveViews(app, WidgetUiState(), Bundle())
        // A responsive parcel must survive transport to the launcher.
        val parcel = android.os.Parcel.obtain()
        try {
            views.writeToParcel(parcel, 0); parcel.setDataPosition(0)
            assertNotNull(android.widget.RemoteViews.CREATOR.createFromParcel(parcel))
        } finally { parcel.recycle() }
    }

    @Test fun pasteWordsFollowTheAppLanguageWhenTheLauncherUsesAnother() {
        val arabic = app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(java.util.Locale.forLanguageTag("ar")) })
        for (size in WidgetSize.entries) {
            // The launcher inflates in the system locale (en here); the app language is Arabic.
            val rendered = NezzelWidgetProvider.views(arabic, WidgetUiState.from(emptyList(), emptyList()), size).apply(app, FrameLayout(app))
            val label = arabic.getString(if (size == WidgetSize.WIDE) R.string.paste_link else R.string.widget_paste_short)
            assertEquals(label, text(rendered, R.id.widget_paste_label))
            assertEquals(arabic.getString(R.string.paste_link), rendered.findViewById<View>(R.id.widget_paste).contentDescription)
        }
    }

    @Test fun rtlResolvesForEverySizeWithIsolatedPercentAndArabicTitle() {
        for (size in WidgetSize.entries) {
            val rendered = view(WidgetUiState.from(job().copy(filename = "رحلة إلى الجبل.mp4")), size, "ar")
            assertEquals(View.LAYOUT_DIRECTION_RTL, rendered.layoutDirection)
            assertEquals("⁦35%⁩", text(rendered, R.id.widget_status))
            if (size == WidgetSize.WIDE) assertEquals("⁨رحلة إلى الجبل⁩", text(rendered, R.id.widget_title))
        }
    }

    @Test fun unknownSizesAndUnmeasuredOrPausedTransfersNeverInventTimeOrPercent() {
        val known = job().copy(bytesPerSecond = 10.0, remainingTransferSeconds = 6)
        assertEquals(6L, WidgetUiState.from(known).jobs.single().remainingSeconds)
        for (unknown in listOf(known.copy(total = Size.Unknown), known.copy(stalled = true),
            known.copy(waiting = true), known.copy(paused = true), known.copy(bytesPerSecond = Double.NaN))) {
            assertNull(WidgetUiState.from(unknown).jobs.single().remainingSeconds)
        }
        assertNull(WidgetUiState.from(job().copy(total = Size.Unknown)).jobs.single().percent)
    }

    @Test fun tapTargetsFitAllThreeSizesInBothDirectionsAtLargeText() {
        val density = app.resources.displayMetrics.density
        for (size in WidgetSize.entries) for (language in listOf("en", "ar")) for (live in listOf(false, true)) {
            val root = view(if (live) WidgetUiState.from(job()) else WidgetUiState(), size, language, 1.3f)
            val width = (size.bounds.width * density).toInt()
            // The 40dp fitting bound selects slim; its actual actions also fit a 48dp cell.
            val height = (maxOf(size.bounds.height, 48f) * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, width, height)
            for (id in if (!live) listOf(R.id.widget_paste) else when (size) {
                WidgetSize.SLIM -> listOf(R.id.widget_control)
                WidgetSize.SQUARE -> listOf(R.id.widget_paste_icon, R.id.widget_control)
                // One job: pause sits above Paste at the end, so the title keeps the width.
                WidgetSize.WIDE -> listOf(R.id.widget_paste_icon, R.id.widget_control_end)
            }) {
                val target = root.findViewById<View>(id)
                val rect = android.graphics.Rect(0, 0, target.width, target.height)
                (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(target, rect)
                assertTrue("$size/$language/$live width $rect", target.width >= 48 * density)
                assertTrue("$size/$language/$live height $rect", target.height >= 48 * density)
                assertTrue("$size/$language/$live bounds $rect", rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height)
            }
        }
    }

    @Test fun wideRowsShareBarsAndEtaAppearsOnlyForASingleJob() {
        val first = job("first").copy(bytesPerSecond = 10.0, remainingTransferSeconds = 6)
        val state = WidgetUiState.from(listOf(job("second"), first))
        for (height in listOf(110, 150)) {
            val root = view(state, WidgetSize.WIDE, heightDp = height)
            assertNull(root.findViewById<View>(R.id.widget_ring))
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_progress).visibility)
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_progress_second).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_remaining).visibility)
            // Two rows: each keeps its own pause; the end column only has Paste.
            assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_control_second).visibility)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_control_end).visibility)
            val single = view(WidgetUiState.from(first), WidgetSize.WIDE, heightDp = height)
            assertEquals(View.VISIBLE, single.findViewById<View>(R.id.widget_remaining).visibility)
            assertEquals("0:06 left", text(single, R.id.widget_remaining).replace(Regex("[\u2066-\u2069]"), ""))
            assertEquals(2, single.findViewById<android.widget.TextView>(R.id.widget_title).maxLines)
            assertEquals(View.GONE, single.findViewById<View>(R.id.widget_control).visibility)
            assertEquals(View.VISIBLE, single.findViewById<View>(R.id.widget_control_end).visibility)
        }
        assertEquals(View.GONE, view(state, WidgetSize.SLIM).findViewById<View>(R.id.widget_remaining).visibility)
        val square = view(state, WidgetSize.SQUARE)
        assertNotNull(square.findViewById<View>(R.id.widget_ring))
        assertEquals("1 more", square.findViewById<View>(R.id.widget_more).contentDescription.toString())
    }

    @Test fun idleThumbnailsKeepTheChosenGapAndFillTheRemainingHeight() {
        val state = WidgetUiState.from(emptyList(), (1..4).map(::saved))
        val density = app.resources.displayMetrics.density
        for (size in listOf(WidgetSize.SQUARE, WidgetSize.WIDE)) for (height in listOf(110, 150)) {
            val root = view(state, size, heightDp = height)
            val widthPx = (size.bounds.width * density).toInt()
            val heightPx = (height * density).toInt()
            root.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, widthPx, heightPx)
            fun bounds(id: Int): android.graphics.Rect {
                val child = root.findViewById<View>(id)
                return android.graphics.Rect(0, 0, child.width, child.height).also {
                    (root as android.view.ViewGroup).offsetDescendantRectToMyCoords(child, it)
                }
            }
            val pill = bounds(R.id.widget_paste)
            val thumb = bounds(R.id.widget_recent_1)
            val padding = if (height == 110) 5 else 8
            val gap = if (height == 110) 4 else 8
            assertEquals((gap * density).toInt(), thumb.top - pill.bottom)
            assertEquals((padding * density).toInt(), heightPx - thumb.bottom)
            assertTrue(thumb.height() >= 48 * density)
            if (size == WidgetSize.WIDE) {
                assertEquals((8 * density).toInt(), bounds(R.id.widget_recent_2).left - thumb.right)
                assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_recent_gap_3).visibility)
            }
        }
    }

    @Test @Config(sdk = [33]) fun android12ThumbnailsClipToTheSystemInnerRadius() {
        val picture = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val state = WidgetUiState.from(emptyList(), (1..4).map(::saved)).let { ui ->
            ui.copy(recents = ui.recents.map { it.copy(thumbnail = picture) })
        }
        for (size in listOf(WidgetSize.SQUARE, WidgetSize.WIDE)) {
            val tile = view(state, size).findViewById<android.widget.ImageView>(R.id.widget_recent_1)
            assertTrue(tile.clipToOutline)
            val outline = android.graphics.Outline()
            tile.layout(0, 0, 100, 100)
            tile.outlineProvider.getOutline(tile, outline)
            assertEquals(app.resources.getDimension(android.R.dimen.system_app_widget_inner_radius), outline.radius, 0.01f)
        }
    }

    @Test fun beforeAndroid12ThumbnailsAreMaskedToTheInnerRadius() {
        // Pixels need native graphics, which cannot share this JVM with the SDK 35 capture; the mask
        // path is proven by the parceled bitmap being a new tile-shaped copy, not the square source.
        val picture = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val state = WidgetUiState.from(emptyList(), listOf(saved(1))).let { ui ->
            ui.copy(recents = ui.recents.map { it.copy(thumbnail = picture) })
        }
        val tile = view(state, WidgetSize.WIDE).findViewById<android.widget.ImageView>(R.id.widget_recent_1)
        val bitmap = (tile.drawable as android.graphics.drawable.BitmapDrawable).bitmap
        assertNotSame(picture, bitmap)
        // One wide tile at 250x110: (250 - 16) x (110 - 10 - 48 - 4) dp.
        assertEquals(234f / 48f, bitmap.width.toFloat() / bitmap.height, 0.05f)
        assertFalse(tile.clipToOutline)
    }

    @Test fun liveStatesHideRecentsAndProgressTicksTouchOnlyTextAndBars() {
        val picture = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val idle = WidgetUiState.from(emptyList(), listOf(saved(1)))
        val live = WidgetUiState.from(listOf(job()), listOf(saved(1))).let { ui ->
            ui.copy(recents = ui.recents.map { it.copy(thumbnail = picture) })
        }
        val tile = view(live).findViewById<android.widget.ImageView>(R.id.widget_recent_1)
        assertEquals(View.GONE, tile.visibility)
        assertNull((tile.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap?.takeIf { it.width == 8 })
        val later = WidgetUiState.from(listOf(job(bytes = 47)), listOf(saved(1))).copy(recents = live.recents)
        assertTrue(later.sameLayout(live))
        assertFalse(later.sameLayout(idle))
        assertFalse(WidgetUiState.from(listOf(job().copy(paused = true)), listOf(saved(1))).copy(recents = live.recents).sameLayout(live))
        val root = view(live)
        NezzelWidgetProvider.progressViews(app, later, WidgetSize.WIDE).reapply(app, root)
        assertEquals("45%", text(root, R.id.widget_status))
        assertEquals(45, root.findViewById<android.widget.ProgressBar>(R.id.widget_progress).progress)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.widget_control_end).visibility)
        // Partial views never carry bitmaps; pre-31 squares take a full update instead.
        val square = view(live, WidgetSize.SQUARE)
        val ring = square.findViewById<android.widget.ImageView>(R.id.widget_ring).drawable
        NezzelWidgetProvider.progressViews(app, later, WidgetSize.SQUARE).reapply(app, square)
        assertSame(ring, square.findViewById<android.widget.ImageView>(R.id.widget_ring).drawable)
        assertEquals("45%", text(square, R.id.widget_status))
    }

    @Test fun runningRowsShowTheJobPictureOrItsKindIconNeverAStandIn() {
        val picture = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888)
        val art = checkNotNull(WidgetArt.of(picture))
        val with = WidgetUiState.from(job()).let { ui -> ui.copy(jobs = ui.jobs.map { it.copy(art = art) }) }
        val tile = view(with).findViewById<android.widget.ImageView>(R.id.widget_art)
        // Pre-31: a masked copy, never the shared source bitmap.
        assertNotSame(picture, (tile.drawable as android.graphics.drawable.BitmapDrawable).bitmap)
        assertEquals(0, tile.paddingTop)
        val audio = WidgetUiState.from(job().copy(mimeType = "audio/mp4"))
        val icon = view(audio).findViewById<android.widget.ImageView>(R.id.widget_art)
        assertTrue(icon.paddingTop > 0)
        assertEquals(R.drawable.ic_widget_music, shadowOf(icon.drawable).createdFromResId)
        // Square: the picture is the tile behind the ring; without one the plain ring stays.
        assertEquals(View.VISIBLE, view(with, WidgetSize.SQUARE).findViewById<View>(R.id.widget_art).visibility)
        assertEquals(View.GONE, view(audio, WidgetSize.SQUARE).findViewById<View>(R.id.widget_art).visibility)
        // A new picture is a layout change; the same one is not.
        assertFalse(with.sameLayout(WidgetUiState.from(job())))
        assertTrue(with.sameLayout(with.copy(jobs = with.jobs.map { it.copy(percent = 50) })))
    }

    @Test @Config(sdk = [33]) fun android12RunningPicturesTravelAsSmallJpegsSizedToTheRow() {
        val picture = android.graphics.Bitmap.createBitmap(160, 160, android.graphics.Bitmap.Config.ARGB_8888)
        val art = checkNotNull(WidgetArt.of(picture))
        val density = app.resources.displayMetrics.density
        fun state(vararg ids: String) = WidgetUiState.from(ids.map(::job)).let { ui -> ui.copy(jobs = ui.jobs.map { it.copy(art = art) }) }
        // One job at 300x150: min(150 - 16, (300 - 16) * 0.3) = 85dp; two jobs: (134 - 8) / 2 = 63dp each.
        for ((state, side) in listOf(state("a") to 85, state("a", "b") to 63)) {
            val tile = view(state, WidgetSize.WIDE, widthDp = 300, heightDp = 150).findViewById<android.widget.ImageView>(R.id.widget_art)
            assertTrue(tile.clipToOutline)
            assertNotNull(tile.drawable)
            assertEquals((side * density).toInt().toFloat(), tile.layoutParams.width.toFloat(), 1f)
            assertEquals(tile.layoutParams.width, tile.layoutParams.height)
        }
        val views = NezzelWidgetProvider.views(app, state("a"), WidgetSize.WIDE)
        val parcel = android.os.Parcel.obtain()
        try { views.writeToParcel(parcel, 0); assertTrue("${parcel.dataSize()} bytes", parcel.dataSize() < 64 * 1024) }
        finally { parcel.recycle() }
    }

    @Test fun updaterLoadsEachJobPictureOnceRetryingWhileItIsSaved() = runTest {
        val progress = MutableStateFlow(listOf(job()))
        val calls = mutableListOf<Pair<String, WidgetUiState>>()
        var loads = 0
        val picture = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val updater = WidgetUpdater(app, progress, widgetIds = { intArrayOf(1) },
            render = { state, _ -> calls += "full" to state }, renderProgress = { state, _ -> calls += "progress" to state },
            savedMedia = { flowOf(emptyList()) }, thumbnail = { null },
            artwork = { id -> assertEquals("new", id); loads++; if (loads == 1) null else picture })
        val collection = updater.start(backgroundScope)
        runCurrent()
        assertNull(calls.last().second.jobs.single().art)
        advanceTimeBy(1_001); runCurrent()
        assertEquals(2, loads)
        assertEquals("full", calls.last().first)
        val art = checkNotNull(calls.last().second.jobs.single().art)
        assertSame(picture, art.bitmap)
        progress.value = listOf(job(bytes = 47)); advanceTimeBy(5_000); runCurrent()
        assertEquals("progress", calls.last().first)
        assertSame(art, calls.last().second.jobs.single().art)
        assertEquals(2, loads)
        collection.cancel()
    }

    @Test fun aWidgetPlacedAfterTheRetriesRanOutStillGetsThePicture() = runTest {
        val progress = MutableStateFlow(listOf(job()))
        var ids = intArrayOf()
        var loads = 0
        val picture = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        val calls = mutableListOf<WidgetUiState>()
        val updater = WidgetUpdater(app, progress, widgetIds = { ids },
            render = { state, _ -> calls += state }, renderProgress = { state, _ -> calls += state },
            savedMedia = { flowOf(emptyList()) }, thumbnail = { null }, artwork = { loads++; picture })
        val collection = updater.start(backgroundScope)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(0, loads)
        ids = intArrayOf(1); updater.refresh(); runCurrent()
        assertEquals(1, loads)
        assertSame(picture, checkNotNull(calls.last().jobs.single().art).bitmap)
        collection.cancel()
    }

    @Test fun titleDropsOnlyAnExtensionLikeSuffix() {
        assertEquals("clip", WidgetUiState.title("clip.mp4"))
        assertEquals("Part 1. Intro", WidgetUiState.title("Part 1. Intro"))
        assertEquals("Release v1.2", WidgetUiState.title("Release v1.2"))
        assertEquals(".mp4", WidgetUiState.title(".mp4"))
    }

    @Test fun updaterSendsProgressTicksAsPartialUpdatesAtMostOncePerSecond() = runTest {
        val progress = MutableStateFlow(listOf(job()))
        val calls = mutableListOf<Triple<Long, String, WidgetUiState>>()
        val updater = WidgetUpdater(app, progress, widgetIds = { intArrayOf(1) },
            render = { state, _ -> calls += Triple(testScheduler.currentTime, "full", state) },
            renderProgress = { state, _ -> calls += Triple(testScheduler.currentTime, "progress", state) },
            savedMedia = { flowOf(emptyList()) }, thumbnail = { null })
        val collection = updater.start(backgroundScope)
        runCurrent()
        assertEquals(listOf(0L), calls.map { it.first })
        progress.value = listOf(job(bytes = 39)); runCurrent()
        advanceTimeBy(999); runCurrent()
        assertEquals(1, calls.size)
        progress.value = listOf(job(bytes = 47)); runCurrent()
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(0L to "full", 1000L to "progress"), calls.map { it.first to it.second })
        assertEquals(45, calls.last().third.jobs.single().percent)
        progress.value = listOf(job(bytes = 52).copy(paused = true)); runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertEquals("full", calls.last().second)
        collection.cancel()
    }

    @Test fun addingAndRemovingWidgetsStartsAndStopsLocalLibraryObservation() = runTest {
        var ids = intArrayOf()
        var observing = false
        val updater = WidgetUpdater(app, MutableStateFlow(emptyList()), widgetIds = { ids }, render = { _, _ -> },
            savedMedia = { kotlinx.coroutines.flow.flow {
                observing = true
                try { emit(emptyList<SavedMedia>()); kotlinx.coroutines.awaitCancellation() }
                finally { observing = false }
            } }, thumbnail = { null })
        val collection = updater.start(backgroundScope)
        runCurrent()
        assertFalse(observing)
        ids = intArrayOf(1); updater.refresh(); runCurrent()
        assertTrue(observing)
        ids = intArrayOf(); updater.refresh(); runCurrent()
        assertFalse(observing)
        collection.cancel()
    }

    @Test fun savedChangesUpdateCountAndThumbnailsWithoutProgressChanges() = runTest {
        val saved = MutableStateFlow(emptyList<SavedMedia>())
        val calls = mutableListOf<WidgetUiState>()
        var loads = 0
        val picture = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val updater = WidgetUpdater(app, MutableStateFlow(emptyList()), widgetIds = { intArrayOf(1) },
            render = { state, _ -> calls += state }, savedMedia = { saved }, thumbnail = { loads++; picture })
        val collection = updater.start(backgroundScope)
        runCurrent()
        saved.value = (1..5).map(::saved); runCurrent(); advanceTimeBy(2000); runCurrent()
        assertEquals(5, calls.last().savedCount)
        assertEquals(4, loads)
        saved.value = (2..5).map(::saved); runCurrent(); advanceTimeBy(2000); runCurrent()
        assertEquals(4, calls.last().savedCount)
        assertEquals(4, loads)
        collection.cancel()
    }
    @Test fun missingThumbnailIsRetriedOnTheNextLibrarySnapshot() = runTest {
        val saved = MutableStateFlow(listOf(saved(1)))
        val calls = mutableListOf<WidgetUiState>()
        var loads = 0
        val picture = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        val updater = WidgetUpdater(app, MutableStateFlow(emptyList()), widgetIds = { intArrayOf(1) },
            render = { state, _ -> calls += state }, savedMedia = { saved },
            thumbnail = { media -> if (media.uri.lastPathSegment == "1") { loads++; if (loads == 1) null else picture } else picture })
        val collection = updater.start(backgroundScope)
        runCurrent(); advanceTimeBy(2000); runCurrent()
        assertNull(calls.last().recents.single().thumbnail)
        saved.value = listOf(saved(1), saved(2))
        runCurrent(); advanceTimeBy(2000); runCurrent()
        assertEquals(2, loads)
        assertSame(picture, calls.last().recents.first { it.media.uri.lastPathSegment == "1" }.thumbnail)
        collection.cancel()
    }

}
