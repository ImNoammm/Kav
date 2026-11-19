package uk.noammm.kav.ext

import org.json.JSONObject

// A bundle (.kavb): every extension of one source in one zip, as its GitHub Release carries it or the rider picks it
// on the phone. bundle.json ({"format": 1, "name": "...", "icon": "icon.png"}, the name and icon optional) and one folder
// per extension, named as its manifest names it, holding its manifest.json, its dex and its own files. Nothing else at
// the top but the icon: the source's own picture, a PNG or JPEG within SourceImages' limits.
//
// Read whole and checked before anything is written (Archive's limits, each manifest's rules, each extension's dex where
// its manifest says). One without Android code (a JavaScript one, or one with none) is kept in the list, marked older:
// it shows, with why, and can't be switched on.
class Bundle(val name: String, val sha256: String, val extensions: List<Ext>, val icon: ByteArray? = null) {
    // sha256: a digest of the extension's own files, so an update replaces only what changed.
    class Ext(val name: String, val manifest: Manifest, val files: Map<String, ByteArray>, val sha256: String) {
        val older get() = manifest.older
    }

    companion object {
        const val FILE = "bundle.json"
        const val SUFFIX = ".kavb"
        private const val MOST = 64

        fun read(bytes: ByteArray): Bundle {
            val digest = sha256(bytes).hex()
            val files = try { Archive.read(bytes) } catch (e: Archive.Bad) { throw TrustError(e.message ?: "bad bundle") }
            val head = files[FILE]?.let { StrictJson.obj(it, 16 shl 10) } ?: JSONObject()
            if (head.has("format") && head.whole("format", 0) != 1L) throw TrustError("unknown bundle format")
            val iconName = head.optString("icon")
            files.keys.firstOrNull { '/' !in it && it != FILE && it != iconName }?.let { throw TrustError("a file outside the extensions' folders: $it") }
            val icon = iconName.takeIf { it.isNotEmpty() }?.let { n ->
                val b = files[n] ?: throw TrustError("its icon isn't in it")
                b.takeIf { SourceImages.size(it, jpegToo = true) != null } ?: throw TrustError("its icon isn't a PNG or JPEG of at most 256 KB and 1024 by 1024 pixels")
            }
            val byFolder = files.keys.filter { '/' in it }.groupBy { it.substringBefore('/') }
            if (byFolder.isEmpty()) throw TrustError("no extensions in it")
            if (byFolder.size > MOST) throw TrustError("more than $MOST extensions")
            val exts = byFolder.toSortedMap().map { (folder, names) ->
                val own = names.associate { it.substringAfter('/') to files.getValue(it) }
                ext(folder, own)
            }
            return Bundle(Names.clean(head.optString("name"), 64), digest, exts, icon)
        }

        private fun ext(folder: String, files: Map<String, ByteArray>): Ext {
            val bytes = files[Packages.MANIFEST] ?: throw TrustError("$folder: no manifest.json")
            val m = Manifest(StrictJson.obj(bytes, 64 shl 10))
            if (m.name != folder) throw TrustError("$folder: its folder isn't named as its manifest names it")
            if (!Manifest.NAME.matches(m.name)) throw TrustError("$folder: bad name")
            if (!m.older) Packages.check(m, files)
            val listed = files.keys.sorted().joinToString("\n") { "$it ${sha256(files.getValue(it)).hex()}" }
            return Ext(m.name, m, files, sha256(listed.toByteArray()).hex())
        }
    }
}
