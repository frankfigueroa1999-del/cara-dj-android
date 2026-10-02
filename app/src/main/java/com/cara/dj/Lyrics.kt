package com.cara.dj

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class LyricLine(val timeMs: Int, val text: String)

/** What we found for a song: timed lines, or plain text, or a message saying why not. */
data class LyricsResult(val lines: List<LyricLine> = emptyList(), val plain: String = "", val message: String = "")

/** Looks up lyrics on LRCLIB (a free, open lyrics database; no key needed). */
object Lyrics {
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    suspend fun find(track: Track, durationMs: Int): LyricsResult {
        var synced = ""
        var unsynced = ""
        try {
            val secs = maxOf(1, durationMs / 1000)
            val url = "https://lrclib.net/api/get?track_name=${enc(track.title)}&artist_name=${enc(track.artist)}" +
                "&album_name=${enc(track.album)}&duration=$secs"
            val (code, data) = fetchBytes(url, timeout = 12000)
            if (code == 200) {
                val o = JSONObject(String(data))
                synced = o.optString("syncedLyrics", "").let { if (it == "null") "" else it }
                unsynced = o.optString("plainLyrics", "").let { if (it == "null") "" else it }
            }
        } catch (e: Exception) { }
        if (synced.isEmpty() && unsynced.isEmpty()) {
            try {
                val url = "https://lrclib.net/api/search?track_name=${enc(track.title)}&artist_name=${enc(track.artist)}"
                val (code, data) = fetchBytes(url, timeout = 12000)
                if (code == 200) {
                    val arr = JSONArray(String(data))
                    for (i in 0 until arr.length()) {
                        val o = arr.getJSONObject(i)
                        val s = o.optString("syncedLyrics", "").let { if (it == "null") "" else it }
                        val p = o.optString("plainLyrics", "").let { if (it == "null") "" else it }
                        if (s.isNotEmpty()) { synced = s; break }
                        if (unsynced.isEmpty() && p.isNotEmpty()) unsynced = p
                    }
                }
            } catch (e: Exception) { }
        }
        return when {
            synced.isNotEmpty() -> LyricsResult(lines = parse(synced))
            unsynced.isNotEmpty() -> LyricsResult(plain = unsynced)
            else -> LyricsResult(message = "No lyrics found for this song.")
        }
    }

    private val words = mutableMapOf<String, String>()          // "title|artist" -> lyrics ("" when there are none)

    /** A song's lyrics as plain text, so the DJs can tell what it's about when they read the room.
     *  Never shown, read out or quoted on air. Looked up once per song. */
    suspend fun text(track: Track): String? {
        if (!track.isMusic) return null
        val key = track.title + "|" + track.artist
        synchronized(words) { words[key] }?.let { return it.ifEmpty { null } }
        val r = find(track, track.durationMs)
        val out = r.plain.ifEmpty { r.lines.map { it.text }.filter { it.isNotEmpty() }.joinToString("\n") }
        synchronized(words) {
            if (words.size > 200) words.clear()
            words[key] = out
        }
        return out.ifEmpty { null }
    }

    /** Turns "[01:23.45] some words" lines into timed lines. */
    fun parse(lrc: String): List<LyricLine> {
        val out = mutableListOf<LyricLine>()
        for (raw in lrc.lines()) {
            var rest = raw
            val stamps = mutableListOf<Int>()
            while (rest.startsWith("[")) {
                val close = rest.indexOf(']')
                if (close < 0) break
                val parts = rest.substring(1, close).split(":")
                if (parts.size == 2) {
                    val m = parts[0].toIntOrNull()
                    val s = parts[1].toDoubleOrNull()
                    if (m != null && s != null) stamps.add(m * 60000 + (s * 1000).toInt())
                }
                rest = rest.substring(close + 1)
            }
            val text = rest.trim()
            for (ms in stamps) out.add(LyricLine(ms, text))
        }
        return out.sortedBy { it.timeMs }
    }
}
