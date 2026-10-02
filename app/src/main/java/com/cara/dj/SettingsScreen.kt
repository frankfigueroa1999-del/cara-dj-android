package com.cara.dj

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Keys, Spotify connection, the three voices and her town, on the same frosted glass as the rest of the app. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var cityText by remember { mutableStateOf(Config.city) }
    var confirmLogout by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val models = listOf(
        "eleven_v4" to "Eleven v4 (most expressive)",
        "eleven_v4_turbo" to "Eleven v4 Turbo (faster)",
        "eleven_multilingual_v2" to "Multilingual v2 (older)",
    )
    fun done() {
        val c = cityText
        scope.launch { Engine.changeCity(c) }
        onDismiss()
    }
    ModalBottomSheet(
        onDismissRequest = { done() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xF21A1A1F),
        contentColor = Color.White,
    ) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
                Text("Settings", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                TextButton(onClick = { done() }, modifier = Modifier.align(Alignment.CenterEnd)) {
                    Text("Done", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 48.dp)) {
                // you and Spotify
                item {
                    FormSection {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Artwork(Library.me?.image, Modifier.size(54.dp), px = 150, circle = true)
                            Spacer(Modifier.width(14.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(Library.me?.name ?: (if (Engine.loggedIn) "Spotify" else "Not connected"), color = Color.White,
                                    fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Box(Modifier.size(7.dp).background(if (Engine.connected) Theme.green else Color.White.copy(alpha = 0.35f), CircleShape))
                                    Text(
                                        if (Engine.loggedIn) (if (Engine.connected) "Connected to Spotify" else "Logged in") else "Connect to use the player",
                                        color = Theme.text2, fontSize = 14.sp,
                                    )
                                }
                            }
                        }
                        if (Engine.loggedIn) {
                            if (Library.needsReconnect) {
                                FormDivider()
                                FormButton("Reconnect Spotify (unlocks your library)") {
                                    onDismiss()
                                    Engine.scope.launch { Engine.connect(forceLogin = true) }
                                }
                            }
                            FormDivider()
                            FormButton("Log Out of Spotify", color = Color(0xFFFF453A)) { confirmLogout = true }
                        } else {
                            FormDivider()
                            FormButton("Connect Spotify", enabled = Config.clientID.trim().isNotEmpty()) {
                                onDismiss()
                                Engine.scope.launch { Engine.connect(forceLogin = true) }
                            }
                        }
                    }
                }
                item {
                    FormSection(
                        header = "Spotify App",
                        footer = "From developer.spotify.com. Its Redirect URI must be caradj://callback, and everyone using it has to be added under User Management (5 people at most).",
                    ) {
                        FormField("Client ID", Config.clientID) { Config.clientID = it.trim() }
                    }
                }
                item {
                    FormSection(header = "Her Voice (ElevenLabs)") {
                        FormField("API key (starts with sk_)", Config.elevenKey, secure = true) { Config.elevenKey = it.trim() }
                        FormDivider()
                        FormField("Voice ID", Config.elevenVoice) { Config.elevenVoice = it.trim() }
                        FormDivider()
                        Box {
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).tap { modelMenu = true }.padding(horizontal = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("Model", color = Color.White, fontSize = 17.sp, modifier = Modifier.weight(1f))
                                Text(models.firstOrNull { it.first == Config.elevenModel }?.second ?: Config.elevenModel, color = Theme.text2, fontSize = 15.sp)
                                Icon(sym("chevron.down"), contentDescription = null, tint = Theme.text2, modifier = Modifier.size(18.dp))
                            }
                            DarkMenu(modelMenu, { modelMenu = false }) {
                                for ((tag, title) in models) {
                                    MenuItem(title, if (Config.elevenModel == tag) "checkmark" else "waveform") {
                                        modelMenu = false
                                        Config.elevenModel = tag
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    FormSection(
                        header = "Scratch's Voice (Co-Host)",
                        footer = "MC Scratch, Cara's co-host. Add any voice from the ElevenLabs Voice Library to My Voices and paste its ID here, or leave it blank for a deep radio voice.",
                    ) {
                        FormField("Voice ID (blank for the default)", Config.coVoice) { Config.coVoice = it.trim() }
                    }
                }
                item {
                    FormSection(
                        header = "Station Voice (Stingers)",
                        footer = "The announcer on your station stingers. Add any voice from the ElevenLabs Voice Library to My Voices, then paste its ID here. Leave it blank for the default.",
                    ) {
                        FormField("Voice ID (blank for the default)", Config.stationVoice) { Config.stationVoice = it.trim() }
                    }
                }
                item {
                    FormSection(header = "Her Words (Gemini)", footer = "Without a key she still talks, using simpler built-in lines.") {
                        FormField("API key", Config.geminiKey, secure = true) { Config.geminiKey = it.trim() }
                    }
                }
                item {
                    FormSection(header = "Your Town", footer = "Town, State. Used for local news and weather.") {
                        FormField("Yakima, Washington", cityText, onDone = { scope.launch { Engine.changeCity(cityText) } }) { cityText = it }
                    }
                }
                item {
                    FormSection(footer = "Cara DJ 2.0. Music plays through the Spotify app; Cara talks over it from here.") {
                        FormButton("Show the Welcome Setup Again") {
                            onDismiss()
                            Engine.scope.launch {
                                delay(700)
                                Config.welcomed = false
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            containerColor = Color(0xFF26262C),
            titleContentColor = Color.White,
            title = { Text("Log out of Spotify?") },
            confirmButton = {
                TextButton(onClick = { confirmLogout = false; Engine.logout() }) { Text("Log Out", color = Color(0xFFFF453A), fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancel", color = Color.White) } },
        )
    }
}

/** One rounded group of rows, with its small header and grey footnote, like an iPhone settings form. */
@Composable
private fun FormSection(header: String? = null, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        if (header != null) {
            Text(header.uppercase(), color = Theme.text2, fontSize = 13.sp, letterSpacing = 0.4.sp,
                modifier = Modifier.padding(start = 16.dp, bottom = 7.dp))
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.07f)), content = content)
        if (footer != null) {
            Text(footer, color = Theme.text2, fontSize = 13.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 7.dp))
        }
    }
}

@Composable
private fun FormDivider() {
    Box(Modifier.padding(start = 16.dp).fillMaxWidth().heightIn(min = 0.5.dp, max = 0.5.dp).background(Color.White.copy(alpha = 0.12f)))
}

@Composable
private fun FormButton(title: String, color: Color = Color.White, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.4f).tap(enabled = enabled) { Haptics.tap(); onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(title, color = color, fontSize = 17.sp)
    }
}

/** A text box row: grey placeholder, white text, no auto-correct (keys and IDs are pasted in). */
@Composable
private fun FormField(placeholder: String, value: String, secure: Boolean = false, onDone: (() -> Unit)? = null, onChange: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, color = Theme.text3, fontSize = 17.sp)
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
            cursorBrush = SolidColor(Color.White),
            visualTransformation = if (secure) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = if (secure) KeyboardType.Password else KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
