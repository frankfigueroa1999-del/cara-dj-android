package com.cara.dj

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowOutward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HeartBroken
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicExternalOn
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Nightlife
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The iPhone app's symbols, by the same names, drawn with Android's Material icons.
 * sym("heart.fill") gives the filled heart, and so on. Unknown names fall back to a music note.
 */
fun sym(name: String): ImageVector = when (name) {
    // tabs and the mini player
    "house.fill", "house" -> Icons.Filled.Home
    "dot.radiowaves.left.and.right" -> Icons.Filled.Sensors
    "square.stack.fill" -> Icons.Filled.LibraryMusic
    "magnifyingglass" -> Icons.Filled.Search
    "forward.fill" -> Icons.Filled.FastForward
    "play.fill" -> Icons.Filled.PlayArrow
    "pause.fill" -> Icons.Filled.Pause
    "stop.fill" -> Icons.Filled.Stop
    // Cara
    "waveform" -> Icons.Filled.GraphicEq
    "forward.end.fill", "forward.end" -> Icons.Filled.SkipNext
    "mic.fill" -> Icons.Filled.Mic
    "sparkles" -> Icons.Filled.AutoAwesome
    "bolt.fill" -> Icons.Filled.Bolt
    "person.2.fill" -> Icons.Filled.People
    "person.fill" -> Icons.Filled.Person
    "quote.opening" -> Icons.Filled.FormatQuote
    "mappin.and.ellipse" -> Icons.Filled.Place
    "arrow.clockwise" -> Icons.Filled.Refresh
    "slider.horizontal.3" -> Icons.Filled.Tune
    // arrows
    "chevron.down" -> Icons.Filled.KeyboardArrowDown
    "chevron.up" -> Icons.Filled.KeyboardArrowUp
    "chevron.left" -> Icons.AutoMirrored.Filled.KeyboardArrowLeft
    "chevron.right" -> Icons.AutoMirrored.Filled.KeyboardArrowRight
    "arrow.up.right" -> Icons.Filled.ArrowOutward
    "arrow.up.left.and.arrow.down.right" -> Icons.Filled.OpenInFull
    // music and the library
    "music.note.list" -> Icons.AutoMirrored.Filled.QueueMusic
    "music.mic" -> Icons.Filled.MicExternalOn
    "square.stack", "opticaldisc.fill" -> Icons.Filled.Album
    "music.note" -> Icons.Filled.MusicNote
    "music.quarternote.3" -> Icons.Filled.LibraryMusic
    "heart" -> Icons.Filled.FavoriteBorder
    "heart.fill" -> Icons.Filled.Favorite
    "heart.slash" -> Icons.Filled.HeartBroken
    "text.line.last.and.arrowtriangle.forward" -> Icons.AutoMirrored.Filled.PlaylistPlay
    "text.badge.plus" -> Icons.AutoMirrored.Filled.PlaylistAdd
    "square.and.arrow.up" -> Icons.Filled.Share
    "ellipsis" -> Icons.Filled.MoreHoriz
    "shuffle" -> Icons.Filled.Shuffle
    "plus" -> Icons.Filled.Add
    "plus.circle" -> Icons.Filled.AddCircleOutline
    "minus.circle" -> Icons.Filled.RemoveCircleOutline
    "checkmark" -> Icons.Filled.Check
    "checkmark.circle.fill" -> Icons.Filled.CheckCircle
    "lock.fill" -> Icons.Filled.Lock
    "text.bubble" -> Icons.AutoMirrored.Filled.Chat
    // warnings and timers
    "exclamationmark.triangle.fill" -> Icons.Filled.Warning
    "wifi.exclamationmark" -> Icons.Filled.WifiOff
    "hourglass" -> Icons.Filled.HourglassEmpty
    "moon.zzz", "moon.zzz.fill" -> Icons.Filled.Bedtime
    // devices
    "iphone" -> Icons.Filled.PhoneAndroid
    "laptopcomputer" -> Icons.Filled.Laptop
    "ipad" -> Icons.Filled.Tablet
    "hifispeaker.fill" -> Icons.Filled.Speaker
    "tv" -> Icons.Filled.Tv
    "car.fill" -> Icons.Filled.DirectionsCar
    "gamecontroller.fill" -> Icons.Filled.SportsEsports
    "tv.and.hifispeaker.fill" -> Icons.Filled.Cast
    "speaker.wave.2.fill" -> Icons.AutoMirrored.Filled.VolumeUp
    // browse tiles
    "figure.dance" -> Icons.Filled.Nightlife
    "guitars.fill" -> Icons.Filled.Speaker
    "leaf.fill" -> Icons.Filled.Eco
    "sun.max.fill" -> Icons.Filled.WbSunny
    "star.fill" -> Icons.Filled.Star
    "headphones" -> Icons.Filled.Headphones
    "moon.stars.fill" -> Icons.Filled.NightsStay
    "flame.fill" -> Icons.Filled.LocalFireDepartment
    "balloon.2.fill" -> Icons.Filled.Celebration
    else -> Icons.Filled.MusicNote
}
