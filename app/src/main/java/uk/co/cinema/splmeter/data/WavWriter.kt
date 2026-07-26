package uk.co.cinema.splmeter.data

import java.io.File
import java.io.RandomAccessFile

/**
 * Streaming 16-bit PCM WAV writer.
 *
 * Header sizes are patched on close, and also refreshed periodically, so a
 * recording cut short by a flat battery still opens in anything sane.
 */
class WavWriter(file: File, private val sampleRate: Int, private val channels: Int = 1) : AutoCloseable {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var sinceHeaderRefresh = 0L

    init {
        raf.setLength(0)
        writeHeader(0)
    }

    /** Writes little-endian 16-bit samples. [count] is a number of shorts. */
    fun write(samples: ShortArray, count: Int) {
        val bytes = ByteArray(count * 2)
        var j = 0
        for (i in 0 until count) {
            val v = samples[i].toInt()
            bytes[j++] = (v and 0xFF).toByte()
            bytes[j++] = ((v shr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
        dataBytes += bytes.size
        sinceHeaderRefresh += bytes.size
        if (sinceHeaderRefresh > 8L * 1024 * 1024) {
            refreshSizes()
            sinceHeaderRefresh = 0
        }
    }

    private fun refreshSizes() {
        val pos = raf.filePointer
        raf.seek(4); writeSizeLe(36 + dataBytes)
        raf.seek(40); writeSizeLe(dataBytes)
        raf.seek(pos)
    }

    /**
     * A RIFF size field is an unsigned 32-bit count, so it cannot describe more
     * than 4 GiB — about 12 h 25 m of 48 kHz mono. Saturate rather than let the
     * value wrap round to something small, which would silently truncate the
     * file for every reader. Note that many readers parse the field as *signed*
     * and so break at 2 GiB (about 6 h 13 m) regardless of what is written here.
     * The sample data is intact in either case; only the header is unable to
     * describe it, and [MAX_RIFF_BYTES] is where that starts.
     */
    private fun writeSizeLe(v: Long) = writeIntLe(v.coerceAtMost(MAX_RIFF_BYTES).toInt())

    /** True once the header can no longer describe the data written so far. */
    val exceedsRiffLimit: Boolean get() = 36 + dataBytes > MAX_RIFF_BYTES

    override fun close() {
        runCatching { refreshSizes() }
        runCatching { raf.close() }
    }

    private fun writeHeader(dataLen: Int) {
        val byteRate = sampleRate * channels * 2
        raf.write("RIFF".toByteArray())
        writeIntLe(36 + dataLen)
        raf.write("WAVE".toByteArray())
        raf.write("fmt ".toByteArray())
        writeIntLe(16)
        writeShortLe(1)                 // PCM
        writeShortLe(channels)
        writeIntLe(sampleRate)
        writeIntLe(byteRate)
        writeShortLe(channels * 2)      // block align
        writeShortLe(16)                // bits per sample
        raf.write("data".toByteArray())
        writeIntLe(dataLen)
    }

    private fun writeIntLe(v: Int) {
        raf.write(byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte()
        ))
    }

    private fun writeShortLe(v: Int) {
        raf.write(byteArrayOf((v and 0xFF).toByte(), ((v shr 8) and 0xFF).toByte()))
    }

    companion object {
        /** Largest value a RIFF size field can hold, treated as unsigned. */
        const val MAX_RIFF_BYTES = 0xFFFFFFFFL
    }
}
