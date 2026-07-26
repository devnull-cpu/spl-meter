package uk.co.cinema.splmeter.audio

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import uk.co.cinema.splmeter.data.Prefs

/**
 * Opens the microphone the same way for every part of the app.
 *
 * Shared deliberately: a calibration measured through a different source or
 * channel layout than the one used to record would not be a calibration of
 * anything. Recording and calibrating must go through identical settings.
 */
object AudioCapture {

    const val SAMPLE_RATE = 48000
    private const val TAG = "AudioCapture"

    class Opened(val record: AudioRecord, val sourceName: String, val channels: Int) {
        val description: String
            get() = sourceName + if (channels == 2) " (stereo, left)" else " (mono)"
    }

    /**
     * UNPROCESSED is the whole point — it bypasses AGC, noise suppression and
     * echo cancellation, all of which would wreck an SPL measurement.
     *
     * Always try it first. PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED looks like
     * the right thing to gate on, but plenty of devices never populate it and
     * still support the source perfectly well — a Galaxy S25 reports it as false
     * and records from it happily. Gating on the property silently downgraded
     * every measurement to VOICE_RECOGNITION. The only trustworthy test is
     * whether AudioRecord actually initialises.
     */
    fun open(context: Context): Opened {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.i(TAG, "device reports UNPROCESSED support: " +
            am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED))

        val chain = listOf(
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.MIC to "MIC"
        )
        // A pinned source is not a preference to be quietly overridden: falling
        // back would measure against the wrong calibration without saying so.
        val preference = Prefs.state.value.source
        val sources = if (preference == Prefs.Source.AUTO) chain
        else chain.filter { it.second == preference.name }

        // Channel layout matters as much as the source. Asking for MONO lets the
        // HAL pick whichever built-in mic it considers primary, or downmix
        // several of them — and a calibration made from the left channel of a
        // stereo capture is only valid for that one physical mic.
        val layouts = if (Prefs.state.value.stereoLeft) {
            listOf(AudioFormat.CHANNEL_IN_STEREO to 2, AudioFormat.CHANNEL_IN_MONO to 1)
        } else {
            listOf(AudioFormat.CHANNEL_IN_MONO to 1)
        }

        for ((source, name) in sources) {
            for ((layout, channels) in layouts) {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, layout, AudioFormat.ENCODING_PCM_16BIT)
                if (minBuf <= 0) continue
                val bufferBytes = maxOf(minBuf, SAMPLE_RATE * 2 * channels * 2) // ~2 s
                val rec = try {
                    AudioRecord(source, SAMPLE_RATE, layout, AudioFormat.ENCODING_PCM_16BIT, bufferBytes)
                } catch (e: Exception) {
                    Log.w(TAG, "source $name / ${channels}ch unavailable", e)
                    null
                }
                if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                    Log.i(TAG, "capturing from $name, $channels channel(s) at $SAMPLE_RATE Hz")
                    return Opened(rec, name, channels)
                }
                runCatching { rec?.release() }
            }
        }
        throw IllegalStateException(
            if (preference == Prefs.Source.AUTO) "No usable audio source"
            else "${preference.name} is not available on this device — " +
                "choose a different source in Settings"
        )
    }

    /** Copies the left channel out of an interleaved buffer. */
    fun takeLeft(interleaved: ShortArray, out: ShortArray, frames: Int, channels: Int) {
        if (channels == 1) {
            System.arraycopy(interleaved, 0, out, 0, frames)
        } else {
            for (i in 0 until frames) out[i] = interleaved[i * channels]
        }
    }
}
