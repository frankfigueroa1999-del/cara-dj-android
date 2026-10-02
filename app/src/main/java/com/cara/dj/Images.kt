package com.cara.dj

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.math.max
import kotlin.math.min

/** Downloads cover art once, shrinks it to the size it's shown at, and keeps it (in memory, and on the phone),
 *  so lists scroll smoothly and covers appear instantly the second time. */
object ImageCache {
    private val mem = object : LruCache<String, Bitmap>(72 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, Deferred<Bitmap?>>()
    private var dir: File? = null
    private var writes = 0

    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "covers").also { it.mkdirs() }
    }

    private fun key(url: String, px: Int) = "$px|$url"

    fun cached(url: String, px: Int): Bitmap? {
        if (url == Silence.LOGO) return logo(px)
        return mem.get(key(url, px))
    }

    /** Cara's own artwork, drawn on the phone (no download). */
    private fun logo(px: Int): Bitmap {
        val k = key(Silence.LOGO, px)
        mem.get(k)?.let { return it }
        val b = CaraArt.image(px)
        mem.put(k, b)
        return b
    }

    /** The picture at this size. A download carries on even if the page that asked for it goes away,
     *  so the next page to ask gets it straight away. */
    suspend fun load(url: String, px: Int): Bitmap? {
        if (url.isEmpty()) return null
        if (url == Silence.LOGO) return logo(px)
        val k = key(url, px)
        mem.get(k)?.let { return it }
        val job = inflight.getOrPut(k) {
            Engine.scope.async(Dispatchers.IO) {
                try {
                    val data = bytes(url)
                    val img = if (data == null) null else downsample(data, px)
                    if (img != null) mem.put(k, img)
                    img
                } catch (e: Exception) {
                    null
                } finally {
                    inflight.remove(k)
                }
            }
        }
        return job.await()
    }

    private fun fileFor(url: String): File? {
        val d = dir ?: return null
        val h = MessageDigest.getInstance("MD5").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(d, h)
    }

    /** The picture's bytes, from the phone if we've had it before. */
    private suspend fun bytes(url: String): ByteArray? {
        val f = fileFor(url)
        if (f != null && f.exists() && f.length() > 0) {
            try { return f.readBytes().also { f.setLastModified(System.currentTimeMillis()) } } catch (e: Exception) { }
        }
        val (st, data) = fetchBytes(url, timeout = 15000)
        if (st !in 200..299 || data.isEmpty()) return null
        if (f != null) {
            try {
                f.writeBytes(data)
                if (++writes % 50 == 0) prune()
            } catch (e: Exception) { }
        }
        return data
    }

    /** Keeps the 1500 most recently used covers on the phone. */
    private fun prune() {
        val files = dir?.listFiles() ?: return
        if (files.size <= 1500) return
        files.sortedByDescending { it.lastModified() }.drop(1500).forEach { it.delete() }
    }

    fun downsample(data: ByteArray, px: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val longest = max(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return null
        var sample = 1
        while (longest / (sample * 2) >= px) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val b = BitmapFactory.decodeByteArray(data, 0, data.size, opts) ?: return null
        val big = max(b.width, b.height)
        if (big <= px) return b
        val s = px.toFloat() / big
        return Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true)
    }
}

/** A cover / artist picture with a soft placeholder, that fades in when it arrives. */
@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, px: Int = 300, corner: Dp = 8.dp, circle: Boolean = false) {
    val shape = if (circle) CircleShape else RoundedCornerShape(corner)
    var bmp by remember(url, px) { mutableStateOf(if (url.isNullOrEmpty()) null else ImageCache.cached(url, px)) }
    LaunchedEffect(url, px) {
        if (bmp == null && !url.isNullOrEmpty()) bmp = ImageCache.load(url, px)
    }
    Box(
        modifier
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.06f))))
            .border(0.5.dp, Color.White.copy(alpha = 0.09f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = bmp, animationSpec = tween(250), label = "art") { b ->
            if (b != null) {
                val img = remember(b) { b.asImageBitmap() }
                Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val side = max(10f, min(maxWidth.value, maxHeight.value) * 0.32f)
                    Icon(if (circle) Icons.Filled.Mic else Icons.Filled.MusicNote, contentDescription = null,
                        tint = Color.White.copy(alpha = 0.4f), modifier = Modifier.size(side.dp))
                }
            }
        }
    }
}

/** Cara's "cover" while she's on the air: the station's waveform logo, glowing on her colours. */
object CaraArt {
    private val bars = floatArrayOf(0.35f, 0.65f, 1.0f, 0.55f, 0.85f, 0.45f, 0.7f)

    fun image(px: Int): Bitmap {
        val side = min(max(px, 64), 1200)
        val b = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val s = side.toFloat()
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, s, s,
            intArrayOf(android.graphics.Color.rgb(250, 51, 92), android.graphics.Color.rgb(107, 38, 158), android.graphics.Color.rgb(18, 10, 41)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, s, p)
        // a soft glow behind the logo
        p.shader = RadialGradient(s / 2, s / 2, s * 0.42f, intArrayOf(android.graphics.Color.argb(56, 255, 255, 255), android.graphics.Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, s, p)
        p.shader = null
        p.color = android.graphics.Color.WHITE
        val count = bars.size
        val width = s * 0.46f
        val gap = width / (count * 2 - 1)
        val tall = s * 0.32f
        val x0 = (s - width) / 2
        for (i in 0 until count) {
            val h = tall * bars[i]
            val r = RectF(x0 + i * gap * 2, s / 2 - h / 2, x0 + i * gap * 2 + gap, s / 2 + h / 2)
            c.drawRoundRect(r, gap / 2, gap / 2, p)
        }
        return b
    }
}

/** The colours behind the pages and the big player. */
object ArtColors {
    /** A small, soft, slightly richer copy of a cover, made to be stretched across a whole page,
     *  plus how bright it is (bright covers get darkened more, so white text always reads). */
    fun ambient(src: Bitmap): Pair<Bitmap, Float> {
        val w = 36
        val h = max(1, (src.height * w.toFloat() / max(1, src.width)).toInt())
        val small = Bitmap.createScaledBitmap(src, w, h, true)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(1.3f) }
        Canvas(out).drawBitmap(small, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) })
        boxBlur(out, 2, 3)
        return Pair(out, brightness(out))
    }

    /** A quick blur of a tiny picture (so it stretches across a page without blocks). */
    private fun boxBlur(b: Bitmap, radius: Int, passes: Int) {
        val w = b.width
        val h = b.height
        val px = IntArray(w * h)
        b.getPixels(px, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        fun pass(src: IntArray, dst: IntArray, horizontal: Boolean) {
            val outer = if (horizontal) h else w
            val inner = if (horizontal) w else h
            for (o in 0 until outer) {
                for (i in 0 until inner) {
                    var r = 0; var g = 0; var bl = 0; var n = 0
                    for (k in -radius..radius) {
                        val j = (i + k).coerceIn(0, inner - 1)
                        val c = if (horizontal) src[o * w + j] else src[j * w + o]
                        r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF; n++
                    }
                    val idx = if (horizontal) o * w + i else i * w + o
                    dst[idx] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (bl / n)
                }
            }
        }
        repeat(passes) {
            pass(px, tmp, true)
            pass(tmp, px, false)
        }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** 0 = black, 1 = white. */
    fun brightness(b: Bitmap): Float {
        val one = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
        val r = android.graphics.Color.red(one) / 255f
        val g = android.graphics.Color.green(one) / 255f
        val bl = android.graphics.Color.blue(one) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * bl
    }

    /** The cover's average colour, for tinting cards. */
    fun average(b: Bitmap): Color {
        val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
        return Color(android.graphics.Color.red(px) / 255f * 0.8f, android.graphics.Color.green(px) / 255f * 0.8f,
            android.graphics.Color.blue(px) / 255f * 0.8f)
    }
}

/** A small, very soft copy of the cover that's playing, shared by every page. */
object Ambience {
    var image by mutableStateOf<Bitmap?>(null)
        private set
    var brightness by mutableStateOf(0.3f)
        private set
    var key by mutableStateOf("")
        private set
    private var wanted = ""
    private val made = mutableMapOf<String, Pair<Bitmap, Float>>()

    /** Follow the cover of what's playing. Keeps the last colour when nothing is. */
    suspend fun follow(art: String) {
        wanted = art
        if (art.isEmpty() || art == key) return
        val a = make(art) ?: return
        if (wanted != art) return
        image = a.first
        brightness = a.second
        key = art
    }

    suspend fun make(art: String): Pair<Bitmap, Float>? {
        made[art]?.let { return it }
        val src = ImageCache.load(art, 300) ?: return null
        val out = withContext(Dispatchers.Default) { ArtColors.ambient(src) }
        if (made.size > 60) made.clear()
        made[art] = out
        return out
    }
}
