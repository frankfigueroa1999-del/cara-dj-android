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
import java.util.Calendar
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

/** Nothing about anyone dying, being hurt, or being remembered after death. Ever. */
val deathRegex = Regex(
    "\\b(?:kill|killed|killing|dead|death|deaths|deadly|die|dies|died|dying|fatal|fatally|fatality|fatalities|passed away|passes away|obituary|obituaries|funeral|memorial|vigil|mourn|mourning|mourners|grief|coroner|autopsy|remains|body|bodies|drowned|drowning|perished|lost (?:his|her|their) life|tragic|tragedy|injured|injuries|injury|hospitalized|crash|crashed|collision|rip)\\b",
    RegexOption.IGNORE_CASE,
)
fun mentionsDeath(s: String): Boolean = deathRegex.containsMatchIn(s)

private val sighRegex = Regex(
    "(?:[\\[\\(\\*]\\s*(?:deep |long |heavy )?(?:sigh|sighs|sighing|exhales?)\\s*[\\]\\)\\*]\\s*|(?<![\\w'])\\*?(?:deep |long |heavy )?(?:sigh|sighs|sighing)\\*?(?![\\w'])[.,!\\u2026]*\\s*)",
    RegexOption.IGNORE_CASE,
)
/** Snorts and sniffs written as a voice tag or a stage direction ("[snorts]", "(sniffs)", "*snort*"). */
private val noseRegex = Regex(
    "[\\[\\(\\*]\\s*(?:a |one |little |small |loud |quick )?(?:snort|sniff)\\w*(?:[- ]\\w+){0,3}\\s*[\\]\\)\\*]\\s*",
    RegexOption.IGNORE_CASE,
)
private val multiSpace = Regex("\\s{2,}")
/** Last line of defence on anything Cara (or Scratch) is about to say: no sighing, no nose noises. */
fun tidy(s: String): String = s.replace(sighRegex, "").replace(noseRegex, "").replace(multiSpace, " ").trim()

fun isSafe(title: String, extra: Regex? = null): Boolean {
    if (mentionsDeath(title)) return false
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

// ---------- picking from weighted choices ----------
fun weightedPick(entries: List<Pair<String, Int>>): String {
    var r = Random.nextInt(entries.sumOf { it.second })
    for ((v, w) in entries) { if (r < w) return v; r -= w }
    return entries[0].first
}

// ---------- writing the line (Gemini) and speaking it (ElevenLabs) ----------
fun timeOfDayWord(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return if (h < 5) "late night" else if (h < 12) "morning" else if (h < 17) "afternoon" else "evening"
}

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
            val clean = tidy(text)
            if (clean.isNotEmpty()) return clean
        } catch (e: Exception) { log("Gemini $model gave an unreadable answer.") }
    }
    return null
}

fun isExpressive(): Boolean = Config.elevenModel.startsWith("eleven_v4") || Config.elevenModel.startsWith("eleven_v3")

/** Speaks [text] in Cara's voice, or in [voiceOverride] (Scratch's voice) when that's given. */
suspend fun elevenLabsTTS(text: String, voiceOverride: String? = null): ByteArray {
    val key = Config.elevenKey.trim()
    val voice = (voiceOverride ?: Config.elevenVoice).trim()
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
        val hint = if (msg.contains("invalid_api_key")) " (Use the secret key that starts with sk_, not the key ID.)"
            else if (voiceOverride != null && (status == 404 || msg.contains("voice_not_found"))) " (That's Scratch's voice: check his Voice ID in Settings, or leave it empty for the default.)"
            else ""
        throw Exception("ElevenLabs $status: ${msg.take(120)}$hint")
    }
    return data
}
