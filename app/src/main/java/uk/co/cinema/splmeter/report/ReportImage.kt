package uk.co.cinema.splmeter.report

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import uk.co.cinema.splmeter.data.SessionMeta
import uk.co.cinema.splmeter.dsp.Bands
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The report as a single PNG.
 *
 * The HTML report is the complete one, but it cannot be posted into a chat —
 * most apps will not preview it and some will not accept it at all. This is the
 * same measurement reduced to one image: the headline numbers, the level over
 * time, and the average spectrum. Anything needing interaction or the full
 * resolution stays in the HTML.
 *
 * Drawn straight onto a Canvas rather than rendered from the Compose UI, so the
 * output does not depend on screen size, density or theme.
 */
object ReportImage {

    private const val W = 1080
    private const val PAD = 48f
    private const val GAP = 16f

    private const val BG = 0xFF0A0A0B.toInt()
    private const val CARD = 0xFF18181B.toInt()
    private const val LINE = 0x14FFFFFF
    private const val TEXT = 0xFFE4E4E7.toInt()
    private const val BRIGHT = 0xFFFAFAFA.toInt()
    private const val MUTED = 0xFF71717A.toInt()
    private const val A_COLOUR = 0xFF3B82F6.toInt()
    private const val C_COLOUR = 0xFFF59E0B.toInt()
    private const val Z_COLOUR = 0xFF52525B.toInt()
    private const val BAR = 0xFF3B82F6.toInt()

    private val plain = Typeface.create("sans-serif", Typeface.NORMAL)
    private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

    private fun paint(size: Float, colour: Int, face: Typeface = plain) = Paint().apply {
        isAntiAlias = true
        textSize = size
        color = colour
        typeface = face
    }

    fun write(meta: SessionMeta, m: Metrics, file: File): File {
        val uncal = m.calibration.splOffset == 0.0
        val unit = if (uncal) "dBFS" else "dB"

        // Measure first so the bitmap is exactly as tall as the content.
        val cardsTop = PAD + 132f
        val cardRows = 2
        val cardH = 148f
        val chartTop = cardsTop + cardRows * cardH + (cardRows - 1) * GAP + 40f
        val chartH = 330f
        val specTop = chartTop + chartH + 40f
        val specH = 300f
        val height = ceil(specTop + specH + 92f).toInt()

        val bitmap = Bitmap.createBitmap(W, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(BG)

        drawHeader(canvas, meta, m)
        drawCards(canvas, m, cardsTop, cardH, uncal, unit)
        drawLevels(canvas, m, chartTop, chartH, unit)
        drawSpectrum(canvas, m, specTop, specH, unit)
        drawFooter(canvas, meta, height.toFloat())

        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }

    private fun drawHeader(canvas: Canvas, meta: SessionMeta, m: Metrics) {
        val started = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.UK)
            .format(Date(meta.startEpochMillis))
        canvas.drawText(meta.title, PAD, PAD + 40f, paint(42f, BRIGHT, medium))

        val line1 = "$started · ${Metrics.formatTime(m.durationSec)} · ${m.log.size} windows"
        canvas.drawText(line1, PAD, PAD + 78f, paint(24f, MUTED))

        val line2 = buildString {
            append("Cal: ${m.calibration.name}")
            if (m.calibration.splOffset != 0.0) {
                append(" · offset %+.2f dB".format(Locale.UK, m.calibration.splOffset))
            }
            if (meta.device.isNotBlank()) append(" · ${meta.device}")
        }
        canvas.drawText(ellipsise(line2, W - 2 * PAD, 24f), PAD, PAD + 110f, paint(24f, MUTED))
    }

    private fun drawCards(
        canvas: Canvas, m: Metrics, top: Float, cardH: Float, uncal: Boolean, unit: String
    ) {
        val cards = listOf(
            "Leq (A)" to (m.leqA to if (uncal) "dBFS" else "dBA"),
            "Leq (C)" to (m.leqC to if (uncal) "dBFS" else "dBC"),
            "Leq (Z)" to (m.leqZ to unit),
            "LZpeak" to (m.lzPeak to unit),
            "LASmax" to (m.lasMax to if (uncal) "dBFS" else "dBA"),
            "LASmin" to (m.lasMin to if (uncal) "dBFS" else "dBA"),
            "LCSmax" to (m.lcsMax to if (uncal) "dBFS" else "dBC"),
            "LCSmin" to (m.lcsMin to if (uncal) "dBFS" else "dBC")
        )
        val cols = 4
        val cardW = (W - 2 * PAD - (cols - 1) * GAP) / cols
        val fill = Paint().apply { isAntiAlias = true; color = CARD }
        val stroke = Paint().apply {
            isAntiAlias = true; color = LINE; style = Paint.Style.STROKE; strokeWidth = 1f
        }

        cards.forEachIndexed { i, (label, valueUnit) ->
            val (value, u) = valueUnit
            val x = PAD + (i % cols) * (cardW + GAP)
            val y = top + (i / cols) * (cardH + GAP)
            val rect = RectF(x, y, x + cardW, y + cardH)
            canvas.drawRoundRect(rect, 12f, 12f, fill)
            canvas.drawRoundRect(rect, 12f, 12f, stroke)

            canvas.drawText(label.uppercase(Locale.UK), x + 20f, y + 40f, paint(20f, MUTED, medium))
            // A metric with nothing behind it says so rather than printing a
            // floor value that reads like a measurement.
            val shown = if (!value.isFinite() || value <= -199.0) "—"
                        else "%.1f".format(Locale.UK, value)
            canvas.drawText(shown, x + 20f, y + 100f, paint(44f, TEXT, medium))
            val w = paint(44f, TEXT, medium).measureText(shown)
            canvas.drawText(u, x + 26f + w, y + 100f, paint(20f, MUTED))
        }
    }

    /** SPL over time, all three weightings. */
    private fun drawLevels(canvas: Canvas, m: Metrics, top: Float, h: Float, unit: String) {
        panel(canvas, top, h, "SPL over time ($unit)")

        val series = listOf(
            Triple("A", m.splA, A_COLOUR),
            Triple("C", m.splC, C_COLOUR),
            Triple("Z", m.splZ, Z_COLOUR)
        )
        val all = series.flatMap { it.second.asIterable() }.filter { it.isFinite() && it > -199f }
        val plotL = PAD + 78f
        val plotR = W - PAD - 20f
        val plotT = top + 62f
        val plotB = top + h - 46f
        if (all.isEmpty()) return

        val lo = floor((all.min() - 2f) / 5f) * 5f
        val hi = ceil((all.max() + 2f) / 5f) * 5f
        val span = max(hi - lo, 1f)
        fun yFor(v: Float) = plotB - (v - lo) / span * (plotB - plotT)

        // Horizontal guides, five of them, labelled in dB.
        val grid = Paint().apply { isAntiAlias = true; color = LINE; strokeWidth = 1f }
        val label = paint(20f, MUTED)
        for (i in 0..4) {
            val v = lo + span * i / 4f
            val y = yFor(v)
            canvas.drawLine(plotL, y, plotR, y, grid)
            canvas.drawText("%.0f".format(Locale.UK, v), PAD + 8f, y + 7f, label)
        }

        val n = m.splA.size
        if (n > 1) {
            series.forEach { (_, data, colour) ->
                val stroke = Paint().apply {
                    isAntiAlias = true
                    color = colour
                    style = Paint.Style.STROKE
                    strokeWidth = if (colour == A_COLOUR) 3f else 2f
                }
                val path = Path()
                var started = false
                for (i in 0 until n) {
                    val v = data[i]
                    if (!v.isFinite() || v <= -199f) { started = false; continue }
                    val x = plotL + i.toFloat() / (n - 1) * (plotR - plotL)
                    val y = yFor(v)
                    if (started) path.lineTo(x, y) else { path.moveTo(x, y); started = true }
                }
                canvas.drawPath(path, stroke)
            }

            // Time axis: start, middle, end is enough at this size.
            listOf(0, (n - 1) / 2, n - 1).forEach { i ->
                val x = plotL + i.toFloat() / (n - 1) * (plotR - plotL)
                val t = Metrics.formatTime(m.times[i].toDouble())
                val p = paint(20f, MUTED)
                canvas.drawText(t, x - p.measureText(t) / 2f, plotB + 30f, p)
            }
        }

        // Legend, right-aligned in the panel header.
        var x = plotR
        series.reversed().forEach { (name, _, colour) ->
            val p = paint(20f, MUTED)
            val w = p.measureText(name)
            canvas.drawText(name, x - w, top + 40f, p)
            canvas.drawRect(
                RectF(x - w - 20f, top + 26f, x - w - 8f, top + 38f),
                Paint().apply { isAntiAlias = true; color = colour }
            )
            x -= w + 44f
        }
    }

    /** Average third-octave spectrum. */
    private fun drawSpectrum(canvas: Canvas, m: Metrics, top: Float, h: Float, unit: String) {
        panel(canvas, top, h, "Average spectrum, 1/3 octave ($unit)")

        val values = m.thirdAvg
        val usable = values.filter { it.isFinite() && it > -199.0 }
        if (usable.isEmpty()) return

        val plotL = PAD + 78f
        val plotR = W - PAD - 20f
        val plotT = top + 62f
        val plotB = top + h - 52f
        val lo = floor((usable.min() - 2.0) / 10.0) * 10.0
        val hi = ceil((usable.max() + 2.0) / 10.0) * 10.0
        val span = max(hi - lo, 1.0)

        val grid = Paint().apply { isAntiAlias = true; color = LINE; strokeWidth = 1f }
        val label = paint(20f, MUTED)
        for (i in 0..4) {
            val v = lo + span * i / 4.0
            val y = (plotB - (v - lo) / span * (plotB - plotT)).toFloat()
            canvas.drawLine(plotL, y, plotR, y, grid)
            canvas.drawText("%.0f".format(Locale.UK, v), PAD + 8f, y + 7f, label)
        }

        val slot = (plotR - plotL) / values.size
        val barW = min(slot * 0.72f, 26f)
        val bar = Paint().apply { isAntiAlias = true; color = BAR }
        values.forEachIndexed { i, v ->
            if (!v.isFinite() || v <= -199.0) return@forEachIndexed
            val x = plotL + i * slot + (slot - barW) / 2f
            val y = (plotB - (v - lo) / span * (plotB - plotT)).toFloat()
            canvas.drawRect(RectF(x, y, x + barW, plotB), bar)
        }

        // Every third centre, or the labels collide.
        m.thirdCentres.forEachIndexed { i, hz ->
            if (i % 3 != 0) return@forEachIndexed
            val text = Bands.label(hz)
            val p = paint(19f, MUTED)
            val x = plotL + i * slot + slot / 2f
            canvas.drawText(text, x - p.measureText(text) / 2f, plotB + 30f, p)
        }
    }

    private fun panel(canvas: Canvas, top: Float, h: Float, title: String) {
        val rect = RectF(PAD, top, W - PAD, top + h)
        canvas.drawRoundRect(rect, 12f, 12f, Paint().apply { isAntiAlias = true; color = CARD })
        canvas.drawRoundRect(rect, 12f, 12f, Paint().apply {
            isAntiAlias = true; color = LINE; style = Paint.Style.STROKE; strokeWidth = 1f
        })
        canvas.drawText(title, PAD + 20f, top + 40f, paint(24f, TEXT, medium))
    }

    private fun drawFooter(canvas: Canvas, meta: SessionMeta, height: Float) {
        val parts = listOfNotNull(
            meta.audioSource.takeIf { it.isNotBlank() && it != "unknown" },
            meta.microphone.takeIf { it.isNotBlank() },
            meta.appVersion.takeIf { it.isNotBlank() }?.let { "SPL Meter $it" }
        )
        if (parts.isEmpty()) return
        canvas.drawText(
            ellipsise(parts.joinToString(" · "), W - 2 * PAD, 20f),
            PAD, height - 34f, paint(20f, MUTED)
        )
    }

    private fun ellipsise(text: String, maxWidth: Float, size: Float): String {
        val p = paint(size, MUTED)
        if (p.measureText(text) <= maxWidth) return text
        var cut = text
        while (cut.isNotEmpty() && p.measureText("$cut…") > maxWidth) cut = cut.dropLast(1)
        return "$cut…"
    }
}
