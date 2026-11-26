package uk.noammm.kav.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.togetherWith
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import uk.noammm.kav.MainActivity
import uk.noammm.kav.Prefs
import uk.noammm.kav.TripBridge
import uk.noammm.kav.tripNotice

// A button of Kav's own over Rav-Pass, after a Pay tap, that brings the rider back to Kav in one tap. It needs Android's
// "Display over other apps", which the rider turns on or not; without it the hand-off is as it always was. It only
// draws: it reads nothing of Rav-Pass's screen and takes no touch but its own.
//
// Rav-Pass opens inside Kav's own task (its pay link is started without a new task), so Kav can tell from its own task
// whether Rav-Pass is what's in front. Two things in Rav-Pass take it further, and the button stays through both: its
// sign-in screen puts Google's phone number picker over itself, another app's screen in Kav's task, and signing in
// starts Rav-Pass again in a task of its own (a new task that clears it), which Kav's task can't see but the ticket
// reader can. Only from Android 12L (API 32) does Android say whether a task is visible
// (TaskInfo.isVisible); before it Kav could see that Rav-Pass is on top of its task but not whether the rider has gone
// Home or to another app since, so there the button is never offered.
internal object PayOverlay {
    const val MIN_SDK = 32
    // How long it waits for Rav-Pass to come up after the tap, and how long it keeps watching once hidden (the phone
    // locked while paying, say) before giving up until the next Pay tap.
    const val WAIT_MS = 3_000L
    const val GIVE_UP_MS = 5 * 60_000L
    const val AWAY_MS = 60_000L

    // Whether Kav's task is in split screen, as Kav's own screen (the task's root) last heard (MainActivity).
    @Volatile var split = false

    fun supported(sdk: Int = Build.VERSION.SDK_INT) = sdk >= MIN_SDK

    // What Kav can see of its own task and the phone at one look.
    data class Seen(
        val ravPassOnTop: Boolean, val taskVisible: Boolean, val screenOn: Boolean, val locked: Boolean,
        val allowed: Boolean, val chosen: Boolean,
        // Kav's task shares the screen with another app (split screen): the button, placed by the whole screen, could sit
        // over the other app, so it isn't shown.
        val split: Boolean = false,
        // Once Rav-Pass has been seen: another app's screen on top of Kav's task, which Rav-Pass opened over itself.
        val overRavPass: Boolean = false,
        // Rav-Pass's window in front as the ticket reader sees it, in whatever task it now is.
        val ravPassFront: Boolean = false,
    )

    // Rav-Pass in front, from Kav: on top of Kav's task, under a screen it opened there, or started again in its own task.
    fun inRavPass(s: Seen) = s.ravPassFront || s.taskVisible && (s.ravPassOnTop || s.overRavPass)

    // Shown only with Rav-Pass in front, the phone on and unlocked, and the rider's yes.
    fun shows(s: Seen) = s.chosen && s.allowed && inRavPass(s) && s.screenOn && !s.locked && !s.split

    // Whether to go on watching after a Pay tap: until Rav-Pass has come up (for a moment), then for as long as it stays
    // on top of Kav's task, unless hidden for so long that the rider has plainly moved on.
    fun watches(s: Seen, sinceTapMs: Long, seenRavPass: Boolean, hiddenMs: Long): Boolean = when {
        !s.chosen || !s.allowed -> false
        !seenRavPass -> sinceTapMs < WAIT_MS
        // Something over Rav-Pass for a moment (Android's camera permission, a payment sheet): watch on for a minute.
        !s.ravPassOnTop && !s.overRavPass && !s.ravPassFront -> hiddenMs < AWAY_MS
        else -> hiddenMs < GIVE_UP_MS
    }

    // What the button says while the reader has a purchase Kav saw (TicketReader.seenDone): reading its ticket, then got it
    // (Kav comes back by itself), or back to Back to Kav when the code doesn't come. A tap goes back to Kav in every one.
    enum class Reading { NO, READING, GOT }
    var reading by mutableStateOf(Reading.NO)

    // Where the button sits: against the left or right edge, at a fraction of the height between the bars.
    data class Spot(val right: Boolean, val y: Float)

    // Where the rider dragged it; null until they do.
    fun spot(text: String?): Spot? {
        val parts = text?.split(',')
        val y = parts?.getOrNull(1)?.toFloatOrNull()
        if (parts?.size != 2 || y == null || y.isNaN() || parts[0] !in setOf("L", "R")) return null
        return Spot(parts[0] == "R", y.coerceIn(0f, 1f))
    }

    // At first on the end side, TOP_DP under the status bar, where Android's keyboard never comes (Noam, 19 Nov 2026). From
    // Rav-Pass 10.2.30's layouts: its toolbars (ipsdk_toolbar_pay, ipsdk_toolbar_profile) are about 58 dp, with the route card
    // in the middle and Cancel or search at the end, and the scan screen has none; the bus scanner's frame, the drawer's ticket
    // list and the train and light rail screens' codes all start 130 dp or more below it. So only a title is under it.
    const val TOP_DP = 64
    fun first(rtl: Boolean, topPx: Int, room: Int) = Spot(right = !rtl, y = if (room > 0) (topPx.toFloat() / room).coerceIn(0f, 1f) else 0f)

    fun text(s: Spot) = (if (s.right) "R," else "L,") + "%.3f".format(java.util.Locale.US, s.y)

    // The window's corner for a spot, kept inside the screen and off the system bars.
    fun corner(s: Spot, screenW: Int, screenH: Int, w: Int, h: Int, top: Int, bottom: Int, margin: Int): Pair<Int, Int> {
        val x = if (s.right) screenW - w - margin else margin
        val room = (screenH - top - bottom - h).coerceAtLeast(0)
        return x.coerceAtLeast(0) to top + (s.y * room).toInt()
    }

    // Let go after a drag: to the nearer edge, at the height it was left.
    fun settle(x: Int, y: Int, screenW: Int, screenH: Int, w: Int, h: Int, top: Int, bottom: Int): Spot {
        val room = (screenH - top - bottom - h).coerceAtLeast(1)
        return Spot(right = x + w / 2 > screenW / 2, y = ((y - top).toFloat() / room).coerceIn(0f, 1f))
    }

    // A height for the button (at x, w by h) clear of every no-go place (left, top, right, bottom) it would cover: its own
    // if it covers none, else the nearest just above or below one, between minY and maxY. Its own when none is clear.
    fun clearY(x: Int, y: Int, w: Int, h: Int, noGo: List<IntArray>, minY: Int, maxY: Int, gap: Int): Int {
        val across = noGo.filter { it[0] < x + w && it[2] > x }
        fun clear(at: Int) = across.none { it[1] < at + h && it[3] > at }
        if (clear(y)) return y
        return across.flatMap { listOf(it[1] - h - gap, it[3] + gap) }.map { it.coerceIn(minY, maxY.coerceAtLeast(minY)) }
            .filter(::clear).minByOrNull { kotlin.math.abs(it - y) } ?: y
    }

    fun clampY(y: Int, screenH: Int, h: Int, top: Int, bottom: Int) = y.coerceIn(top, (screenH - bottom - h).coerceAtLeast(top))

    fun allowed(ctx: Context) = supported() && Settings.canDrawOverlays(ctx)

    // Android 15 put "Display over other apps" under restricted settings (AOSP's EnhancedConfirmationService), where a
    // phone's build turns that on.
    fun mayBeRestricted(sdk: Int = Build.VERSION.SDK_INT) = sdk >= 35

    // The system screen where the rider allows it for Kav.
    fun openSystemScreen(ctx: Context): Boolean = runCatching {
        ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)))
    }.isSuccess

    // ---- On the phone ----

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var app: Context? = null
    private var tappedAt = 0L
    private var seenRavPass = false
    private var hiddenSince = 0L
    private var window: Shown? = null
    private var displayId = android.view.Display.DEFAULT_DISPLAY

    // The button's window on that display, never on another (a phone with a second screen, or a hidden test display).
    @androidx.annotation.RequiresApi(MIN_SDK)
    private fun onDisplay(ctx: Context): Context? {
        val d = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)?.getDisplay(displayId) ?: return null
        return ctx.createWindowContext(d, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }

    // A Pay tap that opened Rav-Pass's own link in Kav's task.
    fun arm(ctx: Context) {
        if (!supported() || !Prefs.payOverlay(ctx) || !Settings.canDrawOverlays(ctx)) return
        app = ctx.applicationContext
        // The display Kav's screen is on, which is the one its task (and Rav-Pass in it) is on.
        displayId = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) (ctx as? android.app.Activity)?.display?.displayId else null)
            ?: android.view.Display.DEFAULT_DISPLAY
        tappedAt = System.currentTimeMillis()
        reading = Reading.NO
        seenRavPass = false
        hiddenSince = tappedAt
        main.removeCallbacks(look)
        main.postDelayed(look, 300)
    }

    // Kav is back in front (or the rider turned it off): gone at once, and no more watching until the next Pay tap.
    fun disarm() {
        main.removeCallbacks(look)
        reading = Reading.NO
        hide()
        app = null
    }

    private val look = object : Runnable {
        override fun run() {
            val ctx = app ?: return
            val s = seen(ctx)
            val now = System.currentTimeMillis()
            if (s.ravPassOnTop || s.ravPassFront) seenRavPass = true
            if (shows(s)) { show(ctx); hiddenSince = now; window?.keepClear() } else hide()
            if (!watches(s, now - tappedAt, seenRavPass, if (window != null) 0 else now - hiddenSince)) { disarm(); return }
            main.postDelayed(this, if (window != null) 250 else 500)
        }
    }

    @SuppressLint("NewApi")
    private fun seen(ctx: Context): Seen {
        // Every task appTasks lists is Kav's own, whatever sits at its bottom (Android can leave Rav-Pass's screen there
        // once Kav's own is gone): the one with Rav-Pass on top, else the latest.
        val tasks = runCatching {
            ctx.getSystemService(ActivityManager::class.java).appTasks.mapNotNull { t -> runCatching { t.taskInfo }.getOrNull() }
        }.getOrDefault(emptyList())
        val task = tasks.firstOrNull { it.topActivity?.packageName == RavPass.PACKAGE } ?: tasks.firstOrNull()
        val top = task?.topActivity?.packageName
        // Rav-Pass's window the one in front as the ticket reader sees it; null without the reader. Not yet while Kav's
        // own screen is still showing as Rav-Pass starts (the reader is told only of Rav-Pass's windows, so its active
        // window is none while Kav's is in front).
        val reader = uk.noammm.kav.TicketReader.running?.let { r ->
            runCatching { r.rootInActiveWindow?.packageName == RavPass.PACKAGE }.getOrDefault(true)
        }
        return Seen(
            ravPassOnTop = top == RavPass.PACKAGE && reader != false,
            overRavPass = seenRavPass && top != null && top != RavPass.PACKAGE && top != ctx.packageName,
            ravPassFront = reader == true,
            taskVisible = task?.isVisible == true,
            screenOn = ctx.getSystemService(PowerManager::class.java)?.isInteractive == true,
            locked = ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false,
            allowed = Settings.canDrawOverlays(ctx),
            chosen = Prefs.payOverlay(ctx),
            split = split,
        )
    }

    // Back to Kav: its own screen to the front of its task. Kav's screen is the task's first (singleTask), so this also
    // closes the Rav-Pass screen sitting above it, as Back does; Rav-Pass itself keeps running, and the next Pay tap opens it again.
    private fun back(ctx: Context) {
        disarm()
        runCatching {
            ctx.startActivity(Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
    }

    private fun show(ctx: Context) {
        if (window != null || Build.VERSION.SDK_INT < MIN_SDK) return
        // That display gone: no button anywhere else.
        window = runCatching { onDisplay(ctx)?.let { Shown(it) } }.getOrNull()
    }

    private fun hide() {
        if (Build.VERSION.SDK_INT >= MIN_SDK) window?.remove()
        window = null
    }

    // The trip's one line, as its notification has it, from what Kav already holds: nothing is asked of anyone.
    // After a Pay tap on a trip's ride, that ride's line and the stop to get off at, for Rav-Pass's approve screen.
    private fun tripLine(ctx: Context): String? = PayHandOff.shown?.label() ?: runCatching {
        val (journey, step) = TripBridge.journey?.invoke() ?: return null
        tripNotice(journey, step, TripBridge.fixNow?.invoke(), System.currentTimeMillis() / 1000, Prefs.accent(ctx))?.title
    }.getOrNull()?.takeIf { it.isNotBlank() }

    // The button's window: just the size of the button, so every touch beside it goes to Rav-Pass.
    @androidx.annotation.RequiresApi(MIN_SDK)
    private class Shown(val ctx: Context) {
        val wm = ctx.getSystemService(WindowManager::class.java)
        val owner = Owner()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // In the screen's own coordinates (corner() adds the status bar itself; without it Android added it again), and
            // untouchable until it's in its place and showing.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.LEFT or Gravity.TOP; title = "Kav: back to Kav" }
        val icon: ImageBitmap = ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(96, 96).asImageBitmap()
        var line by mutableStateOf(tripLine(ctx))
        val frame = Drag(ctx)
        val margin = (8 * ctx.resources.displayMetrics.density).toInt()
        val tick: Runnable = object : Runnable {
            override fun run() { line = tripLine(ctx); main.postDelayed(this, 15_000) }
        }

        init {
            val content = ComposeView(ctx).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                setContent { Pill(icon, line, reading) }
            }
            frame.addView(content)
            // TalkBack reads the button by its own words and can press it.
            frame.contentDescription = T("Back to Kav", "חזרה ל-Kav") + (line?.let { ", $it" } ?: "")
            frame.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            frame.setViewTreeLifecycleOwner(owner)
            frame.setViewTreeSavedStateRegistryOwner(owner)
            owner.up()
            // Added unseen, placed once it has its size, then faded in: never a frame anywhere else.
            frame.alpha = 0f
            place(Spot(true, 0f), first = true)
            wm.addView(frame, params)
            frame.post {
                params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                place(saved())
                frame.animate().alpha(1f).setDuration(180).start()
            }
            // The button's words change (Reading your ticket, Got it): kept against its edge as it grows or shrinks.
            frame.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or_, _ -> if (r - l != or_ - ol) at?.let { place(it) } }
            main.postDelayed(tick, 15_000)
        }

        // Where the rider dragged it, else the first spot under the status bar.
        fun saved(): Spot = spot(Prefs.payOverlaySpot(ctx)) ?: screen().let { (_, h, top, bottom) ->
            first(T.rtl, (TOP_DP * ctx.resources.displayMetrics.density).toInt(), h - top - bottom - frame.height)
        }

        fun screen(): IntArray {
            val m = wm.currentWindowMetrics
            val bars = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return intArrayOf(m.bounds.width(), m.bounds.height(), bars.top, bars.bottom)
        }

        var at: Spot? = null

        fun place(s: Spot, first: Boolean = false) {
            if (!first) at = s
            val (w, h, top, bottom) = screen().toList()
            val (x, y) = corner(s, w, h, frame.width, frame.height, top, bottom, margin)
            params.x = x; params.y = y
            if (!first) runCatching { wm.updateViewLayout(frame, params) }
        }

        // Never resting over a code, the scanner or Rav-Pass's top bar buttons (TicketReader.noGo): it glides to the nearest
        // clear height when what's under it changes, and after a drop.
        private var gliding: android.animation.ValueAnimator? = null

        fun clearOf(x: Int, y: Int): Int {
            val noGo = uk.noammm.kav.TicketReader.running?.let { runCatching { it.noGo() }.getOrNull() }.orEmpty()
            val (_, h, top, bottom) = screen().toList()
            return clearY(x, y, frame.width, frame.height, noGo, top, h - bottom - frame.height, margin)
        }

        fun keepClear() {
            if (frame.dragging || gliding?.isRunning == true || frame.alpha == 0f) return
            val to = clearOf(params.x, params.y)
            if (to != params.y) glide(to)
        }

        fun glide(to: Int) {
            gliding?.cancel()
            gliding = android.animation.ValueAnimator.ofInt(params.y, to).setDuration(220).apply {
                addUpdateListener { params.y = it.animatedValue as Int; runCatching { wm.updateViewLayout(frame, params) } }
                start()
            }
        }

        fun remove() {
            gliding?.cancel()
            main.removeCallbacks(tick)
            owner.down()
            runCatching { wm.removeViewImmediate(frame) }
        }

        // Moves with the finger past the touch slop; a touch that doesn't move is the tap. Let go, it goes to the nearer edge
        // and is remembered there.
        inner class Drag(ctx: Context) : FrameLayout(ctx) {
            private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
            private var downX = 0f; private var downY = 0f; private var fromX = 0; private var fromY = 0
            var dragging = false
                private set

            init {
                isClickable = true
                setOnClickListener { back(ctx) }
            }

            override fun onInterceptTouchEvent(ev: MotionEvent) = true

            @SuppressLint("ClickableViewAccessibility")
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; fromX = params.x; fromY = params.y; dragging = false }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX; val dy = ev.rawY - downY
                        if (!dragging && dx * dx + dy * dy > slop * slop) dragging = true
                        if (dragging) {
                            val (w, h, top, bottom) = screen().toList()
                            params.x = (fromX + dx.toInt()).coerceIn(0, (w - width).coerceAtLeast(0))
                            params.y = clampY(fromY + dy.toInt(), h, height, top, bottom)
                            runCatching { wm.updateViewLayout(this, params) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (dragging) {
                            dragging = false
                            val (w, h, top, bottom) = screen().toList()
                            // Dropped over a code or the scanner: the nearest clear height there, remembered.
                            val dropped = settle(params.x, params.y, w, h, width, height, top, bottom)
                            val (x, _) = corner(dropped, w, h, width, height, top, bottom, margin)
                            val s = settle(x, clearOf(x, params.y), w, h, width, height, top, bottom)
                            Prefs.setPayOverlaySpot(ctx, text(s))
                            place(s)
                        } else performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> if (dragging) { dragging = false; place(saved()) }
                }
                return true
            }
        }
    }

    // A window has no activity to give Compose its lifecycle and saved state, so the button carries its own.
    private class Owner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        init { saved.performRestore(null) }
        fun up() { registry.currentState = Lifecycle.State.RESUMED }
        fun down() { registry.currentState = Lifecycle.State.DESTROYED }
    }
}

// The button itself: Kav's icon and where it goes, on Kav's raised surface with a firm edge, readable over anything.
@Composable
private fun Pill(icon: ImageBitmap, line: String?, reading: PayOverlay.Reading) {
    CompositionLocalProvider(LocalLayoutDirection provides if (T.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        Row(
            Modifier.clip(K.capsule).background(K.surfaceRaised).border(1.dp, K.borderStrong, K.capsule)
                .heightIn(min = 44.dp).padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
                .animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 500f)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(K.gap2),
        ) {
            // Kav's icon, or while it reads the ticket a small spinner in its place, then a check.
            androidx.compose.animation.AnimatedContent(reading, transitionSpec = {
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)) +
                    androidx.compose.animation.scaleIn(initialScale = .7f)) togetherWith androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
            }, label = "pillMark") { r ->
                Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                    when (r) {
                        PayOverlay.Reading.NO -> Image(icon, contentDescription = null, modifier = Modifier.size(30.dp).clip(CircleShape))
                        PayOverlay.Reading.READING -> Spinner(20.dp)
                        PayOverlay.Reading.GOT -> androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Rounded.CheckCircle,
                            contentDescription = null, tint = K.accentInk, modifier = Modifier.size(26.dp))
                    }
                }
            }
            Column {
                ChangingText(when (reading) {
                    PayOverlay.Reading.NO -> T("Back to Kav", "חזרה ל-Kav")
                    PayOverlay.Reading.READING -> T("Reading your ticket…", "קוראת את הכרטיס…")
                    PayOverlay.Reading.GOT -> T("Got it", "הכרטיס נקרא")
                }) { Text(it, style = K.type.bodyStrong, color = K.text, maxLines = 1) }
                if (reading == PayOverlay.Reading.NO) line?.let {
                    // The ride and the stop to get off at: a long stop name takes a second line rather than being cut.
                    Text(it, style = K.type.caption, color = K.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 260.dp))
                }
            }
        }
    }
}
