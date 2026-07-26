package uk.co.cinema.splmeter.audio

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import uk.co.cinema.splmeter.MainActivity
import uk.co.cinema.splmeter.R
import uk.co.cinema.splmeter.data.Prefs
import uk.co.cinema.splmeter.data.SessionMeta
import uk.co.cinema.splmeter.data.SessionStore
import uk.co.cinema.splmeter.data.SpectralLog
import uk.co.cinema.splmeter.data.WavWriter
import uk.co.cinema.splmeter.dsp.Repair
import uk.co.cinema.splmeter.dsp.Weighting
import uk.co.cinema.splmeter.dsp.WindowAnalyzer
import uk.co.cinema.splmeter.dsp.WindowResult
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.pow

/**
 * Captures audio and analyses it window by window for as long as it runs.
 *
 * Foreground service with a wake lock — a film is two to three hours with the
 * screen off in a bag, so neither the process nor the CPU can be allowed to
 * doze off.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "uk.co.cinema.splmeter.START"
        const val ACTION_STOP = "uk.co.cinema.splmeter.STOP"
        const val EXTRA_TITLE = "title"

        private const val CHANNEL_ID = "recording"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "RecordingService"

        const val SAMPLE_RATE = 48000

        fun start(context: Context, title: String) {
            val i = Intent(context, RecordingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, title)
            }
            ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, RecordingService::class.java).apply { action = ACTION_STOP })
        }
    }

    private var captureThread: Thread? = null
    @Volatile private var running = false
    /**
     * Which session the shared state belongs to, incremented on every start.
     *
     * Stopping is asynchronous — the last queued windows still have to be
     * analysed — so a start can arrive while the previous session is still
     * draining. Without a generation, the new start flips [running] back to
     * true, the old analysis loop never reaches its exit condition, and it
     * carries on consuming the new session's windows into the old session's
     * log. Every loop checks that the generation is still its own, so an
     * outgoing session can only ever end itself.
     */
    @Volatile private var generation = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private val processor = Executors.newSingleThreadExecutor()

    /**
     * A window plus the sample index it starts at.
     *
     * Carrying the index means the time axis is derived from the sample counter
     * rather than from how many windows happen to have been analysed, so it stays
     * truthful even if a buffer is ever dropped under load.
     */
    private class PendingWindow(val samples: FloatArray, val startSample: Long)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (!running) startCapture(intent.getStringExtra(EXTRA_TITLE).orEmpty())
            }
        }
        return START_STICKY
    }

    private fun startCapture(title: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Recorder.fail("Microphone permission not granted")
            stopSelf()
            return
        }

        createChannel()
        val notification = buildNotification("Starting…")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "splmeter:recording")
            .also { it.setReferenceCounted(false); it.acquire() }

        running = true
        val gen = ++generation
        captureThread = Thread({ captureLoop(title, gen) }, "capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Finishing can take a moment — the last queued windows still have to be
     * analysed and flushed — so it happens off the main thread.
     */
    private fun stopCapture() {
        running = false
        val thread = captureThread
        captureThread = null
        Thread({
            thread?.join(15000)
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, "capture-stop").start()
    }

    // ------------------------------------------------------------------

    private fun captureLoop(title: String, gen: Int) {
        val settings = Prefs.state.value
        val calibration = Prefs.activeCalibration()
        val windowSamples = (SAMPLE_RATE * settings.windowSeconds).toInt()
        val startedAt = Date()
        val id = SessionStore.newId(startedAt)

        // Owned by this session alone: a queue shared between sessions lets an
        // outgoing analysis loop swallow the incoming session's windows.
        val pending = LinkedBlockingQueue<PendingWindow>(8)
        // True while this session is the current one and has not been stopped.
        fun live() = running && generation == gen

        var record: AudioRecord? = null
        var wav: WavWriter? = null
        var writer: SpectralLog.Writer? = null

        try {
            val opened = AudioCapture.open(this)
            record = opened.record
            val channels = opened.channels

            val correction = buildCorrectionCurve(windowSamples, calibration)
            val minHz = if (settings.bandLimit) Weighting.MIN_HZ else Weighting.UNLIMITED_HZ
            val analyzer = WindowAnalyzer(
                SAMPLE_RATE, windowSamples, correction, minHz, settings.removeDc
            )

            writer = SpectralLog.Writer(
                SessionStore.logFile(id),
                SpectralLog.defaultHeader(SAMPLE_RATE, windowSamples, startedAt.time)
            )
            if (settings.saveRawAudio) wav = WavWriter(SessionStore.wavFile(id), SAMPLE_RATE)

            // Embed the calibration so the session folder stands on its own.
            val embedded = settings.calFileName?.let { Prefs.calText(it) }
            if (embedded != null) SessionStore.embeddedCalFile(id).writeText(embedded)

            Recorder.reset(
                sessionId = id,
                title = title.ifBlank { id },
                calName = calibration.name,
                calibrated = calibration.splOffset != 0.0,
                source = opened.description,
                savingWav = settings.saveRawAudio,
                fastDisplay = settings.displaySeconds > 0f
            )
            SessionStore.save(
                SessionMeta(
                    id = id, title = title.ifBlank { id },
                    startEpochMillis = startedAt.time, durationSec = 0.0,
                    sampleRate = SAMPLE_RATE, windowSamples = windowSamples, windowCount = 0,
                    calNameAtRecord = calibration.name,
                    audioSource = opened.description,
                    calOverride = if (embedded != null) SessionMeta.EMBEDDED else "",
                    hasWav = settings.saveRawAudio,
                    clippedWindows = 0
                )
            )

            // Analysis runs on its own thread so a slow window can never stall
            // the reader and drop samples.
            val stats = SessionStats()
            val logWriter = writer
            processor.execute {
                while (true) {
                    val buf = pending.poll(500, TimeUnit.MILLISECONDS)
                    if (buf == null) {
                        if (!live() && pending.isEmpty()) break else continue
                    }
                    try {
                        val repair = Repair.repair(buf.samples, settings.declick, settings.declip)
                        val t = buf.startSample.toDouble() / SAMPLE_RATE
                        val result = analyzer.analyze(buf.samples, t.toFloat(), repair)
                        stats.accumulate(result)
                        logWriter.append(result)
                        Recorder.onWindow(result, calibration.splOffset, t + windowSamples.toDouble() / SAMPLE_RATE)
                        updateNotification(result, calibration.splOffset)
                    } catch (e: Throwable) {
                        // Without this the task dies inside the executor, which
                        // swallows the throw: capture would carry on filling a
                        // queue nobody reads, the display would freeze on the
                        // last good window, and the recording would look live
                        // for the rest of the session. Storage filling mid-
                        // recording is the realistic way to get here. End the
                        // session instead, and say why.
                        Log.e(TAG, "analysis failed", e)
                        stats.analysisFailure = e.message ?: e.javaClass.simpleName
                        break
                    }
                }
            }

            record.startRecording()
            val micInfo = logActiveMicrophones(record)
            Recorder.setMicInfo(micInfo)

            val interleaved = ShortArray(4096 * channels)
            val chunk = ShortArray(4096)
            val window = FloatArray(windowSamples)
            var filled = 0
            var totalSamples = 0L

            // Display path, entirely separate from the measurement path above.
            val displayMeter = if (settings.displaySeconds > 0f) {
                val blockSamples = SAMPLE_RATE / 40
                DisplayMeter(
                    SAMPLE_RATE, minHz, settings.removeDc,
                    buildCorrectionCurve(blockSamples, calibration)
                )
            } else null
            val displayIntervalMs = (settings.displaySeconds * 1000).toLong()
            var lastDisplay = 0L

            while (live() && stats.analysisFailure == null) {
                val read = record.read(interleaved, 0, interleaved.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION || read == AudioRecord.ERROR_BAD_VALUE) {
                        throw IllegalStateException("AudioRecord.read failed ($read)")
                    }
                    continue
                }
                // Left channel only — the channel the calibration was measured on.
                val n = read / channels
                AudioCapture.takeChannel(interleaved, chunk, n, channels, opened.channelIndex)
                wav?.write(chunk, n)
                totalSamples += n

                if (displayMeter != null && displayMeter.feed(chunk, n)) {
                    val now = System.currentTimeMillis()
                    if (now - lastDisplay >= displayIntervalMs) {
                        lastDisplay = now
                        Recorder.onDisplay(
                            displayMeter.aDb, displayMeter.cDb, displayMeter.zDb,
                            calibration.splOffset
                        )
                    }
                }

                var i = 0
                while (i < n) {
                    val take = minOf(n - i, windowSamples - filled)
                    for (j in 0 until take) window[filled + j] = chunk[i + j] / 32768f
                    filled += take
                    i += take
                    if (filled == windowSamples) {
                        // Windows are back to back: the accumulator carries on
                        // from the same chunk, so no sample falls between two
                        // windows and none is counted twice.
                        val w = PendingWindow(window.copyOf(), totalSamples - n + i - windowSamples)
                        if (!pending.offer(w)) {
                            // Last resort under sustained overload. Recorded so
                            // it can never pass silently.
                            pending.poll()
                            pending.offer(w)
                            stats.droppedWindows++
                            Log.w(TAG, "analysis fell behind — dropped a window")
                        }
                        filled = 0
                    }
                }
                Recorder.tick(totalSamples.toDouble() / SAMPLE_RATE)
                checkForMissedFrames(record, totalSamples, stats)
            }

            // Wait for our own analysis task to drain. If a newer session has
            // already taken over the executor this can time out through no
            // fault of ours, so it must not be allowed to fail that session.
            runCatching { processor.submit { }.get(10, TimeUnit.SECONDS) }
                .onFailure { Log.w(TAG, "analysis drain did not complete", it) }

            val (leqA, leqC, leqZ) = Recorder.summary()
            SessionStore.save(
                SessionMeta(
                    id = id, title = title.ifBlank { id },
                    startEpochMillis = startedAt.time,
                    durationSec = stats.windows * windowSamples.toDouble() / SAMPLE_RATE,
                    sampleRate = SAMPLE_RATE, windowSamples = windowSamples,
                    windowCount = stats.windows,
                    calNameAtRecord = calibration.name,
                    audioSource = opened.description,
                    hasWav = settings.saveRawAudio,
                    calOverride = if (embedded != null) SessionMeta.EMBEDDED else "",
                    clippedWindows = stats.clippedWindows,
                    droppedWindows = stats.droppedWindows,
                    missedFrames = stats.missedFrames,
                    leqA = leqA, leqC = leqC, leqZ = leqZ,
                    lasMax = stats.lasMax, lasMin = stats.lasMin,
                    lcsMax = stats.lcsMax, lcsMin = stats.lcsMin,
                    lzPeak = stats.lzPeak
                )
            )
            // The session is saved either way; a mid-recording analysis failure
            // means it is short, not that it is worthless.
            val failure = stats.analysisFailure
            if (generation != gen) Unit
            else if (failure != null) Recorder.fail("Analysis stopped: $failure")
            else Recorder.finish()
        } catch (e: Throwable) {
            Log.e(TAG, "capture failed", e)
            if (generation == gen) Recorder.fail(e.message ?: e.javaClass.simpleName)
        } finally {
            // Only ever end our own session: a newer one may be running.
            if (generation == gen) running = false
            runCatching { record?.stop() }
            runCatching { record?.release() }
            runCatching { writer?.close() }
            runCatching { wav?.close() }
        }
    }

    private class SessionStats {
        var windows = 0
        var clippedWindows = 0
        /** Windows discarded because analysis could not keep up. Should stay 0. */
        @Volatile var droppedWindows = 0
        /** Frames the driver captured but we never read. Should stay 0. */
        @Volatile var missedFrames = 0L
        /** Set by the analysis thread if it had to give up. Null while healthy. */
        @Volatile var analysisFailure: String? = null
        /**
         * Startup offset between the driver's frame counter and ours, per
         * session — the driver begins filling before the first read returns.
         */
        var frameOffsetBaseline = Long.MIN_VALUE
        var lasMax = Float.NaN
        var lasMin = Float.NaN
        var lcsMax = Float.NaN
        var lcsMin = Float.NaN
        var lzPeak = -200f

        // Windows with no usable level report NaN and are skipped, rather than
        // dragging a running extreme to the floor.
        private fun lower(a: Float, b: Float) = if (b.isNaN()) a else if (a.isNaN() || b < a) b else a
        private fun higher(a: Float, b: Float) = if (b.isNaN()) a else if (a.isNaN() || b > a) b else a

        fun accumulate(w: WindowResult) {
            windows++
            if (w.clippedSamples > 0) clippedWindows++
            lasMax = higher(lasMax, w.lasMax)
            lasMin = lower(lasMin, w.lasMin)
            lcsMax = higher(lcsMax, w.lcsMax)
            lcsMin = lower(lcsMin, w.lcsMin)
            if (w.peak > lzPeak) lzPeak = w.peak
        }
    }

    private var lastFrameCheck = 0L

    /**
     * Compares the driver's own frame counter against how many frames we have
     * actually read.
     *
     * Windows are assembled from consecutive samples, so nothing can be lost
     * between them by construction — but that only covers audio we were handed.
     * If the reader thread ever stalls long enough for the capture ring buffer
     * to wrap, samples are gone before the app sees them, and no error is
     * returned. The hardware frame position advances regardless, so any gap
     * between the two counts is exactly what was missed.
     */
    private fun checkForMissedFrames(record: AudioRecord, framesRead: Long, stats: SessionStats) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val now = System.currentTimeMillis()
        if (now - lastFrameCheck < 10_000) return
        lastFrameCheck = now
        runCatching {
            val ts = AudioTimestamp()
            if (record.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) != AudioRecord.SUCCESS) return
            val offset = ts.framePosition - framesRead
            if (stats.frameOffsetBaseline == Long.MIN_VALUE) {
                stats.frameOffsetBaseline = offset
                Log.i(TAG, "capture frame offset baseline: $offset frames")
                return
            }
            val missed = offset - stats.frameOffsetBaseline
            // A frame or two of slop is just where the timestamp was sampled.
            if (missed > SAMPLE_RATE / 100 && missed > stats.missedFrames) {
                stats.missedFrames = missed
                Log.w(TAG, "capture gap: driver at ${ts.framePosition}, read $framesRead, " +
                    "$missed frames beyond the startup baseline of ${stats.frameOffsetBaseline}")
            }
        }
    }

    /**
     * Logs which physical microphones the capture actually landed on.
     *
     * This is the only way to check that the mic being measured is the mic the
     * calibration was made on — position is in device coordinates, so a bottom
     * mic and a top mic are immediately distinguishable.
     */
    private fun logActiveMicrophones(record: AudioRecord): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return ""
        return runCatching {
            val mics = record.activeMicrophones
            if (mics.isEmpty()) {
                Log.i(TAG, "device reported no active microphone info")
                return ""
            }
            mics.forEach { m ->
                val p = m.position
                Log.i(
                    TAG,
                    "active mic: id=${m.id} \"${m.description}\" address=${m.address} " +
                        "position=(${p.x}, ${p.y}, ${p.z}) " +
                        "directionality=${m.directionality} channelMapping=${m.channelMapping}"
                )
            }
            mics.joinToString(", ") { m ->
                if (m.address.isNullOrBlank()) m.description else m.address
            }
        }.onFailure { Log.w(TAG, "could not read active microphones", it) }.getOrDefault("")
    }

    /** Per-bin power multipliers for the mic response, for the live display only. */
    private fun buildCorrectionCurve(nFft: Int, cal: uk.co.cinema.splmeter.data.Calibration): DoubleArray? {
        if (!cal.hasCurve) return null
        val bins = nFft / 2 + 1
        val binHz = SAMPLE_RATE.toDouble() / nFft
        return DoubleArray(bins) { k -> 10.0.pow(cal.bandCorrection(k * binHz) / 10.0) }
    }

    // ------------------------------------------------------------------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while an SPL measurement is running"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_meter)
            .setContentTitle("Measuring SPL")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    private var lastNotify = 0L

    private fun updateNotification(w: WindowResult, offset: Double) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1900) return
        lastNotify = now
        val text = if (offset != 0.0) {
            "%.1f dBA · %.1f dBC".format(w.laeqCal + offset, w.lceqCal + offset)
        } else {
            "%.1f dBFS (A) — uncalibrated".format(w.laeqCal)
        }
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    override fun onDestroy() {
        running = false
        processor.shutdown()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
