package fr.groggy.racecontrol.tv.ui.channel.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Taps decoded PCM (via [TeeAudioProcessor]) and turns it into a compact loudness fingerprint:
 * the energy in a few bands above most of the speech energy, every 20 ms, stamped with the
 * wall-clock time it was processed. Comparing the F1 audio's fingerprint with the radio's shows
 * how far apart the shared circuit sound is, independent of any stream clock.
 */
internal class AcousticFeatureStream : TeeAudioProcessor.AudioBufferSink {

    companion object {
        const val HOP_MS = 20L
        private const val FFT_SIZE = 1024
        // Engine/crowd sound carries well above the bulk of commentary energy
        private val BANDS_HZ = arrayOf(1_000.0 to 2_000.0, 2_000.0 to 4_000.0, 4_000.0 to 8_000.0)
        const val BAND_COUNT = 3
        // Processing runs slightly ahead of playout; re-anchor if it drifts further (pause, seek, stall)
        private const val REANCHOR_THRESHOLD_MS = 300.0
        private const val MAX_HISTORY_MS = 70_000L
    }

    class Frame(val timeMs: Double, val bands: FloatArray)

    private val lock = Any()
    private val frames = ArrayDeque<Frame>()

    // Audio thread state
    private var sampleRate = 0
    private var channels = 0
    private var encoding = C.ENCODING_INVALID
    private var hopSamples = 0
    // Circular: the newest FFT_SIZE samples, oldest at windowPos once full
    private val window = DoubleArray(FFT_SIZE)
    private var windowPos = 0
    private var windowFill = 0
    private var samplesSinceHop = 0
    private var anchorWallMs = Double.NaN
    private var samplesSinceAnchor = 0L
    private val re = DoubleArray(FFT_SIZE)
    private val im = DoubleArray(FFT_SIZE)
    private val hann = DoubleArray(FFT_SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (FFT_SIZE - 1)) }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        channels = channelCount
        this.encoding = encoding
        hopSamples = (sampleRateHz * HOP_MS / 1000L).toInt()
        windowFill = 0
        windowPos = 0
        samplesSinceHop = 0
        anchorWallMs = Double.NaN
        samplesSinceAnchor = 0L
        // A flush means a seek/format change: older audio no longer lines up with what follows
        clear()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (sampleRate <= 0 || channels <= 0) return
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_16BIT -> 2
            C.ENCODING_PCM_FLOAT -> 4
            else -> return
        }
        val data = buffer.duplicate().order(ByteOrder.nativeOrder())
        val frameCount = data.remaining() / (bytesPerSample * channels)
        if (frameCount == 0) return

        val nowMs = SystemClock.elapsedRealtime().toDouble()
        val expectedMs = anchorWallMs + samplesSinceAnchor * 1000.0 / sampleRate
        if (anchorWallMs.isNaN() || abs(expectedMs - nowMs) > REANCHOR_THRESHOLD_MS) {
            anchorWallMs = nowMs
            samplesSinceAnchor = 0L
        }

        for (i in 0 until frameCount) {
            var mono = 0.0
            for (c in 0 until channels) {
                mono += if (bytesPerSample == 2) data.short / 32768.0 else data.float.toDouble()
            }
            push(mono / channels)
        }
    }

    private fun push(sample: Double) {
        window[windowPos] = sample
        windowPos = (windowPos + 1) % FFT_SIZE
        if (windowFill < FFT_SIZE) windowFill++
        samplesSinceAnchor++
        if (++samplesSinceHop >= hopSamples && windowFill == FFT_SIZE) {
            samplesSinceHop = 0
            val timeMs = anchorWallMs + samplesSinceAnchor * 1000.0 / sampleRate
            add(Frame(timeMs, bandEnergies()))
        }
    }

    private fun bandEnergies(): FloatArray {
        for (i in 0 until FFT_SIZE) {
            re[i] = window[(windowPos + i) % FFT_SIZE] * hann[i]
            im[i] = 0.0
        }
        fft(re, im)
        val binHz = sampleRate.toDouble() / FFT_SIZE
        return FloatArray(BAND_COUNT) { b ->
            val (lo, hi) = BANDS_HZ[b]
            var energy = 0.0
            var bin = (lo / binHz).toInt().coerceAtLeast(1)
            val last = (hi / binHz).toInt().coerceAtMost(FFT_SIZE / 2)
            while (bin <= last) {
                energy += re[bin] * re[bin] + im[bin] * im[bin]
                bin++
            }
            ln(energy + 1e-9).toFloat()
        }
    }

    private fun add(frame: Frame) = synchronized(lock) {
        frames.addLast(frame)
        while (frames.isNotEmpty() && frame.timeMs - frames.first().timeMs > MAX_HISTORY_MS) {
            frames.removeFirst()
        }
    }

    fun clear() = synchronized(lock) { frames.clear() }

    /** Band energies resampled onto a [HOP_MS] grid starting at [startMs]; null where there's no data. */
    fun gridded(startMs: Double, count: Int): Array<FloatArray?> = synchronized(lock) {
        val out = arrayOfNulls<FloatArray>(count)
        if (frames.isEmpty()) return out
        var index = 0
        val list = frames
        for (i in 0 until count) {
            val t = startMs + i * HOP_MS
            while (index < list.size - 1 && list[index + 1].timeMs <= t) index++
            val f = list[index]
            if (abs(f.timeMs - t) <= HOP_MS * 1.5) out[i] = f.bands
        }
        out
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wRe = cos(angle)
            val wIm = sin(angle)
            var start = 0
            while (start < n) {
                var curRe = 1.0
                var curIm = 0.0
                for (k in 0 until len / 2) {
                    val aRe = re[start + k]
                    val aIm = im[start + k]
                    val bRe = re[start + k + len / 2] * curRe - im[start + k + len / 2] * curIm
                    val bIm = re[start + k + len / 2] * curIm + im[start + k + len / 2] * curRe
                    re[start + k] = aRe + bRe
                    im[start + k] = aIm + bIm
                    re[start + k + len / 2] = aRe - bRe
                    im[start + k + len / 2] = aIm - bIm
                    val nextRe = curRe * wRe - curIm * wIm
                    curIm = curRe * wIm + curIm * wRe
                    curRe = nextRe
                }
                start += len
            }
            len = len shl 1
        }
    }
}

/**
 * Finds how much later the shared circuit sound reaches the listener through the radio than
 * through the F1 audio, by cross-correlating their loudness fingerprints.
 */
internal object RadioAcousticCorrelator {

    /** [lagMs] > 0: the radio is behind the F1 audio by that much. */
    data class Estimate(val lagMs: Long, val score: Double, val peakRatio: Double)

    private const val WINDOW_MS = 30_000L
    private const val MAX_LAG_MS = 10_000L
    // Leave the newest audio out: it may not have been heard yet
    private const val END_MARGIN_MS = 2_000L
    // Removes slow level changes so short events (cars passing, gear changes) dominate
    private const val DETREND_FRAMES = 50
    private const val MIN_COVERAGE = 0.9
    private const val SECOND_PEAK_EXCLUSION_MS = 600L

    fun estimate(main: AcousticFeatureStream, radio: AcousticFeatureStream, notBeforeMs: Double): Estimate? {
        val hop = AcousticFeatureStream.HOP_MS
        val maxLag = (MAX_LAG_MS / hop).toInt()
        val n = (WINDOW_MS / hop).toInt()
        val nowMs = SystemClock.elapsedRealtime().toDouble()
        val mainStart = nowMs - END_MARGIN_MS - MAX_LAG_MS - WINDOW_MS
        val radioStart = mainStart - MAX_LAG_MS
        if (radioStart < notBeforeMs) return null

        val mainGrid = main.gridded(mainStart, n)
        val radioGrid = radio.gridded(radioStart, n + 2 * maxLag)
        if (mainGrid.count { it != null } < n * MIN_COVERAGE) return null
        if (radioGrid.count { it != null } < (n + 2 * maxLag) * MIN_COVERAGE) return null

        val bands = AcousticFeatureStream.BAND_COUNT
        val correlation = DoubleArray(2 * maxLag + 1)
        for (b in 0 until bands) {
            val m = normalize(DoubleArray(n) { mainGrid[it]?.get(b)?.toDouble() ?: Double.NaN })
            val r = normalize(DoubleArray(n + 2 * maxLag) { radioGrid[it]?.get(b)?.toDouble() ?: Double.NaN })
            for (lag in -maxLag..maxLag) {
                // main[t] lines up with radio[t + lag]; radioGrid index 0 is maxLag steps before main's
                var sum = 0.0
                var count = 0
                for (t in 0 until n) {
                    val a = m[t]
                    val c = r[t + maxLag + lag]
                    if (!a.isNaN() && !c.isNaN()) {
                        sum += a * c
                        count++
                    }
                }
                if (count > 0) correlation[lag + maxLag] += sum / count / bands
            }
        }

        var best = 0
        for (i in correlation.indices) if (correlation[i] > correlation[best]) best = i
        val exclusion = (SECOND_PEAK_EXCLUSION_MS / hop).toInt()
        var second = Double.NEGATIVE_INFINITY
        for (i in correlation.indices) {
            if (abs(i - best) > exclusion && correlation[i] > second) second = correlation[i]
        }
        // Parabolic interpolation around the peak for sub-hop precision
        var offset = 0.0
        if (best in 1 until correlation.size - 1) {
            val y0 = correlation[best - 1]
            val y1 = correlation[best]
            val y2 = correlation[best + 1]
            val denominator = y0 - 2 * y1 + y2
            if (denominator != 0.0) offset = (0.5 * (y0 - y2) / denominator).coerceIn(-0.5, 0.5)
        }
        val lagMs = ((best - maxLag + offset) * hop).toLong()
        val ratio = if (second > 0) correlation[best] / second else Double.POSITIVE_INFINITY
        return Estimate(lagMs, correlation[best], ratio)
    }

    /** Detrend with a moving average, then z-normalise; gaps stay NaN. */
    private fun normalize(values: DoubleArray): DoubleArray {
        val out = DoubleArray(values.size) { Double.NaN }
        val half = DETREND_FRAMES / 2
        for (i in values.indices) {
            if (values[i].isNaN()) continue
            var sum = 0.0
            var count = 0
            for (k in (i - half).coerceAtLeast(0)..(i + half).coerceAtMost(values.size - 1)) {
                if (!values[k].isNaN()) {
                    sum += values[k]
                    count++
                }
            }
            out[i] = values[i] - sum / count
        }
        var mean = 0.0
        var count = 0
        for (v in out) if (!v.isNaN()) { mean += v; count++ }
        if (count == 0) return out
        mean /= count
        var variance = 0.0
        for (v in out) if (!v.isNaN()) variance += (v - mean) * (v - mean)
        val std = sqrt(variance / count).takeIf { it > 1e-9 } ?: return out
        for (i in out.indices) if (!out[i].isNaN()) out[i] = (out[i] - mean) / std
        return out
    }
}

/**
 * Selects (or undoes selecting) the F1 stream's ambient-only audio track ("FX": circuit sound
 * without commentary) with an audio track override. Returns whether such a track was found.
 * Manual language choices are preferences, not overrides, so clearing audio overrides keeps them.
 */
internal fun ExoPlayer.selectAmbientAudioTrack(select: Boolean): Boolean {
    if (!select) {
        trackSelectionParameters = trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            .build()
        return true
    }
    for (group in currentTracks.groups) {
        if (group.type != C.TRACK_TYPE_AUDIO) continue
        for (i in 0 until group.length) {
            if (!group.isTrackSupported(i) || !group.getTrackFormat(i).isAmbientAudio()) continue
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                .build()
            return true
        }
    }
    return false
}

private fun Format.isAmbientAudio(): Boolean {
    if (language.equals("cfx", ignoreCase = true)) return true
    val names = labels.map { it.value } + listOfNotNull(label)
    return names.any { name ->
        name.equals("FX", ignoreCase = true) || name.contains("effects", ignoreCase = true) ||
            name.contains("ambient", ignoreCase = true)
    }
}
