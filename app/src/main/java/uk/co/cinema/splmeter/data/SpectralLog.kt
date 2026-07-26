package uk.co.cinema.splmeter.data

import uk.co.cinema.splmeter.dsp.Bands
import uk.co.cinema.splmeter.dsp.WindowResult
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

/**
 * Compact per-window spectral log.
 *
 * Fixed-size records, big-endian, ~1.27 KB per 2 s window — about 6.9 MB for a
 * 3 hour film, so every recording can be kept indefinitely.
 *
 * Everything stored is uncalibrated (dB relative to full scale). Calibration is
 * applied on read, which is what allows an old recording to be re-analysed with
 * a better cal file later.
 */
object SpectralLog {

    private const val MAGIC = 0x53504C47 // "SPLG"
    const val VERSION = 1

    class Header(
        val sampleRate: Int,
        val windowSamples: Int,
        val startEpochMillis: Long,
        val subLowHz: Int,
        val subCount: Int,
        val thirdCentres: DoubleArray
    ) {
        val windowSeconds: Double get() = windowSamples.toDouble() / sampleRate
        val recordBytes: Int get() = 4 + 8 * 4 + 3 * 4 + subCount * 4 + thirdCentres.size * 4
    }

    class Writer(file: File, header: Header) : AutoCloseable {
        private val out = DataOutputStream(BufferedOutputStream(file.outputStream(), 1 shl 16))
        var count = 0
            private set

        init {
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(header.sampleRate)
            out.writeInt(header.windowSamples)
            out.writeLong(header.startEpochMillis)
            out.writeInt(header.subLowHz)
            out.writeInt(header.subCount)
            out.writeInt(header.thirdCentres.size)
            header.thirdCentres.forEach { out.writeFloat(it.toFloat()) }
            out.flush()
        }

        fun append(w: WindowResult) {
            out.writeFloat(w.tSec)
            out.writeFloat(w.laeq)
            out.writeFloat(w.lceq)
            out.writeFloat(w.lzeq)
            out.writeFloat(w.peak)
            out.writeFloat(w.lasMax)
            out.writeFloat(w.lasMin)
            out.writeFloat(w.lcsMax)
            out.writeFloat(w.lcsMin)
            out.writeInt(w.clipRuns)
            out.writeInt(w.clippedSamples)
            out.writeInt(w.clicksFixed)
            w.sub.forEach { out.writeFloat(it) }
            w.third.forEach { out.writeFloat(it) }
            count++
            // Flush every window: a recording interrupted by a dead battery
            // should still yield everything up to that point.
            out.flush()
        }

        override fun close() {
            runCatching { out.flush() }
            runCatching { out.close() }
        }
    }

    /** A whole log read into memory. 3 hours is ~7 MB, so this is fine. */
    class Log(
        val header: Header,
        val tSec: FloatArray,
        val laeq: FloatArray,
        val lceq: FloatArray,
        val lzeq: FloatArray,
        val peak: FloatArray,
        val lasMax: FloatArray,
        val lasMin: FloatArray,
        val lcsMax: FloatArray,
        val lcsMin: FloatArray,
        val clipRuns: IntArray,
        val clippedSamples: IntArray,
        /** [window][subBin] */
        val sub: Array<FloatArray>,
        /** [window][thirdBand] */
        val third: Array<FloatArray>
    ) {
        val size: Int get() = tSec.size
        val durationSec: Double get() = if (size == 0) 0.0 else tSec.last() + header.windowSeconds
        fun subHz(i: Int) = (header.subLowHz + i).toDouble()

        /**
         * A time range of this log, for re-analysing part of a recording —
         * dropping the trailers off the front of a film, most obviously.
         *
         * Because every window is stored independently and uncalibrated, this is
         * just an array slice: no audio is needed and nothing is recomputed from
         * the signal. Timestamps are rebased to zero so the trimmed section
         * reads as if the recording had started there.
         */
        fun slice(fromSec: Double, toSec: Double): Log {
            val keep = (0 until size).filter { tSec[it] >= fromSec - 1e-6 && tSec[it] < toSec - 1e-6 }
            if (keep.isEmpty() || keep.size == size) return this
            val base = tSec[keep.first()]
            fun col(src: FloatArray) = FloatArray(keep.size) { src[keep[it]] }
            return Log(
                header = header,
                tSec = FloatArray(keep.size) { tSec[keep[it]] - base },
                laeq = col(laeq), lceq = col(lceq), lzeq = col(lzeq), peak = col(peak),
                lasMax = col(lasMax), lasMin = col(lasMin),
                lcsMax = col(lcsMax), lcsMin = col(lcsMin),
                clipRuns = IntArray(keep.size) { clipRuns[keep[it]] },
                clippedSamples = IntArray(keep.size) { clippedSamples[keep[it]] },
                sub = Array(keep.size) { sub[keep[it]] },
                third = Array(keep.size) { third[keep[it]] }
            )
        }
    }

    fun read(file: File): Log {
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 16)).use { input ->
            require(input.readInt() == MAGIC) { "not a spectral log: ${file.name}" }
            val version = input.readInt()
            require(version <= VERSION) { "log version $version is newer than this app understands" }
            val sampleRate = input.readInt()
            val windowSamples = input.readInt()
            val startMillis = input.readLong()
            val subLowHz = input.readInt()
            val subCount = input.readInt()
            val thirdCount = input.readInt()
            val centres = DoubleArray(thirdCount) { input.readFloat().toDouble() }
            val header = Header(sampleRate, windowSamples, startMillis, subLowHz, subCount, centres)

            val t = ArrayList<FloatArray>()
            val subs = ArrayList<FloatArray>()
            val thirds = ArrayList<FloatArray>()
            val clips = ArrayList<IntArray>()
            while (true) {
                val scalars = FloatArray(9)
                var eof = false
                for (i in scalars.indices) {
                    try {
                        scalars[i] = input.readFloat()
                    } catch (e: java.io.EOFException) {
                        eof = true
                        break
                    }
                }
                if (eof) break
                val clip = try {
                    intArrayOf(input.readInt(), input.readInt(), input.readInt())
                } catch (e: java.io.EOFException) {
                    break
                }
                val sub = FloatArray(header.subCount)
                val third = FloatArray(header.thirdCentres.size)
                try {
                    for (i in sub.indices) sub[i] = input.readFloat()
                    for (i in third.indices) third[i] = input.readFloat()
                } catch (e: java.io.EOFException) {
                    break // truncated final record — drop it
                }
                t.add(scalars)
                clips.add(clip)
                subs.add(sub)
                thirds.add(third)
            }

            val n = t.size
            fun col(i: Int) = FloatArray(n) { t[it][i] }
            return Log(
                header = header,
                tSec = col(0),
                laeq = col(1), lceq = col(2), lzeq = col(3), peak = col(4),
                lasMax = col(5), lasMin = col(6), lcsMax = col(7), lcsMin = col(8),
                clipRuns = IntArray(n) { clips[it][0] },
                clippedSamples = IntArray(n) { clips[it][1] },
                sub = Array(n) { subs[it] },
                third = Array(n) { thirds[it] }
            )
        }
    }

    fun defaultHeader(sampleRate: Int, windowSamples: Int, startEpochMillis: Long) = Header(
        sampleRate = sampleRate,
        windowSamples = windowSamples,
        startEpochMillis = startEpochMillis,
        subLowHz = Bands.SUB_LOW_HZ,
        subCount = Bands.SUB_COUNT,
        thirdCentres = Bands.THIRD_OCTAVE_STORED
    )
}
