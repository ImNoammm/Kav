package uk.noammm.kav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.noammm.kav.ui.T
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

// A map's files on the phone, fetched once from Kav's GitHub releases. Each file is checked against the SHA-256 Kav was
// built with before anything reads it, a download stopped half way goes on from where it got to, and every map type
// drawn from the same files shares the pack, its one download and its state (MapPacks).
class MapPack(
    val id: String,
    // Its folder in the app's files.
    private val folder: String,
    val parts: List<Part>,
) {
    // A file kept as it comes, or a .tar.gz unpacked into a folder named after it.
    class Part(val name: String, val url: String, val bytes: Long, val sha256: String) {
        val unpacked: Boolean get() = name.endsWith(".tar.gz")
        val kept: String get() = name.removeSuffix(".tar.gz")
    }

    sealed interface State {
        data object Missing : State
        data class Downloading(val progress: Float) : State
        data class Failed(val why: String) : State
        data object Ready : State
    }

    var state by mutableStateOf<State>(State.Missing)
        private set

    val bytes: Long = parts.sumOf { it.bytes }
    private var job: Job? = null

    fun dir(files: File) = File(files, folder)

    fun file(files: File, part: Part) = File(dir(files), part.kept)

    // A file gets its name only once its hash matched (or, kept from before 3.4, its size did), and a folder only once
    // all of it was unpacked.
    fun has(files: File, part: Part): Boolean {
        val kept = file(files, part)
        return if (part.unpacked) File(kept, DONE).let { it.isFile && it.readText() == part.sha256 }
        else kept.length() == part.bytes
    }

    fun onPhone(files: File) = parts.all { has(files, it) }

    // What it takes on the phone, a download stopped half way included.
    fun used(files: File): Long = parts.sumOf { p ->
        file(files, p).let { if (it.isDirectory) it.walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } else it.length() } +
            File(dir(files), p.name + PART).length()
    }

    fun refresh(files: File) {
        if (state !is State.Downloading) state = if (onPhone(files)) State.Ready else State.Missing
    }

    fun download(files: File) = synchronized(this) {
        if (job?.isActive == true) return@synchronized
        if (onPhone(files)) { state = State.Ready; return@synchronized }
        state = State.Downloading(0f)
        job = MapPacks.scope.launch {
            try {
                var done = 0L
                for (part in parts) {
                    if (!has(files, part)) fetch(files, part) { got ->
                        state = State.Downloading(((done + got).toFloat() / bytes).coerceIn(0f, 1f))
                    }
                    done += part.bytes
                }
                state = State.Ready
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("KavMap", "$id download failed", e)
                state = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // Its files and anything half done go; the maps drawn from it then show what they show before a download.
    fun remove(files: File) = synchronized(this) {
        if (job?.isActive == true) return@synchronized
        for (p in parts) {
            file(files, p).deleteRecursively()
            File(dir(files), p.name + PART).delete()
            File(dir(files), p.kept + UNPACKING).deleteRecursively()
        }
        state = State.Missing
    }

    private fun fetch(files: File, part: Part, progress: (Long) -> Unit) {
        val dir = dir(files).apply { mkdirs() }
        val download = File(dir, part.name + PART)
        if (download.length() > part.bytes) download.delete()
        val have = if (download.length() >= part.bytes) download.length() else try {
            get(part, download, progress)
        } catch (e: HttpError) {
            throw e
        } catch (e: IOException) {
            // The connection dropped: what came stays, and Try again goes on from there.
            if (download.length() == 0L) throw e
            download.length()
        }
        if (have != part.bytes) throw IOException(T("Interrupted at ${have shr 20} MB, try again", "ההורדה נקטעה אחרי ${have shr 20} MB, נסו שוב"))
        place(dir, part, download)
    }

    // Asks for the rest of the file from what's there already, following GitHub's redirect to its storage by hand so
    // each hop asks for the same range.
    private fun get(part: Part, into: File, progress: (Long) -> Unit): Long {
        var have = into.length()
        var at = part.url
        var c = open(at, have)
        var hops = 0
        while (c.responseCode in 300..399 && hops++ < 5) {
            val next = c.getHeaderField("Location") ?: break
            c.disconnect()
            at = URL(URL(at), next).toString()
            c = open(at, have)
        }
        when (c.responseCode) {
            200 -> have = 0
            206 -> {}
            416 -> { c.disconnect(); return into.length() }
            else -> { c.disconnect(); throw HttpError(T("Download HTTP ${c.responseCode}", "הורדה נכשלה, שגיאת HTTP ${c.responseCode}")) }
        }
        try {
            c.inputStream.use { input ->
                FileOutputStream(into, have > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        have += n
                        progress(have)
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        return into.length()
    }

    private fun open(url: String, from: Long) = (RiderProxy.open(URL(url)) as HttpURLConnection).apply {
        instanceFollowRedirects = false
        connectTimeout = 15_000
        readTimeout = 30_000
        setRequestProperty("User-Agent", "Kav")
        if (from > 0) setRequestProperty("Range", "bytes=$from-")
    }

    private class HttpError(message: String) : IOException(message)

    companion object {
        const val PART = ".part"
        const val UNPACKING = ".unpacking"
        // Written into a folder once all of it is unpacked, with the hash of what it came from.
        const val DONE = ".kav-unpacked"

        // A whole download gets its file's name, or is unpacked into its folder, only if its hash is the one Kav was
        // built with: a file changed on the way or at its source is thrown away unread.
        internal fun place(dir: File, part: Part, download: File) {
            if (!sha256(download).equals(part.sha256, ignoreCase = true)) {
                download.delete()
                throw IOException(T("The download wasn't the file Kav expects, so it was thrown away. Try again.",
                    "ההורדה לא הייתה הקובץ ש-Kav מצפה לו, ולכן נמחקה. נסו שוב."))
            }
            val kept = File(dir, part.kept)
            if (part.unpacked) {
                val temp = File(dir, part.kept + UNPACKING)
                temp.deleteRecursively()
                TarGz.unpack(download, temp)
                File(temp, DONE).writeText(part.sha256)
                kept.deleteRecursively()
                if (!temp.renameTo(kept)) throw IOException(T("Could not keep the download", "לא ניתן היה לשמור את ההורדה"))
                download.delete()
            } else if (!download.renameTo(kept)) throw IOException(T("Could not keep the download", "לא ניתן היה לשמור את ההורדה"))
        }

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

// Kav's map packs. The standard map is the same file every version with the offline map downloaded to files/map from
// the map-1 release, so a phone that has it keeps it and downloads nothing more. OpenFreeMap's tiles, fonts and sprite come
// from the maps-1 release (tools/map_packs.py builds them).
object MapPacks {
    private const val RELEASES = "https://github.com/${Updates.OWNER}/${Updates.REPO}/releases/download"

    val STANDARD = MapPack("standard", "map", listOf(
        MapPack.Part("israel.pmtiles", "$RELEASES/map-1/israel.pmtiles", 185_001_087L,
            "a13b4b6ccf8fdf7d98d678debb9067aa724aa8d2d3bcd38882be5b335dee78e6"),
    ))

    val OPENFREEMAP = MapPack("openfreemap", "openfreemap", listOf(
        MapPack.Part("openfreemap-israel.pmtiles", "$RELEASES/maps-1/openfreemap-israel.pmtiles", 104_694_611L,
            "d5c0d6206298ce6c9b097a823d57b3d056ea2d86c0f7d052432c34bfe2a73cd7"),
        MapPack.Part("openfreemap-assets.tar.gz", "$RELEASES/maps-1/openfreemap-assets.tar.gz", 1_918_472L,
            "91cd75b1b7db2bf43a42adaf6db1761613e2bb668a80768e1aed58dcc348c8f1"),
    ))

    val all = listOf(STANDARD, OPENFREEMAP)

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(files: File) {
        for (p in all) p.refresh(files)
        scope.launch { tidy(files) }
    }

    // What earlier versions left: satellite tiles an older version kept, PR 71's test builds' mappacks/ (packs in another
    // layout, and folders of downloads cut short), and an unpacking cut short. Downloads stopped half way stay, to go
    // on from where they got to.
    internal fun tidy(files: File) {
        File(files, "tiles-esri").deleteRecursively()
        File(files, "mappacks").deleteRecursively()
        for (p in all) for (part in p.parts) File(p.dir(files), part.kept + MapPack.UNPACKING).deleteRecursively()
    }
}

// A .tar.gz unpacked into a new folder, file by file: only plain files and folders, each of them inside it. Anything
// else (a link, a name from the root, a "..") stops it before it's written.
internal object TarGz {
    fun unpack(archive: File, into: File) {
        into.mkdirs()
        val root = into.canonicalFile
        GZIPInputStream(archive.inputStream().buffered(1 shl 16), 1 shl 16).use { input ->
            val header = ByteArray(512)
            val buf = ByteArray(1 shl 16)
            while (true) {
                if (!block(input, header)) throw IOException("The archive is cut short")
                if (header.all { it == 0.toByte() }) return
                val short = text(header, 0, 100)
                val prefix = text(header, 345, 155)
                val name = if (prefix.isEmpty()) short else "$prefix/$short"
                val size = octal(header, 124, 12)
                val target = inside(root, name)
                when (header[156].toInt().toChar()) {
                    '5' -> target.mkdirs()
                    '0', '\u0000' -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out ->
                            var left = size
                            while (left > 0) {
                                val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                                if (n < 0) throw IOException("The archive is cut short")
                                out.write(buf, 0, n)
                                left -= n
                            }
                        }
                        skip(input, (512 - size % 512) % 512)
                    }
                    else -> throw IOException("Not a file or a folder: $name")
                }
            }
        }
    }

    // Where a name in the archive goes: under root, or nowhere.
    fun inside(root: File, name: String): File {
        val clean = name.removePrefix("./").trimEnd('/')
        if (clean.isEmpty() || clean == ".") return root
        if (clean.startsWith("/") || '\\' in clean || clean.split('/').any { it == ".." })
            throw IOException("Outside the folder: $name")
        val f = File(root, clean).canonicalFile
        if (!f.path.startsWith(root.path + File.separator)) throw IOException("Outside the folder: $name")
        return f
    }

    private fun block(input: InputStream, into: ByteArray): Boolean {
        var got = 0
        while (got < into.size) {
            val n = input.read(into, got, into.size - got)
            if (n < 0) return false
            got += n
        }
        return true
    }

    private fun skip(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) { if (input.read() < 0) throw IOException("The archive is cut short"); left-- } else left -= s
        }
    }

    private fun text(b: ByteArray, at: Int, len: Int): String {
        var end = at
        while (end < at + len && b[end] != 0.toByte()) end++
        return String(b, at, end - at, Charsets.UTF_8)
    }

    private fun octal(b: ByteArray, at: Int, len: Int): Long {
        val s = text(b, at, len).trim()
        if (s.isEmpty()) return 0
        return s.toLongOrNull(8)?.takeIf { it >= 0 } ?: throw IOException("A size the archive can't have")
    }
}
