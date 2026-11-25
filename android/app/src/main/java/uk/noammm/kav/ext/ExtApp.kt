package uk.noammm.kav.ext

import android.content.Context
import android.util.Log
import uk.noammm.kav.BuildConfig
import uk.noammm.kav.data.JourneyFile
import uk.noammm.kav.data.RiderProxy
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Extensions on the phone: the Hub, made the first time something needs it (Settings › Extensions, a link or file the
// rider opened, the background check once anything is installed). With nothing added Kav never makes it.
object ExtApp {
    private const val TAG = "KavExt"

    // Debug builds' fixtures (Fixtures.kt) point these at a test list and key before anything reads them.
    @Volatile var blockListUrl = BlockList.URL
    @Volatile var blockListKey = BlockList.KEY

    @Volatile private var hub: Hub? = null

    fun dir(ctx: Context) = File(ctx.applicationContext.filesDir, "ext")

    // The sources every Kav starts with (Hub.addDefault): Kav's own extensions, which are also the examples of what an
    // extension can do (github.com/ImNoammm/kav-extensions). The name shows until its bundle is first read; then it's
    // the one its bundle gives.
    val DEFAULT_SOURCES get() = listOf(
        "ImNoammm/kav-extensions" to uk.noammm.kav.ui.T("Kav extensions", "התוספים של Kav"),
    )

    // Made by Kav: from one of Kav's own sources, judged by the source's address (its key), never by a name.
    fun kavMade(sourceKey: String?) = sourceKey != null && DEFAULT_SOURCES.any { Hub.Source.github(it.first) == sourceKey }

    @Synchronized fun hub(ctx: Context): Hub = hub ?: Hub(dir(ctx), Http, BuildConfig.VERSION_CODE, blockListKey, images = SourceImages(dir(ctx), Http)).also {
        hub = it
        Selections.installed = it::manifestOf
        Hooks.attach(ctx, it)
        addDefaults(it)
    }

    // Added the first time the hub opens, then read at once, off the main thread; the screens draw again when it's in.
    private fun addDefaults(h: Hub) {
        val added = DEFAULT_SOURCES.mapNotNull { (repo, name) -> runCatching { h.addDefault(repo, name) }.getOrNull() }
        if (added.isNotEmpty()) Thread({
            added.forEach { s -> runCatching { h.refresh(s.key) }.onFailure { Log.w(TAG, "default source: ${it.message}") } }
            android.os.Handler(android.os.Looper.getMainLooper()).post { uk.noammm.kav.ui.ExtUi.changed() }
        }, "kav-ext-defaults").start()
    }

    // Kav starting with the extensions folder there (KavApp checks, so a Kav without extensions loads none of this).
    fun start(ctx: Context) {
        began = true
        val d = dir(ctx)
        SafeMode.start(d)
        SafeMode.watch(d)
        Selections.open(d)
        // Installed extensions' hooks registered off the main thread, so nothing of this is on Kav's way to its first screen.
        Hooks.arm(ctx)
        // The rider's Home body, tab and theme (ext/Screens.kt), shown at once and checked after.
        Screens.open(d)
        Slots.Picks.open(d)
        TripAccess.open(d)
        Widgets.Placed.open(d)
        uk.noammm.kav.ExtWidgets.attach(ctx)
        uk.noammm.kav.ui.ExtShown.start(ctx)
        // Native extensions' crashes are counted from the first moment (NativeGuard).
        NativeGuard.open(d)
        NativeGuard.install()
        if (!SafeMode.active) Thread({
            runCatching { SafeStart.sync(ctx.applicationContext, hub(ctx).registry.installed.isNotEmpty()) }
            runCatching { startNative(ctx) }.onFailure { Log.w(TAG, "native: ${it.message}") }
            started.countDown()
            runCatching { Hooks.refresh() }
        }, "kav-ext-start").start() else started.countDown()
    }

    // Counted down once this run's native extensions have started (or none will): a home screen widget's update in a Kav
    // Android just started for it waits for this before asking their code (ExtWidgets).
    private val started = java.util.concurrent.CountDownLatch(1)

    @Volatile private var began = false

    // Whether they started within ms; at once when this run never started extensions (nothing was added when Kav
    // started: one added since loads by loadNative).
    fun awaitStarted(ms: Long): Boolean = !began || started.await(ms, java.util.concurrent.TimeUnit.MILLISECONDS)

    // An extension that crashed Kav twice is switched off before anything of it loads, and Kav says which one; then the
    // others load and start (Native).
    private fun startNative(ctx: Context) {
        val h = hub(ctx)
        if (h.registry.installed.none { Native.declares(it.manifest) }) return
        NativeGuard.check(h.registry)
        uk.noammm.kav.ui.ExtNative.start()
        // Said in a popup as Kav opens, naming each (StartPopups).
        NativeGuard.switchedOff.takeIf { it.isNotEmpty() }?.let { off ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { uk.noammm.kav.ui.ExtCrashes.items = off }
        }
        Native.start(ctx, h)
        uk.noammm.kav.ui.ExtNative.started()
        uk.noammm.kav.ui.ExtShown.publish()
        // A Kav installed or updated since the last start asks every source once, at once: its extensions may need a release built for it.
        val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).let { "${it.longVersionCode}:${it.lastUpdateTime}" } }.getOrDefault("")
        val seen = File(dir(ctx), "kav-version")
        val updated = version.isNotEmpty() && runCatching { seen.readText() }.getOrDefault("") != version
        if (updated) runCatching { seen.writeText(version) }
        updateOutdated(ctx, h, if (updated) h.sources.filter { it.kind == Hub.Kind.GITHUB }.map { it.key } else emptyList())
    }

    // Sources asked this run because an extension of theirs was built for an older Kav: once each.
    private val asked = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    // Extensions whose code this Kav can't load (Native.outdated): their sources are asked for a newer release now, not
    // at the next periodic check, and what came loads at once. While that runs their tabs and pages say so (ExtUpdates).
    private fun updateOutdated(ctx: Context, h: Hub, also: List<String> = emptyList()) {
        val keys = (Native.outdated.mapNotNull { h.registry.get(it)?.source } + also).toSet().filter { asked.add(it) }
        if (keys.isNotEmpty()) {
            uk.noammm.kav.ui.ExtUpdates.set(Native.outdated.filter { h.registry.get(it)?.source in keys }.toSet(), Native.outdated.toSet())
            var changed = false
            for (k in keys) runCatching { val before = h.source(k)?.updatedAt; if (h.refresh(k).updatedAt != before) changed = true }
                .onFailure { Log.w(TAG, "update check: ${it.message}") }
            if (changed) {
                Native.start(ctx, h)
                uk.noammm.kav.ui.ExtNative.started()
                uk.noammm.kav.ui.ExtShown.publish()
                runCatching { uk.noammm.kav.ui.ExtShown.refresh(ctx) }
            }
        }
        uk.noammm.kav.ui.ExtUpdates.set(emptySet(), Native.outdated.toSet())
        android.os.Handler(android.os.Looper.getMainLooper()).post { uk.noammm.kav.ui.ExtUi.changed() }
    }

    // An extension added, updated or switched on while Kav runs: its native code loads now, off the main
    // thread, and the screens that wait for it draw again. Before this it only loaded at Kav's next start, and a phone
    // that keeps Kav's process when it's swiped away never started it.
    fun loadNative(ctx: Context) {
        if (SafeMode.active) return
        // Not startNative: the crash check and its notices belong to Kav's start only.
        Thread({
            runCatching {
                val h = hub(ctx)
                if (h.registry.installed.none { Native.declares(it.manifest) }) return@runCatching
                uk.noammm.kav.ui.ExtNative.start()
                Native.start(ctx, h)
                uk.noammm.kav.ui.ExtNative.started()
                uk.noammm.kav.ui.ExtShown.publish()
                updateOutdated(ctx, h)
            }.onFailure { Log.w(TAG, "native: ${it.message}") }
        }, "kav-ext-load").start()
    }

    // The background check (Checks), and Check now: the block list, then every source and its updates. Never with
    // nothing added.
    fun check(ctx: Context): Hub.Report? {
        if (!dir(ctx).isDirectory) return null
        val h = hub(ctx)
        if (h.sources.isEmpty() && h.registry.installed.isEmpty()) return null
        if (blockListKey.isNotEmpty() && h.useBlockList) runCatching {
            h.takeBlockList(Http.get(blockListUrl, 512 shl 10))
        }.onFailure { Log.w(TAG, "block list: ${it.message}") }
        return h.check(tripUnderWay = JourneyFile.mtime(ctx) != 0L).also { if (it.updated.isNotEmpty()) loadNative(ctx) }
    }

    // An https GET through the rider's proxy like the rest of Kav, carrying nothing of Kav's: no key, no cookie, no
    // account. Redirects are followed only to https, and the body is cut off at max. The whole request, redirects
    // included, has a deadline besides the per-read timeout, so a server that answers a byte at a time can't hold a
    // check: the time max bytes take at 16 KB a second, at least 30 seconds and at most 5 minutes. With an ETag, GitHub's
    // conditional request: "not changed" (304) is null.
    object Http : Fetch {
        private val cutter = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "kav-ext-deadline").apply { isDaemon = true } }

        override fun get(url: String, max: Int): ByteArray = request(url, max, "")!!.body

        override fun getIfChanged(url: String, max: Int, etag: String): Fetch.Got? = request(url, max, etag)

        private fun request(url: String, max: Int, etag: String): Fetch.Got? {
            val deadline = System.nanoTime() + (max / 16L).coerceIn(30_000L, 300_000L) * 1_000_000
            var at = URL(url)
            repeat(5) {
                if (at.protocol != "https") throw java.io.IOException("not https")
                val left = (deadline - System.nanoTime()) / 1_000_000
                if (left <= 0) throw java.io.IOException("took too long")
                val c = (RiderProxy.open(at) as HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 15_000
                    instanceFollowRedirects = false
                    useCaches = false
                    setRequestProperty("User-Agent", "Kav")
                    if (url.startsWith(GitHub.API)) setRequestProperty("Accept", "application/vnd.github+json")
                    if (etag.isNotEmpty() && at.toString() == url) setRequestProperty("If-None-Match", etag)
                }
                // At the deadline the connection is closed under whatever read is waiting.
                val cut = cutter.schedule({ c.disconnect() }, left, java.util.concurrent.TimeUnit.MILLISECONDS)
                try {
                    val code = c.responseCode
                    // "Not changed" first: it's a 3xx too, with no place to go.
                    if (code == 304 && etag.isNotEmpty()) return null
                    if (code in 300..399) {
                        at = URL(at, c.getHeaderField("Location") ?: throw java.io.IOException("redirect without a place"))
                        return@repeat
                    }
                    if (code != 200) throw java.io.IOException("HTTP $code")
                    if (c.contentLengthLong > max) throw java.io.IOException("too large")
                    c.inputStream.use { s ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(16 shl 10)
                        while (true) {
                            val n = s.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > max) throw java.io.IOException("too large")
                            if (System.nanoTime() > deadline) throw java.io.IOException("took too long")
                        }
                        return Fetch.Got(out.toByteArray(), c.getHeaderField("ETag").orEmpty().take(200))
                    }
                } catch (e: java.io.IOException) {
                    throw if (System.nanoTime() >= deadline) java.io.IOException("took too long") else e
                } finally {
                    cut.cancel(false)
                    c.disconnect()
                }
            }
            throw java.io.IOException("too many redirects")
        }
    }
}
