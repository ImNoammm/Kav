package uk.noammm.kav.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// Who finds the places typed into a search. Stations come from the phone either way; GovMap backs up house numbers for
// both, getting only the typed text.
interface PlaceSearch {
    val key: String

    // Places as the rider types, and corrections of the text when it finds no places for it.
    fun suggest(query: String, at: Pair<Double, Double>?): GoogleMaps.Suggestions

    // The house itself for a typed address ("דיזנגוף 50 תל אביב"), when the search knows it.
    fun house(query: String, at: Pair<Double, Double>?): Transit.Place?
}

object GoogleSearch : PlaceSearch {
    override val key = "google"
    override fun suggest(query: String, at: Pair<Double, Double>?) = GoogleMaps.suggest(query, at)
    override fun house(query: String, at: Pair<Double, Double>?) = GoogleMaps.geocode(query, at)
}

// OpenStreetMap's places through Photon (photon.komoot.io), which is made for searching as you type. It gets the typed
// text and, to rank what's near first, the point private search allows (GoogleMaps.shown), the same as Google does.
object PhotonSearch : PlaceSearch {
    override val key = "osm"
    private const val API = "https://photon.komoot.io/api/"
    // Israel and the West Bank, west, south, east, north.
    private const val BOX = "34.2,29.4,35.95,33.4"

    override fun suggest(query: String, at: Pair<Double, Double>?): GoogleMaps.Suggestions {
        if (query.isBlank()) return GoogleMaps.Suggestions(emptyList(), emptyList())
        return GoogleMaps.Suggestions(places(ask(query, at, 6)), emptyList())
    }

    override fun house(query: String, at: Pair<Double, Double>?): Transit.Place? {
        val typed = TypedAddress.parse(query) ?: return null
        return places(ask(query, at, 6), houses = true).firstOrNull { p ->
            typed.numberIs(p.number) && typed.streetIs(p.name) && typed.townIs(p.detail)
        }
    }

    private fun ask(query: String, at: Pair<Double, Double>?, limit: Int): String {
        val lang = if (query.any { it in 'א'..'ת' }) "default" else "en"
        val near = GoogleMaps.shown(at)?.let { (lat, lon) -> "&lat=$lat&lon=$lon" }.orEmpty()
        val c = RiderProxy.open(URL("$API?q=${URLEncoder.encode(query.trim(), "UTF-8")}&limit=$limit&lang=$lang&bbox=$BOX$near")) as HttpURLConnection
        return try {
            c.connectTimeout = 4000; c.readTimeout = 4000
            c.setRequestProperty("User-Agent", "Kav")
            if (c.responseCode != 200) throw java.io.IOException("Photon HTTP ${c.responseCode}")
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private val Transit.Place.number get() = name.substringAfterLast(' ', "")

    // Photon's GeoJSON as Kav's places: a named place by its name, an address as "street number", its town after it.
    // houses: only addresses, named "street number" for TypedAddress to check.
    internal fun places(body: String, houses: Boolean = false): List<Transit.Place> {
        val features = JSONObject(body).optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Transit.Place>()
        for (i in 0 until features.length()) {
            val f = features.optJSONObject(i) ?: continue
            val p = f.optJSONObject("properties") ?: continue
            val xy = f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
            val street = p.optString("street"); val number = p.optString("housenumber")
            val address = if (street.isNotBlank() && number.isNotBlank()) "$street $number" else null
            if (houses && address == null) continue
            val name = (if (houses) address else p.optString("name").ifBlank { null } ?: address ?: street.ifBlank { null }) ?: continue
            val town = p.optString("city").ifBlank { p.optString("county") }
            val kind = p.optString("osm_value")
            out += Transit.Place(
                name = name,
                detail = listOfNotNull(address?.takeIf { !houses && it != name }, town.ifBlank { null }).joinToString(", "),
                lat = xy.optDouble(1), lon = xy.optDouble(0),
                type = if (kind == "city" || kind == "town" || kind == "village") Transit.PLACE_TOWN else 5,
            )
        }
        return out.distinctBy { "${it.name}|${it.detail}" }
    }
}

// The search picked in Settings, Google's until the settings are read.
object Search {
    val ALL: List<PlaceSearch> = listOf(GoogleSearch, PhotonSearch)
    var provider: PlaceSearch by mutableStateOf(GoogleSearch)
    fun of(key: String?): PlaceSearch = ALL.firstOrNull { it.key == key } ?: GoogleSearch
}
