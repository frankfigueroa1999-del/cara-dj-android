package com.cara.dj

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/** Sound as plain numbers: decoding mp3 / m4a with Android's own decoders, resampling, and writing WAV files. */
object Pcm {
    /** Decoded sound: one FloatArray per channel (-1..1), and its sample rate. */
    class Sound(val channels: List<FloatArray>, val rate: Int) {
        val frames: Int get() = channels.firstOrNull()?.size ?: 0
    }

    private class Floats {
        var a = FloatArray(1 shl 16)
        var n = 0
        fun add(v: Float) {
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): FloatArray = a.copyOf(n)
    }

    fun decodeFile(f: File): Sound {
        val ex = MediaExtractor()
        ex.setDataSource(f.absolutePath)
        return decode(ex)
    }

    fun decodeAsset(ctx: Context, name: String): Sound {
        val ex = MediaExtractor()
        ctx.assets.openFd(name).use { fd -> ex.setDataSource(fd.fileDescriptor, fd.startOffset, fd.length) }
        return decode(ex)
    }

    private fun decode(ex: MediaExtractor): Sound {
        var track = -1
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) { track = i; break }
        }
        if (track < 0) { ex.release(); throw Exception("the sound file had no audio") }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = max(1, fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
        var floatPcm = false
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME) ?: "audio/mpeg")
        var outs = Array(channels) { Floats() }
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
                        if (outs.size != channels) outs = Array(channels) { Floats() }
                        if (floatPcm) {
                            val fb = bb.asFloatBuffer()
                            val frames = fb.remaining() / channels
                            for (k in 0 until frames) for (c in 0 until channels) outs[c].add(fb.get())
                        } else {
                            val sb = bb.asShortBuffer()
                            val frames = sb.remaining() / channels
                            for (k in 0 until frames) for (c in 0 until channels) outs[c].add(sb.get() / 32768f)
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
        return Sound(outs.map { it.toArray() }, rate)
    }

    fun mono(s: Sound): FloatArray {
        val chans = s.channels
        val first = chans.firstOrNull() ?: return FloatArray(0)
        if (chans.size == 1) return first
        val out = FloatArray(first.size)
        for (c in chans) for (k in 0 until min(out.size, c.size)) out[k] += c[k]
        val sc = 1f / chans.size
        for (k in out.indices) out[k] *= sc
        return out
    }

    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || from <= 0 || x.size < 2) return x
        val step = from.toDouble() / to
        val n = (x.size / step).toInt()
        val y = FloatArray(n)
        for (i in 0 until n) {
            val p = i * step
            val j = p.toInt()
            val f = (p - j).toFloat()
            y[i] = if (j + 1 < x.size) x[j] * (1 - f) + x[j + 1] * f else x[min(j, x.size - 1)]
        }
        return y
    }

    /** 16-bit stereo WAV. */
    fun writeWav(left: FloatArray, right: FloatArray, rate: Int, out: File) {
        val n = min(left.size, right.size)
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
        val tmp = File(out.parentFile, out.name + ".tmp")
        tmp.writeBytes(bb.array())
        if (!tmp.renameTo(out)) { out.writeBytes(bb.array()); tmp.delete() }
    }
}
