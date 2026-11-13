package uk.noammm.kav.data

import org.json.JSONObject

// Live locations, planning and timetables have independent providers and share one own-server
// configuration. Unknown stored choices use the legacy switches (Sources.fromOld).
enum class Source(val key: String) {
    KAV("kav"), OWN("own"), BUS_NEARBY("bus-nearby"), EGG("egg"), GOOGLE("google");

    val hopOn get() = this == BUS_NEARBY || this == EGG
    // Another app's own service rather than a copy of Kav's server: nothing of it to set up or tap through.
    val provider get() = hopOn || this == GOOGLE
    // Google only gives line alerts: live locations never come from it.
    val live get() = this != GOOGLE

    companion object {
        fun of(key: String?): Source? = entries.firstOrNull { it.key == key }
        // A stored choice: one this Kav no longer has is Kav's own from now on; null for none stored.
        fun stored(key: String?): Source? = of(key) ?: key?.takeIf { it.isNotEmpty() }?.let { KAV }
        fun liveOf(key: String?): Source? = of(key)?.takeIf { it.live }

        // Each choice's options, in the order Settings offers them.
        val LIVE = listOf(KAV, BUS_NEARBY, EGG, OWN)
        val PLANNING = listOf(KAV, BUS_NEARBY, EGG, OWN)
        val TIMETABLE = listOf(KAV, BUS_NEARBY, EGG, OWN)
        val ALERTS = listOf(KAV, GOOGLE, OWN)
    }
}

internal object Sources {
    // The two settings from before they were apart: live times from a server (the rider's own when one was set up, else
    // Kav's), and Kav's planner, which planned on that same server, only with live times from it too. Whatever they left
    // to a provider this Kav no longer has is Kav's server's now, as is a new rider's, who has neither switch yet.
    fun fromOld(liveFromKav: Boolean, planWithKav: Boolean, ownSet: Boolean): Pair<Source, Source> {
        val server = if (ownSet) Source.OWN else Source.KAV
        return (if (liveFromKav) server else Source.KAV) to (if (liveFromKav && planWithKav) server else Source.KAV)
    }

    // The same from a backup: its own choices where it has them, else its old switches; null for what it doesn't hold.
    fun fromBackup(o: JSONObject, ownSet: Boolean): Pair<Source?, Source?> {
        val old = if (o.has("liveFromKav") || o.has("planWithKav")) {
            fromOld(o.optBoolean("liveFromKav"), o.optBoolean("planWithKav"), ownSet)
        } else null
        return (Source.liveOf(o.optString("liveSource")) ?: old?.first) to (Source.stored(o.optString("planSource")) ?: old?.second)
    }

    // The timetable for a rider who hasn't picked one: the week from the rider's own server when either other choice
    // uses it, as it came before it was a choice of its own, else Kav's, the default.
    fun timetableOf(live: Source, plan: Source, ownSet: Boolean): Source =
        if (ownSet && (live == Source.OWN || plan == Source.OWN)) Source.OWN else Source.KAV

    // The server the week on the phone comes from: the rider's own when it's the timetable picked, else none: GitHub
    // builds it (Timetable.RELEASE), never Kav's server. A provider picked still has the week as the phone's own copy.
    fun timetableFrom(timetable: Source, own: String): String? = if (own.isNotEmpty() && timetable == Source.OWN) own else null

    // The server new share links are made on, a choice of its own: Kav's or the rider's own. Not picked yet, as before it
    // was a choice: the rider's own when live times or trips come from it, else Kav's. Own picked with none set up: Kav's.
    fun shareOn(picked: Source?, live: Source, plan: Source, own: String): String = when {
        own.isEmpty() -> KavLive.KAV_SERVER
        picked == Source.OWN -> own
        picked == Source.KAV -> KavLive.KAV_SERVER
        live == Source.OWN || plan == Source.OWN -> own
        else -> KavLive.KAV_SERVER
    }

    // What the rider's choices still take from Kav's server, for Settings to say so: a choice of Kav's, or of their own
    // before it's set up. tabs: the planners asked on every search (KavPlan.tabs()), none without a tab each.
    enum class Part { LIVE, TRIPS, TAB, LINKS }

    fun onKav(live: Source, plan: Source, tabs: List<Source>, share: String, own: String): List<Part> {
        fun kav(s: Source) = s == Source.KAV || (s == Source.OWN && own.isEmpty())
        return listOfNotNull(
            Part.LIVE.takeIf { kav(live) },
            Part.TRIPS.takeIf { kav(plan) },
            Part.TAB.takeIf { !kav(plan) && tabs.any(::kav) },
            Part.LINKS.takeIf { share == KavLive.KAV_SERVER },
        )
    }
}
