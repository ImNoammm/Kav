package uk.noammm.kav.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ConfirmationNumber
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import uk.noammm.kav.Prefs
import uk.noammm.kav.R
import uk.noammm.kav.ui.PayMode.Need

// The one popup that sets up "Pay with Rav-Pass" (PayMode), opened by everything that does: the switch in Settings and
// setup, Home's offer, Home's Pay row while it says Action required, and a Pay from outside Kav then. While Rav-Pass isn't
// on the phone, where to get it; then, like Android's own permission screens, a switch for each permission still missing.
// Back from the store or Android's settings it reads the phone again and moves on by itself: once nothing is missing,
// paying is on, it says so and closes. Closed before that, paying stays as it was.
internal object PaySetup {
    var open by mutableStateOf(false)
        private set
    // From Settings' switch while paying is on with something missing: turning it off is done here.
    var offersOff = false
        private set

    fun show(offerOff: Boolean = false) { offersOff = offerOff; open = true }
    fun hide() { open = false }
}

private enum class Page { GET, ALLOW, READER, DONE }

// Android 15 and newer can grey out the switch for showing over other apps, and Android 13 and newer the accessibility
// one, for an app that wasn't installed from an app store (restricted settings).
private fun mayBeGreyed(n: Need) = when (n) {
    Need.OVERLAY -> PayOverlay.mayBeRestricted()
    Need.READER -> Build.VERSION.SDK_INT >= 33
    Need.APP -> false
}

private fun openAccessibility(ctx: Context) = runCatching { ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }.isSuccess
private fun openAppInfo(ctx: Context) = runCatching {
    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
}

@Composable
internal fun PaySetupHost() {
    if (!PaySetup.open) return
    val ctx = LocalContext.current
    val installed = rememberRavPass()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var looks by remember { mutableIntStateOf(0) }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { looks++ } }
    val missing = remember(installed, looks) { PayMode.missing(ctx) }
    // The permissions it asked for: each keeps its row once allowed, its switch on.
    val asked = remember { linkedSetOf<Need>() }
    asked += missing.filter { it != Need.APP }
    // Gone to Android's screen for one: back without it, where Android may have greyed it out, how to allow it.
    var went by remember { mutableStateOf<Need?>(null) }
    var greyed by remember { mutableStateOf<Need?>(null) }
    LaunchedEffect(looks) {
        val w = went ?: return@LaunchedEffect
        went = null
        if (w in missing && mayBeGreyed(w)) greyed = w
    }
    var reading by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    val page = when {
        done -> Page.DONE
        Need.APP in missing -> Page.GET
        reading -> Page.READER
        else -> Page.ALLOW
    }
    BottomSheet(onDismiss = { PaySetup.hide(); PayMode.refresh(ctx) }, scrolls = true) { close ->
        LaunchedEffect(missing.isEmpty()) {
            if (missing.isNotEmpty()) return@LaunchedEffect
            PayMode.done(ctx)
            done = true
            kotlinx.coroutines.delay(1_400)
            close { PaySetup.hide() }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(K.gap2))
            RavPassLogo(installed)
            Spacer(Modifier.height(K.gap4))
            // Which app this setup is for, here too: switching swaps the logo, the store page and what the phone is
            // checked for (rememberRavPass re-reads), and with nothing missing the flow finishes by itself.
            if (page != Page.DONE) {
                PayAppChoice()
                Spacer(Modifier.height(K.gap4))
            }
            AnimatedContent(
                page,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 60)) + scaleIn(spring(dampingRatio = 1f, stiffness = 500f), initialScale = .96f))
                        .togetherWith(fadeOut(tween(150)))
                        .using(SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 1f, stiffness = 500f) })
                },
                label = "paySetup",
            ) { p ->
                KavPart("pay.setup", p, { org.json.JSONObject().put("page", p.name.lowercase()) }) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        when (p) {
                            Page.GET -> GetRavPass()
                            Page.ALLOW -> Allow(
                                asked.sortedBy { it.ordinal }, missing, greyed?.takeIf { it in missing },
                                onOverlay = { if (PayOverlay.openSystemScreen(ctx)) went = Need.OVERLAY },
                                onReader = { reading = true },
                            )
                            Page.READER -> AboutReader(
                                onAgree = {
                                    reading = false
                                    Prefs.setTickets(ctx, true)
                                    if (openAccessibility(ctx)) went = Need.READER
                                },
                                onBack = { reading = false },
                            )
                            Page.DONE -> Done()
                        }
                    }
                }
            }
            AnimatedVisibility(page != Page.DONE && page != Page.READER, enter = fadeIn(tween(200)) + expandVertically(),
                exit = fadeOut(tween(150)) + shrinkVertically()) {
                Column(Modifier.fillMaxWidth().padding(top = K.gap2)) {
                    KavButton(T("Not now", "לא עכשיו"), onClick = { close { PaySetup.hide(); PayMode.refresh(ctx) } },
                        style = ButtonStyle.Secondary, wide = true)
                    if (PaySetup.offersOff && Prefs.ravPass(ctx)) {
                        Spacer(Modifier.height(K.gap2))
                        KavButton(T("Turn off paying", "כיבוי התשלום"), onClick = { close { PaySetup.hide(); payMode(ctx, false, "setup") } },
                            style = ButtonStyle.Destructive, wide = true)
                    }
                }
            }
        }
    }
}

// The chosen payment app's mark as a launcher shows it: Rav-Pass's own launcher icon (its foreground on its white
// background, cropped to the middle 72 of its 108 dp; scaled at any other size), and egg's icon from the phone, filling
// the square the same way (squareIcon()). A QR mark in Kav's accent stands in for egg while it isn't installed.
@Composable
internal fun RavPassLogo(installed: Boolean, size: androidx.compose.ui.unit.Dp = 72.dp) {
    val ctx = LocalContext.current
    val app = PayApp.current
    val icon = remember(app, installed) {
        if (app == PayApp.EGG && installed) runCatching { squareIcon(ctx.packageManager.getApplicationIcon(app.pkg), 192) }.getOrNull()
        else null
    }
    val shape = RoundedCornerShape(size / 4)
    Box(Modifier.size(size).clip(shape).background(Color.White).border(0.5.dp, K.separator, shape), contentAlignment = Alignment.Center) {
        when {
            app == PayApp.RAV_PASS -> Image(painterResource(R.drawable.ravpass_logo), contentDescription = app.label(), modifier = Modifier.requiredSize(size * 1.5f))
            icon != null -> Image(icon, contentDescription = app.label(), modifier = Modifier.fillMaxSize())
            else -> Icon(Icons.Rounded.QrCodeScanner, contentDescription = app.label(), tint = K.accentInk, modifier = Modifier.size(size * 0.6f))
        }
    }
}

// An app's icon filling a square of px: an adaptive icon's own layers, the middle 72 of their 108 dp, without the phone's
// icon shape (a circle on some phones, which would leave the square's corners bare); any other icon as it is.
private fun squareIcon(icon: android.graphics.drawable.Drawable, px: Int): androidx.compose.ui.graphics.ImageBitmap {
    if (icon !is android.graphics.drawable.AdaptiveIconDrawable) return icon.toBitmap(px, px).asImageBitmap()
    val bitmap = android.graphics.Bitmap.createBitmap(px, px, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val edge = px / 4
    for (layer in listOfNotNull(icon.background, icon.foreground)) {
        layer.setBounds(-edge, -edge, px + edge, px + edge)
        layer.draw(canvas)
    }
    return bitmap.asImageBitmap()
}

@Composable
private fun Title(text: String) = Text(text, style = K.type.title, color = K.text, textAlign = TextAlign.Center)

// Rav-Pass isn't on the phone: one button to get it, from the store the phone has.
@Composable
private fun GetRavPass() {
    val ctx = LocalContext.current
    val store = remember(PayApp.current) { RavPass.store(ctx) }
    // That store's own icon, as it is on the phone.
    val icon = remember(store) {
        store.pkg?.let { runCatching { ctx.packageManager.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
    }
    Title(T("${PayApp.current.label()} is required", "נדרשת ${PayApp.current.he}"))
    Spacer(Modifier.height(K.gap2))
    Text(T("Install it, sign in and add a way to pay, then come back to Kav.",
        "התקינו אותה, התחברו והוסיפו אמצעי תשלום, ואז חזרו ל-Kav."),
        style = K.type.body, color = K.textSecondary, textAlign = TextAlign.Center)
    Spacer(Modifier.height(K.gap5))
    KavButton(when (store) {
        RavPass.Store.PLAY -> T("Open in Play Store", "פתיחה ב-Play Store")
        RavPass.Store.AURORA -> T("Open in Aurora Store", "פתיחה ב-Aurora Store")
        RavPass.Store.APKMIRROR -> if (PayApp.current == PayApp.RAV_PASS) T("Download from APKMirror", "הורדה מ-APKMirror")
            else T("Open the Play Store page", "פתיחת הדף ב-Play Store")
    }, onClick = { RavPass.get(ctx, store) }, wide = true, image = icon)
}

// Rav-Pass is there: a switch for each permission, showing whether it's allowed now; each opens Android's own screen.
@Composable
private fun Allow(rows: List<Need>, missing: List<Need>, greyed: Need?, onOverlay: () -> Unit, onReader: () -> Unit) {
    val ctx = LocalContext.current
    Title(T("Allow permissions", "אישור הרשאות"))
    Spacer(Modifier.height(K.gap4))
    ListGroup {
        for (n in rows) row {
            val on = n !in missing
            when (n) {
                Need.OVERLAY -> ToggleRow(T("Show over other apps", "הצגה מעל אפליקציות אחרות"), on,
                    { Icon(Icons.Rounded.Layers, contentDescription = null, tint = toggleInk(on), modifier = Modifier.size(22.dp)) },
                    subtitle = T("Brings you back to Kav after paying", "מחזיר אתכם ל-Kav אחרי התשלום"), subtitleMaxLines = 2,
                ) { if (it) onOverlay() }
                Need.READER -> ToggleRow(T("Kav's ticket reader", "קורא הכרטיסים של Kav"), on,
                    { Icon(Icons.Rounded.ConfirmationNumber, contentDescription = null, tint = toggleInk(on), modifier = Modifier.size(22.dp)) },
                    subtitle = T("Shows your ${PayApp.current.label()} ticket in Kav", "מציג את הכרטיס שלכם ${PayApp.current.heFrom} ב-Kav"), subtitleMaxLines = 2,
                ) { if (it) onReader() }
                Need.APP -> {}
            }
        }
    }
    // Kav can't ask Android whether it greyed the switch out, so this says what to do if it did, with Android's own words.
    AnimatedVisibility(
        greyed != null,
        enter = fadeIn(tween(220, delayMillis = 60)) + expandVertically(spring(dampingRatio = 1f, stiffness = 500f)),
        exit = fadeOut(tween(150)) + shrinkVertically(spring(dampingRatio = 1f, stiffness = 500f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = K.gap3).clip(RoundedCornerShape(K.rTile)).background(K.fill).padding(K.gap4),
            verticalArrangement = Arrangement.spacedBy(K.gap2)) {
            Text(T("Was the switch grayed out?", "המתג היה אפור?"), style = K.type.bodyStrong, color = K.text)
            Text(T("In Kav's App info, tap ⋮ at the top, then \"Allow restricted settings\", and try again.",
                "בפרטי האפליקציה של Kav, הקישו על ⋮ למעלה ואז על \"הרשאה להגדרות מוגבלות\", ונסו שוב."),
                style = K.type.subhead)
            KavButton(T("Open Kav's App info", "פתיחת פרטי האפליקציה של Kav"), onClick = { openAppInfo(ctx) },
                style = ButtonStyle.Secondary, wide = true)
        }
    }
}

// Before Android's accessibility settings: what the reader does, at a glance, Google Play's prominent disclosure, with the
// privacy policy's full account one tap away.
@Composable
private fun AboutReader(onAgree: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Title(T("Kav's ticket reader", "קורא הכרטיסים של Kav"))
    Spacer(Modifier.height(K.gap4))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(K.gap3)) {
        for ((icon, line) in listOf(
            Icons.Rounded.ConfirmationNumber to T("Reads only ${PayApp.current.en}'s ticket screens", "קורא רק את מסכי הכרטיס ${PayApp.current.heOf}"),
            Icons.Rounded.CloudOff to T("Fully offline, never sent to Kav's servers", "אופליין לגמרי, לא נשלח לשרתים של Kav"),
            Icons.Rounded.Replay to T("Brings you back to Kav after you pay", "מחזיר אתכם ל-Kav אחרי התשלום"),
        )) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap3)) {
            Icon(icon, contentDescription = null, tint = K.accentInk, modifier = Modifier.size(22.dp))
            Text(line, style = K.type.body, color = K.text, modifier = Modifier.weight(1f))
        }
    }
    KavButton(T("Full details", "כל הפרטים"), onClick = {
        runCatching {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(T("https://kavapp.link/privacy-policy#tickets-from-rav-pass",
                "https://kavapp.link/he/privacy-policy#tickets-from-rav-pass"))).addCategory(Intent.CATEGORY_BROWSABLE))
        }
    }, style = ButtonStyle.Plain, modifier = Modifier.padding(vertical = K.gap2))
    KavButton(T("Agree and turn on", "הסכמה והפעלה"), onClick = onAgree, wide = true)
    Spacer(Modifier.height(K.gap2))
    KavButton(T("Back", "חזרה"), onClick = onBack, style = ButtonStyle.Secondary, wide = true)
}

@Composable
private fun Done() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(K.gap2),
        modifier = Modifier.padding(vertical = K.gap3)) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = K.accentInk, modifier = Modifier.size(26.dp))
        Title(T("Paying with ${PayApp.current.label()} is on", "התשלום עם ${PayApp.current.he} פועל"))
    }
}
