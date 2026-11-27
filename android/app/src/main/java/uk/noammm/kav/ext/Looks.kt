package uk.noammm.kav.ext

import org.json.JSONObject

// Themes: data. A theme package ("kind": "theme", Themes below), or an extension that declares a "theme" contribution,
// carries its look in its signed manifest:
//
//   "theme": {"looks": {"dark": {"bg": "#0B0F14", "accent": "#7FD1C7"}, "light": {...}},
//             "lineBadges": "strip", "corners": {"card": 16, "sheet": 24, "tile": 8, "badge": 4}}
//
// Any of Kav's 18 colour roles (ui/Theme.kt, Palette) and the accent, any subset, any value, for any of the three
// looks; the bus line badges' style (Kav's plate or the strip); and the corners of cards, sheets, tiles and line plates,
// in dp within CORNERS. Whatever it leaves out stays Kav's, or the rider's own (the accent, the badges) (OPEN-PLAN
// section 3). Fields Kav doesn't take (type, a map style) are ignored: the map type stays the rider's, since every map
// but Kav's own loads from someone else. Readability is the maker's to check (the developer kit warns); Kav refuses
// nothing for it. A theme resolves once to one immutable value.
object Looks {
    const val CAPABILITY = "theme"

    val ROLES = listOf(
        "bg", "surface", "surfaceRaised", "fill", "fillStrong", "separator", "text", "textSecondary", "textTertiary",
        "surface2", "surface3", "surface4", "borderStrong", "sunken", "routeIdle", "problem", "critical", "realtime",
        // Interaction and selection (K.accent): the rider's own color until a theme sets it.
        "accent",
    )
    val LOOKS = listOf("dark", "oled", "light")
    val BADGES = listOf("kav", "strip")
    // Each corner a theme may set, with the smallest and largest it takes, in dp. Kav's own: 22, 28, 12, 8.
    val CORNERS = mapOf("card" to 0..32, "sheet" to 0..40, "tile" to 0..24, "badge" to 0..16)

    // The corners a theme sets, in dp; one it leaves out is null (Kav's own).
    class Corners(values: Map<String, Int>) {
        val set: Map<String, Int> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
        operator fun get(k: String): Int? = set[k]
    }

    // One look's colours, as ARGB, for the roles the theme sets; a role it leaves out is null (Kav's own).
    class Colours(values: Map<String, Int>) {
        private val v: Map<String, Int> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
        operator fun get(role: String): Int? = v[role]
        val set: Map<String, Int> get() = v
    }

    // A theme as it resolves: immutable, made once. badges: "kav" or "strip", null for the rider's own.
    class Theme(val identity: String, val name: String, looks: Map<String, Colours>, val badges: String? = null, val corners: Corners? = null) {
        val looks: Map<String, Colours> = java.util.Collections.unmodifiableMap(LinkedHashMap(looks))

        // The colours for a look, or null for Kav's own: a look the theme leaves out, or while a protected surface shows.
        fun colours(look: String, protectedShown: Boolean): Colours? = if (protectedShown) null else looks[look]

        // The whole theme as the manifest writes it.
        fun json(): JSONObject = JSONObject().put("looks", JSONObject().apply {
            for ((k, c) in looks) put(k, JSONObject().apply { c.set.forEach { (r, v) -> put(r, hex(v)) } })
        }).apply {
            badges?.let { put("lineBadges", it) }
            corners?.let { c -> put("corners", JSONObject().apply { c.set.forEach { (k, v) -> put(k, v) } }) }
        }
    }

    fun declares(m: Manifest) = m.isTheme || m.contributions.any { it.capability == CAPABILITY }

    // The package's theme, or null when it declares none (or one with nothing Kav can use). Throws TrustError only when
    // its "theme" isn't an object: for an extension's theme contribution, also when there is none (a theme package may
    // bring only settings and code).
    fun of(m: Manifest, identity: String = "", name: String = m.displayName): Theme? {
        if (!declares(m)) return null
        val t = m.json.opt("theme")
        if (t !is JSONObject) {
            if (t == null && m.isTheme && m.contributions.none { it.capability == CAPABILITY }) return null
            throw TrustError("a theme contribution without its theme")
        }
        return resolve(t, identity, name)
    }

    // A theme object as the manifest (or the rider's kept choice) writes it, or null when it sets nothing Kav can use.
    fun resolve(t: JSONObject, identity: String, name: String): Theme? {
        val looks = read(t)
        val badges = t.optString("lineBadges").takeIf { it in BADGES }
        val corners = t.optJSONObject("corners")?.let { o ->
            CORNERS.mapNotNull { (k, range) -> (o.opt(k) as? Number)?.toDouble()?.takeIf { it == Math.floor(it) && it.toInt() in range }?.let { k to it.toInt() } }.toMap()
        }?.takeIf { it.isNotEmpty() }?.let(::Corners)
        if (looks.isEmpty() && badges == null && corners == null) return null
        return Theme(identity, name, looks, badges, corners)
    }

    // Checking needs no name in the rider's language, so installing reads nothing of Kav's screens.
    fun check(m: Manifest) { of(m, name = m.defaultName) }

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

// Theme packages ("kind": "theme" in the manifest): Kav calls each a theme everywhere, lists them apart from extensions,
// and applies one at a time. Applied means switched on: its look is the rider's chosen look (Screens.theme), and
// anything else it brings (settings, parts, hooks, pages, its code) runs, as an extension's does. Applying one switches
// every other theme off; Kav's own look switches them all off. An extension's look ("Use this look") is the same single
// choice, so choosing it switches the applied theme off too.
object Themes {
    const val KIND = "theme"

    // The theme applied now: the one switched on (at most one, by the rule below).
    fun applied(installed: List<Registry.Installed>): Registry.Installed? = installed.firstOrNull { it.manifest.isTheme && it.enabled }

    // The themes to switch off when identity is applied (null: Kav's own look), so only it is on.
    fun toSwitchOff(installed: List<Registry.Installed>, identity: String?): List<String> =
        installed.filter { it.manifest.isTheme && it.enabled && it.identity != identity }.map { it.identity }
}
