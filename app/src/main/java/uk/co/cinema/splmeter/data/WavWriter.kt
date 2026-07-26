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
        raf.seek(4); writeIntLe((36 + dataBytes).toInt())
        raf.seek(40); writeIntLe(dataBytes.toInt())
        raf.seek(pos)
    }

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
}
