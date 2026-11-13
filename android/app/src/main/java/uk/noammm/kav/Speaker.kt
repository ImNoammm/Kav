package uk.noammm.kav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import uk.noammm.kav.ui.Lang
import uk.noammm.kav.ui.T
import java.util.Locale

// Trip alerts said out loud ("your bus is arriving"): the phone's own text-to-speech, in Kav's
// language, over music the way a navigation app talks. A phone with no voice for that language keeps to the sound.
internal object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var unsaid: (() -> Unit)? = null
    private val waits = Handler(Looper.getMainLooper())

    // onSilent: told when nothing can be said, the phone having no text-to-speech engine or no voice for Kav's language
    // (Settings says so when the rider turns speech on, where the trip's alerts just keep to their sound).
    fun say(ctx: Context, text: String, onSilent: (() -> Unit)? = null) {
        val engine = tts
        if (engine != null && ready) { if (!speak(engine, text)) onSilent?.invoke(); return }
        pending = text
        unsaid = onSilent
        if (engine == null) connect(ctx)
    }

    // An alert whose notification sounds at the same moment: said at once, that sound cuts the words off. So the words
    // wait for the sound to start and then to end, however long the phone's own is; with none started in time (a phone
    // on vibrate talking into headphones) they go ahead. A later alert's words take the place of ones still waiting.
    fun sayAfterSound(ctx: Context, text: String) {
        val app = ctx.applicationContext
        val am = app.getSystemService(AudioManager::class.java)
        val from = SystemClock.uptimeMillis()
        waits.removeCallbacksAndMessages(null)
        waits.post(object : Runnable {
            var heard = false
            override fun run() {
                val sounding = am?.activePlaybackConfigurations.orEmpty().any { it.audioAttributes.usage in ALERT_SOUNDS }
                heard = heard || sounding
                val waited = SystemClock.uptimeMillis() - from
                if (sounding && waited < SOUND_AT_MOST_MS || !heard && waited < SOUND_STARTS_MS) waits.postDelayed(this, 100)
                else say(app, text)
            }
        })
    }

    // The engine connected ahead of the first alert, when a trip starts: connecting takes a few seconds, which the first
    // thing said would otherwise wait for (a turn 25 m ahead).
    fun warm(ctx: Context) { if (tts == null) connect(ctx) }

    private fun connect(ctx: Context) {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            val started = tts
            val said = ready && started != null && pending?.let { speak(started, it) } != false
            if (!said) {
                unsaid?.invoke()
                // An engine installed later is found by the next try.
                if (!ready) { started?.shutdown(); tts = null }
            }
            pending = null
            unsaid = null
        }
    }

    // Whether the text went to the engine: not when it has no voice for Kav's language.
    private fun speak(engine: TextToSpeech, text: String): Boolean {
        val main = if (T.lang == Lang.HE) HEBREW else Locale.UK
        // A street's name kept apart from the sentence round it (turnText) is said as one; the marks aren't words.
        val said = text.replace('/', ',').replace(Regex("[\u2066-\u2069]"), "")
        engine.setAudioAttributes(VOICE)
        // In English, a name in Hebrew letters (a street's, as the planners give them, or a stop's) is said by the Hebrew
        // voice, which can read it, between the English words; a phone with no Hebrew voice leaves it out.
        val parts = if (main == HEBREW) listOf(said to main) else spokenRuns(said)
        var first = true
        for ((part, voice) in parts) {
            val found = engine.setLanguage(voice)
            if (found == TextToSpeech.LANG_MISSING_DATA || found == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (voice == main) return false else continue
            }
            engine.speak(part, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "kav-alert")
            first = false
        }
        return !first
    }

    private val VOICE = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val ALERT_SOUNDS = setOf(AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.USAGE_NOTIFICATION_EVENT)
    // A notification's sound starts within a second of it being posted, and one longer than this isn't waited out.
    private const val SOUND_STARTS_MS = 1_500L
    private const val SOUND_AT_MOST_MS = 5_000L
    private val EARS = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID)

    // Whether a trip alert may be said now: always with the ringer on; on silent or vibrate only into Bluetooth
    // headphones, never out of the phone's own speaker.
    fun mayTalk(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return true
        if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) return true
        val out = if (Build.VERSION.SDK_INT >= 33) am.getAudioDevicesForAttributes(VOICE).map { it.type }
            else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }.filter { it in EARS }
        return out.isNotEmpty() && out.all { it in EARS }
    }

    fun shutdown() {
        waits.removeCallbacksAndMessages(null)
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
        unsaid = null
    }
}

// The text in runs of Hebrew letters and the rest, each with the voice that reads it.
internal fun spokenRuns(text: String): List<Pair<String, Locale>> =
    Regex("[֐-׿][֐-׿0-9'\"׳״\\- ]*[֐-׿0-9'\"׳״]|[֐-׿]|[^֐-׿]+")
        .findAll(text).map { it.value }.filter { it.isNotBlank() }
        .map { it.trim() to if (it.any { c -> c in '֐'..'׿' }) HEBREW else Locale.UK }.toList()

private val HEBREW = Locale.forLanguageTag("he-IL")
