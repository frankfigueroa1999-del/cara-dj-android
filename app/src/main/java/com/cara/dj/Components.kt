package com.cara.dj

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- sharing and links
/** Opens the phone's share sheet for a link. */
fun shareLink(url: String) {
    try {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
        Engine.app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { }
}

/** Opens a web page (or another app's link). */
fun openUrl(url: String) {
    try {
        Engine.app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { }
}

// ---------------------------------------------------------------- the "..." menu for a song (also when you press and hold a song)
@Composable
fun MenuItem(title: String, symbol: String, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(title, fontSize = 16.sp) },
        onClick = onClick,
        enabled = enabled,
        trailingIcon = { Icon(sym(symbol), contentDescription = null, modifier = Modifier.size(20.dp)) },
        colors = MenuDefaults.itemColors(textColor = Color.White, trailingIconColor = Color.White),
    )
}

/** A dark menu that drops down from where it's attached. */
@Composable
fun DarkMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        containerColor = Color(0xFF26262C), shape = RoundedCornerShape(14.dp),
    ) {
        content()
    }
}

@Composable
fun TrackMenu(
    track: Track,
    expanded: Boolean,
    onDismiss: () -> Unit,
    showAlbum: Boolean = true,
    showArtist: Boolean = true,
    /** Set this when the menu lives inside the big player, which shows its own playlist picker. */
    onAddToPlaylist: ((Track) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    DarkMenu(expanded, onDismiss) {
        val liked = Library.likedState[track.uri] ?: false
        MenuItem("Add to Queue", "text.line.last.and.arrowtriangle.forward") {
            onDismiss(); scope.launch { Engine.addToQueue(track) }
        }
        MenuItem(if (liked) "Remove from Liked Songs" else "Add to Liked Songs", if (liked) "heart.slash" else "heart") {
            onDismiss(); scope.launch { Library.setLiked(track, !liked) }
        }
        MenuItem("Add to a Playlist…", "text.badge.plus") {
            onDismiss()
            if (onAddToPlaylist != null) onAddToPlaylist(track) else Router.addToPlaylist = track
        }
        HorizontalDivider(color = Color.White.copy(alpha = 0.12f))
        if (showAlbum && track.albumId.isNotEmpty()) MenuItem("Go to Album", "square.stack") {
            onDismiss(); Router.open(Route.AlbumPage(track.albumRef))
        }
        if (showArtist && track.artistId.isNotEmpty()) MenuItem("Go to Artist", "music.mic") {
            onDismiss(); Router.open(Route.ArtistPage(track.artistRef))
        }
        track.shareURL?.let { u -> MenuItem("Share Song", "square.and.arrow.up") { onDismiss(); shareLink(u) } }
    }
}

// ---------------------------------------------------------------- one song in a list
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(track: Track, number: Int? = null, showAlbumInMenu: Boolean = true, onClick: () -> Unit) {
    val current = track.uri.isNotEmpty() && Engine.displayItem?.uri == track.uri
    val sub = if (number == null) track.artistLine else (if (track.artists.size > 1) track.artistLine else "")
    var menu by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth()
                .heightIn(min = if (number == null) 64.dp else 52.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null,
                    onClick = { Haptics.tap(); onClick() },
                    onLongClick = { Haptics.firm(); menu = true },
                )
                .padding(start = Theme.hPad, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (number != null) {
                Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
                    if (current) EqualizerBars(playing = Engine.now.isPlaying)
                    else Text("$number", color = Theme.text3, fontSize = 16.sp)
                }
            } else {
                Box(Modifier.size(48.dp)) {
                    Artwork(track.artMid, Modifier.fillMaxSize(), px = 150, corner = 8.dp)
                    if (current) {
                        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                            EqualizerBars(playing = Engine.now.isPlaying, color = Color.White)
                        }
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    track.title, color = if (current) Theme.accent else Color.White, fontSize = 16.sp,
                    fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (track.explicit || sub.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        if (track.explicit) ExplicitBadge()
                        if (sub.isNotEmpty()) Text(sub, color = Theme.text2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Spacer(Modifier.width(6.dp))
            Box {
                Box(Modifier.width(34.dp).height(44.dp).tap { menu = true }, contentAlignment = Alignment.Center) {
                    Icon(sym("ellipsis"), contentDescription = "More", tint = Theme.text2, modifier = Modifier.size(20.dp))
                }
                TrackMenu(track, menu, { menu = false }, showAlbum = showAlbumInMenu)
            }
        }
        Box(
            Modifier.align(Alignment.BottomStart).padding(start = if (number == null) Theme.hPad + 60.dp else Theme.hPad + 38.dp)
                .fillMaxWidth().height(0.5.dp).background(Theme.line)
        )
    }
}

// ---------------------------------------------------------------- cards for carousels and grids
@Composable
fun AlbumCard(album: Album, width: Dp = 160.dp, subtitle: String? = null, onClick: () -> Unit = { Router.open(Route.AlbumPage(album)) }) {
    Column(Modifier.width(width).press(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Artwork(album.artMid, Modifier.size(width).shadow(10.dp, RoundedCornerShape(12.dp)), px = 420, corner = 12.dp)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(album.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle ?: album.artist, color = Theme.text2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun PlaylistCard(playlist: Playlist, width: Dp = 160.dp, onClick: () -> Unit = { Router.open(Route.PlaylistPage(playlist)) }) {
    Column(Modifier.width(width).press(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Artwork(playlist.imageMid, Modifier.size(width).shadow(10.dp, RoundedCornerShape(12.dp)), px = 420, corner = 12.dp)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(playlist.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playlist.owner.ifEmpty { "Playlist" }, color = Theme.text2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun ArtistCircle(artist: Artist, size: Dp = 120.dp, onClick: () -> Unit = { Router.open(Route.ArtistPage(artist)) }) {
    Column(Modifier.width(size).press(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Artwork(artist.imageMid, Modifier.size(size).shadow(10.dp, CircleShape), px = 360, circle = true)
        Text(artist.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A grid tile that fills whatever width the grid gives it. */
@Composable
fun AlbumTile(title: String, subtitle: String, art: String, circle: Boolean = false, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Artwork(art, Modifier.fillMaxWidth().aspectRatio(1f).shadow(10.dp, if (circle) CircleShape else RoundedCornerShape(12.dp)), px = 420, corner = 12.dp, circle = circle)
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Theme.text2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A playlist / artist / album as a list row. [leading] replaces the cover (the Liked Songs heart). */
@Composable
fun MediaRow(title: String, subtitle: String, art: String, circle: Boolean = false, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().tap { Haptics.tap(); onClick() }) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Theme.hPad, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) Box(Modifier.size(56.dp)) { leading() }
            else Artwork(art, Modifier.size(56.dp), px = 180, corner = 9.dp, circle = circle)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = Theme.text2, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            Icon(sym("chevron.right"), contentDescription = null, tint = Theme.text3, modifier = Modifier.size(18.dp))
        }
        Box(Modifier.align(Alignment.BottomStart).padding(start = Theme.hPad + 68.dp).fillMaxWidth().height(0.5.dp).background(Theme.line))
    }
}

/** A sideways row of cards that settles on a card as you swipe. */
@Composable
fun Carousel(spacing: Dp = 14.dp, content: LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = Theme.hPad),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.Top,
        flingBehavior = rememberSnapFlingBehavior(state),
        content = content,
    )
}

/** The Liked Songs "cover": a glowing heart on Cara's colours. */
@Composable
fun LikedArt(size: Dp, corner: Dp = 12.dp) {
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier.size(size).clip(shape)
            .background(Brush.linearGradient(listOf(Theme.accent, Theme.caraPurple, Theme.caraNight)))
            .border(0.5.dp, Color.White.copy(alpha = 0.1f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.25f), Color.Transparent))))
        Icon(sym("heart.fill"), contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.36f))
    }
}

/** Your picture; opens Settings. */
@Composable
fun AvatarButton(size: Dp = 32.dp) {
    val img = Library.me?.image ?: ""
    Box(Modifier.size(size).press(0.9f) { Haptics.tap(); Router.showSettings = true }, contentAlignment = Alignment.Center) {
        if (img.isNotEmpty()) {
            Artwork(img, Modifier.fillMaxSize().border(0.7.dp, Color.White.copy(alpha = 0.2f), CircleShape), px = 120, circle = true)
        } else {
            Box(Modifier.fillMaxSize().glass(size / 2, 0.1f), contentAlignment = Alignment.Center) {
                Icon(sym("person.fill"), contentDescription = "Settings", tint = Color.White, modifier = Modifier.size(size * 0.5f))
            }
        }
    }
}

// ---------------------------------------------------------------- a page: the backdrop, the list, and a bar along the top
/**
 * Every page is built the same way. [title] is the big title at the top of the list (Home has its own header instead);
 * it shrinks into the bar as you scroll. [inlineTitle] shows in the bar once you've scrolled (detail pages).
 * [back] shows the back button, [art] gives the page its own cover colours, and [underBar] lets a big picture run up
 * under the bar (the artist page).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Page(
    title: String? = null,
    inlineTitle: String? = null,
    back: Boolean = false,
    art: String? = null,
    state: LazyListState = rememberLazyListState(),
    onRefresh: (suspend () -> Unit)? = null,
    underBar: Boolean = false,
    showBar: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
    content: LazyListScope.() -> Unit,
) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barH = 52.dp
    val scrolled by remember(state) { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 40 } }
    val barFill by animateFloatAsState(if (scrolled) 1f else 0f, tween(200), label = "bar")
    Box(Modifier.fillMaxSize()) {
        AmbientBackdrop(art)
        val list: @Composable () -> Unit = {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = if (underBar || !showBar) 0.dp else top + barH,
                    bottom = Theme.tabBarHeight + Theme.miniHeight + 40.dp + bottom,
                ),
            ) {
                if (title != null) item(key = "page-title") {
                    Text(
                        title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = Theme.hPad, end = Theme.hPad, bottom = 10.dp),
                    )
                }
                content()
            }
        }
        if (onRefresh != null) {
            var refreshing by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    scope.launch {
                        refreshing = true
                        try { onRefresh() } finally { refreshing = false }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) { list() }
        } else {
            list()
        }
        if (showBar) {
            Box(
                Modifier.fillMaxWidth().height(top + barH)
                    .background(Brush.verticalGradient(listOf(Color(0xF0101014).copy(alpha = 0.94f * barFill), Color(0xF0101014).copy(alpha = 0.86f * barFill))))
            ) {
                Row(
                    Modifier.padding(top = top).height(barH).fillMaxWidth().padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(88.dp), contentAlignment = Alignment.CenterStart) {
                        if (back) BackButton()
                    }
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        val t = inlineTitle ?: title
                        if (t != null) {
                            Text(
                                t, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.alpha(barFill),
                            )
                        }
                    }
                    Row(Modifier.width(88.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                        actions()
                    }
                }
            }
        }
    }
}

/** The round back button in the bar. */
@Composable
fun BackButton() {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)).press(0.9f) { Haptics.tap(); Router.pop() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(sym("chevron.left"), contentDescription = "Back", tint = Color.White, modifier = Modifier.size(26.dp))
    }
}

/** A round icon button for the bar (share, save, plus). */
@Composable
fun BarButton(symbol: String, description: String? = null, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.28f)).press(0.9f) { Haptics.tap(); onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(sym(symbol), contentDescription = description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

// ---------------------------------------------------------------- toasts
@Composable
fun BoxScope.ToastOverlay() {
    val t = Toasts.current
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    AnimatedVisibility(
        visible = t != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = top + 8.dp, start = 24.dp, end = 24.dp),
    ) {
        val shown = remember { mutableStateOf(t) }
        if (t != null) shown.value = t
        val item = shown.value
        if (item != null) {
            Row(
                Modifier.shadow(14.dp, RoundedCornerShape(24.dp)).frosted(24.dp).padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(item.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Text(item.text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            }
        }
    }
}

// ---------------------------------------------------------------- "Add to a Playlist"
/** Asks for a name for a new playlist. */
@Composable
fun NewPlaylistDialog(message: String? = null, onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF26262C),
        titleContentColor = Color.White,
        textContentColor = Theme.text2,
        title = { Text("New Playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (message != null) Text(message)
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true, placeholder = { Text("Name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White, unfocusedTextColor = Color.White, cursorColor = Color.White,
                        focusedBorderColor = Color.White.copy(alpha = 0.6f), unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                    ),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onCreate(name) }) { Text("Create", color = Color.White, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Theme.text2) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddToPlaylistSheet(track: Track, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var askName by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        Library.loadAll()
        Library.loadAllPlaylists()
    }
    fun add(p: Playlist) {
        working = true
        scope.launch {
            val ok = Library.add(track, p)
            working = false
            if (ok) {
                Haptics.success()
                onDismiss()
            }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        containerColor = Color(0xF21A1A1F),
        contentColor = Color.White,
    ) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = Color.White) }
                    Text("Add to a Playlist", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f), maxLines = 1)
                    Spacer(Modifier.width(64.dp))
                }
            }
            item {
                Row(
                    Modifier.padding(16.dp).fillMaxWidth().glass(14.dp, 0.07f).padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(track.artMid, Modifier.size(44.dp), px = 150, corner = 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(track.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artistLine, color = Theme.text2, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                Row(
                    Modifier.fillMaxWidth().tap { askName = true }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(sym("plus"), contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("New Playlist…", color = Color.White, fontSize = 16.sp)
                }
            }
            items(Library.editablePlaylists, key = { it.id }) { p ->
                Row(
                    Modifier.fillMaxWidth().tap(enabled = !working) { add(p) }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(p.imageMid, Modifier.size(44.dp), px = 150, corner = 8.dp)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(p.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${p.total} songs", color = Theme.text2, fontSize = 12.sp)
                    }
                }
            }
            item {
                Text("Only playlists you made (or collaborate on) can take new songs.", color = Theme.text3, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
    }
    if (askName) {
        NewPlaylistDialog(message = "This song goes straight into it.", onDismiss = { askName = false }) { name ->
            scope.launch { Library.createPlaylist(name)?.let { add(it) } }
        }
    }
}
