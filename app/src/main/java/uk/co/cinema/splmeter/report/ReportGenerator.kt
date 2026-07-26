package uk.co.cinema.splmeter.report

import android.content.Context
import uk.co.cinema.splmeter.data.SessionMeta
import uk.co.cinema.splmeter.dsp.Bands
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Self-contained HTML report — same look and the same metric set as
 * the reference Python implementation, with Chart.js inlined from
 * assets so the file works offline once shared off the phone.
 */
object ReportGenerator {

    fun generate(context: Context, meta: SessionMeta, m: Metrics): String {
        val chartJs = context.assets.open("chart.umd.min.js").bufferedReader().use { it.readText() }

        val labels = m.times.map { Metrics.formatTime(it.toDouble()) }
        val uncal = m.calibration.splOffset == 0.0
        val unit = if (uncal) "dBFS" else "dB"

        val started = SimpleDateFormat("EEE d MMM yyyy, HH:mm", Locale.UK).format(Date(meta.startEpochMillis))

        val peaksRows = m.topPeaks.mapIndexed { i, p ->
            // A nominal 1/3 octave centre rather than one frequency, so it gets
            // the band label: "%.0f Hz" would print 31.5 Hz as 32.
            "<tr><td>${i + 1}</td><td>%.1f</td><td>%s</td><td>%s Hz</td></tr>"
                .format(p.db, Metrics.formatTime(p.timeSec), Bands.label(p.dominantHz))
        }.joinToString("\n")

        val calNote = buildString {
            append("Cal: ${escape(m.calibration.name)}")
            if (!uncal) append(" · offset %+.2f dB".format(m.calibration.splOffset))
            if (m.calibration.hasCurve) append(" · response curve applied, levels recomputed from stored bands")
            else if (!uncal) append(" · offset only")
        }

        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>SPL report — ${escape(meta.title)}</title>
<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; background: #0a0a0b; color: #e4e4e7; padding: 1rem; }
@media (min-width: 700px) { body { padding: 2rem; } }
.header { margin-bottom: 1.5rem; }
.header h1 { font-size: 1.25rem; font-weight: 500; color: #fafafa; margin-bottom: 0.25rem; }
.header p { font-size: 0.8rem; color: #71717a; line-height: 1.5; }
.metrics { display: grid; grid-template-columns: repeat(auto-fit, minmax(130px, 1fr)); gap: 10px; margin-bottom: 1.5rem; }
.metric { background: #18181b; border: 0.5px solid rgba(255,255,255,0.08); border-radius: 8px; padding: 0.85rem; }
.metric .label { font-size: 0.7rem; color: #71717a; margin-bottom: 4px; text-transform: uppercase; letter-spacing: 0.05em; }
.metric .value { font-size: 1.4rem; font-weight: 500; font-variant-numeric: tabular-nums; }
.metric .unit { font-size: 0.75rem; color: #71717a; margin-left: 2px; }
.chart-container { background: #18181b; border: 0.5px solid rgba(255,255,255,0.08); border-radius: 8px; padding: 1rem; margin-bottom: 1.25rem; }
.chart-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 1rem; flex-wrap: wrap; gap: 8px; }
.chart-title { font-size: 0.875rem; font-weight: 500; }
.legend { display: flex; gap: 14px; font-size: 0.72rem; color: #a1a1aa; }
.legend span { display: flex; align-items: center; gap: 4px; }
.legend .dot { width: 8px; height: 8px; border-radius: 2px; }
.chart-wrap { position: relative; height: 300px; }
.toggle-group { display: flex; gap: 4px; background: #0a0a0b; border-radius: 6px; padding: 2px; }
.toggle-group button { background: transparent; border: none; color: #71717a; font-size: 0.72rem; padding: 4px 9px; border-radius: 4px; cursor: pointer; font-family: inherit; }
.toggle-group button.active { background: #27272a; color: #fafafa; }
table { width: 100%; border-collapse: collapse; font-size: 0.8rem; font-variant-numeric: tabular-nums; }
th { text-align: left; color: #71717a; font-weight: 500; font-size: 0.7rem; text-transform: uppercase; letter-spacing: 0.05em; padding: 6px 8px; }
td { padding: 6px 8px; border-top: 0.5px solid rgba(255,255,255,0.06); }
.info { font-size: 0.72rem; color: #52525b; margin-top: 1rem; line-height: 1.6; }
.warn { color: #f59e0b; }
</style>
</head>
<body>

<div class="header">
  <h1>${escape(meta.title)}</h1>
  <p>$started · ${Metrics.formatTime(m.durationSec)} · window ${"%.0f".format(m.windowSeconds)}s · ${m.log.size} windows<br>
  $calNote${clipNote(m, uncal)}${deviceNote(meta)}</p>
</div>

<div class="metrics">
  ${card("Leq (A)", m.leqA, if (uncal) "dBFS" else "dBA")}
  ${card("Leq (C)", m.leqC, if (uncal) "dBFS" else "dBC")}
  ${card("Leq (Z)", m.leqZ, unit)}
  ${card("LZpeak", m.lzPeak, unit)}
  ${card("LASmax", m.lasMax, if (uncal) "dBFS" else "dBA")}
  ${card("LASmin", m.lasMin, if (uncal) "dBFS" else "dBA")}
  ${card("LCSmax", m.lcsMax, if (uncal) "dBFS" else "dBC")}
  ${card("LCSmin", m.lcsMin, if (uncal) "dBFS" else "dBC")}
</div>

<div class="chart-container">
  <div class="chart-header">
    <span class="chart-title">SPL over time</span>
    <div class="toggle-group" id="toggles">
      <button class="active" data-w="A">A-weighted</button>
      <button data-w="C">C-weighted</button>
      <button data-w="Z">Z (flat)</button>
      <button data-w="all">All</button>
    </div>
  </div>
  <div class="chart-wrap"><canvas id="splChart"></canvas></div>
</div>

<div class="chart-container">
  <div class="chart-header">
    <span class="chart-title">Frequency spectrum (1/3 octave, Z-weighted)</span>
    <div class="legend">
      <span><span class="dot" style="background: #3b82f6;"></span>Average</span>
      <span><span class="dot" style="background: rgba(239,68,68,0.7);"></span>Peak</span>
    </div>
  </div>
  <div class="chart-wrap"><canvas id="specChart"></canvas></div>
</div>

<div class="chart-container">
  <div class="chart-header">
    <span class="chart-title">Sub spectrum (${Bands.SUB_LOW_HZ}–${Bands.SUB_HIGH_HZ} Hz, 1 Hz resolution, average)</span>
  </div>
  <div class="chart-wrap"><canvas id="subChart"></canvas></div>
</div>

<div class="chart-container">
  <div class="chart-header"><span class="chart-title">Top peak moments</span></div>
  <table>
    <tr><th>#</th><th>Peak ($unit)</th><th>Time</th><th>Dominant band</th></tr>
    $peaksRows
  </table>
</div>

<div class="info">
  Measured on Android from the ${escape(meta.audioSource)} microphone source at ${meta.sampleRate} Hz, 16-bit.
  A calibration is only valid for the source it was measured on.
  Clicks and clipped peaks are repaired in real time before analysis; A and C weighting are applied
  in the frequency domain. Fast (125 ms) time weighting is used for the LAS/LCS values, energy
  averaging over the whole session for Leq.
  ${if (m.recomputedFromBands) "Levels were recomputed from the stored 1 Hz / third-octave bands with the mic response curve applied." else ""}
</div>

<script>$chartJs</script>
<script>
const labels = ${jsonStrings(labels)};
const dataA = ${jsonFloats(m.splA)};
const dataC = ${jsonFloats(m.splC)};
const dataZ = ${jsonFloats(m.splZ)};
const bandLabels = ${jsonStrings(m.thirdCentres.map { Bands.label(it) })};
const avgSpec = ${jsonDoubles(m.thirdAvg)};
const peakSpec = ${jsonDoubles(m.thirdPeak)};
const subLabels = ${jsonStrings(m.subHz.map { it.toInt().toString() })};
const subAvg = ${jsonDoubles(m.subAvg)};

function getSplColor(v) {
  if (v >= 100) return 'rgb(239,68,68)';
  if (v >= 90) return 'rgb(249,115,22)';
  if (v >= 80) return 'rgb(250,204,21)';
  if (v >= 70) return 'rgb(74,222,128)';
  return 'rgb(34,197,94)';
}

function scatter(label, data) {
  return { label, data, pointRadius: 1.6, pointHoverRadius: 4, showLine: false, fill: false,
    pointBackgroundColor: data.map(getSplColor), pointBorderColor: data.map(getSplColor) };
}

const datasets = {
  A: [scatter('A-weighted', dataA)],
  C: [scatter('C-weighted', dataC)],
  Z: [scatter('Z-weighted', dataZ)],
  all: [
    { label: 'A-weighted', data: dataA, pointRadius: 1.6, showLine: false, pointBackgroundColor: '#3b82f6', pointBorderColor: '#3b82f6' },
    { label: 'C-weighted', data: dataC, pointRadius: 1.6, showLine: false, pointBackgroundColor: '#f59e0b', pointBorderColor: '#f59e0b' },
    { label: 'Z-weighted', data: dataZ, pointRadius: 1.6, showLine: false, pointBackgroundColor: '#71717a', pointBorderColor: '#71717a' }
  ]
};

const tooltip = { backgroundColor: '#27272a', titleColor: '#fafafa', bodyColor: '#a1a1aa',
  borderColor: 'rgba(255,255,255,0.1)', borderWidth: 0.5, padding: 8 };
const grid = { color: 'rgba(255,255,255,0.04)' };
const tick = { color: '#52525b', font: { size: 10 } };

const all = dataA.concat(dataC, dataZ);
const splChart = new Chart(document.getElementById('splChart'), {
  type: 'line',
  data: { labels, datasets: datasets.A },
  options: {
    responsive: true, maintainAspectRatio: false, animation: { duration: 200 },
    interaction: { mode: 'index', intersect: false },
    plugins: { legend: { display: false }, tooltip, decimation: { enabled: false } },
    scales: {
      x: { ticks: Object.assign({}, tick, { maxTicksLimit: 14, maxRotation: 0 }), grid },
      y: { ticks: Object.assign({}, tick, { callback: v => v + ' dB' }), grid,
           suggestedMin: Math.min.apply(null, all) - 5, suggestedMax: Math.max.apply(null, all) + 5 }
    }
  }
});

document.getElementById('toggles').addEventListener('click', e => {
  const btn = e.target.closest('button');
  if (!btn) return;
  document.querySelectorAll('#toggles button').forEach(b => b.classList.remove('active'));
  btn.classList.add('active');
  splChart.data.datasets = datasets[btn.dataset.w];
  splChart.update();
});

new Chart(document.getElementById('specChart'), {
  type: 'line',
  data: { labels: bandLabels, datasets: [
    { label: 'Peak', data: peakSpec, borderColor: 'rgba(239,68,68,0.7)', backgroundColor: 'rgba(239,68,68,0.06)', borderWidth: 1.2, pointRadius: 0, fill: true, tension: 0.3 },
    { label: 'Average', data: avgSpec, borderColor: '#3b82f6', backgroundColor: 'rgba(59,130,246,0.08)', borderWidth: 1.2, pointRadius: 0, fill: true, tension: 0.3 }
  ]},
  options: {
    responsive: true, maintainAspectRatio: false, animation: { duration: 200 },
    interaction: { mode: 'index', intersect: false },
    plugins: { legend: { display: false }, tooltip },
    scales: { x: { ticks: Object.assign({}, tick, { maxRotation: 45, maxTicksLimit: 20 }), grid },
              y: { ticks: Object.assign({}, tick, { callback: v => v + ' dB' }), grid } }
  }
});

new Chart(document.getElementById('subChart'), {
  type: 'line',
  data: { labels: subLabels, datasets: [
    { label: 'Average', data: subAvg, borderColor: '#22c55e', backgroundColor: 'rgba(34,197,94,0.08)', borderWidth: 1.2, pointRadius: 0, fill: true, tension: 0.15 }
  ]},
  options: {
    responsive: true, maintainAspectRatio: false, animation: { duration: 200 },
    interaction: { mode: 'index', intersect: false },
    plugins: { legend: { display: false }, tooltip },
    scales: { x: { ticks: Object.assign({}, tick, { maxTicksLimit: 20, maxRotation: 0 }), grid },
              y: { ticks: Object.assign({}, tick, { callback: v => v + ' dB' }), grid } }
  }
});
</script>
</body>
</html>"""
    }

    /**
     * How far the loudest peak went past full scale, and whether that is beyond
     * what the microphone can be believed at.
     *
     * A phone's MEMS diaphragm distorts once it is driven a few dB into
     * overload, so a reconstruction from that waveform is an extrapolation
     * rather than a measurement. Saying so is more useful than quietly capping
     * the number.
     */
    private fun clipNote(m: Metrics, uncal: Boolean): String {
        if (m.clippedWindows == 0) return ""
        val overload = m.lzPeak - m.calibration.splOffset
        val base = "${m.clippedWindows} window(s) contained clipped samples — peaks reconstructed"
        val caveat = if (!uncal && overload > 6.0)
            ", and the loudest reached ${f1(overload)} dB past full scale, " +
                "beyond the microphone's linear range — treat LZpeak as a lower bound"
        else ""
        return "<br><span class=\"warn\">$base$caveat</span>"
    }

    /**
     * What made the recording. A measurement is only as attributable as its
     * chain: which phone, which source and channel, and which physical mic the
     * device resolved that to — a cal file is valid for exactly one of them.
     */
    private fun deviceNote(meta: SessionMeta): String {
        val parts = listOfNotNull(
            meta.device.takeIf { it.isNotBlank() },
            meta.audioSource.takeIf { it.isNotBlank() && it != "unknown" },
            meta.microphone.takeIf { it.isNotBlank() },
            meta.appVersion.takeIf { it.isNotBlank() }?.let { "app $it" }
        )
        return if (parts.isEmpty()) "" else "<br>" + escape(parts.joinToString(" · "))
    }

    private fun card(label: String, value: Double, unit: String): String {
        // A metric with nothing usable behind it says so rather than printing a
        // floor value that reads like a measurement.
        val shown = if (!value.isFinite() || value <= -199.0) "—" else f1(value)
        return """<div class="metric"><div class="label">$label</div>
           <div class="value">$shown<span class="unit">$unit</span></div></div>"""
    }

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    /** Always UK-formatted: a comma decimal separator would produce invalid JS. */
    private fun f1(v: Double) = String.format(Locale.UK, "%.1f", v)

    private fun jsonStrings(v: List<String>) =
        v.joinToString(",", "[", "]") { "\"" + it.replace("\"", "\\\"") + "\"" }

    private fun jsonFloats(v: FloatArray) =
        v.joinToString(",", "[", "]") { f1(it.toDouble()) }

    private fun jsonDoubles(v: DoubleArray) =
        v.joinToString(",", "[", "]") { f1(it) }
}
