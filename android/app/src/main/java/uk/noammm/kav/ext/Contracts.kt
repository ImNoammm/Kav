package uk.noammm.kav.ext

// What Kav and an extension agree on, whoever's code is on the other side (PLAN.md section 2, phase 1).
// Nothing here runs an extension; Extensions.kt holds what is registered and asks it.

enum class Kind(val key: String) {
    STOP("stop"), ROUTE("route"), TRIP("trip"), AGENCY("agency");

    companion object {
        fun of(key: String): Kind? = entries.firstOrNull { it.key == key }
    }
}

// A thing in someone's data: who publishes it, in which of their datasets, what it is and their own id for it.
// The id is opaque: "123" from two publishers, or from a publisher and the ministry, are three different stops.
// Written publisher/dataset/kind/localId, the local id escaped so it can hold any character.
data class Ref(val publisher: String, val dataset: String, val kind: Kind, val localId: String) {
    init {
        require(NAME.matches(publisher)) { "bad publisher: $publisher" }
        require(NAME.matches(dataset)) { "bad dataset: $dataset" }
        require(localId.isNotEmpty() && localId.length <= MAX_LOCAL && localId.none { it.isISOControl() }) { "bad local id" }
    }

    val namespace get() = Namespace(publisher, dataset)

    override fun toString() = "$publisher/$dataset/${kind.key}/${escape(localId)}"

    companion object {
        private val NAME = Regex("[a-z0-9][a-z0-9._-]{0,127}")
        private const val MAX_LOCAL = 256

        fun parse(text: String): Ref? {
            val parts = text.split('/', limit = 4)
            if (parts.size != 4) return null
            val kind = Kind.of(parts[2]) ?: return null
            val local = unescape(parts[3]) ?: return null
            return runCatching { Ref(parts[0], parts[1], kind, local) }.getOrNull()
        }

        private fun escape(s: String) = buildString {
            for (c in s) when (c) {
                '%', '/', '#' -> append('%').append(String.format(java.util.Locale.US, "%02X", c.code))
                else -> append(c)
            }
        }

        private fun unescape(s: String): String? {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '/' || c == '#') return null
                if (c != '%') { out.append(c); i++; continue }
                if (i + 3 > s.length) return null
                // Only what escape() writes, so each local id has one spelling.
                val c2 = s.substring(i + 1, i + 3).takeIf { it == it.uppercase() }?.toIntOrNull(16)?.toChar() ?: return null
                if (c2 !in "%/#") return null
                out.append(c2); i += 3
            }
            return out.toString()
        }
    }
}

// One publisher's dataset: where a planner's or a board's bare ids belong.
data class Namespace(val publisher: String, val dataset: String) {
    val key get() = "$publisher/$dataset"
    val ministry get() = this == MINISTRY

    fun ref(kind: Kind, localId: String) = Ref(publisher, dataset, kind, localId)

    companion object {
        // The Ministry of Transport's GTFS, which Kav's own server plans and answers on.
        val MINISTRY = Namespace("il.mot", "gtfs")

        fun of(key: String): Namespace? {
            val parts = key.split('/')
            if (parts.size != 2) return null
            return runCatching { Ref(parts[0], parts[1], Kind.STOP, "x").namespace }.getOrNull()
        }
    }
}

// Who gave an answer, filled in by Kav from the contribution it asked, never by the extension.
data class Provenance(val namespace: Namespace, val contribution: String, val revision: String)

// What a provider answered. Empty is a valid answer with nothing in it; a malformed answer is not an empty one.
sealed class Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>()
    object Empty : Outcome<Nothing>()
    data class Unsupported(val reason: String) : Outcome<Nothing>()
    data class Unavailable(val retryAfterS: Int? = null) : Outcome<Nothing>()
    data class Failed(val code: String) : Outcome<Nothing>()
    data class Malformed(val reason: String) : Outcome<Nothing>()
}

// An answer with where it came from.
class Answer<out T>(val outcome: Outcome<T>, val provenance: Provenance)

// A contract by name and version. Same major means compatible; a minor adds only optional fields.
data class ContractVersion(val name: String, val major: Int, val minor: Int) {
    override fun toString() = "$name-$major.$minor"

    companion object {
        // The version both sides speak: the highest major they share, at the lower of their minors. Null when none.
        fun negotiate(host: List<ContractVersion>, offered: List<ContractVersion>): ContractVersion? =
            host.flatMap { h -> offered.filter { it.name == h.name && it.major == h.major }.map { o -> h.copy(minor = minOf(h.minor, o.minor)) } }
                .maxWithOrNull(compareBy({ it.major }, { it.minor }))
    }
}

// What a contribution may do, and in which domain it may do it.
object Capabilities {
    const val PLAN = "plan.provide"
    const val BOARD = "board.provide"

    // Every capability this Kav runs, each by the extension's own code. A capability not here is ignored, not refused.
    val KNOWN: Set<String> get() = setOf(PLAN, BOARD, StopPhotoHook.CAPABILITY, TripAccess.HISTORY, TripAccess.FOLLOW, Widgets.CAPABILITY, PageSpecs.PAGE, PageSpecs.HOME, Looks.CAPABILITY, MethodHooks.CAPABILITY) + Pipeline.HOOKS +
        Slots.CAPABILITIES + Actions.CAPABILITIES + Parts.CAPABILITIES

    fun known(capability: String) = capability in KNOWN
}

// A grant the rider gave one contribution. Revoking it raises the generation, so an answer that started under the old
// one is thrown away when it comes back.
data class Grant(val capability: String, val generation: Long)

// Which contribution Kav is asking, bound by Kav for the call: the extension cannot name another.
data class Execution(val namespace: Namespace, val contribution: String, val generation: Long)
