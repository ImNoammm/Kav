package uk.noammm.kav.ext

import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.Collections

// A source's picture, the way Morphe shows its patch sources: the one its bundle brings (Bundle.icon), else for a GitHub
// source its owner's avatar, else nothing (the screen draws a letter tile). Kept on disk, so a cold start shows it before the network answers, and fetched again
// with each check of the source.
//
// Decoration only: it takes no part in any decision, and never blocks or delays anything. It's fetched on a thread of
// its own, through the same Fetch (so the same proxy and deadline), and any failure keeps what was there. A PNG or a
// JPEG (GitHub keeps photos as JPEG), at most 256 KB and 1024 by 1024 pixels as its header says (the screen decodes it
// within those bounds too).
class SourceImages(private val dir: File, private val fetch: Fetch) {
    // Called on the fetching thread when a picture changed, so the screen can draw it.
    @Volatile var onChange: (() -> Unit)? = null
    private val busy = Collections.synchronizedSet(HashSet<String>())

    fun file(key: String) = File(File(dir, "images"), sha256(key.toByteArray()).copyOf(16).hex())

    // Fetches it again in the background, once at a time per source.
    fun refresh(s: Hub.Source) {
        if (s.kind != Hub.Kind.GITHUB || s.icon || !busy.add(s.key)) return
        Thread({ try { runCatching { load(s) } } finally { busy.remove(s.key) } }, "kav-ext-image").apply { isDaemon = true }.start()
    }

    fun forget(key: String) { file(key).delete() }

    // The picture its bundle brings, kept as it came (Bundle checked it against the limits).
    fun put(key: String, bytes: ByteArray) {
        val f = file(key)
        if (f.exists() && f.readBytes().contentEquals(bytes)) return
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes) }
        if (tmp.renameTo(f)) onChange?.invoke()
    }

    // Its owner's avatar, kept. False when there was none within the limits (what was kept stays).
    fun load(s: Hub.Source): Boolean {
        val b = runCatching { fetch.get(githubAvatar(s.repo.substringBefore('/')), MAX) }.getOrNull()?.takeIf { size(it, jpegToo = true) != null } ?: return false
        val f = file(s.key)
        if (f.exists() && f.readBytes().contentEquals(b)) return true
        f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp")
        FileOutputStream(tmp).use { it.write(b) }
        if (!tmp.renameTo(f)) return false
        onChange?.invoke()
        return true
    }

    companion object {
        const val MAX = 256 shl 10
        const val SIDE = 1024

        fun githubAvatar(owner: String) = "https://github.com/${URLEncoder.encode(owner, "UTF-8")}.png?size=128"

        // Width and height from the file's header, when it's a PNG (or a JPEG, where allowed) within the limits; null
        // for anything else. Nothing is decoded here.
        fun size(b: ByteArray, jpegToo: Boolean = false): Pair<Int, Int>? {
            if (b.size > MAX) return null
            val wh = png(b) ?: if (jpegToo) jpeg(b) else null
            return wh?.takeIf { (w, h) -> w in 1..SIDE && h in 1..SIDE }
        }

        private fun int(b: ByteArray, at: Int) = ((b[at].toInt() and 0xff) shl 24) or ((b[at + 1].toInt() and 0xff) shl 16) or
            ((b[at + 2].toInt() and 0xff) shl 8) or (b[at + 3].toInt() and 0xff)

        private val PNG = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        private fun png(b: ByteArray): Pair<Int, Int>? {
            if (b.size < 24 || !b.copyOf(8).contentEquals(PNG) || String(b, 12, 4, Charsets.ISO_8859_1) != "IHDR") return null
            return int(b, 16) to int(b, 20)
        }

        // The first start-of-frame marker's size, walking the JPEG's segments.
        private fun jpeg(b: ByteArray): Pair<Int, Int>? {
            if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
            var i = 2
            while (i + 9 < b.size) {
                if (b[i] != 0xFF.toByte()) return null
                val marker = b[i + 1].toInt() and 0xff
                val len = ((b[i + 2].toInt() and 0xff) shl 8) or (b[i + 3].toInt() and 0xff)
                if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                    val h = ((b[i + 5].toInt() and 0xff) shl 8) or (b[i + 6].toInt() and 0xff)
                    val w = ((b[i + 7].toInt() and 0xff) shl 8) or (b[i + 8].toInt() and 0xff)
                    return w to h
                }
                if (len < 2) return null
                i += 2 + len
            }
            return null
        }
    }
}
