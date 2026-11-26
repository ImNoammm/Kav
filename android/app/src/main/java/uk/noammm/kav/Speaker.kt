package uk.noammm.kav

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import uk.noammm.kav.ui.Lang
import uk.noammm.kav.ui.T
import java.util.Locale

// Trip alerts said out loud ("your bus is arriving"): the phone's own text-to-speech, in Kav's
// language, over music the way a navigation app talks: the music goes quieter while Kav speaks and comes back right after
// (issue 74: in Bluetooth earphones Kav could hardly be heard over it). A phone with no voice for that language keeps to
// the sound. During a phone call only when the rider asked for it (Prefs.spokenInCalls): a bus arriving and getting off,
// in the call's own sound, which only they hear.
internal object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null
    private var pendingKey: String? = null
    private var unsaid: (() -> Unit)? = null
    private val waits = Handler(Looper.getMainLooper())

    // onSilent: told when nothing can be said, the phone having no text-to-speech engine or no voice for Kav's language
    // (Settings says so when the rider turns speech on, where the trip's alerts just keep to their sound).
    // key: the trip alert's (TripAlert.key), which decides whether it may be said during a call; none, never then.
    fun say(ctx: Context, text: String, key: String? = null, onSilent: (() -> Unit)? = null) {
        val engine = tts
        if (engine != null && ready) { if (!speak(ctx, engine, text, key)) onSilent?.invoke(); return }
        pending = text
        pendingKey = key
        unsaid = onSilent
        if (engine == null) connect(ctx)
    }

    // An alert whose notification sounds at the same moment: said at once, that sound cuts the words off. So the words
    // wait for the sound to start and then to end, however long the phone's own is; with none started in time (a phone
    // on vibrate talking into headphones) they go ahead. A later alert's words take the place of ones still waiting.
    fun sayAfterSound(ctx: Context, text: String, key: String? = null) {
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
                else say(app, text, key)
            }
        })
    }

    // The engine connected ahead of the first alert, when a trip starts: connecting takes a few seconds, which the first
    // thing said would otherwise wait for (a turn 25 m ahead).
    fun warm(ctx: Context) { if (tts == null) connect(ctx) }

    private fun connect(ctx: Context) {
        val app = ctx.applicationContext
        tts = TextToSpeech(app) { status ->
            ready = status == TextToSpeech.SUCCESS
            val started = tts
            started?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = done(id)
                @Deprecated("Deprecated in Java")
                override fun onError(id: String?) = done(id)
                override fun onError(id: String?, code: Int) = done(id)
                override fun onStop(id: String?, interrupted: Boolean) = done(id)
            })
            val said = ready && started != null && pending?.let { speak(app, started, it, pendingKey) } != false
            if (!said) {
                unsaid?.invoke()
                // An engine installed later is found by the next try.
                if (!ready) { started?.shutdown(); tts = null }
            }
            pending = null
            pendingKey = null
            unsaid = null
        }
    }

    // Whether the text went to the engine: not when it has no voice for Kav's language. During a call what may not be
    // said there isn't, and that counts as said (it isn't a missing voice).
    private fun speak(ctx: Context, engine: TextToSpeech, text: String, key: String?): Boolean {
        val main = if (T.lang == Lang.HE) HEBREW else Locale.UK
        // A street's name kept apart from the sentence round it (turnText) is said as one; the marks aren't words.
        val said = text.replace('/', ',').replace(Regex("[\u2066-\u2069]"), "")
        val am = ctx.getSystemService(AudioManager::class.java)
        val call = am != null && inCall(am)
        val voice = callVoice(call, call && Prefs.spokenInCalls(ctx), key, if (call) callOutputs(am!!) else emptyList())
        if (voice == CallVoice.NONE) return true
        engine.setAudioAttributes(if (voice == CallVoice.CALL) IN_CALL else VOICE)
        // In English, a name in Hebrew letters (a street's, as the planners give them, or a stop's) is said by the Hebrew
        // voice, which can read it, between the English words; a phone with no Hebrew voice leaves it out.
        val parts = if (main == HEBREW) listOf(said to main) else spokenRuns(said)
        // The call has the sound to itself: nothing to lower, and asking would be refused.
        if (voice == CallVoice.USUAL && !duck(ctx)) return true
        var first = true
        val id = "kav-alert-${++alerts}"
        for ((i, pair) in parts.withIndex()) {
            val (part, voice) = pair
            val found = engine.setLanguage(voice)
            if (found == TextToSpeech.LANG_MISSING_DATA || found == TextToSpeech.LANG_NOT_SUPPORTED) {
                if (voice == main) { release(); return false } else continue
            }
            // Each part its own id, the last one's the alert's: the music comes back once that one is said.
            engine.speak(part, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$id-$i")
            last = "$id-$i"
            first = false
        }
        if (first) release()
        return !first
    }

    // The other sound lowered while Kav speaks (transient, may duck: music goes quieter, never pauses), asked as a
    // navigation app's voice. Refused, as during a call, nothing is said; a call coming while Kav speaks stops it.
    private fun duck(ctx: Context): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return true
        if (inCall(am)) return false
        val ask = focus ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(VOICE)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    tts?.stop(); release()
                }
            }, waits)
            .build().also { focus = it }
        if (am.requestAudioFocus(ask) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        audio = am
        // However the engine ends (or never says it has), the music comes back.
        giveBack.removeCallbacksAndMessages(null)
        giveBack.postDelayed({ release() }, FOCUS_AT_MOST_MS)
        return true
    }

    private fun release() {
        giveBack.removeCallbacksAndMessages(null)
        val f = focus ?: return
        audio?.abandonAudioFocusRequest(f)
        audio = null
    }

    // An alert said, stopped or failed: the other sound comes back. Not for an earlier alert's words cut off by a newer
    // one, which is still being said.
    private fun done(id: String?) {
        if (id != null && id == last) release()
    }

    private var focus: AudioFocusRequest? = null
    @Volatile private var audio: AudioManager? = null
    @Volatile private var last: String? = null
    private var alerts = 0
    private val giveBack = Handler(Looper.getMainLooper())
    // The longest an alert keeps the music down: a turn or "your bus is arriving" takes a few seconds to say.
    private const val FOCUS_AT_MOST_MS = 20_000L

    private val VOICE = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    // Said into a call: on its own sound, at its volume, where the rider hears the other side.
    private val IN_CALL = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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

    fun inCall(ctx: Context): Boolean = ctx.getSystemService(AudioManager::class.java)?.let(::inCall) == true
    private fun inCall(am: AudioManager) = am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION

    // Where the call's sound goes now, as AudioDeviceInfo types. Before Android 12 only whether the speakerphone is on.
    @Suppress("DEPRECATION")
    private fun callOutputs(am: AudioManager): List<Int> = when {
        Build.VERSION.SDK_INT >= 33 -> am.getAudioDevicesForAttributes(IN_CALL).map { it.type }
        Build.VERSION.SDK_INT >= 31 -> listOfNotNull(am.communicationDevice?.type)
        am.isSpeakerphoneOn -> listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        else -> listOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)
    }

    fun shutdown() {
        waits.removeCallbacksAndMessages(null)
        last = null
        release()
        tts?.shutdown()
        tts = null
        ready = false
        pending = null
        pendingKey = null
        unsaid = null
    }
}

internal enum class CallVoice { USUAL, CALL, NONE }

// How a trip alert may be said: with no call, as always. During one, only when the rider asked for it, only a bus
// arriving and getting off, and only while the call's sound goes where just the rider hears it: the earpiece or something
// on their ears. On the speakerphone, or anywhere Kav can't tell, the other side might hear it, so nothing is said.
internal fun callVoice(inCall: Boolean, inCalls: Boolean, key: String?, outputs: List<Int>): CallVoice = when {
    !inCall -> CallVoice.USUAL
    !inCalls || key == null || !(key.startsWith("wait-") || key.startsWith("ride-")) -> CallVoice.NONE
    outputs.isEmpty() || outputs.any { it !in TO_THE_EAR } -> CallVoice.NONE
    else -> CallVoice.CALL
}

private val TO_THE_EAR = setOf(
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_HEARING_AID,
)

// The text in runs of Hebrew letters and the rest, each with the voice that reads it.
internal fun spokenRuns(text: String): List<Pair<String, Locale>> =
    Regex("[֐-׿][֐-׿0-9'\"׳״\\- ]*[֐-׿0-9'\"׳״]|[֐-׿]|[^֐-׿]+")
        .findAll(text).map { it.value }.filter { it.isNotBlank() }
        .map { it.trim() to if (it.any { c -> c in '֐'..'׿' }) HEBREW else Locale.UK }.toList()

private val HEBREW = Locale.forLanguageTag("he-IL")
