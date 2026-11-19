package uk.noammm.kav.ext

import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

// How Kav fetches: an https GET, carrying nothing of Kav's or the rider's (ExtApp.Http), and the same with an ETag, for
// GitHub's conditional requests: null when nothing changed since that ETag.
interface Fetch {
    // The body of an https GET, at most max bytes; throws IOException for anything else.
    fun get(url: String, max: Int): ByteArray

    class Got(val body: ByteArray, val etag: String)

    fun getIfChanged(url: String, max: Int, etag: String): Got?
}

// A source on GitHub: its repository's latest release (GitHub's public API, no sign-in) and the one bundle it carries.
object GitHub {
    const val API = "https://api.github.com"
    const val NO_RELEASE = "no such repository, or it has no release yet"
    private val OWNER = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})")
    private val REPO = Regex("[A-Za-z0-9._-]{1,100}")

    // "owner/repo" from what the rider typed or a link: owner/repo, github.com/owner/repo, an https GitHub address, or
    // an add-source link (kav://add-source?github=owner/repo, https://kavapp.link/add-source?github=...). Null for
    // anything else.
    fun repoOf(text: String): String? {
        val t = text.trim()
        val link = runCatching { URI(t) }.getOrNull()
        if (link != null && ((link.scheme == "kav" && link.host == "add-source") ||
                (link.scheme == "https" && link.host == "kavapp.link" && link.path?.trimEnd('/') == "/add-source"))) {
            val q = link.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            return q["github"]?.let(::plain)
        }
        val path = t.removePrefix("https://").removePrefix("http://").removePrefix("www.").let {
            if (it.startsWith("github.com/", ignoreCase = true)) it.substring("github.com/".length) else if (it.contains("://")) return null else it
        }
        return plain(path.trimEnd('/').removeSuffix(".git"))
    }

    // The first two parts of a path: a link into the repository (its releases, a file) names it too.
    private fun plain(p: String): String? {
        val parts = p.split('/')
        if (parts.size < 2) return null
        val (owner, repo) = parts[0] to parts[1]
        if (!OWNER.matches(owner) || !REPO.matches(repo) || repo == "." || repo == "..") return null
        return "$owner/$repo"
    }

    // The latest release: its tag and its one .kavb asset's address. Null when nothing changed since etag.
    class Release(val tag: String, val bundleUrl: String, val etag: String)

    fun latest(fetch: Fetch, repo: String, etag: String = ""): Release? {
        val got = try { fetch.getIfChanged("$API/repos/$repo/releases/latest", 1 shl 20, etag) ?: return null } catch (e: java.io.IOException) {
            if (e.message == "HTTP 404") throw Registry.Refused(NO_RELEASE) else throw e
        }
        val o = runCatching { JSONObject(String(got.body)) }.getOrElse { throw TrustError("GitHub's answer isn't readable") }
        val bundles = (o.optJSONArray("assets") ?: throw Registry.Refused("its latest release has no files")).objects()
            .filter { it.optString("name").lowercase().endsWith(Bundle.SUFFIX) }
        if (bundles.isEmpty()) throw Registry.Refused("its latest release has no .kavb bundle")
        if (bundles.size > 1) throw Registry.Refused("its latest release has more than one .kavb bundle")
        val url = bundles.single().optString("browser_download_url")
        if (!url.startsWith("https://")) throw TrustError("GitHub's answer isn't readable")
        return Release(o.optString("tag_name").take(100), url, got.etag)
    }
}
