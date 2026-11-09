package com.docscan.app

import android.graphics.PointF
import androidx.annotation.StringRes
import java.io.File
import java.util.Locale

enum class Step { SPLASH, HOME, SCAN, CROP, SIZE, PAGES, EXPORT }

enum class Fmt(val label: String, val ext: String, val mime: String) {
    JPG("JPG", "jpg", "image/jpeg"),
    PNG("PNG", "png", "image/png"),
    PDF("PDF", "pdf", "application/pdf")
}

enum class ScanFilter(@StringRes val label: Int) {
    ORIGINAL(R.string.filter_original),
    ENHANCED(R.string.filter_enhanced),
    GRAY(R.string.filter_gray),
    BW(R.string.filter_bw)
}

/** Physical size of one page. autoH = only the width is fixed, the height follows the page shape. */
data class PageSize(
    val wMm: Double,
    val hMm: Double,
    val autoH: Boolean = false,
    @StringRes val label: Int? = null
)

/** corners: TL, TR, BR, BL — normalized (0..1) relative to the image */
data class ScanPage(val id: Long, val file: File, val corners: List<PointF>, val size: PageSize)

data class ExportSpec(
    val dpi: Int,
    val quality: Int,
    val filter: ScanFilter,
    val name: String,
    val format: Fmt
)

data class Preset(@StringRes val name: Int, val wMm: Double, val hMm: Double, val autoH: Boolean = false) {
    fun toSize() = PageSize(wMm, hMm, autoH, name)
}

val PRESETS = listOf(
    Preset(R.string.preset_a4, 210.0, 297.0),
    Preset(R.string.preset_a5, 148.0, 210.0),
    Preset(R.string.preset_a3, 297.0, 420.0),
    Preset(R.string.preset_letter, 215.9, 279.4),
    Preset(R.string.preset_card, 85.6, 54.0),
    Preset(R.string.preset_photo34, 30.0, 40.0),
    Preset(R.string.preset_photo46, 40.0, 60.0),
    Preset(R.string.preset_receipt80, 80.0, 0.0, autoH = true),
    Preset(R.string.preset_receipt58, 58.0, 0.0, autoH = true)
)

val MODES = listOf(
    R.string.mode_doc to PRESETS[0],
    R.string.mode_card to PRESETS[4],
    R.string.mode_photo to PRESETS[5]
)

/** A message resolved to text in the UI layer (so it follows the selected language). */
class Msg(@StringRes val id: Int, val args: List<Any> = emptyList())

const val MAX_SIDE_PX = 8000

/** returns (pxW, pxH, wasReduced) */
fun pixelsFor(wMm: Double, hMm: Double, dpi: Int): Triple<Int, Int, Boolean> {
    var w = wMm / 25.4 * dpi
    var h = hMm / 25.4 * dpi
    val m = maxOf(w, h)
    var reduced = false
    if (m > MAX_SIDE_PX) {
        val k = MAX_SIDE_PX / m
        w *= k; h *= k; reduced = true
    }
    return Triple(maxOf(1, Math.round(w).toInt()), maxOf(1, Math.round(h).toInt()), reduced)
}

fun parseNum(t: String): Double? {
    val sb = StringBuilder()
    for (c in t) {
        sb.append(
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                '٫', ',', '،' -> '.'
                else -> c
            }
        )
    }
    return sb.toString().trim().toDoubleOrNull()
}

fun fmtNum(v: Double): String {
    val s = String.format(Locale.US, "%.2f", v)
    return s.trimEnd('0').trimEnd('.')
}
