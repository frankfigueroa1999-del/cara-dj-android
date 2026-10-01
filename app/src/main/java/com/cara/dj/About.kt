package com.cara.dj

import org.json.JSONObject

data class PopTrack(val id: String, val name: String, val artists: String, val uri: String, val art: String)

data class About(
    val key: String = "",
    val song: String = "",
    val bio: String = "",
    val followers: Long = 0,
    val genres: List<String> = emptyList(),
    val artistImage: String = "",
    val artistUrl: String = "",
    val label: String = "",
    val copyright: String = "",
    val pop: List<PopTrack> = emptyList(),
)

/** Everything under the lyrics: about the song, about the artist, popular tracks, credits. */
suspend fun loadAbout(t: Track): About {
    val cleanTitle = t.title.replace(Regex("\\s*[\\(\\[-].*$"), "").trim().ifEmpty { t.title }
    val first = t.artist.split(" ").firstOrNull() ?: t.artist
    val bio = try { wikiLookup("${t.artist} musician band singer", listOf(t.artist)) } catch (e: Exception) { null } ?: ""
    var song = try { wikiLookup("\"$cleanTitle\" ${t.artist} song", listOf(cleanTitle, first)) } catch (e: Exception) { null } ?: ""
    if (song == bio) song = ""
    var out = About(key = t.uri, song = song, bio = bio)
    if (t.artistId.isNotEmpty()) {
        val (st, data) = Spotify.call("GET", "/artists/" + t.artistId)
        if (st == 200) {
            val j = JSONObject(String(data))
            val g = j.optJSONArray("genres")
            val imgs = j.optJSONArray("images")
            out = out.copy(
                followers = j.optJSONObject("followers")?.optLong("total", 0) ?: 0,
                genres = (0 until (g?.length() ?: 0)).map { g!!.optString(it) }.take(4),
                artistImage = if (imgs != null && imgs.length() > 0) imgs.optJSONObject(0)?.optString("url", "") ?: "" else "",
                artistUrl = j.optJSONObject("external_urls")?.optString("spotify", "") ?: "",
            )
        }
        val (st2, data2) = Spotify.call("GET", "/artists/" + t.artistId + "/top-tracks", mapOf("market" to "US"))
        if (st2 == 200) {
            val arr = JSONObject(String(data2)).optJSONArray("tracks")
            val list = (0 until minOf(5, arr?.length() ?: 0)).mapNotNull { i ->
                val x = arr!!.optJSONObject(i) ?: return@mapNotNull null
                val ar = x.optJSONArray("artists")
                val names = (0 until (ar?.length() ?: 0)).joinToString(", ") { ar!!.optJSONObject(it)?.optString("name", "") ?: "" }
                val im = x.optJSONObject("album")?.optJSONArray("images")
                PopTrack(x.optString("id"), x.optString("name"), names, x.optString("uri"), if (im != null && im.length() > 0) im.optJSONObject(im.length() - 1)?.optString("url", "") ?: "" else "")
            }
            out = out.copy(pop = list)
        }
    }
    if (t.albumId.isNotEmpty()) {
        val (st, data) = Spotify.call("GET", "/albums/" + t.albumId)
        if (st == 200) {
            val j = JSONObject(String(data))
            out = out.copy(label = j.optString("label", ""), copyright = j.optJSONArray("copyrights")?.optJSONObject(0)?.optString("text", "") ?: "")
        }
    }
    return out
}
