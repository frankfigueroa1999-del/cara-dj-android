package com.cara.dj

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * Cara's brain, the same as the iPhone and PC apps: what she talks about (43 segments), how she says it
 * (styles, openings, landings, voice tags), how long she talks, a memory that keeps her from repeating
 * herself (saved on the phone), the station named after whatever's playing, and breaks with her co-host
 * MC Scratch. The lists live in assets/brain_data.json, shared with the other apps.
 */

data class Topic(val label: String, val facts: String, val plain: String? = null, val name: String = label)
data class Ctx(
    val last: Track?,
    val next: Track?,
    /** The station's name right now: whatever's playing ("Late Night Drives"), or Non Stop Pop. */
    val station: String = Station.FALLBACK,
    /** What it's named after, in her words ("the playlist "Late Night Drives""). */
    val stationNote: String = "",
    /** The station's old name, when the listener switched to something else since her last break. */
    val switchedFrom: String? = null,
) {
    val stationFull: String get() = Station.full(station)
}

class Segment(val id: String, val name: String, val family: String, val weight: Int)
class DuoSegment(val id: String, val name: String, val base: String?, val angle: String, val weight: Int)

object Brain {
    lateinit var segments: List<Segment>
    lateinit var duoSegments: List<DuoSegment>
    lateinit var formats: List<List<String>>      // [id, how]
    lateinit var openings: List<List<String>>     // [id, how, needs]
    lateinit var popinKinds: List<List<String>>   // [id, how]
    lateinit var quiz: List<List<String>>         // [question, answer]
    lateinit var vocab: List<List<String>>        // [word, meaning] for word of the day
    lateinit var holidays: Map<String, String>
    private lateinit var lists: Map<String, List<String>>
    var persona = ""
    var rules = ""
    var coName = "MC Scratch"
    var coShort = "Scratch"
    var coLabel = "SCRATCH"
    var coPersona = ""
    var coBible = ""
    var coIdentity = ""
    var whoIsWho = ""
    var coDefaultVoice = "nPczCjzI2devNBz1zQrb"
    private var coLanguageOn = ""
    private var coLanguageOff = ""
    private var ready = false
    val isReady: Boolean get() = ready

    fun list(name: String): List<String> = lists[name] ?: emptyList()
    val tags: List<String> get() = list("tags")
    val endings: List<String> get() = list("endings")

    fun init(ctx: Context) {
        if (ready) return
        val text = ctx.assets.open("brain_data.json").use { it.readBytes().toString(Charsets.UTF_8) }
        val d = JSONObject(text)
        fun strs(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).map { a.optString(it, "") }
        fun rows(name: String): List<List<String>> {
            val a = d.optJSONArray(name) ?: return emptyList()
            return (0 until a.length()).map { strs(a.optJSONArray(it)) }
        }
        val segs = d.getJSONArray("segments")
        segments = (0 until segs.length()).map {
            val o = segs.getJSONObject(it)
            Segment(o.getString("id"), o.getString("name"), o.optString("family", ""), o.optInt("weight", 1))
        }
        val duo = d.getJSONArray("duoSegments")
        duoSegments = (0 until duo.length()).map {
            val o = duo.getJSONObject(it)
            DuoSegment(o.getString("id"), o.getString("name"), if (o.isNull("base")) null else o.optString("base", "").ifEmpty { null },
                o.getString("angle"), o.optInt("weight", 1))
        }
        formats = rows("formats")
        openings = rows("openings")
        popinKinds = rows("popinKinds")
        quiz = rows("quiz")
        vocab = rows("words")
        val h = d.optJSONObject("holidays")
        val hm = mutableMapOf<String, String>()
        if (h != null) for (k in h.keys()) hm[k] = h.optString(k, "")
        holidays = hm
        val l = mutableMapOf<String, List<String>>()
        for (k in listOf("endings", "tags", "lore", "confessions", "opinions", "fakeAds", "roasts", "wouldYouRather", "dilemmas",
                "challenges", "pepTalks", "hypotheticals", "funFacts", "signs", "yakima", "duoEndings", "coLore", "coMoves", "stopWords")) {
            l[k] = strs(d.optJSONArray(k))
        }
        lists = l
        persona = d.optString("persona", "")
        rules = d.optString("rules", "")
        coName = d.optString("coName", coName)
        coShort = d.optString("coShort", coName)
        coLabel = d.optString("coLabel", coName.uppercase())
        coPersona = d.optString("coPersona", "")
        coBible = d.optString("coBible", "")
        coIdentity = d.optString("coIdentity", "")
        whoIsWho = d.optString("whoIsWho", "")
        coDefaultVoice = d.optString("coDefaultVoice", coDefaultVoice)
        coLanguageOn = d.optString("coLanguageOn", "")
        coLanguageOff = d.optString("coLanguageOff", "HIS LANGUAGE: clean, no swearing. Cara never swears either.")
        stopWords = list("stopWords").toSet()
        Memory.load(File(ctx.filesDir, "cara-memory.json"))
        ready = true
    }

    // ------------------------------------------------------------ never repeating herself
    private var stopWords: Set<String> = emptySet()
    private val tagBits = Regex("""\[[^\]]*\]""")
    private val wordSplit = Regex("""[^\p{L}\p{N}_']+""")

    fun words(t: String?): List<String> =
        (t ?: "").lowercase().replace(tagBits, " ").split(wordSplit).filter { it.isNotEmpty() && it != "'" }

    private fun grams(w: List<String>, n: Int, skip: Set<String>): Set<String> {
        val out = mutableSetOf<String>()
        for (i in 0..(w.size - n)) {
            val g = w.subList(i, i + n)
            if (g.all { it in stopWords } || g.any { it in skip }) continue
            out.add(g.joinToString(" "))
        }
        return out
    }

    private fun opener(t: String): String = words(t).take(2).joinToString(" ")

    private fun wornOut(recent: List<String>, skip: Set<String>): List<String> {
        val count = mutableMapOf<String, Int>()
        for (r in recent.takeLast(40)) for (g in grams(words(r), 3, skip)) count[g] = (count[g] ?: 0) + 1
        return count.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.map { it.key }.take(30)
    }

    /** "Slogan" and friends only show up when she reads out what she was asked to do. */
    /** How Scratch talks: gritty when his cursing is on (the default), clean when it's off (Cara's page, Co-Host). */
    fun coLanguage(): String = if (Config.coHostSwears && coLanguageOn.isNotEmpty()) coLanguageOn else coLanguageOff

    private val strongSwears = setOf("ass", "asses", "asshole", "assholes", "bitch", "bitches", "bastard", "bastards",
        "piss", "pissed", "damn", "damned", "dammit", "goddamn", "goddammit")

    /** The curse words in a line (to keep Cara clean, and Scratch too when his cursing is off). */
    fun swears(text: String): List<String> = words(text).map { it.trim('\'') }.filter {
        it.startsWith("fuck") || it.startsWith("motherfuck") || it.startsWith("shit") || it.startsWith("bullshit") || it in strongSwears
    }

    /** Words he doesn't use even with his cursing on: classy, not crude. */
    fun tooFar(text: String): Boolean = swears(text).any { it.startsWith("bitch") || it.startsWith("motherfuck") }

    /** A curse hidden behind asterisks ("sh*t") gets read out as nonsense. */
    private val masked = Regex("""[A-Za-z]\*+[A-Za-z]|\b[A-Za-z]\*{2,}""")

    fun saysLabel(text: String): String? = words(text).firstOrNull { it in setOf("slogan", "slogans", "tagline", "taglines") }

    /** Why a draft can't be used (null when it's fine). */
    fun problem(text: String, recent: List<String>, skip: Set<String>): String? {
        val w = words(text)
        if (w.isEmpty()) return "It was empty."
        if (mentionsDeath(text)) return "It mentioned death or dying (even as a figure of speech). Leave that out completely."
        val first = w[0]
        if (first in setOf("whoa", "woah", "wow", "oh", "ooh", "ah", "shh", "shhh")) return "It opened with '$first'. Open with a real word instead."
        if ("gasp" in w || "sigh" in w || "sighs" in w) return "It used 'gasp' or 'sigh'. Leave those out."
        if (w.any { it.startsWith("snort") || it.startsWith("sniff") }) return "It had a snort or a sniff in it. Leave nose noises out completely."
        saysLabel(text)?.let { return "It said the word '$it'. Never call anything a slogan or tagline: just say the line itself." }
        val op = opener(text)
        if (op.isNotEmpty() && op in recent.takeLast(30).map { opener(it) }) return "It opened with \"$op\", which you've used before. Open completely differently."
        if (first in recent.takeLast(6).map { words(it).firstOrNull() ?: "" }) return "It started with the same first word (\"$first\") as a recent break. Start differently."
        val mine = grams(w, 4, skip)
        for (r in recent.takeLast(40)) {
            val hit = mine.intersect(grams(words(r), 4, skip))
            if (hit.isNotEmpty()) return "It reused the phrase \"${hit.first()}\" from an earlier break. Say it in completely new words."
        }
        return null
    }

    private fun memoryBlock(skip: Set<String>): String {
        val recent = Memory.recent
        if (recent.isEmpty()) return "- This is your first break today. Make it count."
        val lines = mutableListOf("- Your most recent breaks, newest first. Never reuse their openings, jokes, phrases, angles, facts or structure:")
        recent.takeLast(12).reversed().forEachIndexed { i, b -> lines.add("  ${i + 1}. \"$b\"") }
        val ops = recent.takeLast(30).map { opener(it) }.filter { it.isNotEmpty() }.distinct().take(30)
        if (ops.isNotEmpty()) lines.add("- Never start with any of these: " + ops.joinToString(", ") { "\"$it\"" })
        val worn = wornOut(recent, skip)
        if (worn.isNotEmpty()) lines.add("- Phrases you've worn out (don't use them): " + worn.joinToString(", ") { "\"$it\"" })
        return lines.joinToString("\n")
    }

    private fun reusable(ctx: Ctx, extra: Set<String> = emptySet()): Set<String> {
        val s = mutableSetOf("non", "stop", "pop", "cara", "fm", "station")
        s.addAll(extra)
        s.addAll(words(ctx.station + " " + (ctx.switchedFrom ?: "")))
        for (t in listOf(ctx.next, ctx.last)) if (t != null) s.addAll(words("${t.title} ${t.artist} ${t.album}").filter { it.length > 2 })
        s.addAll(words(Config.city))
        return s
    }

    private val tagRegex = Regex("""\[([^\]]*)\]""")
    private val spaces = Regex("""\s{2,}""")

    /** Allowed voice tags stay; anything else in square brackets goes. */
    fun cleanTags(text: String, allowed: Set<String>): Pair<String, List<String>> {
        val used = mutableListOf<String>()
        val out = tagRegex.replace(text) { m ->
            val tag = m.groupValues[1].trim().lowercase()
            if (tag in allowed) { used.add(tag); "[$tag]" } else " "
        }
        return Pair(out.replace(spaces, " ").trim().trim('"').trim(), used)
    }

    // ------------------------------------------------------------ reading the room
    /** The song the listener picked, and what it's about: now and then a DJ makes one quick, playful guess about them from it.
     *  [lyrics] is the whole text (to catch a quoted line), [excerpt] a short piece for the prompt; both null for a heavy song. */
    class SongRead(val track: Track, val lyrics: String?, val excerpt: String?)

    suspend fun songRead(t: Track?): SongRead? {
        if (t == null || !t.isMusic || t.title.isEmpty()) return null
        var full = try { Lyrics.text(t) } catch (e: Exception) { null }
        if (full != null && mentionsDeath(full)) full = null          // a heavy song: they go on the title alone
        val excerpt = full?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(" / ")?.take(900)
        return SongRead(t, full, excerpt)
    }

    /** The town's short name ("Yakima" from "Yakima, Washington"), for "Who hurt you, Yakima?". */
    fun townName(): String = Config.city.split(",").firstOrNull()?.trim()?.ifEmpty { null } ?: Config.city

    /** What the prompt says when they read the room. [which] is "the song that just played", "the song that's starting" and so on. */
    fun readBlock(r: SongRead, which: String, duo: Boolean): String {
        val town = townName()
        val song = "\"${r.track.title}\" by ${r.track.artist}"
        val who = if (duo) "One of them opens" else "Open"
        val after = if (duo) "the other piles on or sticks up for them, then they move on" else "then move straight on"
        var s = "\n- Read the room: the listener picked $which, $song. $who with ONE quick, playful jab about what that choice says about them, going by the title and what the song's about (a heartbreak song: \"Who hurt you, $town?\"; a revenge anthem: \"Remind me never to cross you\"; a love song: \"Somebody's got a crush\"; a hype song: \"Somebody's feeling dangerous today\"), in brand-new words; $after."
        s += "\n- Keep the read light and affectionate, like a friend clocking your playlist: love life, mood, being in your feelings, main-character energy, harmless mischief. Never guess at anything heavy or personal (mental health, drinking or drugs, money trouble, bodies, anything sexual)."
        if (!r.excerpt.isNullOrEmpty()) s += "\n- What the song's about, from its lyrics (only so you know; never quote, sing or closely paraphrase a line): ${r.excerpt}"
        return s
    }

    /** True when a draft quotes the song: five words in a row from its lyrics (the title doesn't count). */
    fun quotesLyrics(text: String, lyrics: String, title: String): Boolean {
        val lw = words(lyrics)
        val w = words(text)
        if (lw.size < 5 || w.size < 5) return false
        val grams = HashSet<String>()
        for (i in 0..lw.size - 5) grams.add(lw.subList(i, i + 5).joinToString(" "))
        val inTitle = " " + words(title).joinToString(" ") + " "
        for (i in 0..w.size - 5) {
            val g = w.subList(i, i + 5)
            if (g.all { it in stopWords }) continue
            val joined = g.joinToString(" ")
            if (joined in grams && !inTitle.contains(" $joined ")) return true
        }
        return false
    }

    /** Whether this break reads the room: about 3 in 10, never two in a row. */
    private fun timeToRead(): Boolean =
        Math.random() < 0.3 && "read" !in Memory.last("openings", 2) && "read" !in Memory.last("popins", 1)

    private fun readWhich(style: String, ctx: Ctx): String =
        if (style == "intro") "the song that's starting" else if (ctx.last != null) "the song that just played" else "the song coming up next"

    /** Asks Gemini, checks the draft against her memory and rules, rewrites up to twice. */
    private suspend fun freshDraft(prompt: String, skip: Set<String>, log: (String) -> Unit, read: SongRead? = null): Pair<String, List<String>>? {
        var feedback = ""
        var best: Pair<String, List<String>>? = null
        for (attempt in 0 until 3) {
            val ask = if (feedback.isEmpty()) prompt else "$prompt\n\nYour previous draft can't be used: $feedback Write a completely new one."
            val raw = gemini(ask, Config.geminiKey, log) ?: break
            val (text, used) = cleanTags(tidy(raw), tags.toSet())
            if (text.isEmpty()) continue
            val lyr = read?.lyrics
            if (read != null && lyr != null && quotesLyrics(text, lyr, read.track.title)) {
                log("[rewrite ${attempt + 1}: quoted the lyrics]")
                feedback = "It quoted the song's lyrics. Never quote them: react to what the song's about in your own words."
                continue
            }
            val why = problem(text, Memory.recent, skip)
            if (why != null) {
                log("[rewrite ${attempt + 1}: $why]")
                feedback = why
                if (best == null && !mentionsDeath(text) && saysLabel(text) == null) best = Pair(text, used)
                continue
            }
            return Pair(text, used)
        }
        return best
    }

    // ------------------------------------------------------------ who she is, and the station
    fun bible(ctx: Ctx): String {
        val now = if (ctx.station == Station.FALLBACK)
            "worked at a string of terrible stations there, and now broadcasts Non Stop Pop FM to listeners far from the coast"
        else "worked at a string of terrible stations there before making her name on Non Stop Pop FM, and these days runs her own station far from the coast, which always takes the name of whatever the listener puts on"
        return "Her backstory (fixed, never contradict it or add big new facts): she's British, moved to Los Santos years ago chasing fame, $now. " +
            "She misses and mocks Los Santos in equal measure (Vinewood, Vespucci Beach, Del Perro Pier, Rockford Hills, Sandy Shores, Mount Chiliad, the endless freeway traffic), and only ever talks about it as a place from her past."
    }

    fun stationLine(ctx: Ctx): String {
        if (ctx.station == Station.FALLBACK) return "THE STATION: Non Stop Pop FM."
        val from = ctx.stationNote.ifEmpty { "\"${ctx.station}\"" }
        return "THE STATION: it's named after whatever the listener is playing, which right now is $from, so on air it's \"${ctx.stationFull}\". " +
            "Use the name when it fits (dropping it in like a real DJ, bragging about it, a cheeky comment on the name), not in every break. Never call it Non Stop Pop: that was her old station, back in Los Santos."
    }

    private val moodLines = mapOf(
        "chill" to "CHILL: laid-back, warm and smooth, with dry wit and fewer exclamation marks. Still playful, never sleepy.",
        "normal" to "NORMAL: her usual bubbly, cheeky, quick self.",
        "unhinged" to "UNHINGED: maximum playful chaos. Over-the-top drama, absurd tangents, gleeful mock outrage and silly voices described in words, but never mean.",
    )
    private val situations = mapOf(
        "silent" to "The music has stopped and the floor is all hers. She launches straight into her segment with confidence (never mention the silence or the music stopping) and brings the next song in at the end.",
        "intro" to "The next song has just started and she's talking over its opening. A quick, punchy drop-in (the song may kick in straight away, so never ramble), and she brings the song in at the end.",
        "talkover" to "The current song is fading out under her voice. She rides the ending and rolls straight into the next song, no goodbyes or sign-offs.",
    )
    private val duoSituations = mapOf(
        "silent" to "The music has stopped and the studio is theirs. They dive straight in (never mention the silence or the music stopping) and bring the next song in at the end.",
        "intro" to "The next song has just started and they're talking over its opening. A quick exchange (the song may kick in straight away, so never ramble), then they let it play.",
        "talkover" to "The current song is fading out under them. They wrap up as it ends and roll straight into the next song, no goodbyes.",
    )

    fun currentMood(): String = if (Config.mood == "mixed") listOf("chill", "normal", "unhinged").random() else Config.mood
    private fun chattiness(): String = Config.chattiness

    fun wordRange(style: String): Pair<Int, Int> {
        val c = chattiness()
        if (style == "intro") return when (c) { "quick" -> Pair(8, 14); "normal" -> Pair(10, 18); else -> Pair(12, 22) }
        val silent = style == "silent"
        return when (c) {
            "quick" -> if (silent) Pair(20, 40) else Pair(12, 28)
            "normal" -> if (silent) Pair(35, 60) else Pair(20, 40)
            else -> if (silent) Pair(55, 95) else Pair(30, 55)
        }
    }

    private fun describe(t: Track?): String = t?.describe ?: "(unknown)"

    // ------------------------------------------------------------ finding something to talk about
    private val worldFeedsB get() = listOf(gnews("bizarre OR weird OR quirky OR \"world record\" OR viral"), "https://rss.upi.com/news/odd_news.rss",
        "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml")

    private suspend fun freshHeadline(feeds: List<String>, extra: Regex? = null): String? {
        val h = getHeadlines(feeds, extra).filter { Memory.isFresh(it) }
        if (h.isEmpty()) return null
        val p = h.random()
        Memory.markUsed(p)
        return p
    }

    private suspend fun forecast(): Triple<Int, Int, Int>? {
        val (st, data) = fetchBytes(
            "https://api.open-meteo.com/v1/forecast?latitude=${Config.lat}&longitude=${Config.lon}&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&temperature_unit=fahrenheit&timezone=auto&forecast_days=1", 8000,
        )
        if (st != 200) return null
        return try {
            val d = JSONObject(String(data)).getJSONObject("daily")
            Triple(Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                d.optJSONArray("precipitation_probability_max")?.optInt(0, 0) ?: 0)
        } catch (e: Exception) { null }
    }

    private val lightWords = Regex("""\b(?:album|song|single|band|film|movie|television|tv|series|premiere|premiered|released|launch|launched|record|game|video game|invented|opened|first|festival|concert|chart|toy|cartoon|comic|museum|zoo|park|space|satellite|moon|computer|internet|website)\b""", RegexOption.IGNORE_CASE)

    private suspend fun onThisDay(): List<String> {
        val c = Calendar.getInstance()
        val mm = (c.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val dd = c.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val (st, data) = fetchBytes("https://en.wikipedia.org/api/rest_v1/feed/onthisday/events/$mm/$dd", 8000)
        if (st != 200) return emptyList()
        return try {
            val ev = JSONObject(String(data)).optJSONArray("events") ?: return emptyList()
            (0 until ev.length()).mapNotNull {
                val e = ev.optJSONObject(it) ?: return@mapNotNull null
                val text = e.optString("text", "")
                val year = e.optInt("year", 0)
                if (text.isEmpty() || year == 0 || text.length >= 240 || !isSafe(text) || mentionsDeath(text) || !lightWords.containsMatchIn(text)) null
                else "On this day in $year: $text"
            }
        } catch (e: Exception) { emptyList() }
    }

    private var statsAt = 0L
    private var topArtists: List<String> = emptyList()
    private var topTracks: List<String> = emptyList()

    private suspend fun listeningStats(): Pair<List<String>, List<String>> {
        if (System.currentTimeMillis() - statsAt < 6 * 3600_000L) return Pair(topArtists, topTracks)
        statsAt = System.currentTimeMillis()
        topArtists = try {
            val (st, data) = Spotify.call("GET", "/me/top/artists", mapOf("limit" to "5", "time_range" to "short_term"))
            if (st != 200) emptyList() else {
                val a = JSONObject(String(data)).optJSONArray("items")
                if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name", "")?.ifEmpty { null } }
            }
        } catch (e: Exception) { emptyList() }
        topTracks = try {
            val (st, data) = Spotify.call("GET", "/me/top/tracks", mapOf("limit" to "5", "time_range" to "short_term"))
            if (st != 200) emptyList() else {
                val a = JSONObject(String(data)).optJSONArray("items")
                if (a == null) emptyList() else (0 until a.length()).mapNotNull {
                    val o = a.optJSONObject(it) ?: return@mapNotNull null
                    val artist = o.optJSONArray("artists")?.optJSONObject(0)?.optString("name", "") ?: ""
                    if (artist.isEmpty()) null else "\"${o.optString("name", "")}\" by $artist"
                }
            }
        } catch (e: Exception) { emptyList() }
        return Pair(topArtists, topTracks)
    }

    private fun songFactsB(t: Track, whenText: String): String {
        var f = "$whenText song is \"${t.title}\" by ${t.artist}"
        if (t.album.isNotEmpty()) f += ", from the album \"${t.album}\""
        if (t.year.isNotEmpty()) f += " (${t.year})"
        return "$f. Use only these facts about it (the album or year are fine), never claim anything else about the artist or song."
    }

    private fun segName(id: String): String = segments.firstOrNull { it.id == id }?.name ?: id

    private val parens = Regex("""\s*\([^)]*\)""")
    private val sentenceEnd = Regex("""(?<=[.!?])\s+""")

    /** The facts for one segment, or null when there's nothing usable right now (she picks something else). */
    suspend fun topicFor(sid: String, ctx: Ctx): Topic? {
        val nxt = ctx.next
        val last = ctx.last
        val song = nxt ?: last
        val city = Config.city
        fun t(f: String, plain: String? = null) = Topic(sid, f, plain, segName(sid))
        when (sid) {
            "next_intro" -> {
                if (nxt == null) return null
                return t(songFactsB(nxt, "The NEXT") + " Introduce it with real excitement and one playful quip about the title, the artist's name or the vibe.",
                    "Up next, ${nxt.artist} with ${nxt.title}.")
            }
            "last_verdict" -> {
                if (last == null) return null
                return t(songFactsB(last, "The song that JUST played") + " Give it a verdict on a ridiculous scale she invents on the spot (like 'nine out of ten rubber ducks') and explain it in one cheeky line.",
                    "That was ${last.artist} with ${last.title}.")
            }
            "trivia", "artist_story" -> {
                for (tr in listOfNotNull(nxt, last)) {
                    val whenText = if (tr === nxt) "The NEXT" else "The song that JUST played"
                    val got = try { getTrivia(tr) } catch (e: Exception) { null }
                    if (got == null || mentionsDeath(got.second)) continue
                    val (subject, text) = got
                    if (sid == "artist_story" && subject != tr.artist) {
                        val bio = try { wikiLookup("${tr.artist} musician band singer", listOf(tr.artist)) } catch (e: Exception) { null }
                        if (bio == null || mentionsDeath(bio)) continue
                        return t("$whenText song is \"${tr.title}\" by ${tr.artist}. Real background on ${tr.artist} (from Wikipedia): \"\"\"$bio\"\"\" " +
                            "Share ONE surprising, specific detail about the artist from that text, in your own words, like you just remembered it. " +
                            "Nothing that isn't in the text; skip anything sad, dark or about scandals.")
                    }
                    val key = "wiki|" + tr.artist + "|" + text.take(60)
                    if (!Memory.isFresh(key, 2.0)) continue
                    Memory.markUsed(key)
                    val sentences = text.replace(parens, "").split(sentenceEnd).drop(1).filter { it.length in 41..199 && !mentionsDeath(it) }
                    return t("$whenText song is \"${tr.title}\" by ${tr.artist}. Real background on $subject (from Wikipedia): \"\"\"$text\"\"\" " +
                        "Share exactly ONE interesting, specific fact from that text that you haven't used before, in your own words. " +
                        "Never add anything that isn't in the text; skip anything sad, dark, or about scandals or lawsuits.",
                        if (sentences.isNotEmpty()) "Fun fact about ${tr.artist}: " + sentences.random() else null)
                }
                return null
            }
            "time_machine" -> {
                val s = song ?: return null
                val y = s.year.toIntOrNull() ?: return null
                val ago = Calendar.getInstance().get(Calendar.YEAR) - y
                val whenText = if (s === nxt) "The NEXT" else "The song that JUST played"
                val age = if (ago <= 0) "it came out this year" else if (ago == 1) "that's one year ago" else "that's $ago years ago"
                return t("$whenText song, \"${s.title}\" by ${s.artist}, came out in $y ($age). Riff on how long ago that feels and what the listener was probably up to back then, playful and general. Invent nothing about the artist.")
            }
            "your_stats" -> {
                val (artists, tracks) = listeningStats()
                if (artists.isEmpty() && tracks.isEmpty()) return null
                var f = "From the listener's own Spotify listening:"
                if (artists.isNotEmpty()) f += " their most-played artists lately are ${artists.take(3).joinToString(", ")}."
                if (tracks.isNotEmpty()) f += " A song they've had on heavy rotation lately: ${tracks[0]}."
                return t("$f Tease them lovingly about it, like she's caught them red-handed. Pick ONE of these to focus on.")
            }
            "hot_take" -> {
                if (song == null) return null
                return t("A playful hot take about the vibe of \"${song.title}\" by ${song.artist}: what weather it belongs to, what it would smell like, or what it's secretly about. It's an opinion, so invent nothing factual about the artist.")
            }
            "sing_along" -> {
                if (nxt == null) return null
                return t("Dare the listener to sing along to the next song, \"${nxt.title}\" by ${nxt.artist}, at full volume. Don't quote any lyrics.")
            }
            "music_news" -> {
                val h = freshHeadline(musicFeeds, gossipSkipRegex) ?: return null
                return t("A music-industry headline (tell it in your own words, add no claims beyond it): $h", "$h.")
            }
            "local_news" -> {
                val h = freshHeadline(localFeeds(city)) ?: return null
                return t("One headline from around $city (say what it says, in your own words, then react; add nothing): $h", "$h.")
            }
            "weather_now" -> {
                val w = getWeather(Config.lat, Config.lon) ?: return null
                return t("The weather in $city right now: ${w.first} degrees Fahrenheit, ${if (w.second) "and it is raining" else "no rain"}. Turn it into a playful little forecast for the listener's mood or plans.",
                    "It's ${w.first} degrees out there${if (w.second) " and wet" else ""}.")
            }
            "forecast" -> {
                val w = forecast() ?: return null
                return t("Today's forecast for $city: a high of ${w.first} and a low of ${w.second} degrees Fahrenheit, with a ${w.third} percent chance of rain. Deliver it with personality and one cheeky bit of advice.",
                    "Today: a high of ${w.first}, a low of ${w.second}.")
            }
            "time_check" -> {
                val now = SimpleDateFormat("EEEE h:mm a", Locale.US).format(Date())
                return t("It's $now. Riff on what this exact time of day is really for.", "It's $now.")
            }
            "day_vibe" -> {
                val c = Calendar.getInstance()
                val month = c.get(Calendar.MONTH) + 1
                val day = c.get(Calendar.DAY_OF_MONTH)
                val season = when (month) { 12, 1, 2 -> "winter"; 3, 4, 5 -> "spring"; 6, 7, 8 -> "summer"; else -> "autumn" }
                var f = "It's ${SimpleDateFormat("EEEE", Locale.US).format(Date())}, in $season."
                holidays[month.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')]?.let { f += " Today is $it." }
                if (month == 10) f += " It's October, which means spooky season and pumpkin-spice everything."
                if (month == 11 && c.get(Calendar.DAY_OF_WEEK) == Calendar.THURSDAY && day in 22..28) f += " It's Thanksgiving in the US."
                return t("$f Riff on the vibe of the day in one fresh, playful way.")
            }
            "hometown" -> {
                if (city.lowercase().startsWith("yakima") && list("yakima").isNotEmpty()) {
                    val fact = Memory.fresh("yakima", list("yakima"))
                    return t("A fact about $city (true, you can say it): $fact Give the town some playful love.", fact)
                }
                return t("Give $city some playful love: what makes its people great, said generally and warmly. Invent no facts, places or names.")
            }
            "traffic_joke" -> return t("A totally fake, obviously silly 'travel report' about the listener's life (the queue for the kettle, a jam in the sock drawer, delays on the road to bed). Never mention real roads, accidents or delays.")
            "weird_news" -> { val h = freshHeadline(worldFeedsB) ?: return null; return t("A weird-but-true story from somewhere in the world (NOT from $city): $h Tell it in your own words, then react.", "$h.") }
            "science" -> { val h = freshHeadline(listOf("https://feeds.bbci.co.uk/news/science_and_environment/rss.xml", gnews("scientists discover"))) ?: return null; return t("A science headline (say only what it says): $h", "$h.") }
            "space" -> { val h = freshHeadline(listOf(gnews("NASA OR astronomers OR telescope OR \"space station\" OR planet"))) ?: return null; return t("A space headline (say only what it says): $h", "$h.") }
            "tech" -> { val h = freshHeadline(listOf(gtopic("TECHNOLOGY"), gnews("gadget OR \"new device\" OR robot"))) ?: return null; return t("A gadgets-and-tech headline (say only what it says): $h", "$h.") }
            "animals" -> { val h = freshHeadline(listOf(gnews("zoo OR \"animal rescue\" OR wildlife OR puppy OR kitten OR \"baby animal\""))) ?: return null; return t("A heart-warming animal story (say only what it says): $h", "$h.") }
            "food" -> { val h = freshHeadline(listOf(gnews("\"food trend\" OR snack OR \"new menu\" OR chef OR bakery"))) ?: return null; return t("A food headline (say only what it says): $h", "$h.") }
            "sports" -> { val h = freshHeadline(listOf(gtopic("SPORTS"))) ?: return null; return t("A sports headline (say only what it says, keep it light and fun): $h", "$h.") }
            "showbiz" -> { val h = freshHeadline(gossipFeeds, gossipSkipRegex) ?: return null; return t("A showbiz headline (say ONLY what it says, add no rumours, tease affectionately, never mock anyone's looks or private life): $h", "$h.") }
            "screen" -> { val h = freshHeadline(listOf(gnews("trailer OR \"new series\" OR \"box office\" OR premiere OR sequel")), gossipSkipRegex) ?: return null; return t("A films-and-TV headline (say only what it says): $h", "$h.") }
            "on_this_day" -> {
                val events = onThisDay().filter { Memory.isFresh(it, 300.0) }
                if (events.isEmpty()) return null
                val e = events.random()
                Memory.markUsed(e)
                return t("$e Share it in your own words and react.", e)
            }
            "fun_fact" -> {
                val f = Memory.fresh("funFacts", list("funFacts"))
                return t("A true fun fact (say it in your own words): $f", "Fun fact: $f")
            }
            "word" -> {
                val w = Memory.fresh("words", vocab)
                return t("Word of the day: '${w[0]}', meaning ${w[1]}. Teach it, then use it in a silly example about the listener.", "Word of the day: ${w[0]}. It means ${w[1]}.")
            }
            "lore" -> return t("A quick first-person story from her Los Santos days, told with a punchline (use only these details, add reactions but no big new facts): " + Memory.fresh("lore", list("lore")))
            "confession" -> return t("A silly confession about herself: " + Memory.fresh("confessions", list("confessions")) + ". Own it dramatically.")
            "opinion" -> return t("Her strong, ridiculous opinion on this burning question: " + Memory.fresh("opinions", list("opinions")) + " Pick a side, defend it absurdly, and dare the listener to disagree.")
            "fake_ad" -> return t("A short parody advert, read by Cara, for this totally made-up product: " + Memory.fresh("fakeAds", list("fakeAds")) + " Include a ridiculous catchphrase for it and a fake 'terms and conditions' line at top speed.")
            "station_hype" -> return t("Hype the station, ${ctx.stationFull}, itself in a fresh, absurd way: what it'd be if it were a person, a food or a weather system, or a ridiculous line about it, said dead straight as if it's always been the station's motto.")
            "roast" -> return t("A playful roast. " + Memory.fresh("roasts", list("roasts")))
            "compliment" -> return t("Give the listener a backhanded compliment that's really a tease, then a sincere one.")
            "horoscope" -> return t("A completely made-up, obviously silly horoscope for ${list("signs").ifEmpty { listOf("Leo") }.random()}, with a weirdly specific prediction about snacks, socks, songs or parking.")
            "advice" -> return t("Terrible-but-harmless agony-aunt advice for the listener's dilemma: " + Memory.fresh("dilemmas", list("dilemmas")))
            "pep_talk" -> return t("An over-the-top motivational speech about " + Memory.fresh("pepTalks", list("pepTalks")) + ", like it's the biggest moment of the listener's life.")
            "hypothetical" -> return t("Picture this: " + Memory.fresh("hypotheticals", list("hypotheticals")) + ". Paint the scene in a few vivid, silly strokes.")
            "would_you_rather" -> {
                val q = Memory.fresh("wyr", list("wouldYouRather"))
                return t("Ask the listener: would you rather $q? Then give her own answer, with a ridiculous reason.", "Would you rather $q?")
            }
            "pop_quiz" -> {
                val q = Memory.fresh("quiz", quiz)
                return t("A pop quiz for the listener. Question: ${q[0]} Answer: ${q[1]}. Ask it, give them a few seconds of fake suspense, then reveal the answer.",
                    "Quick quiz: ${q[0]} The answer? ${q[1].replaceFirstChar { it.uppercase() }}.")
            }
            "debate" -> return t("Start a silly debate: " + Memory.fresh("opinions", list("opinions")) + " Argue BOTH sides like two people, then declare yourself the winner.")
            "challenge" -> return t("A car-safe challenge for the listener (voice only): " + Memory.fresh("challenges", list("challenges")))
        }
        return null
    }

    private fun available(sid: String, ctx: Ctx): Boolean = when (sid) {
        "next_intro", "sing_along" -> ctx.next != null
        "last_verdict" -> ctx.last != null
        "trivia", "artist_story", "time_machine", "hot_take" -> ctx.next != null || ctx.last != null
        else -> true
    }

    private suspend fun pickTopic(ctx: Ctx, log: (String) -> Unit): Topic {
        val recent = Memory.last("segments", 10).toSet()
        val lastSeg = Memory.last("segments", 1).firstOrNull()
        val lastFamily = segments.firstOrNull { it.id == lastSeg }?.family
        val pool = segments.filter { available(it.id, ctx) && it.id !in recent }
            .map { Pair(it.id, if (it.family == lastFamily) maxOf(1, it.weight / 3) else it.weight) }.toMutableList()
        while (pool.isNotEmpty()) {
            val sid = weightedPick(pool)
            val t = try { topicFor(sid, ctx) } catch (e: Exception) { log("[$sid failed: ${e.message}]"); null }
            if (t != null) return t
            pool.removeAll { it.first == sid }
        }
        return topicFor("fun_fact", ctx) ?: Topic("time_check", "Say hello to the listener in a fresh, playful way.", null, "Hello")
    }

    // ------------------------------------------------------------ Cara on her own
    suspend fun writeBreak(style: String, ctx: Ctx, log: (String) -> Unit): String {
        val topic = pickTopic(ctx, log)
        val mood = currentMood()
        log("[segment: ${topic.name}] [mood: $mood] [${chattiness()}]")
        val fmt = formats.filter { it[0] !in Memory.last("formats", 8) }.ifEmpty { formats }.random()
        val haveSong = ctx.next != null || ctx.last != null
        // now and then she reads the room: one quick jab about what the listener's song says about them, then on with the break
        val read = if (timeToRead()) songRead(if (style == "intro") ctx.next else (ctx.last ?: ctx.next)) else null
        val opening = if (read != null) listOf("read", "Open by reading the room (see below).", "")
            else openings.filter { o -> o[0] !in Memory.last("openings", 8) && (o.getOrElse(2) { "" } != "song" || haveSong) && (o.getOrElse(2) { "" } != "last" || ctx.last != null) }
                .ifEmpty { openings }.random()
        val roomLine = if (read != null) readBlock(read, readWhich(style, ctx), duo = false) else ""
        if (read != null) log("[reading the room: ${read.track.title}${if (read.lyrics == null) ", title only" else ""}]")
        val ending = endings.filter { it !in Memory.last("endings", 5) }.ifEmpty { endings }.random()
        val tagChoices = tags.filter { it !in Memory.last("tags", 4) }.ifEmpty { tags }.shuffled().take(2)
        val (lo, hi) = wordRange(style)
        val skip = reusable(ctx)
        val switched = ctx.switchedFrom
        log("[style: ${fmt[0]} · opening: ${opening[0]} · $lo-$hi words]")
        val tagLine = if (isExpressive())
            "She may use up to two emotion tags, ONLY [${tagChoices.joinToString("] or [")}], each placed mid-sentence right before the words it colours (never first, never on its own). Or none."
        else "Don't use any square-bracket tags."
        val switchLine = if (switched != null)
            "\n- Fresh news: the listener just switched stations, from \"${Station.full(switched)}\" to \"${ctx.stationFull}\". Welcome them to the new one somewhere in this break, in one quick, playful line (new name, same Cara)."
        else ""
        val prompt = listOf(
            "You are Cara, the DJ on ${ctx.stationFull}, broadcasting to ${Config.city}.",
            persona,
            bible(ctx),
            stationLine(ctx),
            "",
            "THIS BREAK",
            "- What's happening: ${situations[style] ?: situations["talkover"]}$switchLine",
            "- Length: $lo to $hi words.",
            "- Talk about: ${topic.facts}$roomLine",
            "- Delivery: ${fmt[1]}",
            "- Mood: ${moodLines[mood] ?: moodLines["normal"]}",
            "- Opening: ${opening[1]}",
            "- Landing: $ending",
            "- Voice: $tagLine",
            "- It's ${timeOfDayWord()} for the listener.",
            "",
            "NEVER REPEAT YOURSELF",
            memoryBlock(skip),
            "",
            rules,
            "",
            "Song that's just finishing: ${describe(ctx.last)}",
            "Next song: ${describe(ctx.next)}",
            "(She may name the next song if it looks like a real song. If it looks like an advert, a radio clip or is unknown, she doesn't mention it.)",
            "Write only the words Cara says.",
        ).joinToString("\n")
        val d = freshDraft(prompt, skip, log, read)
        if (d != null) {
            Memory.remember(d.first, segment = topic.label, fmt = fmt[0], opening = opening[0], ending = ending, tags = d.second)
            return d.first
        }
        val t = templateBreak(topic, ctx)
        Memory.remember(t, segment = topic.label)
        return t
    }

    /** No Gemini key (or Gemini is down): simple lines, still varied. */
    private fun templateBreak(topic: Topic, ctx: Ctx): String {
        val city = Config.city
        val openers = listOf("Cara here, keeping you company.", "${ctx.stationFull}, Cara on the mic.", "Hello, $city!",
            "Cara again. Did you miss me?", "This is Cara, live-ish and lovely.", "Guess who's back.",
            "Your favourite voice, reporting for duty.", "Cara checking in.", "Here's Cara, with absolutely no notes.",
            "It's me, the voice in your speakers.", "${ctx.station}, and I'm still here.", "Cara, back by popular demand.")
        val closers = listOf("Back to the music.", "Here's the next one.", "Turn it up for this.", "Stay right there.",
            "Don't go anywhere.", "More pop, coming right up.", "You're in good hands.", "Off we go.",
            "Right, on with the show.", "This next one's a goodie.")
        var middle = topic.plain ?: ""
        if (middle.isEmpty()) {
            val n = ctx.next
            middle = if (n != null) "Up next, ${n.artist}, with ${n.title}." else "More of the good stuff, coming up."
        }
        return Memory.fresh("tplOpen", openers) + " " + middle + " " + Memory.fresh("tplClose", closers)
    }

    /** A quick drop-in a few seconds into a song. */
    suspend fun writePopin(info: Track?, station: String, log: (String) -> Unit): String {
        val title = info?.title ?: "this one"
        val artist = info?.artist ?: ""
        val name = if (artist.isNotEmpty()) "\"$title\" by $artist" else "\"$title\""
        var fact = ""
        if (info != null) {
            val tr = try { getTrivia(info) } catch (e: Exception) { null }
            if (tr != null && !mentionsDeath(tr.second)) fact = "A real fact you may use (never invent others): " + tr.second.take(400)
        }
        val kinds = popinKinds.filter { k -> k[0] !in Memory.last("popins", 4) && (k[0] != "fact" || fact.isNotEmpty()) && (k[0] != "callback" || Memory.lastBreak != null) &&
            (k[0] != "read" || (info?.isMusic == true && "read" !in Memory.last("openings", 1))) }
        val kind = kinds.ifEmpty { popinKinds }.random()
        val read = if (kind[0] == "read") songRead(info) else null
        val roomLine = if (read != null) readBlock(read, "the song that's playing", duo = false) else ""
        val (lo, hi) = when (chattiness()) { "quick" -> Pair(8, 16); "normal" -> Pair(10, 22); else -> Pair(14, 30) }
        val tag = tags.filter { it !in Memory.last("tags", 4) }.ifEmpty { tags }.random()
        val skip = reusable(Ctx(null, info, station))
        log("[pop-in style: ${kind[0]}]")
        val prompt = listOfNotNull(
            "You are Cara, the DJ on ${Station.full(station)}, broadcasting to ${Config.city}.",
            persona,
            "",
            "The song $name started a few seconds ago, and she pops back in over it.",
            "- What to do: ${kind[1]}$roomLine",
            "- Length: $lo to $hi words.",
            if (fact.isNotEmpty()) "- $fact" else null,
            if (kind[0] == "callback") "- Her last break was: \"${Memory.lastBreak ?: ""}\"" else null,
            "- Voice: " + (if (isExpressive()) "She may use one emotion tag, ONLY [$tag], mid-sentence. Or none." else "No square-bracket tags."),
            "- High energy, quick, no goodbye or sign-off.",
            "",
            "NEVER REPEAT YOURSELF",
            memoryBlock(skip),
            "",
            rules,
            "Write only the words Cara says.",
        ).joinToString("\n")
        val d = freshDraft(prompt, skip, log, read)
        if (d != null) {
            Memory.remember(d.first, tags = d.second, popin = kind[0])
            return d.first
        }
        val line = Memory.fresh("popinTemplates", listOf(
            "That's $name. Turn it up, I'll wait.",
            "$name, and your taste is getting suspiciously good.",
            "Still with me? Course you are. This is $name.",
            "$name. Hum along, nobody's judging. I am, a bit.",
            "Right in the middle of $name, and I've got no notes.",
            "Quick one: $name. Carry on, superstar.",
        ))
        Memory.remember(line, popin = "template")
        return line
    }

    // ------------------------------------------------------------ Cara and Scratch together
    private suspend fun pickDuoTopic(ctx: Ctx, log: (String) -> Unit): Topic {
        val recent = Memory.last("segments", 8).toSet()
        val pool = duoSegments.filter { "duo_" + it.id !in recent && !(it.id == "station_name" && ctx.station == Station.FALLBACK) }
            .map { Pair(it.id, it.weight) }.toMutableList()
        while (pool.isNotEmpty()) {
            val sid = weightedPick(pool)
            val seg = duoSegments.first { it.id == sid }
            val t = try { duoTopicFor(seg, ctx) } catch (e: Exception) { log("[$sid failed: ${e.message}]"); null }
            if (t != null) return t
            pool.removeAll { it.first == sid }
        }
        val roast = duoSegments.firstOrNull { it.id == "roast_battle" }
        return Topic("duo_roast_battle", roast?.angle ?: "A quick, affectionate roast battle between the two DJs.", null, roast?.name ?: "Roast battle")
    }

    private suspend fun duoTopicFor(seg: DuoSegment, ctx: Ctx): Topic? {
        var facts = ""
        var plain: String? = null
        if (seg.base != null) {
            val t = topicFor(seg.base, ctx) ?: return null
            facts = t.facts
            plain = t.plain
        } else {
            when (seg.id) {
                "story_swap" -> facts = "$coShort's story from his Los Santos days (use only these details): " + Memory.fresh("coLore", list("coLore")) +
                    " Cara's story to top it (use only these details): " + Memory.fresh("lore", list("lore"))
                "debate" -> facts = "The burning question: " + Memory.fresh("opinions", list("opinions"))
                "would_you_rather" -> facts = "Would you rather " + Memory.fresh("wyr", list("wouldYouRather")) + "?"
                "quiz" -> { val q = Memory.fresh("quiz", quiz); facts = "Question: ${q[0]} Answer: ${q[1]}." }
                "advice" -> facts = "A listener's dilemma: " + Memory.fresh("dilemmas", list("dilemmas"))
                "fake_ad" -> facts = "A totally made-up product: " + Memory.fresh("fakeAds", list("fakeAds"))
                "station_name" -> facts = "The station is named after ${ctx.stationNote.ifEmpty { "\"" + ctx.station + "\"" }}, so on air it's \"${ctx.stationFull}\"."
            }
        }
        return Topic("duo_" + seg.id, if (facts.isEmpty()) seg.angle else "$facts ${seg.angle}", plain, seg.name)
    }

    /** Reads "CARA: ..." / "SCRATCH: ..." lines (anything else joins the line before it). */
    fun parseDuo(raw: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        // "MC SCRATCH:" counts as "SCRATCH:", and a speaker label in the middle of a line starts a new line
        // (the line breaks can get squashed on the way here)
        var text = raw
        val full = coName.uppercase()
        if (full != coLabel) text = text.replace(Regex("""\**${Regex.escape(full)}\**\s*:"""), "$coLabel:")
        text = text.replace(Regex("""\s+(?=\**(?:CARA|${Regex.escape(coLabel)})\**\s*:)"""), "\n")
        for (piece in text.lines()) {
            val line = piece.replace("*", "").trim().trimStart('-', '•').trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon > 0) {
                val who = line.substring(0, colon).trim().uppercase()
                val text = line.substring(colon + 1).trim()
                if (who == "CARA" || who == coLabel || who == coName.uppercase()) {
                    if (text.isNotEmpty()) out.add(Pair(if (who == "CARA") "CARA" else coLabel, text))
                    continue
                }
            }
            if (out.isNotEmpty()) {
                val lastLine = out.removeAt(out.size - 1)
                out.add(Pair(lastLine.first, lastLine.second + " " + line))
            }
        }
        return out
    }

    /** One Cara-and-Scratch exchange, checked against their memory like her solo breaks. Empty if it couldn't. */
    suspend fun writeDuo(style: String, ctx: Ctx, log: (String) -> Unit): List<Pair<String, String>> {
        val topic = pickDuoTopic(ctx, log)
        val mood = currentMood()
        val c = chattiness()
        log("[segment: ${topic.name} (with $coName)] [mood: $mood] [$c]")
        val (lo, hi, most) = when {
            style == "silent" -> when (c) { "quick" -> Triple(3, 4, 50); "normal" -> Triple(4, 6, 80); else -> Triple(5, 8, 110) }
            style == "intro" -> when (c) { "quick" -> Triple(2, 2, 18); "normal" -> Triple(2, 2, 22); else -> Triple(2, 3, 26) }
            else -> when (c) { "quick" -> Triple(2, 2, 28); "normal" -> Triple(2, 3, 38); else -> Triple(2, 4, 48) }
        }
        val first = listOf("Cara", coShort).random()
        val duoEndings = list("duoEndings")
        val ending = duoEndings.filter { it !in Memory.last("endings", 5) }.ifEmpty { duoEndings }.random()
        val tagChoices = tags.filter { it !in Memory.last("tags", 4) }.ifEmpty { tags }.shuffled().take(3)
        val skip = reusable(ctx, words(coName).toSet() + setOf("london", "vinyl"))
        val songWords = words(listOfNotNull(ctx.last?.describe, ctx.next?.describe).joinToString(" ")).toSet()
        val coMove = Memory.fresh("coMoves", list("coMoves").ifEmpty { listOf("Rides Cara's chaos with big, booming energy, then lands one perfect comeback.") })
        // now and then one of them reads the room: a quick jab about what the listener's song says about them, then on with it
        val read = if (timeToRead()) songRead(if (style == "intro") ctx.next else (ctx.last ?: ctx.next)) else null
        val roomLine = if (read != null) readBlock(read, readWhich(style, ctx), duo = true) else ""
        if (read != null) log("[reading the room: ${read.track.title}${if (read.lyrics == null) ", title only" else ""}]")
        val switched = ctx.switchedFrom
        log("[duo: $lo-$hi lines, $first first]")
        val tagLine = if (isExpressive())
            "Each line may use one emotion tag, ONLY [${tagChoices.joinToString("] or [")}], placed mid-sentence right before the words it colours (never first). Most lines have none."
        else "Don't use any square-bracket tags."
        val switchLine = if (switched != null)
            "\n- Fresh news: the listener just switched stations, from \"${Station.full(switched)}\" to \"${ctx.stationFull}\". One of them welcomes the listener to the new one in a quick, playful line."
        else ""
        val up = coLabel
        val prompt = listOf(
            "You write a short on-air exchange between the two DJs of ${ctx.stationFull}, broadcasting to ${Config.city}.",
            "CARA: $persona",
            bible(ctx),
            "$up: $coPersona",
            coLanguage(),
            coBible,
            coIdentity,
            stationLine(ctx),
            whoIsWho,
            "",
            "THIS BREAK",
            "- What's happening: ${duoSituations[style] ?: duoSituations["talkover"]}$switchLine",
            "- Talk about: ${topic.facts}$roomLine",
            "- $coShort's move this time (work it in naturally): $coMove",
            "- Shape: a quick back-and-forth between two DJs and old friends who've done a thousand shows together: teasing, interruptions, callbacks, each firing back at the other. Every line is short (3 to 22 words) and sounds spoken, not written.",
            "- Length: $lo to $hi lines and $most words at most in total. $first speaks first and they take turns.",
            "- Mood: ${moodLines[mood] ?: moodLines["normal"]}",
            "- Landing: $ending",
            "- Voice: $tagLine",
            "- It's ${timeOfDayWord()} for the listener.",
            "",
            "NEVER REPEAT YOURSELVES",
            memoryBlock(skip),
            "",
            rules,
            "- $coShort is the one exception to the no-invented-characters rule: he's her co-host, in the studio with her. Nobody else joins them.",
            "- $coShort follows every rule too. Neither of them is a real radio host: never mention, name or imitate real DJs or presenters, and never claim to know celebrities personally.",
            "",
            "Song that's just finishing: ${describe(ctx.last)}",
            "Next song: ${describe(ctx.next)}",
            "(They may name the next song if it looks like a real song. If it looks like an advert, a radio clip or is unknown, they don't mention it.)",
            "Write ONLY the dialogue: one line per turn, each starting with CARA: or $up:",
        ).joinToString("\n")
        var feedback = ""
        var best: List<Pair<String, String>>? = null
        for (attempt in 0 until 3) {
            val raw = gemini(if (feedback.isEmpty()) prompt else "$prompt\n\nYour previous draft can't be used: $feedback Write a completely new one.", Config.geminiKey, log) ?: break
            // a masked curse ("sh*t") gets read out as nonsense, so he says it in full or not at all
            if (masked.containsMatchIn(raw)) {
                feedback = "It hid a word behind asterisks. Write every word out in full, or pick a different word."
                log("[rewrite ${attempt + 1}: masked word]")
                continue
            }
            val lines = mutableListOf<Pair<String, String>>()
            val used = mutableListOf<String>()
            for ((who, text) in parseDuo(raw)) {
                val (t, tg) = cleanTags(tidy(text), tags.toSet())
                if (t.isNotEmpty()) { lines.add(Pair(who, t)); used.addAll(tg) }
            }
            val joined = lines.joinToString(" ") { it.second }
            val said = words(joined).toSet()
            if ("grandpa" in said || "gramps" in said) {
                feedback = "Cara gave him an old-man nickname. She only ever calls him $coShort."
                log("[rewrite ${attempt + 1}: old-man nickname]")
                continue
            }
            if ("alex" in said && "alex" !in songWords) {
                feedback = "It called him Alex. His name is $coName, $coShort for short."
                log("[rewrite ${attempt + 1}: wrong name]")
                continue
            }
            if (lines.any { it.first == "CARA" && swears(it.second).isNotEmpty() }) {
                feedback = "Cara swore. Only $coShort curses; Cara keeps it clean."
                log("[rewrite ${attempt + 1}: Cara swore]")
                continue
            }
            if (!Config.coHostSwears && swears(joined).isNotEmpty()) {
                feedback = "Keep it clean this time: no swearing from either of them."
                log("[rewrite ${attempt + 1}: swearing]")
                continue
            }
            if (tooFar(joined)) {
                feedback = "$coShort went too far. He can curse where it lands, but keep it classy: never \"bitch\" or \"motherfucker\"."
                log("[rewrite ${attempt + 1}: too crude]")
                continue
            }
            if (lines.size < 2 || lines.none { it.first == up } || lines.none { it.first == "CARA" }) {
                feedback = "It has to be a conversation: at least two lines, with both CARA: and $up: speaking."
                log("[rewrite ${attempt + 1}: not a conversation]")
                continue
            }
            val lyr = read?.lyrics
            if (read != null && lyr != null && quotesLyrics(joined, lyr, read.track.title)) {
                feedback = "It quoted the song's lyrics. Never quote them: react to what the song's about in your own words."
                log("[rewrite ${attempt + 1}: quoted the lyrics]")
                continue
            }
            val why = problem(joined, Memory.recent, skip)
            if (why != null) {
                log("[rewrite ${attempt + 1}: $why]")
                feedback = why
                if (best == null && !mentionsDeath(joined) && saysLabel(joined) == null) best = lines
                continue
            }
            Memory.remember(joined, segment = topic.label, opening = if (read != null) "read" else null, ending = ending, tags = used)
            return lines
        }
        val b = best
        if (b != null) {
            Memory.remember(b.joinToString(" ") { it.second }, segment = topic.label, opening = if (read != null) "read" else null, ending = ending)
            return b
        }
        return emptyList()
    }
}

// ---------------------------------------------------------------- her memory (saved on the phone)
object Memory {
    private var file: File? = null
    private val breaks = mutableListOf<String>()
    private val kinds = mutableMapOf<String, MutableList<String>>()
    private val keep = mapOf("segments" to 40, "formats" to 20, "openings" to 20, "endings" to 20, "popins" to 20, "tags" to 12)
    private val facts = mutableMapOf<String, Double>()
    private val used = mutableMapOf<String, MutableSet<Int>>()

    fun load(f: File) {
        file = f
        try {
            if (!f.exists()) return
            val j = JSONObject(f.readText())
            j.optJSONArray("breaks")?.let { a -> for (i in 0 until a.length()) breaks.add(a.optString(i, "")) }
            for (k in keep.keys) {
                val a = j.optJSONArray(k) ?: continue
                kinds[k] = (0 until a.length()).map { a.optString(it, "") }.toMutableList()
            }
            j.optJSONObject("facts")?.let { o -> for (k in o.keys()) facts[k] = o.optDouble(k, 0.0) }
            j.optJSONObject("used")?.let { o ->
                for (k in o.keys()) {
                    val a = o.optJSONArray(k) ?: continue
                    used[k] = (0 until a.length()).map { a.optInt(it, 0) }.toMutableSet()
                }
            }
        } catch (e: Exception) { }
    }

    private fun save() {
        val f = file ?: return
        try {
            val j = JSONObject()
            j.put("breaks", JSONArray(breaks))
            for ((k, v) in kinds) j.put(k, JSONArray(v))
            val fo = JSONObject()
            for ((k, v) in facts) fo.put(k, v)
            j.put("facts", fo)
            val uo = JSONObject()
            for ((k, v) in used) uo.put(k, JSONArray(v.sorted()))
            j.put("used", uo)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(f)) { f.writeText(j.toString()); tmp.delete() }
        } catch (e: Exception) { }
    }

    val recent: List<String> get() = breaks.toList()
    val lastBreak: String? get() = breaks.lastOrNull()

    /** Something from the list she hasn't used yet (the list starts over once she's been through it). */
    fun <T> fresh(name: String, items: List<T>): T {
        var u = used.getOrPut(name) { mutableSetOf() }
        if (u.size >= items.size) { u = mutableSetOf(); used[name] = u }
        val left = items.indices.filter { it !in u }.ifEmpty { listOf(0) }
        val i = left.random()
        u.add(i)
        save()
        return items[i]
    }

    fun isFresh(key: String, days: Double = 3.0): Boolean {
        val t = facts[key.lowercase()] ?: return true
        return System.currentTimeMillis() / 1000.0 - t > days * 86400
    }

    fun markUsed(key: String) {
        val now = System.currentTimeMillis() / 1000.0
        facts[key.lowercase()] = now
        facts.entries.removeAll { now - it.value > 14 * 86400 }
        save()
    }

    fun last(kind: String, n: Int): List<String> = (kinds[kind] ?: emptyList<String>()).takeLast(n)

    fun remember(text: String, segment: String? = null, fmt: String? = null, opening: String? = null, ending: String? = null,
                 tags: List<String> = emptyList(), popin: String? = null) {
        breaks.add(text)
        while (breaks.size > 80) breaks.removeAt(0)
        for ((kind, v) in listOf("segments" to segment, "formats" to fmt, "openings" to opening, "endings" to ending, "popins" to popin)) {
            if (v == null) continue
            val l = kinds.getOrPut(kind) { mutableListOf() }
            l.add(v)
            while (l.size > (keep[kind] ?: 20)) l.removeAt(0)
        }
        for (t in tags) {
            val l = kinds.getOrPut("tags") { mutableListOf() }
            l.add(t)
            while (l.size > 12) l.removeAt(0)
        }
        save()
    }
}
