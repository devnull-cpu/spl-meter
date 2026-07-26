package uk.co.cinema.splmeter

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File

/** Left-channel samples read from a WAV, shared by the offline test tools. */
class Region(val samples: FloatArray, val rate: Int)

/**
 * Minimal WAV reader for the offline harnesses: 16/24/32-bit integer, any
 * channel count, left channel only, with a seek to an arbitrary start time.
 */
object TestAudio {

    fun readRegion(file: File, atSeconds: Double, spanSeconds: Double): Region {
        DataInputStream(BufferedInputStream(file.inputStream(), 1 shl 20)).use { input ->
            fun tag(): String {
                val b = ByteArray(4); input.readFully(b); return String(b, Charsets.US_ASCII)
            }
            fun i32(): Int {
                val b = ByteArray(4); input.readFully(b)
                return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                    ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
            }
            fun i16(): Int {
                val b = ByteArray(2); input.readFully(b)
                return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8)
            }

            require(tag() == "RIFF") { "not a RIFF file" }
            i32()
            require(tag() == "WAVE") { "not a WAVE file" }

            var rate = 0; var ch = 0; var bits = 0
            while (true) {
                val t = tag()
                val size = i32().toLong() and 0xFFFFFFFFL
                if (t == "fmt ") {
                    i16(); ch = i16(); rate = i32(); i32(); i16(); bits = i16()
                    if (size > 16) input.skipBytes((size - 16).toInt())
                } else if (t == "data") break
                else {
                    var left = size + (size and 1L)
                    while (left > 0) { val s = input.skip(left); if (s <= 0) break; left -= s }
                }
            }

            val bytesPerFrame = ch * bits / 8
            var toSkip = (atSeconds * rate).toLong() * bytesPerFrame
            while (toSkip > 0) { val s = input.skip(toSkip); if (s <= 0) break; toSkip -= s }

            val count = (spanSeconds * rate).toInt()
            val out = FloatArray(count)
            val frame = ByteArray(bytesPerFrame)
            for (i in 0 until count) {
                var read = 0
                while (read < bytesPerFrame) {
                    val n = input.read(frame, read, bytesPerFrame - read)
                    if (n < 0) return Region(out.copyOf(i), rate)
                    read += n
                }
                val b0 = frame[0].toInt() and 0xFF
                val b1 = frame[1].toInt() and 0xFF
                out[i] = when (bits) {
                    16 -> ((b1 shl 8) or b0).toShort() / 32768f
                    24 -> {
                        var v = ((frame[2].toInt() and 0xFF) shl 16) or (b1 shl 8) or b0
                        if (v and 0x800000 != 0) v = v or -0x1000000
                        v / 8388608f
                    }
                    else -> {
                        val v = ((frame[3].toInt() and 0xFF) shl 24) or
                            ((frame[2].toInt() and 0xFF) shl 16) or (b1 shl 8) or b0
                        v / 2147483648f
                    }
                }
            }
            return Region(out, rate)
        }
    }
}
