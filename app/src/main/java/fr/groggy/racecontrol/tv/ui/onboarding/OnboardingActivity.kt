package fr.groggy.racecontrol.tv.ui.onboarding

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
import dagger.hilt.android.AndroidEntryPoint
import fr.groggy.racecontrol.tv.BuildConfig
import fr.groggy.racecontrol.tv.R
import fr.groggy.racecontrol.tv.core.settings.Settings
import fr.groggy.racecontrol.tv.core.settings.SettingsRepository
import fr.groggy.racecontrol.tv.ui.MainActivity
import fr.groggy.racecontrol.tv.utils.LanguageOverride
import javax.inject.Inject

/**
 * First start: language, the feed a session opens on, and (when a radio is available) what plays
 * during a live session. Every answer is a normal setting, changeable later in Settings.
 */
@AndroidEntryPoint
class OnboardingActivity : FragmentActivity() {

    companion object {
        private const val KEY_DONE = "onboarding_done"
        /** The step to show: kept so a language change (which rebuilds the screen) continues there. */
        private const val KEY_STEP = "onboarding_step"

        fun isNeeded(context: Context): Boolean =
            !PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_DONE, false) || missesNewSetting(context)

        /** Set up before the commentary choice existed: ask only that (once). */
        private fun missesNewSetting(context: Context): Boolean {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return prefs.getBoolean(KEY_DONE, false) && !prefs.contains(Settings.KEY_AUDIO_LANGUAGE)
        }

        fun intent(context: Context) = Intent(context, OnboardingActivity::class.java)
    }

    @Inject internal lateinit var settingsRepository: SettingsRepository

    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }

    private data class Option(val label: String, val detail: String?, val selected: Boolean, val onPick: () -> Unit)

    private val radioAvailable: Boolean
        get() = BuildConfig.CUSTOM_RADIO_URL.isNotBlank() || settingsRepository.getCurrent().customRadioUrl.isNotBlank()

    private val stepCount: Int get() = if (radioAvailable) 4 else 3

    /** An existing user who only gets the new commentary question */
    private var onlyNewSetting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onlyNewSetting = missesNewSetting(this)
        showStep(if (onlyNewSetting) 2 else prefs.getInt(KEY_STEP, 0))
    }

    private fun showStep(step: Int) {
        prefs.edit { putInt(KEY_STEP, step) }
        when (step) {
            0 -> languageStep()
            1 -> feedStep()
            2 -> commentaryStep()
            3 -> if (radioAvailable && !onlyNewSetting) audioStep() else finishOnboarding()
            else -> finishOnboarding()
        }
    }

    private fun commentaryStep() {
        val current = prefs.getString(Settings.KEY_AUDIO_LANGUAGE, null)
            ?: java.util.Locale.getDefault().language.takeIf { it in listOf("en", "nl", "de", "fr", "es", "pt", "ja") }
            ?: Settings.AUDIO_LANGUAGE_STREAM
        fun pick(value: String) = {
            prefs.edit { putString(Settings.KEY_AUDIO_LANGUAGE, value) }
            settingsRepository.applySettings()
            showStep(3)
        }
        val labels = resources.getStringArray(R.array.audio_language_entries)
        val values = resources.getStringArray(R.array.audio_language_values)
        render(
            step = 2,
            title = getString(R.string.onboarding_audio_language_title),
            subtitle = getString(R.string.onboarding_audio_language_subtitle),
            options = values.indices.map { i -> Option(labels[i], null, current == values[i], pick(values[i])) },
            grid = true
        )
    }

    private fun languageStep() {
        val current = prefs.getString(LanguageOverride.KEY, null) ?: BuildConfig.DEFAULT_LANGUAGE
        fun pick(tag: String) = {
            prefs.edit {
                putString(LanguageOverride.KEY, tag)
                putInt(KEY_STEP, 1)
            }
            // May rebuild this screen in the new language; onCreate then continues at step 1
            LanguageOverride.apply(application)
            showStep(1)
        }
        render(
            step = 0,
            title = getString(R.string.onboarding_language_title),
            subtitle = getString(R.string.onboarding_language_subtitle),
            options = listOf(
                Option("Nederlands", null, current == "nl", pick("nl")),
                Option("English", null, current == "en", pick("en")),
                Option(getString(R.string.onboarding_language_system), null, current == "auto", pick("auto"))
            )
        )
    }

    private fun feedStep() {
        val current = settingsRepository.getCurrent().defaultFeed
        fun pick(feed: String) = {
            prefs.edit { putString(Settings.KEY_DEFAULT_FEED, feed) }
            settingsRepository.applySettings()
            showStep(2)
        }
        render(
            step = 1,
            title = getString(R.string.onboarding_feed_title),
            subtitle = getString(R.string.onboarding_feed_subtitle),
            options = listOf(
                Option(getString(R.string.onboarding_feed_international), getString(R.string.onboarding_feed_international_detail),
                    current == Settings.FEED_INTERNATIONAL, pick(Settings.FEED_INTERNATIONAL)),
                Option(getString(R.string.onboarding_feed_f1live), getString(R.string.onboarding_feed_f1live_detail),
                    current == Settings.FEED_F1_LIVE, pick(Settings.FEED_F1_LIVE))
            )
        )
    }

    private fun audioStep() {
        val radioNow = settingsRepository.getCurrent().autoSelectCustomRadio
        fun pick(radio: Boolean) = {
            prefs.edit {
                putBoolean(Settings.KEY_AUTO_SELECT_CUSTOM_RADIO, radio)
                // The radio starts by itself only in live sessions; in a replay it stays in the menu
                putBoolean(Settings.KEY_RESTRICT_CUSTOM_RADIO_TO_LIVE_SESSIONS, true)
            }
            settingsRepository.applySettings()
            finishOnboarding()
        }
        render(
            step = 3,
            title = getString(R.string.onboarding_audio_title),
            subtitle = getString(R.string.onboarding_audio_subtitle),
            options = listOf(
                Option(getString(R.string.onboarding_audio_feed), getString(R.string.onboarding_audio_feed_detail),
                    !radioNow, pick(false)),
                Option(getString(R.string.onboarding_audio_radio), getString(R.string.onboarding_audio_radio_detail),
                    radioNow, pick(true))
            )
        )
    }

    private fun finishOnboarding() {
        prefs.edit {
            putBoolean(KEY_DONE, true)
            remove(KEY_STEP)
        }
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    /** One screen of the redesign: step counter, big title, explanation, and the choices as tiles. */
    private fun render(step: Int, title: String, subtitle: String, options: List<Option>, grid: Boolean = false) {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        val wide = resources.configuration.screenWidthDp >= 720
        val textColor = ContextCompat.getColor(this, R.color.rc_text)
        val secondary = ContextCompat.getColor(this, R.color.rc_text_secondary)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(if (wide) 96 else 24), dp(if (wide) 64 else 32), dp(if (wide) 96 else 24), dp(32))
        }
        column.addView(TextView(this).apply {
            text = (if (onlyNewSetting) getString(R.string.onboarding_new_setting)
                else getString(R.string.onboarding_step, step + 1, stepCount)).uppercase()
            setTextColor(ContextCompat.getColor(this@OnboardingActivity, R.color.rc_accent))
            textSize = 14f
            letterSpacing = 0.06f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        column.addView(TextView(this).apply {
            text = title
            setTextColor(textColor)
            textSize = if (wide) 40f else 30f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        })
        column.addView(TextView(this).apply {
            text = subtitle
            setTextColor(secondary)
            textSize = 16f
            setPadding(0, dp(6), 0, dp(28))
        })

        var focusTarget: View? = null
        // Many short choices (languages): two columns, so they fit on a TV screen without scrolling
        val columns = if (grid && wide) 2 else 1
        var rowLayout: LinearLayout? = null
        options.forEachIndexed { optionIndex, option ->
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                isActivated = option.selected
                setBackgroundResource(R.drawable.onboarding_option_background)
                // Language grid: lower tiles, so all choices fit on a TV screen
                val vertical = if (columns > 1) 8 else 14
                setPadding(dp(22), dp(vertical), dp(22), dp(vertical))
                setOnClickListener { option.onPick() }
            }
            tile.addView(TextView(this).apply {
                text = if (option.selected) "▶ ${option.label}" else option.label
                setTextColor(textColor)
                textSize = 22f
                typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            })
            option.detail?.let { detail ->
                tile.addView(TextView(this).apply {
                    text = detail
                    setTextColor(secondary)
                    textSize = 14f
                })
            }
            if (columns == 1) {
                column.addView(tile, LinearLayout.LayoutParams(
                    if (wide) dp(520) else ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(12) })
            } else {
                if (optionIndex % columns == 0) {
                    rowLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                    column.addView(rowLayout)
                }
                rowLayout?.addView(tile, LinearLayout.LayoutParams(dp(340), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dp(10)
                    marginEnd = dp(12)
                })
            }
            if (focusTarget == null || option.selected) focusTarget = tile
        }

        setContentView(ScrollView(this).apply {
            setBackgroundColor(ContextCompat.getColor(this@OnboardingActivity, R.color.rc_background))
            isFillViewport = true
            addView(column)
        })
        focusTarget?.let { target -> target.post { target.requestFocus() } }
    }

    override fun onBackPressed() {
        val step = prefs.getInt(KEY_STEP, 0)
        if (onlyNewSetting) {
            // Not answered: keep the stream's own commentary, and do not ask again
            prefs.edit { putString(Settings.KEY_AUDIO_LANGUAGE, Settings.AUDIO_LANGUAGE_STREAM) }
            finishOnboarding()
        } else if (step > 0) showStep(step - 1) else super.onBackPressed()
    }
}
