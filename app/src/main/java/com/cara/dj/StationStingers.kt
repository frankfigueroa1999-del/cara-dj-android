package com.cara.dj

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------- station stingers
// Your six Non Stop Pop stingers, word for word, with the station's name swapped for whatever's playing.
// The music, swooshes and hits are the originals (the voice was lifted out of each one); the station voice reads
// the same lines, with the originals' radio EQ, compression and echo, dropped in where the old lines sat.

/** Where each line sits in the six originals, and what it says. */
object StingerScript {
    /** What a line says: the original words, or one of the lines with the station's name in it. */
    sealed class Say {
        class Words(val text: String) : Say()
        /** "Late Night Drives." */
        object Name : Say()
        /** "Late Night Drives!" */
        object Shout : Say()
        /** "This is Late Night Drives." */
        object ThisIs : Say()
        /** "You know you love Late Night Drives FM." */
        object Love : Say()
        /** "One hundred point seven FM, Late Night Drives." */
        object FreqName : Say()
    }

    class Line(val at: Double, val say: Say, val echo: Boolean)

    /** [voiceDB]: how loud the original voice sat in the music (dBFS while talking), so the new one sits the same. */
    class Template(val id: Int, val lines: List<Line>, val voiceDB: Float)

    private fun w(t: String) = Say.Words(t)

    val templates = listOf(
        Template(1, listOf(
            Line(0.10, w("Classic pop hits from the last thirty years."), false),
            Line(2.45, w("It's the best music."), false),
            Line(3.60, w("One hundred point seven FM."), false),
            Line(4.90, Say.Shout, true),
        ), -21.5f),
        Template(2, listOf(
            Line(0.10, w("All your favorite pop hits from the eighties,"), false),
            Line(3.30, w("nineties,"), false),
            Line(4.00, w("noughties,"), true),
            Line(5.20, w("and today."), false),
            Line(6.40, w("One hundred point seven FM."), false),
            Line(8.40, Say.Name, false),
        ), -21.6f),
        Template(3, listOf(
            Line(0.05, w("Dance pop classics that"), false),
            Line(2.85, w("never stop,"), true),
            Line(4.85, w("On one hundred point seven FM."), false),
            Line(7.00, w("The music is awesome."), false),
            Line(8.60, Say.Name, false),
        ), -23.0f),
        Template(4, listOf(
            Line(0.15, w("The music"), true),
            Line(2.40, w("that has really moved you."), false),
            Line(4.60, Say.ThisIs, false),
            Line(6.60, w("They're the best."), true),
        ), -22.5f),
        Template(5, listOf(
            Line(0.10, w("Everyone was happy once in their lives."), false),
            Line(3.10, w("This is music from that special time for you."), false),
            Line(6.20, Say.FreqName, false),
            Line(8.70, w("Contemporary nostalgia is the best."), false),
        ), -22.4f),
        Template(6, listOf(
            Line(0.10, w("Give in to the music."), true),
            Line(3.20, w("This is when you were happy."), false),
            Line(4.60, Say.Love, false),
            Line(7.10, w("Don't be an elitist snotbag."), true),
        ), -20.6f),
    )

    /** The words of one line, with this station's name where Non-Stop-Pop used to be. */
    fun text(say: Say, station: String): String = when (say) {
        is Say.Words -> say.text
        Say.Name -> "$station."
        Say.Shout -> "$station!"
        Say.ThisIs -> "This is $station."
        Say.Love -> "You know you love ${Station.full(station)}."
        Say.FreqName -> "One hundred point seven FM, $station."
    }
}

/** Makes and keeps the stingers for each station. */
object StationStingers {
    /** The announcer when you haven't picked one (one of ElevenLabs' own voices). */
    const val DEFAULT_VOICE = "EXAVITQu4vr4xnSDxMaL"

    private val making = mutableSetOf<String>()
    private val lastPlayed = mutableMapOf<String, String>()
    private var failedAt = 0L

    fun voice(): String = Config.stationVoice.trim().ifEmpty { DEFAULT_VOICE }

    /** Making them has gone wrong in the last ten minutes (the original stingers stand in meanwhile). */
    val failingLately: Boolean get() = failedAt > 0 && System.currentTimeMillis() - failedAt < 600_000

    private val root: File get() = File(Engine.app.filesDir, "StationStingersV2")

    /** The station voice's reads, kept so each line is only ever recorded once. */
    private val linesFolder: File get() = File(root, "lines")

    private fun hash(s: String): String {
        var h: ULong = 5381u
        for (b in s.toByteArray(Charsets.UTF_8)) h = h * 33u + (b.toInt() and 0xFF).toULong()
        return h.toString(36)
    }

    /** One folder of finished stingers per station, voice and model. */
    private fun folder(station: String): File {
        val slug = station.lowercase().map { if (it.isLetterOrDigit()) it else '-' }.joinToString("").take(24)
        val key = slug + "-" + hash(station + "|" + voice() + "|" + Config.elevenModel)
        return File(File(root, "stations"), key)
    }

    private fun made(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray()).filter { it.extension == "wav" }.sortedBy { it.name }

    fun isMaking(station: String): Boolean = folder(station).name in making

    /** A finished stinger for this station (never the same one twice in a row), or null if none is made yet. */
    fun ready(station: String): File? {
        val dir = folder(station)
        val all = made(dir)
        if (all.isEmpty()) return null
        val last = lastPlayed[dir.name]
        val pick = all.filter { it.name != last }.randomOrNull() ?: all[0]
        lastPlayed[dir.name] = pick.name
        dir.setLastModified(System.currentTimeMillis())
        return pick
    }

    /** Makes one more for this station in the background, if it still needs one. */
    fun warm(station: String, log: (String) -> Unit) {
        if (!Config.stationStingers || station == Station.FALLBACK || failingLately) return
        val dir = folder(station)
        if (dir.name in making || made(dir).size >= StingerScript.templates.size) return
        Engine.scope.launch { make(station, log) }
    }

    /** Starts over: the station voice records every line again. */
    fun clearAll() {
        root.deleteRecursively()
        lastPlayed.clear()
        failedAt = 0L
    }

    /** Makes one more stinger for this station right now. Returns it (null if it couldn't). */
    suspend fun make(station: String, log: (String) -> Unit): File? {
        val dir = folder(station)
        val key = dir.name
        if (key in making) return null
        making.add(key)
        try {
            dir.mkdirs()
            val done = made(dir).map { it.nameWithoutExtension }.toSet()
            val todo = StingerScript.templates.filter { "stinger_${it.id}" !in done }
            val t = todo.randomOrNull() ?: return ready(station)
            val texts = t.lines.map { StingerScript.text(it.say, station) }
            log("[making a ${Station.full(station)} stinger: ${texts.joinToString(" ")}]")

            // the station voice, one line at a time (lines it has read before are reused)
            val clips = mutableListOf<StingerMixer.Clip>()
            try {
                for ((i, line) in t.lines.withIndex()) clips.add(StingerMixer.Clip(read(texts[i]), line.at, line.echo))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                failedAt = System.currentTimeMillis()
                log("[couldn't make the stinger: ${e.message}]")
                return null
            }

            // mix it away from the main thread
            val out = File(dir, "stinger_${t.id}.wav")
            val problem: String? = withContext(Dispatchers.Default) {
                try {
                    val bed = Pcm.decodeAsset(Engine.app, "stationbeds/stationbed_${t.id}.m4a")
                    StingerMixer.render(bed, clips, t.voiceDB, out)
                    null
                } catch (e: Exception) {
                    e.message ?: e.toString()
                }
            }
            if (problem != null) {
                failedAt = System.currentTimeMillis()
                log("[couldn't mix the stinger: $problem]")
                return null
            }
            failedAt = 0L
            prune()
            log("[stinger ready: ${made(dir).size} of 6 for ${Station.full(station)}]")
            return out
        } finally {
            making.remove(key)
        }
    }

    /** One line in the station voice, recorded once and kept. */
    private suspend fun read(text: String): File {
        val v = voice()
        val dir = linesFolder
        dir.mkdirs()
        val file = File(dir, hash(v + "|" + Config.elevenModel + "|" + text) + ".mp3")
        if (file.exists() && file.length() > 0) {
            // keep the lines every station uses from being tidied away
            file.setLastModified(System.currentTimeMillis())
            return file
        }
        val data = elevenLabsTTS(text, v, announcer = true)
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeBytes(data)
        if (!tmp.renameTo(file)) { file.writeBytes(data); tmp.delete() }
        return file
    }

    /** Keeps the twenty stations used most recently, and the reads they need. */
    private fun prune() {
        fun newestFirst(d: File): List<File> = (d.listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }
        for (u in newestFirst(File(root, "stations")).drop(20)) u.deleteRecursively()
        for (u in newestFirst(linesFolder).drop(400)) u.delete()
    }
}

/** The mixing desk: EQ, compression and echo on the new voice, laid over the original music. */
object StingerMixer {
    class Clip(val file: File, val at: Double, val echo: Boolean)

    const val RATE = 44100

    fun render(bed: Pcm.Sound, clips: List<Clip>, voiceDB: Float, out: File) {
        var bedL = bed.channels.firstOrNull() ?: FloatArray(0)
        var bedR = if (bed.channels.size > 1) bed.channels[1] else bedL
        if (bed.rate != RATE) {
            bedL = Pcm.resample(bedL, bed.rate, RATE)
            bedR = if (bed.channels.size > 1) Pcm.resample(bedR, bed.rate, RATE) else bedL
        }
        val bedSeconds = bedL.size.toDouble() / RATE
        val placed = mutableListOf<Pair<Int, FloatArray>>()
        var prevEnd = 0.0
        for ((i, c) in clips.withIndex()) {
            val s = Pcm.decodeFile(c.file)
            var v = trim(Pcm.resample(Pcm.mono(s), s.rate, RATE))
            if (v.isEmpty()) continue
            // a line much longer than its spot in the original gets read a touch faster
            val nextAt = if (i + 1 < clips.size) clips[i + 1].at else bedSeconds
            val room = max(0.4, nextAt - c.at - 0.05)
            val length = v.size.toDouble() / RATE
            if (length > room * 1.08) v = stretch(v, min(1.3, length / room).toFloat())
            voiceChain(v)
            val dry = v.size.toDouble() / RATE
            val start = max(c.at, prevEnd + 0.04)
            if (c.echo) v = echo(v, if (dry <= 0.75) dry + 0.04 else 0.42)
            placed.add(Pair((start * RATE).toInt(), v))
            prevEnd = start + dry
        }
        if (placed.isEmpty()) throw Exception("Stinger: there was no voice to mix")
        val lastEnd = placed.maxOf { it.first + it.second.size }
        val total = max(bedL.size, lastEnd + (0.05 * RATE).toInt())
        val voice = FloatArray(total)
        for ((start, samples) in placed) {
            for (k in samples.indices) {
                val at = start + k
                if (at < total) voice[at] += samples[k]
            }
        }
        // the new voice sits in the music exactly as loud as the old one did
        val gain = 10f.pow((voiceDB - activeDB(voice)) / 20f)
        val left = FloatArray(total)
        val right = FloatArray(total)
        var peak = 0f
        for (k in 0 until total) {
            val v = voice[k] * gain
            left[k] = (if (k < bedL.size) bedL[k] else 0f) + v
            right[k] = (if (k < bedR.size) bedR[k] else 0f) + v
            peak = max(peak, max(abs(left[k]), abs(right[k])))
        }
        if (peak > 0.97f) {
            val sc = 0.97f / peak
            for (k in 0 until total) { left[k] *= sc; right[k] *= sc }
        }
        val fade = min(total, (0.03 * RATE).toInt())
        for (k in 0 until fade) {
            val f = k.toFloat() / fade
            left[total - 1 - k] *= f
            right[total - 1 - k] *= f
        }
        out.parentFile?.mkdirs()
        Pcm.writeWav(left, right, RATE, out)
    }

    // ---------------------------------------------------------------- the effects
    /** Cuts the quiet before and after the words. */
    fun trim(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        if (peak <= 0f) return FloatArray(0)
        val gate = peak * 10f.pow(-45f / 20f)
        var first = 0
        while (first < x.size && abs(x[first]) <= gate) first++
        var last = x.size - 1
        while (last > first && abs(x[last]) <= gate) last--
        if (first >= x.size) return FloatArray(0)
        val s = max(0, first - (0.01 * RATE).toInt())
        val e = min(x.size, last + (0.03 * RATE).toInt())
        return x.copyOfRange(s, e)
    }

    /** The radio sound the original voice has: less mud, more sparkle, evened out. */
    fun voiceChain(x: FloatArray) {
        Biquad.highPass(100.0).run(x)
        Biquad.peak(400.0, -4.0, 0.8).run(x)
        Biquad.highShelf(2500.0, 4.5).run(x)
        compress(x)
    }

    fun compress(x: FloatArray, thresholdDB: Float = -20f, ratio: Float = 3f, attack: Double = 0.004, release: Double = 0.09) {
        val ca = exp(-1.0 / (attack * RATE)).toFloat()
        val cr = exp(-1.0 / (release * RATE)).toFloat()
        var env = 0f
        for (i in x.indices) {
            val a = abs(x[i])
            env = if (a > env) ca * env + (1 - ca) * a else cr * env + (1 - cr) * a
            val level = 20f * log10(env + 1e-9f)
            if (level > thresholdDB) x[i] *= 10f.pow((thresholdDB - level) * (1 - 1 / ratio) / 20f)
        }
    }

    /** The echo on the key words: repeats that fade and darken. */
    fun echo(x: FloatArray, delay: Double, feedback: Float = 0.45f, wet: Float = 0.55f, lowPass: Double = 4500.0): FloatArray {
        val d = max(1, (delay * RATE).toInt())
        val n = x.size + d * 6
        val line = FloatArray(n)
        val y = FloatArray(n)
        val c = exp(-2.0 * PI * lowPass / RATE).toFloat()
        var z = 0f
        for (i in 0 until n) {
            val o = if (i >= d) line[i - d] else 0f
            z = (1 - c) * o + c * z
            val input = if (i < x.size) x[i] else 0f
            line[i] = input + feedback * z
            y[i] = input + wet * z
        }
        return y
    }

    /** How loud it is while there's talking (dBFS). */
    fun activeDB(x: FloatArray): Float {
        val hop = (0.02 * RATE).toInt()
        val frames = ArrayList<Float>()
        var k = 0
        while (k + hop <= x.size) {
            var s = 0f
            for (i in k until k + hop) s += x[i] * x[i]
            frames.add(sqrt(s / hop))
            k += hop
        }
        val top = frames.maxOrNull() ?: return -120f
        if (top <= 0f) return -120f
        val gate = top * 10f.pow(-35f / 20f)
        val loud = frames.filter { it > gate }
        val ms = loud.fold(0f) { acc, v -> acc + v * v } / max(1, loud.size)
        return 10f * log10(ms + 1e-12f)
    }

    /** Reads a line faster without making it squeaky (overlap-add, lined up on the waveform so it stays smooth). */
    fun stretch(x: FloatArray, speed: Float): FloatArray {
        if (speed <= 1.01f || x.isEmpty()) return x
        val n = (0.03 * RATE).toInt()                 // 30 ms frames
        val hs = n / 2                                 // written every 15 ms
        val ha = (hs * speed).toInt()                  // read every 15 ms × speed
        val tol = (0.008 * RATE).toInt()               // how far it may look for the best fit
        if (x.size < n * 2) return x
        val want = (x.size / speed).toInt()
        val out = FloatArray(want + n)
        val norm = FloatArray(want + n)
        val win = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / (n - 1))).toFloat() }
        var prev = 0                                   // where the last copied frame started in x
        var k = 0
        while (true) {
            val outPos = k * hs
            if (outPos + n > out.size) break
            val target = k * ha
            var best = target
            if (k > 0) {
                // the natural continuation of what was just written
                val nat = prev + hs
                var bestScore = Float.NEGATIVE_INFINITY
                val lo = max(0, target - tol)
                val hi = min(x.size - n, target + tol)
                if (hi < lo || nat + hs > x.size) break
                var cand = lo
                while (cand <= hi) {
                    var s = 0f
                    var j = 0
                    while (j < hs) {
                        s += x[cand + j] * x[nat + j]
                        j += 2
                    }
                    if (s > bestScore) { bestScore = s; best = cand }
                    cand += 2
                }
            }
            if (best + n > x.size) break
            for (j in 0 until n) {
                out[outPos + j] += x[best + j] * win[j]
                norm[outPos + j] += win[j]
            }
            prev = best
            k++
        }
        val len = min(want, out.size)
        val y = FloatArray(len)
        for (i in 0 until len) y[i] = if (norm[i] > 1e-3f) out[i] / norm[i] else 0f
        return if (y.size > want / 2) y else x
    }

    class Biquad(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        private val b0 = (b0 / a0).toFloat()
        private val b1 = (b1 / a0).toFloat()
        private val b2 = (b2 / a0).toFloat()
        private val a1 = (a1 / a0).toFloat()
        private val a2 = (a2 / a0).toFloat()
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun run(x: FloatArray) {
            for (i in x.indices) {
                val v = x[i]
                val o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = v
                y2 = y1; y1 = o
                x[i] = o
            }
        }

        companion object {
            fun highPass(f: Double, q: Double = 0.707): Biquad {
                val w = 2 * PI * f / RATE
                val c = cos(w)
                val al = sin(w) / (2 * q)
                return Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al)
            }

            fun peak(f: Double, db: Double, q: Double): Biquad {
                val a = 10.0.pow(db / 40)
                val w = 2 * PI * f / RATE
                val c = cos(w)
                val al = sin(w) / (2 * q)
                return Biquad(1 + al * a, -2 * c, 1 - al * a, 1 + al / a, -2 * c, 1 - al / a)
            }

            fun highShelf(f: Double, db: Double): Biquad {
                val a = 10.0.pow(db / 40)
                val w = 2 * PI * f / RATE
                val c = cos(w)
                val s = sin(w)
                val al = s / 2 * sqrt(2.0)
                val sq = 2 * sqrt(a) * al
                return Biquad(a * ((a + 1) + (a - 1) * c + sq), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sq),
                    (a + 1) - (a - 1) * c + sq, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sq)
            }
        }
    }
}
