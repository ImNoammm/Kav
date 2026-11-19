package uk.noammm.kav.ext

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import uk.noammm.kav.R

// "Start in safe mode" on a long press of Kav's icon, while at least one extension is installed: Kav opens once with
// every extension off, as Safe mode does, and the next start is a normal one. Beside PayEntry's shortcuts, never in place
// of them: each side publishes and removes only its own.
object SafeStart {
    const val ACTION = "uk.noammm.kav.SAFE_START"
    private const val ID = "safe-start"

    fun sync(ctx: Context, installed: Boolean) = runCatching {
        if (!installed) { ShortcutManagerCompat.removeDynamicShortcuts(ctx, listOf(ID)); return@runCatching }
        val he = uk.noammm.kav.Prefs.lang(ctx) == uk.noammm.kav.ui.Lang.HE
        val info = ShortcutInfoCompat.Builder(ctx, ID)
            .setShortLabel(if (he) "מצב בטוח" else "Safe mode")
            .setLongLabel(if (he) "פתיחה במצב בטוח" else "Start in safe mode")
            .setIcon(IconCompat.createWithResource(ctx, R.drawable.shortcut_safe))
            .setIntent(Intent(ACTION).setClass(ctx, uk.noammm.kav.MainActivity::class.java))
            .setRank(2).build()
        ShortcutManagerCompat.addDynamicShortcuts(ctx, listOf(info))
    }

    // Opened from the shortcut: unless this run is already in safe mode, marked for one start and started again, since
    // extensions are loaded as Kav's process starts. True when it restarts.
    fun take(a: Activity, intent: Intent?): Boolean {
        if (intent?.action != ACTION) return false
        a.intent = Intent(a.intent).setAction(Intent.ACTION_MAIN)
        if (SafeMode.active) return false
        SafeMode.set(ExtApp.dir(a), true, SafeMode.ONCE)
        a.startActivity(Intent(a, uk.noammm.kav.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        a.finishAffinity()
        Runtime.getRuntime().exit(0)
        return true
    }
}
