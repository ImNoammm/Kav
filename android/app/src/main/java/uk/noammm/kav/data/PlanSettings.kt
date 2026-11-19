package uk.noammm.kav.data

// The rider's planner settings (Settings, Trips): how fast they walk, how much walking they'll take, whether to change
// less, and how much time to leave at the stop. Kav's server, and a rider's own, plans with them (server/live.py
// asked_as()); the usual sends nothing, so its routes are those planned before there were settings.
data class PlanSettings(
    val pace: Pace = Pace.USUAL,
    val walking: Walking = Walking.USUAL,
    val changes: Changes = Changes.USUAL,
    val spare: Spare = Spare.USUAL,
) {
    enum class Pace(val letter: Char) { SLOW('s'), USUAL('n'), FAST('f') }
    enum class Walking(val letter: Char) { LESS('l'), USUAL('n'), MORE('m') }
    enum class Changes(val letter: Char) { USUAL('n'), FEWER('f') }
    enum class Spare(val letter: Char) { TIGHT('t'), USUAL('n'), RELAXED('r') }

    // One letter a setting, in the server's order: how they're kept and sent.
    val letters: String get() = "${pace.letter}${walking.letter}${changes.letter}${spare.letter}"

    // The plan path's last part: nothing for the usual.
    fun path(): String = if (this == USUAL) "" else "/as/$letters"

    companion object {
        val USUAL = PlanSettings()

        // Letters kept or restored: one it doesn't know, or one missing, is the usual.
        fun of(letters: String?): PlanSettings {
            fun <E : Enum<E>> pick(all: Array<E>, i: Int, letter: (E) -> Char, usual: E) =
                all.firstOrNull { letters != null && i < letters.length && letter(it) == letters[i] } ?: usual
            return PlanSettings(
                pick(Pace.entries.toTypedArray(), 0, Pace::letter, Pace.USUAL),
                pick(Walking.entries.toTypedArray(), 1, Walking::letter, Walking.USUAL),
                pick(Changes.entries.toTypedArray(), 2, Changes::letter, Changes.USUAL),
                pick(Spare.entries.toTypedArray(), 3, Spare::letter, Spare.USUAL),
            )
        }
    }
}
