package com.cara.dj

import androidx.compose.runtime.Composable

// Temporary stand-ins while the real screens are written (each lives in its own file).
@Composable fun HomeScreen() = Page(title = "Home") {}
@Composable fun CaraScreen() = Page(title = "Cara") {}
@Composable fun LibraryScreen() = Page(title = "Library") {}
@Composable fun SearchScreen() = Page(title = "Search") {}
@Composable fun AlbumScreen(entry: Entry, seed: Album) = Page(back = true) {}
@Composable fun ArtistScreen(entry: Entry, seed: Artist) = Page(back = true) {}
@Composable fun PlaylistScreen(entry: Entry, seed: Playlist) = Page(back = true) {}
@Composable fun LikedSongsScreen() = Page(back = true) {}
@Composable fun PlaylistsListScreen() = Page(back = true) {}
@Composable fun AlbumsGridScreen() = Page(back = true) {}
@Composable fun ArtistsListScreen() = Page(back = true) {}
@Composable fun GenreScreen(entry: Entry, genre: Genre) = Page(back = true) {}
@Composable fun NowPlayingScreen() {}
@Composable fun SettingsSheet(onDismiss: () -> Unit) {}
@Composable fun WelcomeScreen() {}
