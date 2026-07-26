#!/usr/bin/env python3
"""
Measure a phone microphone's response against a calibrated reference mic.

Play pink noise and record it simultaneously on the phone and on a measurement
microphone (a miniDSP UMIK-1, Dayton iMM-6 or similar), with the two capsules
as close together as you can manage. This produces a cal file the app can
import, containing both the Sens Factor that converts dBFS to dB SPL and the
phone's frequency response.

    python calibrate_phone.py         --ref reference.wav --phone phone.wav         --cal-file reference_mic.txt --output phone_cal.txt

--cal-file is the reference mic's own calibration file, supplied by its
manufacturer. It is used both to correct the reference measurement and to
establish absolute level from its Sens Factor.

Output follows the standard REW convention: the file holds the phone mic's own
response, which analysis software subtracts from a measurement, so a mic that
reads high at some frequency carries a positive value there.

Requires numpy and soundfile.
"""

import numpy as np
import soundfile as sf
import argparse
import os
import sys


def read_freq_corrections(cal_file):
    freqs = []
    corrections = []
    with open(cal_file, "r") as f:
        for line in f:
            line = line.strip().strip('"')
            if line.startswith("Sens Factor") or line.startswith("Auto-generated"):
                continue
            parts = line.split()
            if len(parts) == 2:
                try:
                    freqs.append(float(parts[0]))
                    corrections.append(float(parts[1]))
                except ValueError:
                    continue
    return np.array(freqs), np.array(corrections)


def read_sens_factor(cal_file):
    with open(cal_file, "r") as f:
        first_line = f.readline().strip().strip('"')
        if "Sens Factor" in first_line:
            parts = first_line.split("=")[1]
            sens_str = parts.split("dB")[0].strip()
            return float(sens_str)
    return None


def compute_avg_spectrum(audio, framerate, window_sec=2.0):
    window_samples = int(window_sec * framerate)
    hop = window_samples // 2
    n_fft = window_samples
    freqs = np.fft.rfftfreq(n_fft, 1.0 / framerate)

    power_sum = np.zeros(len(freqs))
    count = 0

    pos = 0
    while pos + window_samples <= len(audio):
        chunk = audio[pos:pos + window_samples]
        windowed = chunk * np.hanning(window_samples)
        spectrum = np.abs(np.fft.rfft(windowed)) ** 2
        power_sum += spectrum
        count += 1
        pos += hop

    if count == 0:
        return freqs, np.zeros(len(freqs))

    avg_power = power_sum / count
    avg_db = 10 * np.log10(avg_power + 1e-30)
    return freqs, avg_db


def smooth_curve(freqs, db_values, smoothing_octave=1/3):
    smoothed = np.zeros_like(db_values)
    for i, f in enumerate(freqs):
        if f <= 0:
            smoothed[i] = db_values[i]
            continue
        f_low = f / (2 ** (smoothing_octave / 2))
        f_high = f * (2 ** (smoothing_octave / 2))
        mask = (freqs >= f_low) & (freqs <= f_high)
        if np.any(mask):
            smoothed[i] = np.mean(db_values[mask])
        else:
            smoothed[i] = db_values[i]
    return smoothed


def main():
    parser = argparse.ArgumentParser(description="Calibrate phone mic using UMIK-1 reference")
    parser.add_argument("--ref", required=True, help="UMIK-1 reference recording (WAV)")
    parser.add_argument("--phone", required=True, help="Phone recording (WAV)")
    parser.add_argument("--cal-file", help="UMIK-1 calibration file (applies correction to ref first)")
    parser.add_argument("--output", default="phone_cal.txt", help="Output calibration file for phone")
    parser.add_argument("--sens-factor", type=float, help="Sens Factor to write into the phone cal file (dB)")
    parser.add_argument("--smoothing", type=float, default=1/3, help="Smoothing width in octaves (default: 1/3)")
    parser.add_argument("--min-freq", type=float, default=20, help="Min frequency for cal file (default: 20)")
    parser.add_argument("--max-freq", type=float, default=20000, help="Max frequency for cal file (default: 20000)")
    args = parser.parse_args()

    print(f"Reading reference: {args.ref}")
    ref_audio, ref_rate = sf.read(args.ref, dtype="float64")
    if ref_audio.ndim > 1:
        ref_audio = ref_audio[:, 0]
    skip_samples = int(0.1 * ref_rate)
    ref_audio = ref_audio[skip_samples:]
    print(f"  {ref_rate}Hz, {len(ref_audio)/ref_rate:.1f}s (skipped first 100ms)")

    print(f"Reading phone: {args.phone}")
    phone_audio, phone_rate = sf.read(args.phone, dtype="float64")
    if phone_audio.ndim > 1:
        phone_audio = phone_audio[:, 0]
    skip_samples = int(0.1 * phone_rate)
    phone_audio = phone_audio[skip_samples:]
    print(f"  {phone_rate}Hz, {len(phone_audio)/phone_rate:.1f}s (skipped first 100ms)")

    if ref_rate != phone_rate:
        print(f"WARNING: Sample rates differ ({ref_rate} vs {phone_rate}). Results may be less accurate.")

    # Compute average spectrum of both
    print("Computing reference spectrum...")
    ref_freqs, ref_db = compute_avg_spectrum(ref_audio, ref_rate)

    # Apply UMIK-1 cal file correction to reference spectrum
    if args.cal_file:
        print(f"Applying UMIK-1 cal file: {args.cal_file}")
        cal_freqs, cal_corrections = read_freq_corrections(args.cal_file)
        if len(cal_freqs) > 0:
            cal_interp = np.interp(ref_freqs, cal_freqs, cal_corrections, left=cal_corrections[0], right=cal_corrections[-1])
            ref_db += cal_interp

    print("Computing phone spectrum...")
    phone_freqs, phone_db = compute_avg_spectrum(phone_audio, phone_rate)

    # Interpolate to common frequency grid (use reference grid)
    if phone_rate != ref_rate:
        phone_db = np.interp(ref_freqs, phone_freqs, phone_db, left=phone_db[0], right=phone_db[-1])
        phone_freqs = ref_freqs

    # Compute SPL offset for phone
    # UMIK-1 known offset from cal file
    umik_sens = read_sens_factor(args.cal_file) if args.cal_file else None
    if umik_sens is not None:
        umik_spl_offset = 100 - umik_sens + 24
        ref_rms = np.sqrt(np.mean(ref_audio ** 2))
        phone_rms = np.sqrt(np.mean(phone_audio ** 2))
        ref_dbfs = 20 * np.log10(ref_rms) if ref_rms > 0 else -120
        phone_dbfs = 20 * np.log10(phone_rms) if phone_rms > 0 else -120
        # True SPL from UMIK-1
        true_spl = ref_dbfs + umik_spl_offset
        # Phone's SPL offset = true_spl - phone_dbfs
        phone_spl_offset = round(true_spl - phone_dbfs, 2)
        # Express as Sens Factor in same convention: offset = 100 - sens + 24
        phone_sens = round(100 + 24 - phone_spl_offset, 2)
        print(f"\n  SPL calibration:")
        print(f"    UMIK-1 RMS: {ref_dbfs:.1f} dBFS -> {true_spl:.1f} dB SPL")
        print(f"    Phone RMS:  {phone_dbfs:.1f} dBFS")
        print(f"    Phone SPL offset: {phone_spl_offset} dB (Sens Factor: {phone_sens})")
    else:
        phone_sens = None
        phone_spl_offset = None

    # Compute the phone mic's own frequency response, relative to the reference.
    #
    # This is the standard cal file convention, as used by REW and miniDSP: the
    # file holds the microphone's actual gain response, which the analysis
    # software then SUBTRACTS from a measurement. A mic that reads high at some
    # frequency therefore gets a POSITIVE value there.
    #
    # This used to be written as ref - phone ("what to add to the phone"), which
    # is the negative of the convention. Files generated before this fix are
    # sign-inverted and are recognised by their old header line.
    response = phone_db - ref_db

    # Smooth the response curve
    print(f"Smoothing response curve ({args.smoothing} octave)...")
    response_smoothed = smooth_curve(ref_freqs, response, args.smoothing)

    # Normalise so the response is 0 dB at 1kHz (relative response only)
    idx_1k = np.argmin(np.abs(ref_freqs - 1000))
    response_smoothed -= response_smoothed[idx_1k]

    # Filter to desired frequency range and resample to ~log-spaced points
    mask = (ref_freqs >= args.min_freq) & (ref_freqs <= args.max_freq)
    valid_freqs = ref_freqs[mask]
    valid_response = response_smoothed[mask]

    # Resample to ~200 log-spaced points for a clean cal file
    out_freqs = np.logspace(np.log10(args.min_freq), np.log10(min(args.max_freq, ref_rate / 2)), 200)
    out_correction = np.interp(out_freqs, valid_freqs, valid_response)

    # Write cal file
    sens_to_write = args.sens_factor if args.sens_factor is not None else phone_sens
    print(f"\nWriting calibration file: {args.output}")
    with open(args.output, "w") as f:
        if sens_to_write is not None:
            f.write(f'"Sens Factor ={sens_to_write}dB, Phone response measured against UMIK-1 (REW convention)"\n')
        else:
            f.write(f'"Phone mic response measured against UMIK-1 reference (REW convention)"\n')
        for freq, corr in zip(out_freqs, out_correction):
            f.write(f"{freq:.3f}\t{corr:.4f}\n")

    print(f"\nDone. Mic response range: {out_correction.min():.1f} to {out_correction.max():.1f} dB")
    print(f"  Reads highest: {out_correction.max():+.1f} dB at {out_freqs[np.argmax(out_correction)]:.0f} Hz")
    print(f"  Reads lowest:  {out_correction.min():+.1f} dB at {out_freqs[np.argmin(out_correction)]:.0f} Hz")
    print("  (positive = mic reads high there; analysis software subtracts this)")

    # Print summary at key frequencies
    print("\n  Mic response at key frequencies:")
    for target in [31.5, 63, 125, 250, 500, 1000, 2000, 4000, 8000, 16000]:
        idx = np.argmin(np.abs(out_freqs - target))
        print(f"    {target:>6.0f} Hz: {out_correction[idx]:+.1f} dB")


if __name__ == "__main__":
    main()
