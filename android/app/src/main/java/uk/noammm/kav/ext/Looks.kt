package uk.noammm.kav.ext

import org.json.JSONObject

// Themes: data. A theme package ("kind": "theme", Themes below), or an extension that declares a "theme" contribution,
// carries its look in its signed manifest:
//
//   "theme": {"looks": {"dark": {"bg": "#0B0F14", "accent": "#7FD1C7"}, "light": {...}},
//             "lineBadges": "strip", "corners": {"card": 16, "sheet": 24, "tile": 8, "badge": 4, "control": 6},
//             "font": "fonts/Mono.ttf", "outline": "double", "map": {"dark": {"ground": "#050805", "ink": "#33FF66"}},
//             "skin": {"button": {"image": "skin/button.png", "insets": [4, 4, 4, 4], "scale": 1.5, "ink": "#000000"},
//                      "background": "skin/wall.jpg"}}
//
// Any of Kav's 18 colour roles (ui/Theme.kt, Palette) and the accent, any subset, any value, for any of the three
// looks; the bus line badges' style (Kav's plate or the strip); the corners of cards, sheets, tiles, line plates and
// controls, in dp within CORNERS; a font file of its own that Kav's text is drawn in (FONT); and how cards and controls
// are outlined (OUTLINES); for any look, the colors Kav's own map is drawn in (MapTone); and pictures drawn in place of
// some of Kav's parts, in every look (Skin). Whatever it leaves out
// stays Kav's, or the rider's own (the accent, the badges) (OPEN-PLAN section 3). Fields Kav doesn't take (a map style)
// are ignored: the map type stays the rider's, since every map but Kav's own loads from someone else. Readability is the maker's to check (the developer kit warns); Kav refuses
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
    // Each corner a theme may set, with the smallest and largest it takes, in dp. Kav's own: 22, 28, 12, 8, and round
    // ends for controls (buttons, chips, segmented controls, icon buttons, switches).
    val CORNERS = mapOf("card" to 0..32, "sheet" to 0..40, "tile" to 0..24, "badge" to 0..16, "control" to 0..24)
    // How cards and controls are outlined (ui/Outlines.kt): Kav's hairline round cards and nothing round controls, one
    // solid line, a double line round cards, a line drawn by hand, or a raised edge lit from above.
    val OUTLINES = listOf("kav", "line", "double", "sketch", "bevel")
    // A font file in the package: a path of plain names, ending .ttf or .otf.
    val FONT = Regex("([A-Za-z0-9_-][A-Za-z0-9._-]{0,95}/){0,3}[A-Za-z0-9_-][A-Za-z0-9._-]{0,91}\\.(ttf|otf|TTF|OTF)")

    // The parts a skin may draw, each a picture stretched by nine slices: the top of a screen, the tab bar and its chosen
    // tab, buttons (as they are, pressed, and the main one), cards and a strip across their top, and fields.
    val PIECES = listOf("topBar", "tabBar", "tabSelected", "button", "buttonPressed", "buttonPrimary", "card", "cardTitle", "field")
    // A piece's picture, PNG, and the page's background picture, PNG, JPEG or WebP: paths of plain names, as for FONT.
    val PIECE_IMAGE = Regex("([A-Za-z0-9_-][A-Za-z0-9._-]{0,95}/){0,3}[A-Za-z0-9_-][A-Za-z0-9._-]{0,91}\\.(png|PNG)")
    val BACKGROUND_IMAGE = Regex("([A-Za-z0-9_-][A-Za-z0-9._-]{0,95}/){0,3}[A-Za-z0-9_-][A-Za-z0-9._-]{0,91}\\.(png|PNG|jpg|JPG|jpeg|JPEG|webp|WEBP)")
    // The most a piece's insets (in its picture's pixels) and its scale (dp for each of them) may be.
    const val INSET_MAX = 512
    const val SCALE_MIN = .25
    const val SCALE_MAX = 8.0

    // One piece: its picture, the left, top, right and bottom edges (in the picture's pixels) kept as they are while the
    // middle stretches, how many dp each pixel is drawn, and the color of text and icons on it (null: Kav's own).
    class Piece(val image: String, insets: List<Int>, val scale: Double = 1.0, val ink: Int? = null) {
        val insets: List<Int> = java.util.Collections.unmodifiableList(insets.toList())
    }

    // A skin: the pieces it sets, and the page's background picture; one it leaves out is Kav's own.
    class Skin(pieces: Map<String, Piece>, val background: String? = null) {
        val pieces: Map<String, Piece> = java.util.Collections.unmodifiableMap(LinkedHashMap(pieces))
        operator fun get(k: String): Piece? = pieces[k]
    }

    // The corners a theme sets, in dp; one it leaves out is null (Kav's own).
    class Corners(values: Map<String, Int>) {
        val set: Map<String, Int> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
        operator fun get(k: String): Int? = set[k]
    }

    // Kav's own map in one look, toned in two colors (MapFile.toned): its ground and the ink of its names, every shade
    // between them where Kav's map has one, and its water in a color of its own, or toned with the rest when null. ARGB.
    data class MapTone(val ground: Int, val ink: Int, val water: Int?)

    // One look's colours, as ARGB, for the roles the theme sets; a role it leaves out is null (Kav's own).
    class Colours(values: Map<String, Int>) {
        private val v: Map<String, Int> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))
        operator fun get(role: String): Int? = v[role]
        val set: Map<String, Int> get() = v
    }

    // A theme as it resolves: immutable, made once. badges: "kav" or "strip", null for the rider's own. font: the path of
    // its font file in its package, null for Kav's type. outline: one of OUTLINES, null for Kav's own. map: each look's
    // map tone it sets; a look it leaves out keeps Kav's map. skin: its pictures, null for none.
    class Theme(
        val identity: String, val name: String, looks: Map<String, Colours>, val badges: String? = null, val corners: Corners? = null,
        val font: String? = null, val outline: String? = null, map: Map<String, MapTone> = emptyMap(), val skin: Skin? = null,
    ) {
        val looks: Map<String, Colours> = java.util.Collections.unmodifiableMap(LinkedHashMap(looks))
        val map: Map<String, MapTone> = java.util.Collections.unmodifiableMap(LinkedHashMap(map))

        // The colours for a look, or null for Kav's own: a look the theme leaves out, or while a protected surface shows.
        fun colours(look: String, protectedShown: Boolean): Colours? = if (protectedShown) null else looks[look]

        // The whole theme as the manifest writes it.
        fun json(): JSONObject = JSONObject().put("looks", JSONObject().apply {
            for ((k, c) in looks) put(k, JSONObject().apply { c.set.forEach { (r, v) -> put(r, hex(v)) } })
        }).apply {
            badges?.let { put("lineBadges", it) }
            corners?.let { c -> put("corners", JSONObject().apply { c.set.forEach { (k, v) -> put(k, v) } }) }
            font?.let { put("font", it) }
            outline?.let { put("outline", it) }
            if (map.isNotEmpty()) put("map", JSONObject().apply {
                for ((k, m) in map) put(k, JSONObject().put("ground", hex(m.ground)).put("ink", hex(m.ink)).apply { m.water?.let { put("water", hex(it)) } })
            })
            skin?.let { sk ->
                put("skin", JSONObject().apply {
                    for ((k, p) in sk.pieces) put(k, JSONObject().put("image", p.image).put("insets", org.json.JSONArray(p.insets))
                        .put("scale", p.scale).apply { p.ink?.let { put("ink", hex(it)) } })
                    sk.background?.let { put("background", it) }
                })
            }
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
        val font = t.optString("font").takeIf { FONT.matches(it) }
        val outline = t.optString("outline").takeIf { it in OUTLINES }
        val map = readMap(t)
        val skin = readSkin(t)
        if (looks.isEmpty() && badges == null && corners == null && font == null && outline == null && map.isEmpty() && skin == null) return null
        return Theme(identity, name, looks, badges, corners, font, outline, map, skin)
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

    // Each look's map tone it sets: one without both its ground and its ink, or a look Kav doesn't know, is left out; a
    // water that isn't a color is toned with the rest.
    fun readMap(t: JSONObject): Map<String, MapTone> {
        val map = t.optJSONObject("map") ?: return emptyMap()
        val out = LinkedHashMap<String, MapTone>()
        for (look in LOOKS) {
            val o = map.optJSONObject(look) ?: continue
            fun c(k: String) = (o.opt(k) as? String)?.let(::parse)
            val ground = c("ground") ?: continue
            val ink = c("ink") ?: continue
            out[look] = MapTone(ground, ink, c("water"))
        }
        return out
    }

    // The skin's pieces and background, or null when it sets none. A piece without a PNG picture, or with insets that
    // aren't four whole numbers within INSET_MAX, is left out; a scale out of range is 1, an ink that isn't a color none.
    fun readSkin(t: JSONObject): Skin? {
        val o = t.optJSONObject("skin") ?: return null
        val pieces = LinkedHashMap<String, Piece>()
        for (k in PIECES) {
            val p = o.optJSONObject(k) ?: continue
            val image = p.optString("image").takeIf { PIECE_IMAGE.matches(it) } ?: continue
            val a = p.optJSONArray("insets")
            val insets = (0 until 4).mapNotNull { i -> (a?.opt(i) as? Number)?.toDouble()?.takeIf { it == Math.floor(it) && it in 0.0..INSET_MAX.toDouble() }?.toInt() }
            if (a == null || a.length() != 4 || insets.size != 4) continue
            val scale = (p.opt("scale") as? Number)?.toDouble()?.takeIf { it in SCALE_MIN..SCALE_MAX } ?: 1.0
            pieces[k] = Piece(image, insets, scale, (p.opt("ink") as? String)?.let(::parse))
        }
        val background = o.optString("background").takeIf { BACKGROUND_IMAGE.matches(it) }
        return if (pieces.isEmpty() && background == null) null else Skin(pieces, background)
    }

    // Whether a font file's start (its table directory) has a variations table: a variable font, drawn at each weight.
    fun variable(head: ByteArray): Boolean {
        if (head.size < 12) return false
        val tables = ((head[4].toInt() and 0xff) shl 8) or (head[5].toInt() and 0xff)
        return (0 until tables).any { i ->
            val at = 12 + 16 * i
            at + 4 <= head.size && String(head, at, 4, Charsets.ISO_8859_1) == "fvar"
        }
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
