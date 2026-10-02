package com.cara.dj

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Cara and Scratch together: each line in its own voice, levelled, Cara a touch left and Scratch a touch right, joined into one clip. */
object Duo {
    private class Floats {
        var a = FloatArray(1 shl 16)
        var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): FloatArray = a.copyOf(n)
    }

    /** Voices every line (Cara in her voice, Scratch in his) and mixes them into one WAV file in [dir]. */
    suspend fun render(lines: List<Pair<String, String>>, dir: File): File {
        val coVoice = Config.coVoice.trim().ifEmpty { Brain.coDefaultVoice }
        val stamp = "${System.currentTimeMillis()}_${randInt(0, 999)}"
        val parts = mutableListOf<Pair<Boolean, FloatArray>>()
        var rate = 0
        val files = mutableListOf<File>()
        try {
            for ((i, l) in lines.withIndex()) {
                val isCo = l.first != "CARA"
                val data = elevenLabsTTS(l.second, if (isCo) coVoice else null)
                val f = File(dir, "duo_${stamp}_$i.mp3")
                f.writeBytes(data)
                files.add(f)
                val (raw, r) = withContext(Dispatchers.Default) { decode(f) }
                if (rate == 0) rate = r
                var v = if (r == rate) raw else resample(raw, r, rate)
                v = trim(v, rate)
                if (v.isEmpty()) continue
                val g = 10.0.pow((-19.0 - activeDb(v, rate)) / 20.0).toFloat()
                for (k in v.indices) v[k] *= g
                parts.add(Pair(isCo, v))
            }
        } finally {
            for (f in files) f.delete()
        }
        if (parts.isEmpty() || rate == 0) throw Exception("no voices came back")
        return withContext(Dispatchers.Default) {
            var pos = (0.1 * rate).toInt()
            val placed = mutableListOf<Triple<Int, Boolean, FloatArray>>()
            for ((co, v) in parts) {
                placed.add(Triple(pos, co, v))
                pos += v.size + ((0.12 + Random.nextDouble() * 0.14) * rate).toInt()
            }
            val total = placed.maxOf { it.first + it.third.size } + (0.2 * rate).toInt()
            val left = FloatArray(total)
            val right = FloatArray(total)
            for ((p, co, v) in placed) {
                val gl = if (co) 0.86f else 1.0f
                val gr = if (co) 1.0f else 0.86f
                for (k in v.indices) {
                    left[p + k] += v[k] * gl
                    right[p + k] += v[k] * gr
                }
            }
            var peak = 1e-9f
            for (k in 0 until total) peak = max(peak, max(abs(left[k]), abs(right[k])))
            if (peak > 0.97f) {
                val s = 0.97f / peak
                for (k in 0 until total) { left[k] *= s; right[k] *= s }
            }
            val out = File(dir, "duo_$stamp.wav")
            writeWav(left, right, rate, out)
            out
        }
    }

    /** An mp3 as mono float samples, decoded by Android's own decoder. */
    private fun decode(f: File): Pair<FloatArray, Int> {
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) { track = i; break }
        }
        if (track < 0) { ex.release(); throw Exception("the voice file had no audio") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = max(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        var floatPcm = false
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg")
        val out = Floats()
        try {
            codec.configure(fmt, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var idle = 0
            while (!outputDone && idle < 3000) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)
                        val n = if (buf != null) ex.readSampleData(buf, 0) else -1
                        if (n < 0) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    idle = 0
                    val ob = codec.getOutputBuffer(oi)
                    if (ob != null && info.size > 0) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        val bb = ob.slice().order(ByteOrder.nativeOrder())
                        if (floatPcm) {
                            val fb = bb.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            for (k in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += fb.get()
                                out.add(s / channels)
                            }
                        } else {
                            val sb = bb.asShortBuffer()
                            val frames = sb.remaining() / channels
                            for (k in 0 until frames) {
                                var s = 0f
                                for (c in 0 until channels) s += sb.get() / 32768f
                                out.add(s / channels)
                            }
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val of = codec.outputFormat
                    rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = max(1, of.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
                    floatPcm = of.containsKey(MediaFormat.KEY_PCM_ENCODING) && of.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else {
                    idle++
                }
            }
        } finally {
            try { codec.stop() } catch (e: Exception) { }
            codec.release()
            ex.release()
        }
        return Pair(out.toArray(), rate)
    }

    private fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val n = (x.size.toLong() * to / from).toInt()
        val out = FloatArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val p = i * step
            val j = p.toInt()
            val fr = (p - j).toFloat()
            val a = x[min(j, x.size - 1)]
            val b = x[min(j + 1, x.size - 1)]
            out[i] = a + (b - a) * fr
        }
        return out
    }

    /** Cuts the silence off both ends (a hair of room left either side). */
    private fun trim(x: FloatArray, rate: Int): FloatArray {
        if (x.isEmpty()) return x
        var top = 0f
        for (v in x) top = max(top, abs(v))
        if (top <= 0f) return FloatArray(0)
        val th = top * 10.0.pow(-45.0 / 20.0).toFloat()
        var a = 0
        while (a < x.size && abs(x[a]) <= th) a++
        var b = x.size - 1
        while (b > a && abs(x[b]) <= th) b--
        val start = max(0, a - (0.009 * rate).toInt())
        val end = min(x.size, b + 1 + (0.03 * rate).toInt())
        return x.copyOfRange(start, end)
    }

    /** How loud the voice is while it's actually speaking (quiet gaps don't count). */
    private fun activeDb(x: FloatArray, rate: Int): Double {
        val hop = max(1, (0.02 * rate).toInt())
        val n = x.size / hop
        if (n == 0) return -120.0
        val fr = DoubleArray(n)
        for (i in 0 until n) {
            var s = 0.0
            for (k in 0 until hop) { val v = x[i * hop + k].toDouble(); s += v * v }
            fr[i] = sqrt(s / hop)
        }
        val top = fr.maxOrNull() ?: 0.0
        if (top <= 0.0) return -120.0
        val floor = top * 10.0.pow(-35.0 / 20.0)
        val loud = fr.filter { it > floor }
        if (loud.isEmpty()) return -120.0
        return 10 * log10(loud.sumOf { it * it } / loud.size + 1e-12)
    }

    /** How long one of these WAV files plays, read from its header. */
    fun wavMs(f: File): Int = try {
        val h = ByteArray(44)
        val n = f.inputStream().use { it.read(h) }
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSec = bb.getInt(28)
        val data = bb.getInt(40)
        if (n == 44 && bytesPerSec > 0 && data > 0) (data.toLong() * 1000 / bytesPerSec).toInt() else DJAudio.duration(f)
    } catch (e: Exception) {
        DJAudio.duration(f)
    }

    private fun writeWav(left: FloatArray, right: FloatArray, rate: Int, out: File) {
        val n = left.size
        val dataLen = n * 4
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII)); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII)); bb.putInt(16)
        bb.putShort(1.toShort()); bb.putShort(2.toShort()); bb.putInt(rate); bb.putInt(rate * 4)
        bb.putShort(4.toShort()); bb.putShort(16.toShort())
        bb.put("data".toByteArray(Charsets.US_ASCII)); bb.putInt(dataLen)
        for (i in 0 until n) {
            bb.putShort((left[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            bb.putShort((right[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        }
        out.writeBytes(bb.array())
    }
}
