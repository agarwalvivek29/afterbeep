package dev.afterbeep.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AppSettings(
    val enabled: Boolean = true,
    val ringSeconds: Int = 15,
    val maxSeconds: Int = 120,
    val silenceLocal: Boolean = true,
    val starredRingThrough: Boolean = false,
    /** A second call from the same number within 3 minutes rings normally (probably urgent). */
    val repeatRingThrough: Boolean = true,
    val autoStart: Boolean = true,
    val paired: Boolean = false,
    val onboarded: Boolean = false,
    /** True after a successful start until the user taps Stop — lets us notice the engine dying. */
    val engineWanted: Boolean = false,
    val statusNotification: Boolean = true,
    /** Engine rejects every "Allow debugging?" prompt; new computers only via Wireless-debugging pairing code. */
    val lockDebugging: Boolean = true,
    val greetingText: String = DEFAULT_GREETING,
) {
    companion object {
        const val DEFAULT_GREETING =
            "Hi, you've reached me. I can't take your call right now, but leave a message after the beep and I'll get back to you."
    }
}

class SettingsStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("afterbeep", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    private fun load() = AppSettings(
        enabled = prefs.getBoolean("enabled", true),
        ringSeconds = prefs.getInt("ringSeconds", 15),
        maxSeconds = prefs.getInt("maxSeconds", 120),
        silenceLocal = prefs.getBoolean("silenceLocal", true),
        starredRingThrough = prefs.getBoolean("starredRingThrough", false),
        repeatRingThrough = prefs.getBoolean("repeatRingThrough", true),
        autoStart = prefs.getBoolean("autoStart", true),
        paired = prefs.getBoolean("paired", false),
        onboarded = prefs.getBoolean("onboarded", false),
        engineWanted = prefs.getBoolean("engineWanted", false),
        statusNotification = prefs.getBoolean("statusNotification", true),
        lockDebugging = prefs.getBoolean("lockDebugging", true),
        greetingText = prefs.getString("greetingText", AppSettings.DEFAULT_GREETING) ?: AppSettings.DEFAULT_GREETING,
    )

    fun update(block: (AppSettings) -> AppSettings) {
        val s = block(_state.value)
        prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putInt("ringSeconds", s.ringSeconds)
            .putInt("maxSeconds", s.maxSeconds)
            .putBoolean("silenceLocal", s.silenceLocal)
            .putBoolean("starredRingThrough", s.starredRingThrough)
            .putBoolean("repeatRingThrough", s.repeatRingThrough)
            .putBoolean("autoStart", s.autoStart)
            .putBoolean("paired", s.paired)
            .putBoolean("onboarded", s.onboarded)
            .putBoolean("engineWanted", s.engineWanted)
            .putBoolean("statusNotification", s.statusNotification)
            .putBoolean("lockDebugging", s.lockDebugging)
            .putString("greetingText", s.greetingText)
            .apply()
        _state.value = s
    }
}
