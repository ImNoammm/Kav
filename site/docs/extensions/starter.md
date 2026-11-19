---
title: "The Kotlin starter"
editLink: false
---

<div v-pre>

# The Kotlin starter

One extension in one Kotlin file, to copy and rename. It comes with the developer kit, in `starter-kotlin/`.

A card on Kav's Home with a button and a dialog, and a screen of its own, in one Kotlin file. Copy this folder,
change the names, and build it:

```
kavx check <this folder>       # builds it and prints what Kav and riders would see
kavx bundle <this folder>      # writes <folder name>.kavb
```

That compiles `src/` against Kav's API, makes the dex and writes a bundle with this one extension in it. You need a
JDK, the Android SDK and the Kotlin compiler; the kit's `docs/native.md` says where to get them, and explains every
method used here.

- `manifest.json`: what Kav and riders are told. Change `name`, `displayName`, `description`, and `native.entry` if
  you rename the class or its package. A card needs its `slot.home` contribution, a screen its page under `pages`.
- `src/org/example/hello/Hello.kt`: the code. `start` registers the card and the screen; Kav asks each for a view
  when its screen shows.

To try it: copy the `.kavb` to a phone or emulator with Kav 5 or newer, open Settings › Extensions › Sources › Add a
bundle file, add it, and switch Hello Kotlin on. The card is on Home, and the screen opens from the extension's page
in Settings › Extensions.


## The files

### `manifest.json`

```json
{
  "format": 1,
  "name": "hello-kotlin",
  "displayName": "Hello Kotlin",
  "version": "1.0.0",
  "release": 1,
  "minKav": 45,
  "api": 2,
  "description": "A card on Kav's Home and a screen of its own, written in Kotlin.",
  "native": { "dex": "classes.dex", "entry": "org.example.hello.Hello" },
  "contributions": [
    { "id": "card", "capability": "slot.home" },
    { "id": "screens", "capability": "page.provide" }
  ],
  "pages": [
    { "id": "about", "title": { "en": "Hello Kotlin", "he": "שלום קוטלין" } }
  ]
}
```

### `src/org/example/hello/Hello.kt`

```kotlin
package org.example.hello

import android.content.Context
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kav.kavapp.api.Kav
import kav.kavapp.api.KavExtension

// Kav makes this class (the manifest's "native" entry) and calls start once each time Kav starts, on a background
// thread. Everything the extension adds to Kav is registered here.
class Hello : KavExtension {
    override fun start(kav: Kav) {
        // The Home card: the manifest declares it as the contribution "slot.home". Kav asks for a view each time Home
        // shows it, on the main thread.
        kav.card("home") { context, _ -> card(kav, context) }
        // The screen for the page "about": Settings › Extensions › Hello Kotlin › About opens it.
        kav.screen("about") { context, _ -> about(kav, context) }
        // A line in its log, on its page in Settings › Extensions.
        kav.log("Hello Kotlin started, API ${kav.apiVersion()}, language ${kav.locale()}")
    }

    private var taps = 0

    // Kav's own card, button and dialog, so it looks like the rest of Kav in light, dark and Hebrew.
    private fun card(kav: Kav, c: Context): View {
        val ui = kav.ui()
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(text(kav, c, if (kav.locale() == "he") "שלום מקוטלין" else "Hello from Kotlin", "headline"))
        box.addView(ui.button(c, if (kav.locale() == "he") "לחצו כאן" else "Tap me", true) {
            taps++
            kav.log("tapped $taps times")
            ui.dialog(c, "Hello Kotlin", "You tapped $taps time${if (taps == 1) "" else "s"}.", "OK") {}
        })
        return ui.card(c, box)
    }

    private fun about(kav: Kav, c: Context): View {
        val ui = kav.ui()
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.header(c, "About"))
            addView(ui.row(c, "info", "Hello Kotlin", "A starter extension for Kav", null))
            addView(ui.row(c, "check", "Your extension's id", kav.id(), null))
        }
    }

    // A text in Kav's colours and type sizes, for a view the extension draws itself.
    private fun text(kav: Kav, c: Context, s: String, style: String) = TextView(c).apply {
        text = s
        setTextColor(kav.ui().color("text"))
        textSize = kav.ui().textSize(style)
        setPadding(0, 0, 0, (kav.ui().dp("gap3") * c.resources.displayMetrics.density).toInt())
    }
}
```

</div>
