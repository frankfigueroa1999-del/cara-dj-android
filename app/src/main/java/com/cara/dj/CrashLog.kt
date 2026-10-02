package com.cara.dj

import android.content.Context
import java.io.File

/** If the app ever crashes, this keeps the reason so it can be shown the next time it opens. */
object CrashLog {
    private var file: File? = null

    /** Call once, as the app starts. */
    fun start(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "cara-last-crash.txt")
        file = f
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val first = (e.javaClass.simpleName + ": " + (e.message ?: "")).take(300)
                f.writeText(first + "\n" + e.stackTraceToString().take(6000))
            } catch (x: Exception) { }
            previous?.uncaughtException(t, e)
        }
    }

    /** Why the app stopped last time, if it crashed (null when it closed normally). Only says it once. */
    fun lastCrash(): String? {
        val f = file ?: return null
        if (!f.exists()) return null
        val first = try { f.readLines().firstOrNull() } catch (e: Exception) { null }
        f.delete()
        return first
    }
}
