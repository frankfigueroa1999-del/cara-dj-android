package com.cara.dj

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class AppTab { HOME, CARA, LIBRARY, SEARCH }

/** Every page you can open on top of a tab. */
sealed class Route {
    data class AlbumPage(val album: Album) : Route()
    data class ArtistPage(val artist: Artist) : Route()
    data class PlaylistPage(val playlist: Playlist) : Route()
    data object Liked : Route()
    data object Playlists : Route()
    data object Albums : Route()
    data object Artists : Route()
    data class GenrePage(val genre: Genre) : Route()
}

/** One open page. It keeps its own loaded data, so going back to it doesn't load it all again. */
class Entry(val route: Route) {
    val key: Int = nextKey++
    var model: Any? = null

    companion object {
        private var nextKey = 1
    }
}

/** Which tab you're on, what's open on each one, and the sheets that can pop up. */
object Router {
    var tab by mutableStateOf(AppTab.HOME)
    val stacks: Map<AppTab, SnapshotStateList<Entry>> = AppTab.entries.associateWith { mutableStateListOf<Entry>() }
    var showPlayer by mutableStateOf(false)
    var showSettings by mutableStateOf(false)
    var addToPlaylist by mutableStateOf<Track?>(null)
    /** The last change was going back (so pages slide the other way). */
    var wentBack by mutableStateOf(false)

    fun stack(t: AppTab = tab): SnapshotStateList<Entry> = stacks.getValue(t)

    fun openPlayer() { showPlayer = true }
    fun closePlayer() { showPlayer = false }

    /** Open a page on the current tab. If the big player is up, it slides away first. */
    fun open(r: Route) {
        if (showPlayer) {
            closePlayer()
            Engine.scope.launch {
                delay(380)
                push(r)
            }
        } else {
            push(r)
        }
    }

    private fun push(r: Route) {
        wentBack = false
        stack().add(Entry(r))
    }

    /** Back one page on this tab. False when there's nowhere to go back to. */
    fun pop(): Boolean {
        val s = stack()
        if (s.isEmpty()) return false
        wentBack = true
        s.removeAt(s.size - 1)
        return true
    }

    fun select(t: AppTab) {
        if (tab == t) {
            wentBack = true
            stack(t).clear()            // tapping the tab you're already on goes back to its first page
        } else {
            tab = t
        }
    }
}

data class ToastItem(val id: Long, val text: String, val icon: ImageVector)

/** Little "Added to Queue" style messages. */
object Toasts {
    var current by mutableStateOf<ToastItem?>(null)
    private var hide: Job? = null
    private var n = 0L

    fun show(text: String, icon: ImageVector = Icons.Filled.Check) {
        current = ToastItem(++n, text, icon)
        hide?.cancel()
        hide = Engine.scope.launch {
            delay(1900)
            current = null
        }
    }

    /** The same, with the iPhone app's symbol name ("heart.fill", "exclamationmark.triangle.fill", ...). */
    fun show(text: String, symbol: String) = show(text, sym(symbol))
}

/** Little taps you can feel. */
object Haptics {
    var view: View? = null

    private fun fire(kind: Int) {
        try { view?.performHapticFeedback(kind) } catch (e: Exception) { }
    }

    fun tap() = fire(HapticFeedbackConstants.CLOCK_TICK)
    fun soft() = fire(HapticFeedbackConstants.KEYBOARD_TAP)
    fun firm() = fire(HapticFeedbackConstants.CONTEXT_CLICK)
    fun success() = fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
}
