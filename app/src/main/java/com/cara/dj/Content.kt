package com.cara.dj

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.random.Random

// ---------- small helpers ----------
fun <T> pick(a: List<T>): T = a[Random.nextInt(a.size)]
fun randInt(lo: Int, hi: Int): Int = if (lo >= hi) lo else Random.nextInt(lo, hi + 1)
fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

/** Plain web request. Returns (status code, body); status 0 means it could not connect. */
suspend fun fetchBytes(
    url: String, timeout: Int = 10000, method: String = "GET",
    headers: Map<String, String> = emptyMap(), body: ByteArray? = null,
): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
    try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = timeout
        c.readTimeout = timeout
        c.setRequestProperty("User-Agent", "Mozilla/5.0 CaraDJ")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (method != "GET") {
            c.doOutput = true
            c.outputStream.use { it.write(body ?: ByteArray(0)) }
        }
        val code = c.responseCode
        val stream = if (code >= 400) c.errorStream else c.inputStream
        val data = stream?.use { it.readBytes() } ?: ByteArray(0)
        c.disconnect()
        Pair(code, data)
    } catch (e: Exception) {
        Pair(0, ByteArray(0))
    }
}

// ---------- RSS ----------
fun parseTitles(data: ByteArray): List<String> {
    val out = mutableListOf<String>()
    try {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(data), null)
        var inItem = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                val n = p.name
                if (n == "item" || n == "entry") inItem = true
                else if (inItem && n == "title") out.add(p.nextText())
            } else if (ev == XmlPullParser.END_TAG) {
                val n = p.name
                if (n == "item" || n == "entry") inItem = false
            }
            ev = p.next()
        }
    } catch (e: Exception) { }
    return out
}

private val publisherTail = Regex("\\s+-\\s+[^-]+$")
fun cleanTitle(t: String): String = t.trim().replace(publisherTail, "").trim()     // drop " - Publisher"

val skipWords = listOf(
    "killed", "dead", "death", "died", "dies", "homicide", "murder", "shooting", "shot", "stabbing", "crash", "fatal",
    "victim", "suicide", "assault", "abuse", "rape", "arrest", "sentenced", "charged", "trial", "manslaughter", "overdose", "missing",
    "drown", "wildfire", "evacuat", "measles", "outbreak", "cancer", "massacre", "genocide", "famine",
)
val skipRegex = Regex(
    "\\b(?:war|wars|attack|attacks|attacked|bomb|bombs|bombing|terror|terrorist|hostage|hostages|airstrike|airstrikes|invasion|troops|missile|missiles|militant|militants|hamas|gaza|ukraine|russia|israel|iran|election|elections|trump|biden|congress|senate|parliament|protest|protests|riot|riots|refugee|refugees|migrant|migrants|abortion|shutdown|sanctions)\\b",
    RegexOption.IGNORE_CASE,
)
val gossipSkipRegex = Regex(
    "\\b(?:lawsuit|sues|sued|suing|court|divorce|rehab|hospital|hospitalized|hospitalised|affair|cheating|leak|leaked|nude|naked|racist|sexual|allegations|alleged|accused|custody|restraining|lawsuits|scandal|feud|passes|obituary|tribute|mourning|grief|funeral)\\b",
    RegexOption.IGNORE_CASE,
)

fun isSafe(title: String, extra: Regex? = null): Boolean {
    val low = title.lowercase()
    if (skipWords.any { low.contains(it) }) return false
    if (skipRegex.containsMatchIn(title)) return false
    if (extra != null && extra.containsMatchIn(title)) return false
    return true
}

fun gnews(q: String) = "https://news.google.com/rss/search?q=" + enc(q) + "&hl=en-US&gl=US&ceid=US:en"
fun gtopic(t: String) = "https://news.google.com/rss/headlines/section/topic/$t?hl=en-US&gl=US&ceid=US:en"

val worldFeeds get() = listOf(
    gnews("bizarre OR weird OR quirky OR \"world record\" OR viral"),
    "https://rss.upi.com/news/odd_news.rss",
    "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",
)
val gossipFeeds get() = listOf(
    gtopic("ENTERTAINMENT"),
    "https://feeds.bbci.co.uk/news/entertainment_and_arts/rss.xml",
    gnews("\"pop star\" OR singer OR \"red carpet\" OR \"new album\" OR tour"),
)
val musicFeeds get() = listOf(
    "https://www.billboard.com/feed/",
    "https://pitchfork.com/feed/feed-news/rss",
    gnews("\"new single\" OR \"new album\" OR \"tour dates\" OR Billboard OR Grammy OR \"chart\" music"),
)
fun localFeeds(city: String): List<String> {
    val f = mutableListOf(gnews(city.replace(",", " ")))
    if (city.lowercase().startsWith("yakima")) f.add(gnews("Yakima Valley"))
    return f
}

private val feedCache = mutableMapOf<String, Pair<Long, List<String>>>()

suspend fun fetchFeed(url: String): List<String> {
    val c = feedCache[url]
    if (c != null && System.currentTimeMillis() - c.first < 600_000) return c.second
    val (status, data) = fetchBytes(url)
    if (status !in 200..299) return emptyList()
    val titles = parseTitles(data).map { cleanTitle(it) }.filter { it.isNotEmpty() }
    feedCache[url] = Pair(System.currentTimeMillis(), titles)
    return titles
}

suspend fun getHeadlines(feeds: List<String>, extra: Regex? = null, perFeed: Int = 6): List<String> {
    val out = mutableListOf<String>()
    for (f in feeds) out += fetchFeed(f).take(perFeed).filter { isSafe(it, extra) }
    return out
}

// ---------- weather, town lookup ----------
suspend fun getWeather(lat: Double, lon: Double): Pair<Int, Boolean>? {
    val (st, data) = fetchBytes(
        "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,precipitation&temperature_unit=fahrenheit", 8000,
    )
    if (st != 200) return null
    return try {
        val cur = JSONObject(String(data)).getJSONObject("current")
        Pair(Math.round(cur.getDouble("temperature_2m")).toInt(), cur.optDouble("precipitation", 0.0) > 0)
    } catch (e: Exception) { null }
}

suspend fun geocodeCity(city: String): Pair<Double, Double>? {
    val parts = city.split(",").map { it.trim() }
    val name = parts.firstOrNull() ?: return null
    val (st, data) = fetchBytes("https://geocoding-api.open-meteo.com/v1/search?name=${enc(name)}&count=10&language=en&format=json", 8000)
    if (st != 200) return null
    return try {
        val results = JSONObject(String(data)).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val region = if (parts.size > 1) parts[1].lowercase() else ""
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            val where = (r.optString("admin1", "") + " " + r.optString("country", "")).lowercase()
            if (region.isNotEmpty() && where.contains(region)) return Pair(r.getDouble("latitude"), r.getDouble("longitude"))
        }
        val r0 = results.getJSONObject(0)
        Pair(r0.getDouble("latitude"), r0.getDouble("longitude"))
    } catch (e: Exception) { null }
}

// ---------- song trivia (real facts from Wikipedia) ----------
private val wikiCache = mutableMapOf<String, String?>()
private val musicWords = listOf("singer", "band", "rapper", "musician", "songwriter", "duo", "group", "vocalist", "record producer", "composer", "artist", "song", "single")

suspend fun wikiLookup(query: String, must: List<String>): String? {
    if (wikiCache.containsKey(query)) return wikiCache[query]
    var result: String? = null
    val (st, data) = fetchBytes(
        "https://en.wikipedia.org/w/api.php?action=query&format=json&generator=search&gsrlimit=4&prop=extracts&exintro=1&explaintext=1&exsentences=7&redirects=1&gsrsearch=${enc(query)}",
        8000,
    )
    if (st == 200) {
        try {
            val pages = JSONObject(String(data)).getJSONObject("query").getJSONObject("pages")
            val list = mutableListOf<JSONObject>()
            val keys = pages.keys()
            while (keys.hasNext()) list.add(pages.getJSONObject(keys.next()))
            list.sortBy { it.optInt("index", 99) }
            for (pg in list) {
                val ext = pg.optString("extract", "").trim()
                val low = ext.lowercase()
                if (ext.length > 80 && must.all { low.contains(it.lowercase()) } && musicWords.any { low.contains(it) }) {
                    result = ext; break
                }
            }
        } catch (e: Exception) { }
    }
    wikiCache[query] = result
    return result
}

private val titleTail = Regex("\\s*[(\\[-].*$")

suspend fun getTrivia(t: Track): Pair<String, String>? {
    var clean = t.title.replace(titleTail, "").trim()
    if (clean.isEmpty()) clean = t.title
    val firstWord = t.artist.split(" ").firstOrNull() ?: t.artist
    val a = wikiLookup("\"$clean\" ${t.artist} song", listOf(clean, firstWord))
    if (a != null) return Pair("the song \"${t.title}\" by ${t.artist}", a)
    val b = wikiLookup("${t.artist} musician band singer", listOf(t.artist))
    if (b != null) return Pair(t.artist, b)
    return null
}

// ---------- topics ----------
data class Topic(val label: String, val facts: String, val plain: String? = null)
data class Ctx(val last: Track?, val next: Track?)

val moodHints = mapOf(
    "chill" to "Mood: CHILL. Laid-back, smooth and warm, like a late-night host who's had a great day. Still upbeat, but relaxed: fewer exclamation marks, gentle dry humor, a little shorter than usual. Never sleepy or bored.",
    "unhinged" to "Mood: UNHINGED. Maximum chaos and drama: big dramatic reactions, absurd exaggerated reactions, mock outrage, wildly over-the-top hyperbole, dramatic beats with '...'. Big, gleeful, slightly out of control. Still only the given facts, still clean, still short.",
)

fun songFacts(t: Track, whenText: String): String {
    var f = "$whenText song is \"${t.title}\" by ${t.artist}"
    if (t.album.isNotEmpty()) f += ", from the album \"${t.album}\""
    if (t.year.isNotEmpty()) f += " (${t.year})"
    f += ". Hype the artist and the song using ONLY the facts here (you may mention the album or year), and never claim anything else about them."
    return f
}

private val parenText = Regex("\\s*\\([^)]*\\)")
private val darkWords = Regex("died|death|killed|arrest|lawsuit|abuse|suicide|overdose", RegexOption.IGNORE_CASE)

suspend fun triviaFacts(t: Track, whenText: String): Topic? {
    val tr = getTrivia(t) ?: return null
    val facts = "$whenText song is \"${t.title}\" by ${t.artist}. Here is real background on ${tr.first} (from Wikipedia): \"\"\"${tr.second}\"\"\" " +
        "Share exactly ONE interesting, specific fun fact taken ONLY from that text, in your own words, like you just remembered it. " +
        "Never add anything that is not in the text, and never guess. Skip anything sad, dark or about deaths, scandals or lawsuits."
    // plain version for when there's no Gemini key: one short sentence from the text
    val cleaned = tr.second.replace(parenText, "")
    val good = cleaned.split(". ").drop(1).map { if (it.endsWith(".")) it else "$it." }
        .filter { it.length in 41..199 && !darkWords.containsMatchIn(it) }
    return Topic("trivia", facts, good.randomOrNull())
}

suspend fun topicFor(label: String, ctx: Ctx): Topic? {
    when (label) {
        "news" -> {
            val h = getHeadlines(localFeeds(Config.city))
            return if (h.isEmpty()) null else Topic(label, "One local headline: " + pick(h))
        }
        "weather" -> {
            val w = getWeather(Config.lat, Config.lon) ?: return null
            return Topic(label, "Current weather in town: ${w.first} degrees Fahrenheit, ${if (w.second) "raining" else "no rain"}")
        }
        "world" -> {
            val h = getHeadlines(worldFeeds)
            return if (h.isEmpty()) null else Topic(label, "One wild story from somewhere in the world (NOT from ${Config.city}, so don't say it happened here): " + pick(h))
        }
        "gossip" -> {
            val h = getHeadlines(gossipFeeds, gossipSkipRegex)
            return if (h.isEmpty()) null else Topic(label, "A celebrity / pop culture story (say ONLY what the headline says, add no rumours or extra claims, and tease affectionately: never mock anyone's looks, body or private life): " + pick(h))
        }
        "music" -> {
            val h = getHeadlines(musicFeeds, gossipSkipRegex)
            return if (h.isEmpty()) null else Topic(label, "A music industry story (say ONLY what the headline says, add no extra claims): " + pick(h))
        }
        "artist_next" -> {
            val n = ctx.next ?: return null
            return Topic(label, songFacts(n, "The NEXT"))
        }
        "artist_last" -> {
            val l = ctx.last ?: return null
            return Topic(label, songFacts(l, "The song that JUST played"))
        }
        "trivia" -> {
            ctx.next?.let { n -> triviaFacts(n, "The NEXT")?.let { return it } }
            ctx.last?.let { l -> triviaFacts(l, "The song that JUST played")?.let { return it } }
            return null
        }
        else -> {
            val f = SimpleDateFormat("EEEE h:mm a", Locale.US)
            return Topic("time", "The time is " + f.format(Date()))
        }
    }
}

fun weightedPick(entries: List<Pair<String, Int>>): String {
    var r = Random.nextInt(entries.sumOf { it.second })
    for ((v, w) in entries) { if (r < w) return v; r -= w }
    return entries[0].first
}

suspend fun pickTopic(ctx: Ctx): Topic {
    val pool = mutableListOf("news" to 4, "world" to 3, "gossip" to 3, "music" to 3, "weather" to 2, "time" to 1)
    if (ctx.next != null) pool.add("artist_next" to 4)
    if (ctx.last != null) pool.add("artist_last" to 2)
    if (ctx.next != null || ctx.last != null) pool.add("trivia" to 5)
    while (pool.isNotEmpty()) {
        val label = weightedPick(pool)
        topicFor(label, ctx)?.let { return it }
        pool.removeAll { it.first == label }
    }
    return topicFor("time", ctx)!!
}

// ---------- writing the line (Gemini) and speaking it (ElevenLabs) ----------
val caraGuide = """
How this DJ's comedy works (write in this spirit, but never copy real lines from any show or game):
- Bubbly and bossy on the surface, a little jaded underneath. She orders the listener to be happy, then undercuts it with a dry, very specific observation.
- Favourite targets: phone and social-media addiction, comment sections, selfies, wellness and diet trends, therapy and pills as a lifestyle, celebrity and fame culture, actors, music snobs who think they are too cool, and the quirks of the town she broadcasts from (tease it gently, without stating specific facts you were not given).
- Shape of a joke: a quick setup, one or two absurdly specific details, then a deflating punchline or a self-aware aside about herself or her radio job.
- She begs and pleads ("please?") after bossy commands, and pretends to be lonely or wounded when listeners might switch stations.
- Light British flavour ("rubbish", "proper", "a bit mad", "lovely", "adverts", comparing things to back home in England). Stay clean, no swearing.
- Song intros are quick: say the artist and song plainly (a fact like the year or where they are from is welcome), then ONE short quip about the title, the band name or the genre.
- Now and then she trails off with "...", asks a rhetorical question, or confesses something silly about herself.
- Cynical observation is fine, but always land back on: dance, be happy, stop taking everything so seriously.
- Show reactions as spoken words, like a sigh ("Ugh.") or a laugh ("Ha!"), never as stage directions.
"""

fun timeOfDayWord(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return if (h < 5) "late night" else if (h < 12) "morning" else if (h < 17) "afternoon" else "evening"
}

const val djStyle = "a bubbly, hyper-energetic British pop radio DJ with a cheeky, deadpan sense of humor. She is relentlessly upbeat but always slips in a dry little jab at the town, celebrity culture, phones and social media, or people who think they're too cool for pop. She is playfully bossy and mock-desperate, begging listeners to cheer up, quit moping and dance, asks the odd rhetorical question, and talks in short punchy fragments. She adores radio, hypes the station as 'Non Stop Pop', and keeps every break clean (no swearing) and very short and punchy"

val styleHints = mapOf(
    "silent" to "The music has just stopped completely, so it's just you alone on the mic. Come in LOUD and high-energy, like a big dramatic 'whoa, the music stopped!' moment, and end by building up to the next song kicking in, like 'here we go!'. Never whisper, never say 'shh' or hush the listener.",
    "intro" to "The next song has only just started, and you've jumped in to talk over its opening. Keep it quick and punchy and end by hyping the song, like 'okay, back to it!'.",
    "talkover" to "The song is still playing quietly underneath your voice and it is about to end. Talk like you're riding the end of the song and handing off to the next one with energy. Don't say goodbye or sign off; it should flow straight into the next track.",
)

suspend fun gemini(prompt: String, key: String, log: (String) -> Unit): String? {
    if (key.isEmpty()) return null
    for (model in listOf("gemini-3.5-flash-lite", "gemini-3.6-flash", "gemini-3.5-flash")) {
        val body = JSONObject().put(
            "contents",
            JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))),
        ).toString().toByteArray()
        val (status, data) = fetchBytes(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", 25000, "POST",
            mapOf("x-goog-api-key" to key, "Content-Type" to "application/json"), body,
        )
        if (status != 200) { log("Gemini $model failed: $status"); continue }
        try {
            val text = JSONObject(String(data)).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).optString("text", "").trim()
            if (text.isNotEmpty()) return text
        } catch (e: Exception) { log("Gemini $model gave an unreadable answer.") }
    }
    return null
}

fun currentMood(): String = if (Config.mood == "mixed") pick(listOf("chill", "normal", "unhinged")) else Config.mood

fun isExpressive(): Boolean = Config.elevenModel.startsWith("eleven_v4") || Config.elevenModel.startsWith("eleven_v3")

suspend fun writeBreak(style: String, topic: Topic, ctx: Ctx, mood: String, log: (String) -> Unit): String {
    val tagLine = if (isExpressive())
        "Voice tags: this voice model understands a few spoken-emotion tags written in square brackets. You may use at most two per break, only where they really fit, chosen from [laughing], [sighs], [excited]. Put a tag mid-sentence right before the words it applies to, never as the very first thing in the break. Never open a break with a gasp, a sigh, Ooh, Oh or Ah: start with a real word or the topic itself. Never invent other tags, never use tags in place of words."
    else ""
    val prompt = """
    You are Cara, $djStyle, on a non-stop pop station in ${Config.city}.
    Write a spoken break of 15-35 words: TWO or THREE short, snappy sentences, max.
    Situation: ${styleHints[style] ?: ""}
    ${moodHints[mood] ?: ""}
    This break is about ONLY this one thing (do not add other topics): ${topic.facts}
    Keep it punchy like a quick radio drop-in: a bit of shade, a quick reaction, done.
    Your comedic habits (use one or two per break, never all): a cheerful command followed by a dry, deadpan jab; mock-pleading ("please", "I'm begging you"); a rhetorical question; gently teasing the listener or the town; a wry aside about phones, social media, or being too cool for pop.
    $caraGuide
    It's ${timeOfDayWord()} where you are, so you can nod to that if it fits.
    $tagLine
    Rules:
    - Only use the facts given above. Never invent news, names or numbers, but you may react to them with over-the-top drama. If it's a headline, actually tell listeners what it says, in your own words, then react.
    - Write for the ear: contractions, sentence fragments, a natural "ugh" or "okay", and dashes or commas where a real person would pause. Never sound like a press release.
    - Delivery: fast, breathy, excited and playful, with the odd exclamation mark, ending on a punchy hand-off line (not a goodbye).
    - Spell out numbers the way people say them ("fifty-nine degrees", "four seventeen").
    - Make every joke original. Never reuse lines from any existing radio show, game or film.
    - Keep it clean: no swearing. Almost never mention hydration or drinking water.
    - Never start with "Shh" and never whisper or hush the listener. Always come in with big energy.
    - No stage directions, no emojis, no hashtags, no asterisks. Just words you'd say out loud.

    Song that is just finishing: ${ctx.last?.describe ?: "(unknown)"}
    Next song: ${ctx.next?.describe ?: "(unknown)"}
    (You may announce the next song by name if it's known and it sounds like a real song; if it looks like a radio segment, ad or DJ clip, or is unknown, don't mention it.)
    """.trimIndent()
    gemini(prompt, Config.geminiKey, log)?.let { return it }
    return templateBreak(style, topic, ctx)
}

private val factPrefix = Regex("^[^:]*:\\s*")

fun templateBreak(style: String, topic: Topic, ctx: Ctx): String {
    val intros = if (style == "silent")
        listOf("Whoa, where did the music go?! It's just me, Cara, and I am thrilled about it!", "Hello, ${Config.city}, it's me, Cara, live and loud!")
    else listOf("Oh my gosh, hi! Cara here, Non Stop Pop!", "Ooh, hold on, it's Cara, and I have news!")
    val fact = topic.facts.replace(factPrefix, "")
    val middle = when (topic.label) {
        "trivia" -> {
            val who = (ctx.next ?: ctx.last)?.artist ?: "this artist"
            topic.plain?.let { "Fun fact about $who: $it Iconic!" } ?: "Up next on Non Stop Pop, and you will NOT sit down!"
        }
        "artist_next" -> ctx.next?.let { "Up next, ${it.artist}, with ${it.title}! Absolutely iconic!" } ?: "Up next, something iconic!"
        "artist_last" -> ctx.last?.let { "That was ${it.artist} with ${it.title}! Chef's kiss!" } ?: "That was iconic!"
        "time" -> "It's ${fact.replace("The time is ", "")}, and everybody should be dancing!"
        "weather" -> topic.facts.replace("Current weather in town", "Weather check") + "!"
        else -> "$fact! Honestly!"
    }
    val outros = listOf("Non Stop Pop, baby!", "Turn it up!", "Right, here we go!", "Don't you dare touch that dial!")
    return "${pick(intros)} $middle ${pick(outros)}"
}

suspend fun elevenLabsTTS(text: String): ByteArray {
    val key = Config.elevenKey.trim()
    val voice = Config.elevenVoice.trim()
    if (key.isEmpty() || voice.isEmpty()) throw Exception("Add your ElevenLabs key and Voice ID in Settings.")
    val settings = if (isExpressive())
        JSONObject().put("stability", 0.25).put("similarity_boost", 1.0)
    else
        JSONObject().put("stability", 0.35).put("similarity_boost", 0.8).put("style", 0.4).put("use_speaker_boost", true).put("speed", 1.05)
    val body = JSONObject().put("text", text).put("model_id", Config.elevenModel).put("voice_settings", settings).toString().toByteArray()
    val (status, data) = fetchBytes(
        "https://api.elevenlabs.io/v1/text-to-speech/${enc(voice)}?output_format=mp3_44100_128", 30000, "POST",
        mapOf("xi-api-key" to key, "Content-Type" to "application/json"), body,
    )
    if (status != 200) {
        val msg = String(data)
        val hint = if (msg.contains("invalid_api_key")) " (Use the secret key that starts with sk_, not the key ID.)" else ""
        throw Exception("ElevenLabs $status: ${msg.take(120)}$hint")
    }
    return data
}
