package uk.noammm.kav.ext

import org.json.JSONObject
import java.io.File

// The community list of extension sources (kavapp.link/community, written each night by site/scripts/community.mjs), as
// suggestions under Add a source. The site publishes it as a small file beside the sources' pictures; Kav asks for it
// only while the rider has Add a source open, at most once a day, with the same Fetch as everything else here (the
// rider's proxy, no key, no cookie, nothing about the rider), and keeps the last copy, so it shows at once next time and
// offline. Any failure keeps what was kept, and no list means no suggestions. A suggestion is only that: tapping one
// reads the source and asks, as typing its address does.
object Community {
    const val URL = "https://kavapp.link/community/sources.json"
    private const val IMAGES = "https://kavapp.link/community/"
    private const val MAX = 256 shl 10
    private const val DAY = 24 * 3600L

    // repo: "owner/repo". image: the picture's file name on the site, or null.
    class Suggestion(val repo: String, val name: String, val image: String?) {
        val key get() = Hub.Source.github(repo)
    }

    // The pictures' names the script writes: 16 hex digits and the kind.
    private val PICTURE = Regex("^[0-9a-f]{16}\\.(png|jpg)$")

    // The list as the site writes it, in its own order (by name); anything that isn't a GitHub repository Kav can use is
    // left out, and a name is cleaned as a source's own name is.
    fun read(bytes: ByteArray): List<Suggestion> {
        val o = StrictJson.obj(bytes, MAX)
        if (o.optInt("format") != 1) return emptyList()
        return o.optJSONArray("sources").objects().mapNotNull { s ->
            val repo = GitHub.repoOf(s.optString("path")) ?: return@mapNotNull null
            val name = Names.clean(s.optString("name")).ifEmpty { repo.substringAfter('/') }
            Suggestion(repo, name, s.optString("image").takeIf { PICTURE.matches(it) })
        }.distinctBy { it.key }
    }

    private fun kept(dir: File) = File(dir, "community.json")

    fun picture(dir: File, s: Suggestion): File? = s.image?.let { File(File(dir, "community"), it) }

    // What was kept, at once (nothing when there's none).
    fun cached(dir: File): List<Suggestion> = runCatching {
        val o = JSONObject(kept(dir).readText())
        read(o.getJSONObject("list").toString().toByteArray())
    }.getOrDefault(emptyList())

    // Asked again when the kept copy is a day old (a conditional request, so an unchanged list isn't sent again), and
    // its pictures fetched once each. Answers the list, the kept one when nothing changed or anything failed.
    fun refresh(dir: File, fetch: Fetch, now: Long = System.currentTimeMillis() / 1000): List<Suggestion> {
        val f = kept(dir)
        val before = runCatching { JSONObject(f.readText()) }.getOrNull()
        var list = cached(dir)
        if (before == null || now - before.optLong("checkedAt") >= DAY || now < before.optLong("checkedAt")) {
            runCatching {
                val got = fetch.getIfChanged(URL, MAX, before?.optString("etag").orEmpty())
                val body = got?.let { JSONObject(String(it.body, Charsets.UTF_8)) } ?: before?.getJSONObject("list") ?: return@runCatching
                if (got != null) list = read(got.body)
                Files.replace(f, JSONObject().put("checkedAt", now).put("etag", got?.etag ?: before?.optString("etag").orEmpty())
                    .put("list", body).toString().toByteArray())
            }
        }
        // Pictures of sources no longer listed go.
        val names = list.mapNotNull { it.image }.toSet()
        File(dir, "community").listFiles()?.forEach { if (it.name !in names) it.delete() }
        for (s in list) {
            val p = picture(dir, s) ?: continue
            if (p.isFile) continue
            runCatching { fetch.get(IMAGES + s.image, SourceImages.MAX).takeIf { SourceImages.size(it, jpegToo = true) != null }?.let { Files.replace(p, it) } }
        }
        return list
    }

    // The first few the rider hasn't added, in the list's order.
    fun suggest(list: List<Suggestion>, added: Set<String>, most: Int = 5): List<Suggestion> = list.filter { it.key !in added }.take(most)
}
