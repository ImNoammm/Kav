package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject

// An extension's manifest.json, as a bundle carries it (Bundle): what it is, the Android code it brings (Native) and
// what it adds to Kav.
class Manifest(val json: JSONObject) {
    val name: String = json.optString("name")
    // Cleaned, as Kav prints it in its own screens (Names.clean); any script, emoji included.
    val displayName: String = Names.clean(json.optString("displayName")).ifBlank { name }
    val version: String = json.optString("version")
    val release: Long = json.whole("release", -1)
    val minKav: Int = json.small("minKav", 0)
    val api: Int = json.small("api", 1)
    val description: String = json.optString("description")
    val permissions: List<String> = json.optJSONArray("permissions").strings().sorted()
    // What it adds that Kav can't see in its code (native code draws and posts by itself): "map", "notifications",
    // "location", "settings". Words Kav says on its row and under "It can"; any other word is ignored.
    val adds: List<String> = json.optJSONArray("adds").strings().filter { it in ADDS }.distinct()
    val issues: String = json.optString("issues")
    val homepage: String = json.optString("homepage")
    // Its own picture, a file of the package (PNG, JPEG or WebP), shown where it's listed and installed. Empty: none.
    val icon: String = json.optString("icon")
    val contributions: List<Declared> = json.optJSONArray("contributions").objects().map {
        Declared(it.optString("id"), it.optString("capability"), it.optJSONArray("permissions").strings().sorted())
    }
    // An annotator's tags with their words: {"quiet": {"en": "Quiet streets", "he": "רחובות שקטים"}}.
    val tags: Map<String, Pair<String, String>> = json.optJSONObject("tags")?.let { t ->
        t.keys().asSequence().mapNotNull { k -> t.optJSONObject(k)?.let { k to (it.optString("en") to it.optString("he")) } }.toMap()
    }.orEmpty()
    // The publisher's one-sentence reason for a line Kav shows about it (ExtUses), by the line's key or permission word:
    // {"internet": {"en": "Fetches the weather forecast", "he": "…"}}. Shown under Kav's own words, never instead.
    // "reasons" in api 2; "explanations", its old name, is still read.
    val explanations: Map<String, Pair<String, String>> = (json.optJSONObject("reasons") ?: json.optJSONObject("explanations"))?.let { t ->
        t.keys().asSequence().mapNotNull { k -> t.optJSONObject(k)?.let { k to (it.optString("en") to it.optString("he")) } }.toMap()
    }.orEmpty()
    class Declared(val id: String, val capability: String, val permissions: List<String>)

    // Android code only: a JavaScript package, or one with no code at all, was made for an older Kav.
    val older: Boolean get() = json.has("runtime") || !Native.declares(this)

    fun check() {
        if (json.whole("format", 0) != 1L) throw TrustError("unknown package format")
        if (older) throw Registry.Refused(OLDER)
        if (!NAME.matches(name)) throw TrustError("bad package name")
        if (version.isBlank() || version.length > 32) throw TrustError("bad version")
        if (release < 1) throw TrustError("bad release number")
        if (contributions.any { !CONTRIBUTION.matches(it.id) } || contributions.map { it.id }.toSet().size != contributions.size)
            throw TrustError("bad contribution ids")
        // A contribution's old "domain" is ignored, and a capability or API this Kav doesn't know installs and does nothing
        // (minKav says what it needs).
        // Permission and capability names are plain words, so a package-wide permission can't read as a contribution's
        // ("permission:<id>:<name>") and a capability can't describe itself in a sentence.
        if (permissions.any { !CONTRIBUTION.matches(it) } || contributions.any { c -> !CONTRIBUTION.matches(c.capability) || c.permissions.any { !CONTRIBUTION.matches(it) } })
            throw TrustError("bad permission or capability name")
        if (description.length > 2000) throw TrustError("text too long")
        for (u in listOf(issues, homepage)) if (u.isNotEmpty() && !u.startsWith("https://")) throw TrustError("bad link")
        val notes = json.opt("reasons") ?: json.opt("explanations")
        if (notes != null && (notes !is JSONObject || notes.keys().asSequence().any { notes.opt(it) !is JSONObject })) throw TrustError("bad reasons")
        if (explanations.any { (_, v) -> v.first.length > 140 || v.second.length > 140 }) throw TrustError("bad reasons")
        if (tags.size > 32 || tags.any { (k, v) -> !CONTRIBUTION.matches(k) || v.first.isBlank() || v.first.length > 40 || v.second.length > 40 })
            throw TrustError("bad tags")
        // Its pages, settings, theme and widgets (ext/Pages.kt, ext/Looks.kt, ext/Widgets.kt).
        PageSpecs.check(this)
        Looks.check(this)
        Widgets.check(this)
    }

    companion object {
        val ADDS = listOf("map", "notifications", "location", "settings")
        val NAME = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        internal val CONTRIBUTION = Regex("[a-z0-9][a-z0-9._-]{0,63}")
        // Why a package that brings no Android code (a JavaScript one, or one with none at all) isn't installed, and
        // an installed one is switched off (Hub): Kav's words for it in both languages are ExtensionsScreen's.
        const val OLDER = "made for an older version of Kav"
    }
}

// The names Kav prints in its own two screens (an extension's and a source's name, a link's name=) go into Kav's own
// sentences, so they are plain text. clean: every control and formatting character removed (line breaks, direction
// controls, zero-width characters other than a joiner between two emoji), and double quotes; spaces of any kind become
// one space between words; at most 64 characters. Any script and any emoji is a name (OPEN-PLAN section 3). Nothing an
// extension draws itself is cleaned.
object Names {
    // The characters a printed name may not carry: control and formatting characters (line breaks, direction controls, zero-width characters), private-use and unassigned code
    // points, lone surrogates, and the Hangul fillers (U+115F, U+1160, U+3164, U+FFA0), letters that draw as nothing.
    private val HIDDEN = setOf(
        Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.PRIVATE_USE, Character.UNASSIGNED,
        Character.SURROGATE,
    ).map { it.toInt() }.toSet()
    private val FILLERS = setOf(0x115F, 0x1160, 0x3164, 0xFFA0)

    fun hidden(codePoint: Int): Boolean = Character.getType(codePoint) in HIDDEN || codePoint in FILLERS
    private val QUOTES = "\"\u201c\u201d\u201e\u201f\u00ab\u00bb\uff02"

    fun clean(s: String, max: Int = 64): String {
        val norm = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC)
        val t = buildString {
            var i = 0
            while (i < norm.length) {
                val cp = norm.codePointAt(i)
                i += Character.charCount(cp)
                when {
                    Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> if (isNotEmpty() && last() != ' ') append(' ')
                    // A straight double quote between two Hebrew letters is how gershayim is typed (ת"א, בע"מ): kept.
                    cp == '"'.code && isNotEmpty() && hebrewLetter(codePointBefore(length)) && i < norm.length && hebrewLetter(norm.codePointAt(i)) -> append('"')
                    cp == ZWJ && joinsEmoji(norm, i - 1) -> appendCodePoint(cp)
                    hidden(cp) || (cp < 0x10000 && cp.toChar() in QUOTES) -> {}
                    else -> appendCodePoint(cp)
                }
            }
        }.trim().take(max).trimEnd()
        return if (t.isNotEmpty() && t.last().isHighSurrogate()) t.dropLast(1) else t
    }

    private fun hebrewLetter(cp: Int) = Character.isLetter(cp) && Character.UnicodeScript.of(cp) == Character.UnicodeScript.HEBREW

    private const val ZWJ = 0x200D

    // The joiner at i has an emoji before it (past a variation selector or skin tone) and an emoji right after it.
    private fun joinsEmoji(s: String, i: Int): Boolean {
        var j = i
        var before = -1
        while (j > 0) {
            val cp = s.codePointBefore(j)
            j -= Character.charCount(cp)
            if (cp == 0xFE0F || cp in 0x1F3FB..0x1F3FF) continue
            before = cp; break
        }
        val after = if (i + 1 < s.length) s.codePointAt(i + 1) else -1
        return emoji(before) && emoji(after)
    }

    private fun emoji(cp: Int) = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF || cp == 0x2640 || cp == 0x2642
}

object Packages {
    const val MANIFEST = "manifest.json"

    // One extension's manifest and files, checked before anything is written: the manifest's rules, its icon, and
    // compiled Android code only as the native code its manifest declares (Native).
    fun check(m: Manifest, files: Map<String, ByteArray>) {
        m.check()
        if (m.icon.isNotEmpty() && (m.icon !in files || !Regex(".+\\.(png|jpg|jpeg|webp)").matches(m.icon.lowercase()))) throw TrustError("its icon isn't a picture in it")
        Native.check(m, files.keys)
        files.keys.firstOrNull { it.lowercase().endsWith(".dex") && it != Native.of(m)?.dex }?.let { throw TrustError("it can't bring code it doesn't declare: $it") }
    }
}

internal fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
