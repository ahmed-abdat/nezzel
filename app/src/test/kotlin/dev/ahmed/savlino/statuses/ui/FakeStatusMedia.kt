package dev.ahmed.savlino.statuses.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import dev.ahmed.savlino.statuses.StatusItem

/** Abstract gradient tiles and a plain round app mark: synthetic only, never a real photo or logo. */
class FakeStatusMedia(private val durations: Boolean = true) : StatusMedia {
    private val palettes = listOf(0xFF2E7D7A to 0xFFB2DFDB, 0xFF5D4037 to 0xFFFFCC80, 0xFF283593 to 0xFF90CAF9,
        0xFF6A1B9A to 0xFFF8BBD0, 0xFF33691E to 0xFFDCE775, 0xFF37474F to 0xFFB0BEC5, 0xFFBF360C to 0xFFFFE0B2,
        0xFF004D40 to 0xFF80CBC4, 0xFF4E342E to 0xFFD7CCC8)

    override suspend fun thumbnail(item: StatusItem, size: Int): Bitmap {
        val n = item.uri.lastPathSegment!!.substringAfterLast('-').toInt()
        val (from, to) = palettes[(n - 1).mod(palettes.size)]
        val bitmap = Bitmap.createBitmap(180, 280, Bitmap.Config.ARGB_8888)
        val paint = Paint().apply { shader = LinearGradient(0f, 0f, 180f, 280f, from.toInt(), to.toInt(), Shader.TileMode.CLAMP) }
        Canvas(bitmap).drawRect(0f, 0f, 180f, 280f, paint)
        return bitmap
    }

    override suspend fun durationMillis(item: StatusItem): Long? = if (!item.isVideo || !durations) null else
        listOf(15_000L, 29_000L, 8_000L, 30_000L, 12_000L)[item.uri.lastPathSegment!!.substringAfterLast('-').toInt() % 5]

    override suspend fun appIcon(packageName: String): Bitmap {
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (packageName.endsWith("w4b")) 0xFF546E7A.toInt() else 0xFF78909C.toInt() }
        Canvas(bitmap).drawCircle(48f, 48f, 46f, paint)
        return bitmap
    }
}
