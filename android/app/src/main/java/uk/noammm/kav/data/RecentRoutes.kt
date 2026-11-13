package uk.noammm.kav.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import uk.noammm.kav.ActiveJourney

// The latest selected journey, reopened as it was. Recent searches retain only places and always plan afresh.
object RecentRoutes {
    class Saved(val routes: List<Transit.Itinerary>, val resolved: Transit.Resolved, val sections: List<Transit.Section>)

    private fun latestFile(ctx: Context) = File(ctx.filesDir, "latest-route.json")

    fun latest(ctx: Context): ActiveJourney? = runCatching {
        JourneyFile.parse(JSONObject(latestFile(ctx).readText()))?.first
    }.getOrNull()

    fun saveLatest(ctx: Context, route: ActiveJourney) {
        val f = latestFile(ctx)
        val tmp = File(f.path + ".tmp")
        try {
            tmp.writeText(JourneyFile.json(route, 0).toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) {
            tmp.delete()
        }
        // The routes of each recent search an older Kav kept, which nothing reads now.
        File(ctx.filesDir, "recent-routes").deleteRecursively()
    }

}
