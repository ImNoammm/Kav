package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// Sources, the installed extensions and the block list together: what Settings › Extensions shows and the background
// check runs. A source is a GitHub repository, whose latest release carries one bundle of its extensions (Bundle), or a
// bundle file the rider picked on the phone, which never updates. Trust is the source, as in Morphe: the rider adds it,
// every extension of it is installed off, and each one runs from the moment the rider switches it on. It asks only
// GitHub, for the sources the rider added (and the block list's own address); never Kav's servers, and nothing about
// the rider.
class Hub(
    val dir: File,
    private val fetch: Fetch,
    private val kavVersion: Int,
    blockKey: String,
    private val now: () -> Long = { System.currentTimeMillis() / 1000 },
    // Sources' pictures (decoration, fetched on threads of their own); none in tests that don't ask for them.
    val images: SourceImages? = null,
) {
    enum class Kind(val key: String) { GITHUB("github"), FILE("file") }

    // key: "github:<owner/repo>" (lower case) or "file:<the first bundle's digest>". bundle: the digest of the bundle in
    // use, "" before the first one was read. tag and etag: its release's, for GitHub's conditional request.
    data class Source(
        val key: String,
        val kind: Kind,
        val repo: String,
        val name: String,
        val bundle: String = "",
        val tag: String = "",
        val etag: String = "",
        val checkedAt: Long = 0,
        val updatedAt: Long = 0,
        // What went wrong at the last check, "" when it worked.
        val error: String = "",
        val addedAt: Long = 0,
        // Its bundle brings its own picture (Bundle.icon), so the owner's GitHub avatar isn't asked.
        val icon: Boolean = false,
    ) {
        // The id its extensions' identities and namespaces start with: "github.<owner>.<repo>" or "file.<digest>".
        val publisher get() = if (kind == Kind.GITHUB) "github." + repo.lowercase().replace('/', '.') else "file." + key.substringAfter(':')
        val address get() = if (kind == Kind.GITHUB) "github.com/$repo" else name
        val page get() = if (kind == Kind.GITHUB) "https://github.com/$repo" else null
        val issuesLink get() = page?.let { "$it/issues" }.orEmpty()

        fun json(): JSONObject = JSONObject().put("key", key).put("kind", kind.key).put("repo", repo).put("name", name)
            .put("bundle", bundle).put("tag", tag).put("etag", etag).put("checkedAt", checkedAt).put("updatedAt", updatedAt)
            .put("error", error).put("addedAt", addedAt).put("icon", icon)

        companion object {
            fun of(o: JSONObject) = Source(
                o.getString("key"), Kind.entries.first { it.key == o.getString("kind") }, o.optString("repo"), o.getString("name"),
                o.optString("bundle"), o.optString("tag"), o.optString("etag"), o.optLong("checkedAt"), o.optLong("updatedAt"),
                o.optString("error"), o.optLong("addedAt"), o.optBoolean("icon"),
            )

            fun github(repo: String) = "github:" + repo.lowercase()
        }
    }

    // A source read to show the rider before it's added: nothing is kept until they confirm.
    class Preview(val source: Source, val bundle: Bundle, val already: Boolean, val blocked: BlockList.Entry?) {
        val extensions get() = bundle.extensions
    }

    // updated and failed: sources' names.
    class Report(val updated: List<String>, val failed: List<String>)

    val registry = Registry(dir)
    val blockList = BlockList(dir, blockKey)
    private val sourcesFile = File(dir, "sources.json")
    private val settingsFile = File(dir, "settings.json")

    @Volatile var sources: List<Source> = load()
        private set

    // The rider's switch, on by default (PLAN.md 9.1).
    var useBlockList: Boolean = settings().optBoolean("blockList", true)
        set(v) { field = v; saveSettings() }

    // The default sources already added once (addDefault), by key: one the rider removed isn't added again.
    private var defaults: List<String> = settings().optJSONArray("defaults").strings()

    private fun settings() = runCatching { JSONObject(settingsFile.readText()) }.getOrElse { JSONObject() }
    private fun saveSettings() = write(settingsFile, JSONObject().put("blockList", useBlockList).put("defaults", JSONArray(defaults)).toString().toByteArray())

    // Sources an older Kav kept (signed ones) are left behind.
    private fun load(): List<Source> = runCatching {
        val o = JSONObject(sourcesFile.readText())
        if (o.optInt("format") != 2) emptyList() else o.getJSONArray("sources").objects().map(Source::of)
    }.getOrElse { emptyList() }

    private fun save(next: List<Source>) {
        write(sourcesFile, JSONObject().put("format", 2).put("sources", JSONArray(next.map { it.json() })).toString().toByteArray())
        sources = next
    }

    private fun bundleFile(s: Source) = File(File(dir, "bundles"), sha256(s.key.toByteArray()).copyOf(16).hex() + Bundle.SUFFIX)

    init { images?.let { im -> File(dir, "images").listFiles()?.forEach { f -> if (sources.none { im.file(it.key) == f }) f.delete() } } }

    fun source(key: String) = sources.firstOrNull { it.key == key }

    // A source's extensions, in its bundle's order.
    // A source's extensions in the order a rider reads their names (Names.order).
    fun extensions(s: Source): List<Registry.Installed> = registry.installed.filter { it.source == s.key }.sortedWith(compareBy(Names.order()) { it.displayName })

    // -- The block list ------------------------------------------------------------------------------------------------

    fun blocked(s: Source): BlockList.Entry? = if (!useBlockList) null else blockList.source(s.repo.takeIf { s.kind == Kind.GITHUB }, s.bundle)

    fun blocked(i: Registry.Installed): BlockList.Entry? = source(i.source)?.let(::blocked)

    fun takeBlockList(bytes: ByteArray) = blockList.take(bytes).also {
        registry.installed.filter { blocked(it) != null }.forEach { Native.retire(it.identity) }
    }

    // Whether an installed extension may run at all: on, nothing wrong with it, not blocked. Safe mode is checked by the
    // caller.
    fun active(i: Registry.Installed) = i.enabled && i.problem.isEmpty() && blocked(i) == null

    // -- Adding a source -----------------------------------------------------------------------------------------------

    // A GitHub repository, as the rider typed it or a link gave it (GitHub.repoOf): its latest release's bundle, read.
    fun preview(text: String): Preview {
        val repo = GitHub.repoOf(text) ?: throw Registry.Refused(NOT_GITHUB)
        val key = Source.github(repo)
        if (useBlockList) blockList.source(repo, "")?.let { throw Registry.Refused("on the security block list (${it.reason})") }
        val r = GitHub.latest(fetch, repo) ?: throw Registry.Refused(GitHub.NO_RELEASE)
        val bytes = fetch.get(r.bundleUrl, Archive.LIMIT_ARCHIVE)
        val b = Bundle.read(bytes)
        val s = Source(key, Kind.GITHUB, repo, b.name.ifEmpty { repo.substringAfter('/') }, b.sha256, r.tag, r.etag, addedAt = now(), icon = b.icon != null)
        return Preview(s, b, source(key) != null, blocked(s)).also { staged[key] = bytes }
    }

    // A bundle file from the phone. Its source is that file: it never updates.
    fun previewFile(fileName: String, bytes: ByteArray): Preview {
        if (bytes.size > Archive.LIMIT_ARCHIVE) throw TrustError("too large")
        val b = Bundle.read(bytes)
        val key = "file:" + b.sha256.take(16)
        val shown = b.name.ifEmpty { Names.clean(fileName.removeSuffix(Bundle.SUFFIX), 64).ifEmpty { "Bundle" } }
        val s = Source(key, Kind.FILE, "", shown, b.sha256, addedAt = now(), icon = b.icon != null)
        return Preview(s, b, source(key) != null, blocked(s)).also { staged[key] = bytes }
    }

    // The bundles previewed, by source key, kept until added: adding needs no second download.
    private val staged = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    // The rider said Add: every extension of it installed, off.
    fun add(p: Preview): Source = synchronized(this) {
        if (p.already) throw Registry.Refused(ALREADY)
        blocked(p.source)?.let { throw Registry.Refused("on the security block list (${it.reason})") }
        val bytes = staged.remove(p.source.key) ?: throw Registry.Refused("preview it again")
        take(p.source, bytes, p.bundle)
        save(sources + p.source)
        images?.refresh(p.source)
        Selections.prune(dir, ::manifestOf)
        p.source
    }

    // A source every Kav starts with (ExtApp.DEFAULT_SOURCES), added the first time the hub opens and read at once by
    // the caller (refresh). Once only: if the rider removes it, it stays removed.
    @Synchronized fun addDefault(repo: String, name: String): Source? {
        val key = Source.github(repo)
        if (key in defaults) return null
        defaults = defaults + key
        saveSettings()
        if (source(key) != null) return null
        val s = Source(key, Kind.GITHUB, repo, name, addedAt = now())
        save(sources + s)
        return s
    }

    // A source removed, with every extension of it and their data.
    fun remove(key: String) = synchronized(this) {
        val s = source(key) ?: return
        registry.removeSource(key)
        save(sources.filter { it.key != key })
        bundleFile(s).delete()
        images?.forget(key)
        Selections.prune(dir, ::manifestOf)
    }

    // The rider's switch for one extension. On, it runs at once (the caller starts its code, ExtApp.loadNative); an
    // extension that can't run (Registry.Installed.problem) stays off. Turning one on that the crash guard switched off
    // clears that.
    fun setEnabled(identity: String, on: Boolean) {
        registry.change(identity) {
            val crashed = it.problem == NativeGuard.CRASHED
            if (on && it.problem.isNotEmpty() && !crashed) it else it.copy(enabled = on, problem = if (on && crashed) "" else it.problem,
                enabledAt = if (on && !it.enabled) now() else it.enabledAt)
        }
    }

    // The manifest an extension has as installed now, by namespace (Selections checks the rider's rules against it).
    fun manifestOf(ns: Namespace): Manifest? = registry.installed.firstOrNull { it.namespace == ns }?.manifest

    // Writes a source's bundle and puts its extensions in the registry: why each can't run, if it can't.
    private fun take(s: Source, bytes: ByteArray, b: Bundle) {
        registry.put(s.key, s.publisher, b.extensions, { e ->
            when {
                e.older -> Manifest.OLDER
                e.manifest.minKav > kavVersion -> NEWER_KAV
                else -> ""
            }
        }, now())
        val f = bundleFile(s)
        f.parentFile?.mkdirs()
        write(f, bytes)
        b.icon?.let { images?.put(s.key, it) }
    }

    // -- Checking for updates ------------------------------------------------------------------------------------------

    private val checking = Any()

    // One GitHub source asked now (Check now): its latest release, and a newer bundle put in place of the old one.
    // waitForTrip: a trip is under way, so nothing is replaced (the next check asks again). A file source: nothing.
    fun refresh(key: String, waitForTrip: Boolean = false): Source = synchronized(checking) {
        val s = source(key) ?: throw Registry.Refused("no such source")
        if (s.kind != Kind.GITHUB) return s
        val next = try {
            val r = GitHub.latest(fetch, s.repo, if (s.bundle.isEmpty()) "" else s.etag)
            when {
                r == null -> s.copy(checkedAt = now(), error = "")
                waitForTrip -> s.copy(checkedAt = now(), error = "")
                else -> {
                    val bytes = fetch.get(r.bundleUrl, Archive.LIMIT_ARCHIVE)
                    val b = Bundle.read(bytes)
                    val fresh = s.copy(name = b.name.ifEmpty { s.name }, bundle = b.sha256, tag = r.tag, etag = r.etag, checkedAt = now(), error = "", icon = b.icon != null)
                    if (useBlockList) blockList.source(s.repo, b.sha256)?.let { throw Registry.Refused("on the security block list (${it.reason})") }
                    if (b.sha256 != s.bundle) { take(fresh, bytes, b); fresh.copy(updatedAt = now()) } else fresh
                }
            }
        } catch (e: Exception) {
            s.copy(checkedAt = now(), error = (e.message ?: e.javaClass.simpleName).take(200))
        }
        synchronized(this) { save(sources.map { if (it.key == key) next else it }) }
        // After its bundle is read, so a bundle's own picture is never replaced by the avatar.
        images?.refresh(next)
        if (next.updatedAt != s.updatedAt) Selections.prune(dir, ::manifestOf)
        next
    }

    // Every GitHub source, each in a thread of its own so a slow one delays no other (Http's deadline ends it). Only in
    // the background check and on Check now (PLAN.md section 4).
    fun check(tripUnderWay: Boolean): Report {
        val updated = java.util.Collections.synchronizedList(ArrayList<String>())
        val failed = java.util.Collections.synchronizedList(ArrayList<String>())
        sources.filter { it.kind == Kind.GITHUB }.map { s0 ->
            Thread({
                runCatching { refresh(s0.key, waitForTrip = tripUnderWay) }.onSuccess { s ->
                    if (s.error.isNotEmpty()) failed += s.name else if (s.updatedAt != s0.updatedAt) updated += s.name
                }.onFailure { failed += s0.name }
            }, "kav-ext-check").apply { start() }
        }.forEach { it.join() }
        return Report(updated.toList(), failed.toList())
    }

    private fun write(f: File, bytes: ByteArray) {
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        if (!tmp.renameTo(f)) throw java.io.IOException("couldn't write ${f.name}")
    }

    companion object {
        // The engine's words for what it refuses; Kav's words for them in both languages are ExtensionsScreen's.
        const val NOT_GITHUB = "not a GitHub repository"
        const val ALREADY = "already added"
        const val NEWER_KAV = "needs a newer version of Kav"
    }
}
