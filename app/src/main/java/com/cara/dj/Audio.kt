package com.cara.dj

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/** One thing to play: either a file Cara's voice was saved to, or a stinger that ships inside the app. */
class Clip(val file: File? = null, val asset: String? = null, val volume: Float = 1f)

/** Plays Cara's clips and your stingers, and asks Android to turn the Spotify app down while they play. */
class DJAudio(private val ctx: Context) {
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun stingers(): List<String> = try {
        (ctx.assets.list("stingers") ?: emptyArray()).filter { it.lowercase().endsWith(".mp3") }.sorted()
    } catch (e: Exception) {
        emptyList()
    }

    /** Plays each clip in order (never overlapping), with other apps ducked while it happens. */
    suspend fun speak(clips: List<Clip>) {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(AudioManager.OnAudioFocusChangeListener { })
            .build()
        am.requestAudioFocus(req)
        try {
            delay(250)                                   // let Spotify fade down first
            for (c in clips) play(c)
        } finally {
            am.abandonAudioFocusRequest(req)             // Spotify comes back up
        }
    }

    private suspend fun play(c: Clip) {
        val mp = MediaPlayer()
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            withContext(Dispatchers.IO) {
                if (c.file != null) {
                    mp.setDataSource(c.file.absolutePath)
                } else if (c.asset != null) {
                    ctx.assets.openFd("stingers/" + c.asset).use { mp.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                }
                mp.prepare()
            }
            val v = maxOf(0f, minOf(1f, c.volume))
            mp.setVolume(v, v)
            val dur = mp.duration
            withTimeoutOrNull(dur + 3000L) {
                suspendCancellableCoroutine<Unit> { cont ->
                    mp.setOnCompletionListener { if (cont.isActive) cont.resume(Unit) }
                    mp.setOnErrorListener { _, _, _ -> if (cont.isActive) cont.resume(Unit); true }
                    mp.start()
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // skip a clip that can't be played
        } finally {
            try { mp.release() } catch (e: Exception) { }
        }
    }

    companion object {
        fun duration(f: File): Int = try {
            val r = MediaMetadataRetriever()
            r.setDataSource(f.absolutePath)
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toInt() ?: 8000
            r.release()
            d
        } catch (e: Exception) {
            8000
        }
    }
}
