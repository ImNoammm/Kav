package uk.noammm.kav.ext

import org.json.JSONObject

// Themes: data. A package that declares a "theme" contribution carries its colours in its signed manifest:
//
//   "theme": {"looks": {"dark": {"bg": "#0B0F14", "realtime": "#3DDC84"}, "light": {...}}}
//
// Any of Kav's 18 colour roles (ui/Theme.kt, Palette), any subset, any value, for any of the three looks; a role or a
// look it leaves out stays Kav's (OPEN-PLAN section 3). Fields Kav doesn't take yet (shapes, type, a map style) are
// ignored. Readability is the maker's to check (the developer kit warns); Kav refuses nothing for it. A theme resolves
// once to one immutable value.
object Looks {
    const val CAPABILITY = "theme"

    val ROLES = listOf(
        "bg", "surface", "surfaceRaised", "fill", "fillStrong", "separator", "text", "textSecondary", "textTertiary",
        "surface2", "surface3", "surface4", "borderStrong", "sunken", "routeIdle", "problem", "critical", "realtime",
    )
    val LOOKS = listOf("dark", "oled", "light")

    // One look's colours, as ARGB, for the roles the theme sets; a role it leaves out is null (Kav's own).
    class Colours(values: Map<String, Int>) {
        private val v: Map<String, Int> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
        operator fun get(role: String): Int? = v[role]
        val set: Map<String, Int> get() = v
    }

    // A theme as it resolves: immutable, made once.
    class Theme(val identity: String, val name: String, looks: Map<String, Colours>) {
        val looks: Map<String, Colours> = java.util.Collections.unmodifiableMap(LinkedHashMap(looks))

        // The colours for a look, or null for Kav's own: a look the theme leaves out, or while a protected surface shows.
        fun colours(look: String, protectedShown: Boolean): Colours? = if (protectedShown) null else looks[look]

        fun json(): JSONObject = JSONObject().apply {
            for ((k, c) in looks) put(k, JSONObject().apply { c.set.forEach { (r, v) -> put(r, hex(v)) } })
        }
    }

    fun declares(m: Manifest) = m.contributions.any { it.capability == CAPABILITY }

    // The package's theme, or null when it declares none (or one with nothing Kav can use). Throws TrustError only when
    // its "theme" isn't an object.
    fun of(m: Manifest, identity: String = "", name: String = m.displayName): Theme? {
        if (!declares(m)) return null
        val t = m.json.opt("theme") as? JSONObject ?: throw TrustError("a theme contribution without its theme")
        return read(t).takeIf { it.isNotEmpty() }?.let { Theme(identity, name, it) }
    }

    fun check(m: Manifest) { of(m) }

    // Each look's colours it sets; a value that isn't a colour, a role or a look Kav doesn't know is left out.
    fun read(t: JSONObject): Map<String, Colours> {
        val looks = t.optJSONObject("looks") ?: return emptyMap()
        val out = LinkedHashMap<String, Colours>()
        for (look in LOOKS) {
            val o = looks.optJSONObject(look) ?: continue
            val values = ROLES.mapNotNull { r -> (o.opt(r) as? String)?.let(::parse)?.let { r to it } }.toMap()
            if (values.isNotEmpty()) out[look] = Colours(values)
        }
        return out
    }

    // "#RRGGBB" or "#AARRGGBB".
    fun parse(s: String): Int? {
        if (!s.startsWith("#") || (s.length != 7 && s.length != 9)) return null
        val n = s.substring(1).toLongOrNull(16) ?: return null
        return if (s.length == 7) (0xFF000000L or n).toInt() else n.toInt()
    }

    fun hex(c: Int) = "#%08X".format(c)
}
