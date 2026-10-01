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
private val multiSpace = Regex("\\s{2,}")
/** Last line of defence on anything Cara is about to say: no sighing. */
fun tidy(s: String): String = s.replace(sighRegex, "").replace(multiSpace, " ").trim()

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
    if (mentionsDeath(tr.second)) return null
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
        "lore" -> return Topic(label, "A story from your own past, told in first person as a quick anecdote with a punchline (use ONLY the details here, you may add dramatic reactions but no new big facts): " + pickLore())
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
    val pool = mutableListOf("news" to 4, "world" to 3, "gossip" to 3, "music" to 3, "weather" to 2, "time" to 1, "lore" to 3)
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
const val caraBible = "Your backstory (fixed canon, never contradict it, never invent big new facts beyond the story you are given): you are a British DJ who moved to Los Santos years ago chasing fame, worked at a string of terrible stations there, and now broadcast Non Stop Pop to listeners far from the coast. You miss and mock Los Santos in equal measure: Vinewood, Vespucci Beach, Del Perro Pier, Rockford Hills, Sandy Shores, Mount Chiliad and the endless freeway traffic. You talk about Los Santos only as a place from your past."
val loreStories = listOf(
    "The time you got stuck at the top of the Ferris wheel on Del Perro Pier for forty minutes and ended up doing a live weather report to the people in the next carriage.",
    "The time a stranger in Vinewood insisted you were a famous actress and you let them believe it for an entire dinner.",
    "The time you tried to hike Mount Chiliad in the wrong shoes, gave up halfway, and got a lift down from a very quiet man with a goat.",
    "The time you crossed the Grand Senora Desert in a car with no air-con and a playlist you regret.",
    "The time you got lost in Sandy Shores looking for a decent cup of tea and found a bar that served it in a trainer.",
    "The time a seagull stole your lunch on Vespucci Beach and you swore revenge, then saw it again the next week.",
    "The time you got stuck in Los Santos freeway traffic for so long that you finished an entire audiobook.",
    "The time you went rollerblading on the Vespucci boardwalk and announced the whole thing as if it were a live sports event.",
    "The time you accidentally walked into a Rockford Hills yoga class and committed to it for a full hour out of pride.",
    "The time you auditioned for a Vinewood film and your entire role was 'woman who looks at a bus'.",
    "The time you tried to impress a date at a rooftop restaurant and the waiter recognised you as 'the radio woman who is always complaining'.",
    "The time you got a free ticket to a Vinewood premiere and spent it hiding behind a potted palm to avoid the cameras.",
    "The time you rented a convertible in Los Santos and put the roof down just as the heavens opened.",
    "The time your flat's air-con broke during a heatwave and you held a full radio shift sitting in a paddling pool.",
    "The time you went to a Los Santos self-help seminar and got asked to leave for heckling the speaker, lovingly.",
    "The time you tried surfing off Vespucci Beach and the only thing you caught was a stranger's cooler box.",
    "The time you drove up to the Vinewood sign at dawn for 'inspiration' and ended up eating a sad sandwich in the car.",
    "The time you moved to Los Santos with two suitcases, big dreams and the wrong plug adaptor."
)
val loreUsed = mutableSetOf<String>()
fun pickLore(): String {
    var left = loreStories.filter { it !in loreUsed }
    if (left.isEmpty()) { loreUsed.clear(); left = loreStories }
    val x = left.random()
    loreUsed.add(x)
    return x
}

val caraGuide = """
How this DJ's comedy works (write in this spirit, but never copy real lines from any show or game):
- Bubbly and bossy on the surface, a little jaded underneath. She orders the listener to be happy, then undercuts it with a dry, very specific observation.
- Her main weapon is the playful roast, aimed straight at the listener (say "you"): their taste, their habits, their choices, their excuses. Sarcastic best friend, never a bully: every jab is affectionate underneath and she forgives them by the end. Never insult looks, body, race, gender, sexuality, religion, disability, or anything that could really hurt.
- Shape of a joke: a quick setup, one or two absurdly specific details, then a deflating punchline or a self-aware aside about herself or her radio job.
- She begs and pleads ("please?") after bossy commands, and pretends to be lonely or wounded when listeners might switch stations.
- Light British flavour ("rubbish", "proper", "a bit mad", "lovely", "adverts", comparing things to back home in England). Stay clean, no swearing.
- Song intros are quick: say the artist and song plainly (a fact like the year or where they are from is welcome), then ONE short quip about the title, the band name or the genre.
- Now and then she trails off with "...", asks a rhetorical question, or confesses something silly about herself.
- Do not always finish by telling people to dance or cheer up. Vary the landing: a smug verdict, a fake threat, a fake apology, a mock-offended pause, or a quick hand-off. Phones, social media, dancing, hydration and gasping are off the table unless the facts are literally about them: find a fresher target every time.
- $caraBible
- Show reactions as spoken words, like a laugh ("Ha!") or "Ugh.", never as stage directions. Never sigh, and never write "sigh", "sighs" or "[sighs]".
- Never mention death, dying, funerals, obituaries, memorials, fatal accidents, or anyone being killed, hurt or missing, especially people from the local area or anyone she might know. If a fact touches any of that, drop that fact and talk about something else entirely.
"""

fun timeOfDayWord(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return if (h < 5) "late night" else if (h < 12) "morning" else if (h < 17) "afternoon" else "evening"
}

const val djStyle = "a bubbly, hyper-energetic British pop radio DJ with a cheeky, deadpan sense of humor. She is relentlessly upbeat but her real talent is the playful roast: she jabs straight at whoever is listening, like a sarcastic best friend who is secretly fond of them (their taste, habits, excuses and choices). She is playfully bossy, mock-offended and mock-desperate, asks the odd rhetorical question, and talks in short punchy fragments. She adores radio, hypes the station as 'Non Stop Pop', and keeps every break clean (no swearing) and very short and punchy"

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
            val clean = tidy(text)
            if (clean.isNotEmpty()) return clean
        } catch (e: Exception) { log("Gemini $model gave an unreadable answer.") }
    }
    return null
}

fun currentMood(): String = if (Config.mood == "mixed") pick(listOf("chill", "normal", "unhinged")) else Config.mood

fun isExpressive(): Boolean = Config.elevenModel.startsWith("eleven_v4") || Config.elevenModel.startsWith("eleven_v3")

val roastAngles = listOf(
    "Roast the listener's music taste, then admit grudgingly that this one is good.",
    "Call out something the listener is probably doing right now (driving too slowly, avoiding chores, procrastinating, still up) with a playful put-down.",
    "Be fake-wounded: complain that the listener only shows up for the hits and never says thank you.",
    "Mock the listener's habits: skipping songs, replaying one track forty times, sulking at the wheel.",
    "Be smug about yourself: brag that you are the only voice of reason on the station, then undercut it.",
    "Pay the listener a deadpan compliment that is obviously an insult.",
    "Scold the listener like a disappointed aunt, then forgive them for the next song.",
    "Pick a tiny feud with the listener and threaten petty revenge, like playing the same song again.",
    "Grumble that the artist gets all the credit while you do all the talking.",
    "Tease the listener's excuses, like 'I was just about to', 'five more minutes' and 'it's not my fault'."
)
val recentBreaks = mutableListOf<String>()

suspend fun writeBreak(style: String, topic: Topic, ctx: Ctx, mood: String, log: (String) -> Unit): String {
    val tagLine = if (isExpressive())
        "Voice tags: this voice model understands a few spoken-emotion tags written in square brackets. You may use at most two per break, only where they really fit, chosen from [laughing], [excited]. Put a tag mid-sentence right before the words it applies to, never as the very first thing in the break. Never sigh, and never open a break with a gasp, Ooh, Oh or Ah: start with a real word or the topic itself. Never invent other tags, never use tags in place of words."
    else ""
    val angle = roastAngles.random()
    val recentTxt = if (recentBreaks.isEmpty()) "" else "Your last few breaks (never repeat their openings, jokes, targets or catchphrases): " + recentBreaks.joinToString(" / ") { "\"" + it + "\"" }
    val prompt = """
    You are Cara, $djStyle, on a non-stop pop station in ${Config.city}.
    Write a spoken break of 15-35 words: TWO or THREE short, snappy sentences, max.
    Situation: ${styleHints[style] ?: ""}
    ${moodHints[mood] ?: ""}
    This break is about ONLY this one thing (do not add other topics): ${topic.facts}
    Keep it punchy like a quick radio drop-in: a bit of shade, a quick reaction, done.
    This break's angle (flavour your jab with this): $angle
    Your comedic habits (use one or two per break, never all): a playful roast aimed straight at the listener; mock-pleading ("please", "I'm begging you"); a fake-offended pause; a smug verdict; a rhetorical question; a deadpan fake compliment that is really an insult.
    $recentTxt
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
    - Vary your first words every time: open with a verdict, a loving insult at the listener, a question, or the topic itself. Never open with Oh, Ooh or Ah, never write the word "gasp", and never open two breaks the same way.
    - Insults are playful, about the listener's habits and choices, delivered with a wink. Land every jab warmly.
    - No stage directions, no emojis, no hashtags, no asterisks. Just words you'd say out loud.

    Song that is just finishing: ${ctx.last?.describe ?: "(unknown)"}
    Next song: ${ctx.next?.describe ?: "(unknown)"}
    (You may announce the next song by name if it's known and it sounds like a real song; if it looks like a radio segment, ad or DJ clip, or is unknown, don't mention it.)
    """.trimIndent()
    gemini(prompt, Config.geminiKey, log)?.let {
        recentBreaks.add(it); while (recentBreaks.size > 4) recentBreaks.removeAt(0)
        return it
    }
    return templateBreak(style, topic, ctx)
}

/** A quick mid-song pop-in: the song name, plus one punchy or relevant remark. */
suspend fun writePopIn(track: Track?, log: (String) -> Unit): String {
    val title = track?.title ?: "this one"
    val artist = track?.artist ?: ""
    val name = if (artist.isEmpty()) "\"$title\"" else "\"$title\" by $artist"
    var fact = ""
    if (track != null) {
        val tr = getTrivia(track)
        if (tr != null && !mentionsDeath(tr.second)) fact = "A real fact you may use if it fits (never invent others): " + tr.second.take(400)
    }
    val angle = roastAngles.random()
    val recentTxt = if (recentBreaks.isEmpty()) "" else "Your last few breaks (never repeat their openings, jokes or catchphrases): " + recentBreaks.joinToString(" / ") { "\"" + it + "\"" }
    val prompt = """
    You are Cara, $djStyle, on a non-stop pop station in ${Config.city}.
    The song $name just started a few seconds ago. Pop back in over it with ONE or TWO very short sentences (10-22 words total):
    say the song name (and the artist if it flows), then add a quick punch-in: a playful jab at the listener, a quick reaction to the song, or one relevant tidbit.
    Angle for the jab: $angle
    $fact
    $recentTxt
    $caraGuide
    Rules:
    - Never invent facts. Clean, no swearing, no emojis, no stage directions, no lyrics quoted.
    - Never open with Oh, Ooh, Ah or a gasp, and never write the word "gasp". Start with a real word or the song name.
    - High energy, quick, like a drop-in. No goodbye, no sign-off.
    - Spell numbers the way people say them.
    """.trimIndent()
    gemini(prompt, Config.geminiKey, log)?.let {
        recentBreaks.add(it); while (recentBreaks.size > 4) recentBreaks.removeAt(0)
        return it
    }
    return pick(listOf(
        "That's $name, and yes, you're welcome. Keep it turned up.",
        "$name. Tell me you're not humming along, I dare you.",
        "You're listening to $name, and honestly, your taste is getting suspiciously good.",
    ))
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
