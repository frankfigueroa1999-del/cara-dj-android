package com.cara.dj

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/** "About this song": the story of the song and the artist (from Wikipedia), plus the credits. */
data class About(
    val key: String = "",
    val song: String = "",
    val bio: String = "",
    val genres: List<String> = emptyList(),
    val artistImage: String = "",
    val copyright: String = "",
)

private val titleTailRx = Regex("""\s*[(\[-].*$""")

suspend fun loadAbout(t: Track): About = coroutineScope {
    if (!t.isMusic) return@coroutineScope About(key = t.uri)          // Cara herself, adverts, jingles
    val clean = t.title.replace(titleTailRx, "").trim().ifEmpty { t.title }
    val first = t.artist.split(" ").firstOrNull() ?: t.artist
    val songTxt = async { try { wikiLookup("\"$clean\" ${t.artist} song", listOf(clean, first)) } catch (e: Exception) { null } }
    val bioTxt = async { try { wikiLookup("${t.artist} musician band singer", listOf(t.artist)) } catch (e: Exception) { null } }
    val artistR = async { if (t.artistId.isEmpty()) null else Spotify.getJSON("/artists/" + t.artistId) }
    val albumR = async { if (t.albumId.isEmpty()) null else Spotify.getJSON("/albums/" + t.albumId) }
    val bio = bioTxt.await() ?: ""
    val s = songTxt.await() ?: ""
    val a: JSONObject? = artistR.await()
    val al: JSONObject? = albumR.await()
    About(
        key = t.uri,
        song = if (s == bio) "" else s,
        bio = bio,
        genres = a?.optJSONArray("genres").strings().take(4),
        artistImage = if (a != null) Img.pick(a.optJSONArray("images")).first else "",
        copyright = al?.optJSONArray("copyrights")?.optJSONObject(0)?.str("text") ?: "",
    )
}

@Composable
fun InfoCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(18.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        content()
    }
}
