package uk.noammm.kav.data

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

// Google Maps' own web search, asked the way a logged-out browser asks it: no key, no account, no cookies. How to ask,
// and where the answers sit, is the recipe Vela Maps keeps current (GoogleRecipe). Only the typed text and the area
// search is told (where) go out.
object GoogleMaps {
    private val sequence = AtomicInteger(1)

    class Suggestions(val places: List<Transit.Place>, val queries: List<String>)

    private val NONE = Suggestions(emptyList(), emptyList())

    // Places as Google suggests them while typing, and the searches it would correct the text to ("תחנת רכת חדרה"
    // becomes "תחנת רכבת חדרה מזרח"). A suggested address only carries its street's position, so callers geocode those.
    fun suggest(query: String, at: Pair<Double, Double>?): Suggestions {
        if (query.isBlank()) return NONE
        val recipe = GoogleRecipe.current
        val (lat, lon) = where(at)
        // The map's centre and span bias what comes back.
        val pb = recipe.suggestPb.replace("{SPAN}", "20000").replace("{LNG}", "$lon").replace("{LAT}", "$lat")
            .replace("{W}", "1920").replace("{H}", "945")
        val body = get(recipe, recipe.suggestUrl(language(query)) +
            "&pb=$pb&q=${enc(query)}&tch=1&ech=${sequence.getAndIncrement()}") ?: return NONE
        // {"c":0,"d":")]}'\n[[query, [rows...]]]"} and a comment after it.
        val d = (JSONTokener(body).nextValue() as? JSONObject)?.optString("d") ?: return NONE
        val rows = at(JSONArray(d.substringAfter('\n')), recipe.suggest("rows")) as? JSONArray ?: return NONE
        val places = ArrayList<Transit.Place>()
        val queries = ArrayList<String>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            // A row is nulls around one block, the first array whose first child is an array starting with text.
            val block = (0 until row.length()).mapNotNull { row.optJSONArray(it) }
                .firstOrNull { b -> b.optJSONArray(0)?.opt(0) is String } ?: continue
            val title = (at(block, recipe.suggest("title")) as? String)?.ifBlank { null } ?: continue
            val la = number(block, recipe.suggest("lat")) ?: number(block, recipe.suggest("lat2"))
            val lo = number(block, recipe.suggest("lng")) ?: number(block, recipe.suggest("lng2"))
            if (la == null || lo == null) { queries.add(title); continue }
            places.add(Transit.Place(
                name = (at(block, recipe.suggest("primary")) as? String)?.ifBlank { null } ?: title,
                detail = (at(block, recipe.suggest("secondary")) as? String).orEmpty(),
                lat = la, lon = lo,
                type = if (isTown(at(block, recipe.suggest("kinds")))) Transit.PLACE_TOWN else 5,
                googleId = placeId(at(block, recipe.suggest("id"))),
            ))
        }
        return Suggestions(places, queries)
    }

    // The house a typed address names, as Google's map search places it, or null when it only knows the street.
    fun geocode(query: String, at: Pair<Double, Double>?): Transit.Place? {
        val typed = TypedAddress.parse(query) ?: return null
        val recipe = GoogleRecipe.current
        val (lat, lon) = where(at)
        val pb = recipe.searchPb.replace("{QUERY}", enc(query.replace("*", "*2A").replace("!", "*21")))
            .replace("{LAT}", "$lat").replace("{LNG}", "$lon")
        val body = get(recipe, recipe.searchUrl(language(query)) + "&q=${enc(query)}&pb=$pb") ?: return null
        val root = JSONArray(body.substringAfter(")]}'").trim())
        // An address comes back as one geocoded place, anything else as a list. An entry holds its place at [1].
        val entry = (at(root, recipe.search("single")) as? JSONArray)?.let { JSONArray().put(JSONObject.NULL).put(it) }
            ?: (at(root, recipe.search("results")) as? JSONArray)?.optJSONArray(0) ?: return null
        val la = number(entry, recipe.search("lat")) ?: return null
        val lo = number(entry, recipe.search("lng")) ?: return null
        val text = (at(entry, recipe.search("address")) as? String)?.ifBlank { null }
            ?: at(entry, recipe.search("name")) as? String ?: return null
        // "5R83+J4 תאו, וינגייט 168, הרצליה, 4675765": the part with the house, then its town.
        val parts = text.split(",").map { it.trim() }
        val house = parts.indexOfFirst { part -> searchWords(part).any(typed::numberIs) && typed.streetIs(part) }
        if (house < 0) return null
        val town = parts.drop(house + 1).firstOrNull { p -> p.any { !it.isDigit() } }.orEmpty()
        // Google answers with the nearest street of that name when the typed town has none.
        if (!typed.townIs(town)) return null
        return Transit.Place(name = parts[house], detail = town, lat = la, lon = lo)
    }

    // The photo Google Maps shows first when the place is opened: the first of the place's own gallery, else the photo
    // its search shows for it. The place is found by searching its name around where it is, and only a result that is
    // that place counts: Google's own id for it, or one within near metres, as a name alone can find a namesake in
    // another town. Only photos of the place: never a Street View frame, which is how Google shows an address (Street
    // View is for stops). Null where there's none; throws when Google couldn't be asked. Only the place's name and where
    // it is go out, never where the rider is.
    fun photoOf(name: String, lat: Double, lon: Double, googleId: String?, near: Double = 300.0): String? {
        val recipe = GoogleRecipe.current
        val entries = searchNear(recipe, name, lat, lon) ?: return null
        fun away(e: JSONArray): Double? {
            val la = number(e, recipe.search("lat")) ?: return null
            val lo = number(e, recipe.search("lng")) ?: return null
            return metres(lat, lon, la, lo)
        }
        val same = entries.firstOrNull { e ->
            (googleId != null && at(e, recipe.search("placeId")) == googleId) || (away(e) ?: Double.MAX_VALUE) <= near
        } ?: return null
        photoIn(recipe, same)?.let { return it }
        // Google's entry for the place has no photo of its own (a shop by that name with only the street's view, as
        // "The Golden Mall" is beside the mall itself). Another of the search's places can be it: for a big place (a
        // mall, a station, a university...) the nearest of that kind within reach; for any other, one by its very name.
        val kind = bigKind(name) ?: bigKind(at(same, recipe.search("category")) as? String)
        val reach = if (kind != null) BIG_PLACE_M else near
        fun fits(e: JSONArray): Boolean {
            if (e === same || (away(e) ?: Double.MAX_VALUE) > reach) return false
            val called = at(e, recipe.search("name")) as? String
            return if (kind != null) bigKind(at(e, recipe.search("category")) as? String) == kind || bigKind(called) == kind
            else called != null && plain(called) == plain(name)
        }
        var others = entries.filter(::fits)
        // A single answer leaves no others: its kind is asked for around the place.
        if (others.isEmpty() && entries.size == 1 && kind != null) others = searchNear(recipe, kind, lat, lon).orEmpty().filter(::fits)
        return others.sortedBy { away(it) }.take(3).firstNotNullOfOrNull { photoIn(recipe, it) }
    }

    // A search of Google's map for these words around a point: its places, each an entry holding its place at [1] (one
    // that comes back alone is held the same way). Null where it reads as nothing.
    private fun searchNear(recipe: GoogleRecipe, words: String, lat: Double, lon: Double): List<JSONArray>? {
        val pb = recipe.searchPb.replace("{QUERY}", enc(words.replace("*", "*2A").replace("!", "*21")))
            .replace("{LAT}", "$lat").replace("{LNG}", "$lon")
        // A photo can wait for a slow network, as typing can't.
        val body = get(recipe, recipe.searchUrl(language(words)) + "&q=${enc(words)}&pb=$pb", PATIENT_MS)
            ?: throw java.io.IOException("Google Maps didn't answer")
        val root = JSONArray(body.substringAfter(")]}'").trim())
        return (at(root, recipe.search("single")) as? JSONArray)?.let { listOf(JSONArray().put(JSONObject.NULL).put(it)) }
            ?: (at(root, recipe.search("results")) as? JSONArray)?.let { r -> (0 until r.length()).mapNotNull { r.optJSONArray(it) } }
    }

    // An entry's own photo: its gallery's first, else its search photo, where either is a photo of the place.
    private fun photoIn(recipe: GoogleRecipe, e: JSONArray): String? =
        (at(e, recipe.search("featureId")) as? String)?.let { galleryPhoto(recipe, it) }
            ?: (at(e, recipe.search("photos") + intArrayOf(0, 6, 0)) as? String)?.let(::placePhoto)

    // A big place's kind, by the words its name or Google's category for it has, in English or Hebrew: what's asked for
    // around a place when it needs another of its kind. Null for anything else.
    private val BIG_KINDS = mapOf(
        "shopping mall" to listOf("mall", "shopping center", "shopping centre", "קניון", "מרכז קניות"),
        "station" to listOf("station", "terminal", "תחנת", "מסוף", "רכבת"),
        "university" to listOf("university", "college", "campus", "אוניברסיט", "מכללה", "קמפוס"),
        "hospital" to listOf("hospital", "medical center", "medical centre", "בית חולים", "בית החולים", "מרכז רפואי"),
        "park" to listOf("park", "פארק"),
        "airport" to listOf("airport", "נמל תעופה", "נמל התעופה"),
        "stadium" to listOf("stadium", "arena", "אצטדיון", "היכל"),
    )
    internal fun bigKind(words: String?): String? {
        val w = words?.lowercase() ?: return null
        return BIG_KINDS.entries.firstOrNull { (_, marks) -> marks.any { it in w } }?.key
    }

    // A name as compared: small letters, without "the" and its marks.
    private fun plain(name: String) = name.lowercase().replace(Regex("\\bthe\\b"), "").replace(Regex("[^\\p{L}\\p{N}]+"), "")

    // How far another place of a big one's kind can be and still be it: a mall's entrance can be far from its middle.
    private const val BIG_PLACE_M = 600.0

    // The first photo of a place's gallery that is a photo of it, from Google Maps' own keyless call for it
    // (MapsPhotoService.ListEntityPhotos). An entry's kind is at [1]: 0 is a Street View frame. Null where the gallery
    // has none, or reads in a way Kav doesn't know.
    private fun galleryPhoto(recipe: GoogleRecipe, featureId: String): String? {
        val proto = recipe.photosProto.replace("{FID}", featureId).replace("{COUNT}", "6")
        val request = JSONArray().put(JSONArray().put(JSONArray().put("hspqX").put(proto).put(JSONObject.NULL).put("generic")))
        val text = post(recipe, recipe.photosEndpoint, "f.req=" + enc(request.toString()), PATIENT_MS)
            ?: throw java.io.IOException("Google Maps didn't answer")
        return runCatching {
            val line = text.lineSequence().firstOrNull { it.startsWith("[[\"wrb.fr\",\"hspqX\"") } ?: return null
            val photos = JSONArray(JSONArray(line).getJSONArray(0).getString(2)).optJSONArray(0) ?: return null
            (0 until photos.length()).mapNotNull { photos.optJSONArray(it) }
                .filter { it.opt(1)?.toString() != "0" }
                .firstNotNullOfOrNull { (at(it, intArrayOf(6, 0)) as? String)?.let(::placePhoto) }
        }.getOrNull()
    }

    // A photo URL, if it is one of Google Maps' photos of places (lh3.googleusercontent.com, as gps-cs-s, grass-cs or
    // p/AF1Qip...): not Street View's frames (streetviewpixels-pa) or panoramas (geo*.ggpht.com/cbk).
    private val PHOTO_HOST = Regex("lh\\d+\\.googleusercontent\\.com")
    internal fun placePhoto(url: String): String? {
        val full = if (url.startsWith("//")) "https:$url" else url
        val host = runCatching { java.net.URI(full).host }.getOrNull() ?: return null
        return full.takeIf { it.startsWith("https://") && PHOTO_HOST.matches(host) }
    }

    // Along the ground, as the crow flies.
    private fun metres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val p1 = Math.toRadians(aLat); val p2 = Math.toRadians(bLat)
        val h = Math.pow(Math.sin((p2 - p1) / 2), 2.0) + Math.cos(p1) * Math.cos(p2) * Math.pow(Math.sin(Math.toRadians(bLon - aLon) / 2), 2.0)
        return 2 * 6_371_000.0 * Math.asin(Math.sqrt(h))
    }

    // A suggestion names its Google place as "BChIJ...=".
    private val PLACE_ID = Regex("ChIJ[A-Za-z0-9_-]+")
    internal fun placeId(token: Any?): String? = (token as? String)?.let { PLACE_ID.find(it)?.value }

    // A suggestion's kinds hold 444 for a town (and a regional council); 429 is a neighbourhood, 446 a district, 426 a
    // business, 463 a station, 427 a street.
    private const val TOWN = 444
    internal fun isTown(kinds: Any?): Boolean = (kinds as? JSONArray)?.let { k -> (0 until k.length()).any { k.optInt(it) == TOWN } } == true

    // Whether search is told where the rider is (private search off); while it's on, it's told the stand-in instead: a
    // town's centre or a chosen place (Seen), else a neutral point.
    @Volatile var shareLocation = true
    @Volatile var standIn: () -> Pair<Double, Double>? = { null }
    internal val NEUTRAL = 32.0755 to 34.7755

    private fun where(at: Pair<Double, Double>?) = shown(at) ?: NEUTRAL

    // The point a search may see: the rider's own, or with private search the stand-in (their town, or a place they
    // picked); null when there's none. Every place search goes by it (PlaceSearch).
    internal fun shown(at: Pair<Double, Double>?) = if (shareLocation) at else standIn()

    private fun language(query: String) = if (query.any { it in 'א'..'ת' }) "iw" else "en"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(recipe: GoogleRecipe, url: String, timeout: Int = 4000): String? {
        val c = (RiderProxy.open(URL(url)) as HttpURLConnection).apply {
            connectTimeout = timeout; readTimeout = timeout
            setRequestProperty("User-Agent", recipe.userAgent)
        }
        return try {
            if (c.responseCode != 200) null else c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private const val PATIENT_MS = 20_000

    private fun post(recipe: GoogleRecipe, url: String, body: String, timeout: Int): String? {
        val c = (RiderProxy.open(URL(url)) as HttpURLConnection).apply {
            connectTimeout = timeout; readTimeout = timeout
            requestMethod = "POST"; doOutput = true
            setRequestProperty("User-Agent", recipe.userAgent)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
        }
        return try {
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (c.responseCode != 200) null else c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun at(a: JSONArray, path: IntArray): Any? {
        var x: Any? = a
        for (i in path) x = (x as? JSONArray)?.opt(i)
        return x
    }

    private fun number(a: JSONArray, path: IntArray) = (at(a, path) as? Number)?.toDouble()
}
