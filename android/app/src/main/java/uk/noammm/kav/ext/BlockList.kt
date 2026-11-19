package uk.noammm.kav.ext

import java.io.File
import java.io.FileOutputStream

// The security block list (PLAN.md 9.1): Morphe's shape, with three differences. It's a file signed with Kav's own
// key, read in Kav's background check, never an API that sees who asks; entries name a GitHub source or one bundle's
// digest; and the rider can turn it off. A failed refresh keeps the last list, which applies offline. An older list never
// replaces a newer one, so a replay can't unblock anything.
//
// Security only: an entry whose reason isn't one of REASONS is ignored, so the list can't be used for anything else
// even by whoever holds its key.
//
// The file: a signed envelope (role block-list) of {"format":1,"sequence":N,"entries":[{"github":"owner/repo" |
// "bundle":sha256, "reason":"malware"}]}. An entry of a kind this Kav doesn't know is ignored.
class BlockList(dir: File, private val key: String) {
    data class Entry(val kind: String, val value: String, val reason: String)

    private val kept = File(dir, "block-list.json")
    @Volatile var entries: List<Entry> = emptyList()
        private set
    @Volatile var sequence = -1L
        private set
    private var payload = ByteArray(0)

    init { if (key.isNotEmpty()) runCatching { kept.takeIf { it.exists() }?.readBytes()?.let { read(it) } } }

    private fun read(bytes: ByteArray): Pair<Long, List<Entry>> {
        val env = Envelope.parse(bytes, Role.BLOCK_LIST, 512 shl 10)
        val o = env.payloadFrom(listOf(Keys.fingerprint(key)))
        if (o.whole("format", 0) != 1L) throw TrustError("unknown block list format")
        val seq = o.whole("sequence", -1)
        val list = o.optJSONArray("entries").objects().mapNotNull { e ->
            val reason = e.optString("reason")
            if (reason !in REASONS) return@mapNotNull null
            val kind = KINDS.firstOrNull { e.has(it) } ?: return@mapNotNull null
            val v = e.optString(kind)
            Entry(kind, v.lowercase(), reason)
        }
        sequence = seq; entries = list; payload = env.payload
        return seq to list
    }

    // A newly fetched list: taken when it verifies and is at least as new as the one kept, the same sequence only with
    // the same contents. Answers whether it was.
    @Synchronized fun take(bytes: ByteArray): Boolean {
        if (key.isEmpty()) return false
        val before = Triple(sequence, entries, payload)
        val (seq, _) = runCatching { read(bytes) }.getOrElse { return false }
        if (seq < before.first || (seq == before.first && !payload.contentEquals(before.third))) {
            sequence = before.first; entries = before.second; payload = before.third; return false
        }
        kept.parentFile?.mkdirs()
        val tmp = File(kept.path + ".tmp")
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        tmp.renameTo(kept)
        return true
    }

    // The entry that blocks a source: its GitHub repository ("owner/repo", null for a file) or its bundle's digest.
    fun source(repo: String?, bundle: String): Entry? = entries.firstOrNull { e ->
        (e.kind == "github" && repo != null && repo.lowercase() == e.value) || (e.kind == "bundle" && bundle.isNotEmpty() && bundle == e.value)
    }

    companion object {
        val REASONS = setOf("malware", "credential-theft", "impersonation", "kav-bug-abuse")
        private val KINDS = listOf("github", "bundle")

        // Where Kav reads the list, and the key it must be signed with (site/extensions/block-list.md). GitHub is asked
        // in the same background check for Kav's own updates, so the list adds no new party.
        const val URL = "https://raw.githubusercontent.com/ImNoammm/kav/HEAD/site/public/extensions/block-list.json"
        const val KEY = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEOEx5RfetEpOsTXCocAwyynzC1FqPyoJhEjT2Whp//iefb5nO8JnwDjsp22BeMmD1v7ADztvuzq0p6hiEGKjjHg=="
    }
}
