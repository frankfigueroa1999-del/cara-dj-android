package com.cara.dj

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlin.reflect.KProperty

/** One saved setting: reads/writes the phone's storage and tells the screen when it changes. */
class SP<T : Any>(private val key: String, private val def: T) {
    private var st: MutableState<T>? = null

    @Suppress("UNCHECKED_CAST")
    private fun state(): MutableState<T> {
        st?.let { return it }
        val sp = Config.prefs
        val v: Any = when (def) {
            is String -> sp.getString(key, def) ?: def
            is Boolean -> sp.getBoolean(key, def)
            is Int -> sp.getInt(key, def)
            is Float -> sp.getFloat(key, def)
            is Double -> java.lang.Double.longBitsToDouble(sp.getLong(key, java.lang.Double.doubleToRawLongBits(def)))
            else -> def
        }
        val s = mutableStateOf(v as T)
        st = s
        return s
    }

    operator fun getValue(thisRef: Any?, prop: KProperty<*>): T = state().value

    operator fun setValue(thisRef: Any?, prop: KProperty<*>, v: T) {
        state().value = v
        val e = Config.prefs.edit()
        when (v) {
            is String -> e.putString(key, v)
            is Boolean -> e.putBoolean(key, v)
            is Int -> e.putInt(key, v)
            is Float -> e.putFloat(key, v)
            is Double -> e.putLong(key, java.lang.Double.doubleToRawLongBits(v))
            else -> {}
        }
        e.apply()
    }
}

/** All your settings, saved on the phone. */
object Config {
    lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        if (this::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("cara", Context.MODE_PRIVATE)
        // anyone who already set the app up never sees the welcome screens
        if (!prefs.getBoolean("welcomed", false) && prefs.getString("clientID", "").orEmpty().isNotEmpty() &&
            prefs.getString("elevenKey", "").orEmpty().isNotEmpty()) {
            prefs.edit().putBoolean("welcomed", true).apply()
        }
    }

    /** Stingers are remade with the station's name (whenever something nameable is playing). */
    var stationStingers: Boolean by SP("stationStingers", true)
    /** The ElevenLabs voice that reads the station stingers ("" for the default announcer). */
    var stationVoice: String by SP("stationVoice", "")
    /** The one-time welcome / setup screens have been shown. */
    var welcomed: Boolean by SP("welcomed", false)
    /** Recent searches, newest first, as a JSON list. */
    var recentSearchesJson: String by SP("recentSearches", "[]")
    var recentSearches: List<String>
        get() = try {
            val a = org.json.JSONArray(recentSearchesJson)
            (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotEmpty() }
        } catch (e: Exception) { emptyList() }
        set(v) { recentSearchesJson = org.json.JSONArray(v).toString() }

    var clientID: String by SP("clientID", "")
    var elevenKey: String by SP("elevenKey", "")
    var elevenVoice: String by SP("elevenVoice", "")
    var elevenModel: String by SP("elevenModel", "eleven_v4")
    var geminiKey: String by SP("geminiKey", "")
    var city: String by SP("city", "Yakima, Washington")
    var lat: Double by SP("lat", 46.60)
    var lon: Double by SP("lon", -120.51)
    var breakMin: Int by SP("breakMin", 2)
    var breakMax: Int by SP("breakMax", 5)
    var mood: String by SP("mood", "normal")
    var djVolume: Float by SP("djVolume", 100f)
    var stingerVolume: Float by SP("stingerVolume", 80f)
    var stingerChance: Int by SP("stingerChance", 50)
    var popinEnabled: Boolean by SP("popinEnabled", true)
    var popinChance: Int by SP("popinChance", 35)
    var popinSeconds: Int by SP("popinSeconds", 15)
    var popinTest: Boolean by SP("popinTest", false)

    /** How long she talks: "quick", "normal" or "chatty". */
    var chattiness: String by SP("chattiness", "chatty")

    // MC Scratch, her co-host
    var coHost: Boolean by SP("coHost", true)
    var coHostChance: Int by SP("coHostChance", 40)
    var coVoice: String by SP("coVoice", "")          // empty = his default voice

    // Spotify login (saved so you only log in once)
    var accessToken: String by SP("accessToken", "")
    var refreshToken: String by SP("refreshToken", "")
    var tokenExpiry: Double by SP("tokenExpiry", 0.0)
    var verifier: String by SP("verifier", "")
    var grantedScopes: String by SP("grantedScopes", "")

    /** The short silent Spotify track silent breaks talk over ("" when none can be played on this account). */
    var silenceURI: String by SP("silenceURI", "")
    /** When that was last checked with Spotify (seconds). */
    var silenceCheckedAt: Double by SP("silenceCheckedAt", 0.0)
    private var silenceBadRaw: String by SP("silenceBad", "")
    /** Silent tracks Spotify wouldn't play on this account (never tried again, but still skipped if they turn up). */
    var silenceBadList: List<String>
        get() = silenceBadRaw.split(",").filter { it.isNotEmpty() }
        set(v) { silenceBadRaw = v.joinToString(",") }
    /** Names of playlists / albums / artists we've seen, so the station is named the moment one starts (JSON object). */
    var contextNamesJson: String by SP("contextNames", "{}")
}
