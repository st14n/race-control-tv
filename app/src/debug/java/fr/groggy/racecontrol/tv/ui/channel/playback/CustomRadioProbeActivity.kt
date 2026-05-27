package fr.groggy.racecontrol.tv.ui.channel.playback

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.core.settings.Settings

class CustomRadioProbeActivity : Activity() {
    private var engine: CustomRadioEngine? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply {
            text = "Custom radio probe running"
            textSize = 20f
            setPadding(32, 32, 32, 32)
        })

        val sourceName = intent.getStringExtra("source") ?: "normalized"
        val customUrl = intent.getStringExtra("url")
        val source = when (sourceName) {
            "url" -> CustomRadioSource(
                name = "probe-url",
                url = customUrl.orEmpty(),
                normalizeWithInAppHls = true
            )
            "normalized" -> CustomRadioSources.defaultCandidate
                ?: CustomRadioSource(
                    name = "probe-configured",
                    url = BuildConfig.CUSTOM_RADIO_URL,
                    normalizeWithInAppHls = true
                )
            else -> CustomRadioSource(
                name = "probe-configured",
                url = BuildConfig.CUSTOM_RADIO_URL,
                normalizeWithInAppHls = true
            )
        }
        val initialDelayMs = intent.getLongExtra("delayMs", 20_000L)
        Log.i(TAG, "starting source=${source.name} delayMs=$initialDelayMs url=${source.url}")
        engine = createCustomRadioEngine(
            context = this,
            planEntry = CustomRadioPlanEntry(
                Settings.CustomRadioBackend.EXOPLAYER,
                source
            ),
            userAgent = BuildConfig.DEFAULT_USER_AGENT,
            initialAudioDelayMs = initialDelayMs,
            initialVolume = 100,
            onStarted = {
                Log.i(TAG, "engine started")
                if (intent.getBooleanExtra("script", false)) {
                    runDelayScript(initialDelayMs)
                }
            },
            onEnded = { Log.w(TAG, "engine ended") },
            onError = { code, throwable -> Log.e(TAG, "engine error code=$code", throwable) }
        )
    }

    override fun onDestroy() {
        Log.i(TAG, "stopping")
        handler.removeCallbacksAndMessages(null)
        engine?.release()
        engine = null
        super.onDestroy()
    }

    private fun runDelayScript(initialDelayMs: Long) {
        val steps = listOf(
            2_000L to initialDelayMs + 500L,
            4_000L to initialDelayMs,
            6_000L to initialDelayMs - 500L,
            8_000L to initialDelayMs,
            10_000L to initialDelayMs + 500L
        )
        for ((atMs, delayMs) in steps) {
            handler.postDelayed({
                Log.i(TAG, "script setAudioDelay($delayMs)")
                engine?.setAudioDelay(delayMs)
            }, atMs)
        }
    }

    companion object {
        private const val TAG = "CustomRadioProbe"
    }
}