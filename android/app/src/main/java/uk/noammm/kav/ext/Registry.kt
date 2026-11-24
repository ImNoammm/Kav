package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

// The installed extensions, every one from a source's bundle (Hub, Bundle). Everything lives under one folder:
//   registry.json        the one record of what is installed: the commit point of every change
//   pkgs/<sha256>/       an extension's files, by their digest (Bundle.Ext.sha256), never changed once written
//   state/<key>/         an extension's saved data: its settings, and what its code keeps there
//   stage/               work in progress, emptied whenever the registry opens
// A change writes every new folder first, then replaces registry.json in one rename. A kill at any point leaves either
// the old record, whose folders were never touched, or the new one, whose folders were all in place before it was
// written. Opening the registry deletes whatever no record points at.
//
// An extension's identity is "<its source's id>/<its name>" (Hub.Source.publisher), so an update of its source's bundle
// is the same extension, with its switch and its data. Installing never runs extension code.
class Registry(val dir: File) {
    companion object {
        private const val FORMAT = 2

        // The folder and file name an identity's things are kept under.
        fun stateKey(identity: String) = sha256(identity.toByteArray()).copyOf(16).hex()
    }

    // enabled: the rider's switch, off until they turn it on, and enabledAt when it last was (0 before Kav kept it).
    // problem: why it can't run, "" when it can (Manifest.OLDER, a newer Kav needed, or NativeGuard.CRASHED).
    data class Installed(
        val identity: String,
        val source: String,
        val sha256: String,
        val manifestJson: JSONObject,
        val enabled: Boolean = false,
        val problem: String = "",
        val installedAt: Long = 0,
        val updatedAt: Long = 0,
        val enabledAt: Long = 0,
    ) {
        val publisher get() = identity.substringBefore('/')
        val name get() = identity.substringAfter('/')
        val namespace get() = Namespace(publisher, name)
        val manifest get() = Manifest(manifestJson)
        val displayName get() = manifest.displayName
        val version get() = manifest.version

        fun json(): JSONObject = JSONObject().put("identity", identity).put("source", source).put("sha256", sha256)
            .put("manifest", manifestJson).put("enabled", enabled).put("problem", problem)
            .put("installedAt", installedAt).put("updatedAt", updatedAt).put("enabledAt", enabledAt)

        companion object {
            fun of(o: JSONObject) = Installed(
                o.getString("identity"), o.getString("source"), o.getString("sha256"), o.getJSONObject("manifest"),
                o.optBoolean("enabled"), o.optString("problem"), o.optLong("installedAt"), o.optLong("updatedAt"), o.optLong("enabledAt"),
            )
        }
    }

    class Refused(message: String) : Exception(message)

    // A test kills the process at a named step; nothing in Kav sets it.
    @Volatile var crashAt: ((String) -> Unit)? = null
    private fun step(name: String) = crashAt?.invoke(name)

    private val file = File(dir, "registry.json")
    private val tmp = File(dir, "registry.json.tmp")
    private val pkgs = File(dir, "pkgs")
    private val states = File(dir, "state")
    private val stage = File(dir, "stage")

    var revision = 0L
        private set
    private var list: List<Installed> = emptyList()

    val installed: List<Installed> @Synchronized get() = list

    @Synchronized fun get(identity: String) = list.firstOrNull { it.identity == identity }

    init { open() }

    // Reads the record and clears up whatever an interrupted change left: the commit point decides what stays. A record
    // an older Kav wrote (signed packages) is left behind whole.
    @Synchronized private fun open() {
        tmp.delete()
        stage.deleteRecursively()
        if (file.exists()) {
            val o = JSONObject(file.readText())
            revision = o.getLong("revision")
            list = if (o.optInt("format") == FORMAT) o.getJSONArray("packages").objects().map(Installed::of) else emptyList()
        }
        collect()
    }

    // Deletes every package and data folder no record points at.
    private fun collect() {
        val shas = list.map { it.sha256 }.toSet()
        pkgs.listFiles()?.filter { it.name !in shas }?.forEach { it.deleteRecursively() }
        val keys = list.map { stateKey(it.identity) }.toSet()
        states.listFiles()?.filter { it.name !in keys }?.forEach { it.deleteRecursively() }
    }

    // The folder the extension's saved data is in.
    @Synchronized fun stateDir(identity: String): File? = get(identity)?.let { File(states, stateKey(it.identity)) }

    @Synchronized fun packageDir(identity: String): File? = get(identity)?.let { File(pkgs, it.sha256) }

    // An installed extension's own picture (Manifest.icon), or null when it names none.
    fun iconFile(identity: String): File? = get(identity)?.manifest?.icon?.takeIf { it.isNotEmpty() }?.let { File(packageDir(identity) ?: return null, it) }?.takeIf { it.isFile }

    // A source's extensions now: its bundle's, under its id (publisher). A new one comes in off; one that changed keeps
    // its switch and data; one the bundle no longer has is uninstalled with its data. problem(ext): why one can't run,
    // "" when it can.
    @Synchronized fun put(source: String, publisher: String, exts: List<Bundle.Ext>, problem: (Bundle.Ext) -> String, nowS: Long = System.currentTimeMillis() / 1000) {
        val work = File(stage, "t${System.nanoTime()}").apply { mkdirs() }
        pkgs.mkdirs()
        for (e in exts) {
            if (File(pkgs, e.sha256).exists()) continue
            val into = File(work, e.sha256)
            writeFiles(into, e.files)
            rename(into, File(pkgs, e.sha256))
        }
        step("packages-placed")
        val mine = exts.map { e ->
            val identity = "$publisher/${e.name}"
            val old = get(identity)
            // The crash guard's switch-off stays for the same code until the rider turns it on again; anything else is
            // judged anew.
            val why = problem(e).ifEmpty { old?.problem?.takeIf { it == NativeGuard.CRASHED && old.sha256 == e.sha256 }.orEmpty() }
            Installed(identity, source, e.sha256, e.manifest.json, enabled = old?.enabled == true, problem = why,
                installedAt = old?.installedAt ?: nowS, updatedAt = if (old != null && old.sha256 == e.sha256) old.updatedAt else nowS,
                enabledAt = old?.enabledAt ?: 0)
        }
        val gone = list.filter { it.source == source && mine.none { m -> m.identity == it.identity } }
        for (i in list.filter { it.source == source }) if (mine.firstOrNull { it.identity == i.identity }?.sha256 != i.sha256) Native.retire(i.identity)
        commit(list.filter { it.source != source } + mine)
        work.deleteRecursively()
        gone.forEach(::forget)
        collect()
        step("cleaned")
    }

    // A source removed: every extension of it, with its data.
    @Synchronized fun removeSource(source: String) {
        val gone = list.filter { it.source == source }
        if (gone.isEmpty()) return
        gone.forEach { Native.retire(it.identity) }
        commit(list.filter { it.source != source })
        gone.forEach(::forget)
        collect()
    }

    // What lives beside an uninstalled extension: its log, and everything the rider chose for it (slot switches, rules,
    // planner, Home, tab and theme). Added again, it starts afresh.
    private fun forget(gone: Installed) {
        File(File(dir, "net"), stateKey(gone.identity) + ".json").delete()
        Slots.Picks.drop(dir, gone.identity)
        TripAccess.drop(dir, gone.identity)
        Screens.drop(dir, gone.identity)
        Selections.drop(dir, gone.namespace)
    }

    @Synchronized fun change(identity: String, how: (Installed) -> Installed) {
        val old = get(identity) ?: return
        val new = how(old)
        if (new != old) commit(list.map { if (it.identity == identity) new else it })
        if (!new.enabled || new.problem.isNotEmpty()) Native.retire(identity)
    }

    private fun commit(next: List<Installed>) {
        dir.mkdirs()
        val o = JSONObject().put("format", FORMAT).put("revision", revision + 1).put("packages", JSONArray(next.map { it.json() }))
        FileOutputStream(tmp).use { it.write(o.toString().toByteArray()); it.fd.sync() }
        step("record-written")
        if (!tmp.renameTo(file)) throw java.io.IOException("couldn't replace the registry")
        syncDir(dir)
        revision++
        list = next
        step("committed")
    }

    private fun writeFiles(into: File, files: Map<String, ByteArray>) {
        val root = into.canonicalFile
        for ((name, bytes) in files) {
            val f = File(into, name)
            // Archive.safePath allows no way out, and this checks again before writing.
            if (!f.canonicalPath.startsWith(root.path + File.separator)) throw TrustError("unsafe path: $name")
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { it.write(bytes); it.fd.sync() }
        }
        into.mkdirs()
    }

    private fun rename(from: File, to: File) {
        if (!from.renameTo(to)) throw java.io.IOException("couldn't move ${from.name}")
        syncDir(to.parentFile!!)
    }

    private fun syncDir(d: File) {
        runCatching { java.nio.channels.FileChannel.open(d.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) } }
    }
}
