package uk.noammm.kav.ext

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater

// A package's ZIP, read by Kav's own reader rather than java.util.zip, so every rule is checked on the central directory
// and the local headers both, before a byte is written anywhere (A1 section 9, "Package format"). Nothing is extracted
// to disk here: the files come back in memory, at most LIMIT_EXPANDED of them, and only a checked bundle is written.
object Archive {
    const val LIMIT_ARCHIVE = 64 shl 20
    const val LIMIT_EXPANDED = 256 shl 20
    const val LIMIT_FILE = 64 shl 20
    const val LIMIT_ENTRIES = 4096
    private const val LIMIT_RATIO = 100
    private const val LIMIT_DEPTH = 16
    private val NAME = Regex("[A-Za-z0-9._-]{1,96}")
    // A .dex is allowed here and refused by Packages.check unless it's the one the manifest's "native" names (Native).
    private val NESTED = listOf(".kavb", ".jar", ".apk", ".so", ".aab")

    class Bad(message: String) : Exception(message)

    // The files in it, by name, in the archive's order. Throws Bad for anything outside the rules.
    fun read(zip: ByteArray): LinkedHashMap<String, ByteArray> = try {
        entries(zip)
    } catch (e: IndexOutOfBoundsException) {
        throw Bad("damaged archive")
    }

    private fun entries(zip: ByteArray): LinkedHashMap<String, ByteArray> {
        if (zip.size > LIMIT_ARCHIVE) throw Bad("archive too large")
        val b = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN)
        val end = (zip.size - 22 downTo maxOf(0, zip.size - 22 - 0xffff)).firstOrNull { it >= 0 && b.getInt(it) == 0x06054b50 }
            ?: throw Bad("not a ZIP")
        if (b.short(end + 4) != 0 || b.short(end + 6) != 0) throw Bad("multi-part archive")
        val count = b.short(end + 10)
        if (count != b.short(end + 8)) throw Bad("multi-part archive")
        if (count > LIMIT_ENTRIES) throw Bad("too many files")
        val cdSize = b.uint(end + 12)
        val cdAt = b.uint(end + 16)
        if (cdAt + cdSize > end) throw Bad("bad central directory")
        val out = LinkedHashMap<String, ByteArray>()
        val folded = HashSet<String>()
        var at = cdAt.toInt()
        var total = 0L
        repeat(count) {
            if (at + 46 > end || b.getInt(at) != 0x02014b50) throw Bad("bad central directory")
            val madeBy = b.short(at + 4)
            val flags = b.short(at + 8)
            val method = b.short(at + 10)
            val crc = b.uint(at + 16)
            val csize = b.uint(at + 20)
            val usize = b.uint(at + 24)
            val nameLen = b.short(at + 28)
            val extraLen = b.short(at + 30)
            val commentLen = b.short(at + 32)
            val external = b.uint(at + 38)
            val local = b.uint(at + 42).toInt()
            val rawName = zip.copyOfRange(at + 46, at + 46 + nameLen)
            at += 46 + nameLen + extraLen + commentLen
            if (at > end) throw Bad("bad central directory")
            val name = name(rawName, flags)
            if (flags and 1 != 0) throw Bad("encrypted: $name")
            if (csize == 0xffffffffL || usize == 0xffffffffL || local == -1) throw Bad("ZIP64 isn't accepted")
            val mode = (external shr 16).toInt() and 0xf000
            if ((madeBy shr 8) == 3 && mode == 0xa000) throw Bad("link: $name")
            if (name.endsWith("/")) {
                if (usize != 0L) throw Bad("bad folder: $name")
                return@repeat
            }
            if (!folded.add(name.lowercase())) throw Bad("two files named $name")
            if (NESTED.any { name.lowercase().endsWith(it) }) throw Bad("nested archive or code: $name")
            if (method != 0 && method != 8) throw Bad("unsupported compression: $name")
            if (usize > LIMIT_FILE) throw Bad("file too large: $name")
            total += usize
            if (total > LIMIT_EXPANDED) throw Bad("too large once expanded")
            if (usize > 1 shl 20 && usize > csize * LIMIT_RATIO) throw Bad("suspicious compression: $name")
            // The local header must say the same name and method; its sizes may be in a data descriptor (flag 8).
            if (local < 0 || local + 30 > cdAt || b.getInt(local) != 0x04034b50) throw Bad("bad local header: $name")
            val lNameLen = b.short(local + 26)
            val lExtraLen = b.short(local + 28)
            if (!rawName.contentEquals(zip.copyOfRange(local + 30, local + 30 + lNameLen))) throw Bad("names disagree: $name")
            if (b.short(local + 8) != method || (b.short(local + 6) and 1) != 0) throw Bad("headers disagree: $name")
            val data = local + 30 + lNameLen + lExtraLen
            if (data + csize > cdAt) throw Bad("bad local header: $name")
            val bytes = if (method == 0) {
                if (csize != usize) throw Bad("bad stored size: $name")
                zip.copyOfRange(data, data + csize.toInt())
            } else inflate(zip, data, csize.toInt(), usize.toInt(), name)
            if (CRC32().apply { update(bytes) }.value != crc) throw Bad("damaged: $name")
            out[name] = bytes
        }
        return out
    }

    // A file's path inside a package: ASCII names of letters, digits, '.', '_' and '-' between single slashes, never "."
    // or "..", at most LIMIT_DEPTH deep. That alone rules out absolute paths, drive letters, backslashes, NULs and
    // Unicode look-alikes; case look-alikes are caught by the caller.
    fun safePath(name: String): Boolean {
        val dir = name.endsWith("/")
        val parts = (if (dir) name.dropLast(1) else name).split('/')
        return name.length <= 255 && parts.size <= LIMIT_DEPTH && parts.all { it.matches(NAME) && it != "." && it != ".." }
    }

    private fun name(raw: ByteArray, flags: Int): String {
        val utf8 = flags and 0x800 != 0
        if (!utf8 && raw.any { it < 0 }) throw Bad("name isn't ASCII")
        val name = String(raw, Charsets.UTF_8)
        if (!safePath(name)) throw Bad("unsafe path: ${name.take(80).filter { it >= ' ' }}")
        return name
    }

    private fun inflate(zip: ByteArray, at: Int, csize: Int, usize: Int, name: String): ByteArray {
        val inf = Inflater(true)
        try {
            inf.setInput(zip, at, csize)
            val out = ByteArray(usize)
            var n = 0
            while (n < usize) {
                val k = inf.inflate(out, n, usize - n)
                if (k == 0 && (inf.finished() || inf.needsInput() || inf.needsDictionary())) break
                n += k
            }
            // Exactly its declared size: no more (a bomb), no less.
            if (n != usize) throw Bad("bad size: $name")
            if (!inf.finished()) {
                val one = ByteArray(1)
                if (inf.inflate(one) > 0 || !inf.finished()) throw Bad("bad size: $name")
            }
            return out
        } catch (e: java.util.zip.DataFormatException) {
            throw Bad("damaged: $name")
        } finally {
            inf.end()
        }
    }

    private fun ByteBuffer.short(at: Int) = getShort(at).toInt() and 0xffff
    private fun ByteBuffer.uint(at: Int) = getInt(at).toLong() and 0xffffffffL
}
