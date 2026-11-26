package uk.noammm.kav.ui

// Settings' and setup's short choices: Kav's own settings, or Custom, which opens the rows behind it. Kept apart from
// the screens so what they show can be checked without them.
internal object Presets {
    // The language Kav, stop names and the map's names are all in, or null when they differ: Custom.
    fun language(kav: Lang, stopsEnglish: Boolean, map: Lang): Lang? =
        kav.takeIf { stopsEnglish == (it == Lang.EN) && map == it }

    // Kav's languages as a choice lists them: the one Kav is in first.
    fun languagesFrom(current: Lang): List<Lang> = listOf(current) + Lang.entries.filter { it != current }

    // Whether Custom is shown: as picked on this screen, or, until it is, whenever the settings aren't Kav's own.
    fun custom(picked: Boolean?, kavs: Boolean): Boolean = picked ?: !kavs
}
