package com.cara.dj

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var resumedNow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.start(applicationContext)
        Config.init(applicationContext)
        Engine.init(applicationContext)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        Haptics.view = window.decorView
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleIntent(intent)
        setContent { CaraApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        Engine.foreground = true
        Engine.poke()
        Engine.scope.launch { Library.loadAll() }
    }

    override fun onStop() {
        super.onStop()
        Engine.foreground = false
    }

    override fun onResume() {
        super.onResume()
        resumedNow = true
        // back from the browser without finishing the Spotify login: stop waiting for it
        if (Spotify.loginPending) Engine.scope.launch {
            delay(1500)
            if (resumedNow && Spotify.loginPending) Spotify.loginAbandoned()
        }
    }

    override fun onPause() {
        super.onPause()
        resumedNow = false
    }

    /** Spotify sends you back here (caradj://callback?code=...) after you log in. */
    private fun handleIntent(i: Intent?) {
        val d = i?.data ?: return
        if (d.scheme == "caradj") {
            if (!Spotify.onCallback(d)) Engine.scope.launch { Engine.finishLogin(d) }
        }
    }
}
