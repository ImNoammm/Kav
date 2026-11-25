---
title: "Testing"
editLink: false
---

<div v-pre>

# Testing

An extension is Android code, so it runs only inside Kav. Test it in three steps.

## 1. Check it

```
kavx check <folder>
```

This builds the folder and checks it the way Kav does: the manifest, the dex, the icon, the pages, settings and
theme. It prints what riders will see and warns about what Kav accepts but you probably did not mean (an unknown
manifest field, a reason that is never shown, a theme color that is hard to read). `REFUSED:` means Kav would
refuse it, and says why.

## 2. Unit-test your hook code

Your hooks take JSON text and answer JSON text ([hooks.md](/docs/extensions/hooks)), so test them on your computer like any other
Kotlin or Java code: build an input shaped as hooks.md describes, call your hook, and check the answer. Keep the code
that works out the answer in a plain function with no Android types, and the test needs nothing from Kav.

```kotlin
// In your extension:
fun hideLongWalks(input: String): String {
    val routes = JSONObject(input).getJSONArray("routes")
    return JSONArray().apply {
        for (i in 0 until routes.length()) {
            val legs = routes.getJSONObject(i).getJSONArray("legs")
            val walked = (0 until legs.length()).map { legs.getJSONObject(it) }.filter { it.getString("kind") == "walk" }.sumOf { it.getInt("metres") }
            put(if (walked > 1500) "Over 1.5 km on foot" else true)
        }
    }.toString()
}

// In a test, with org.json on the classpath:
val search = """{"query": {"way": "d", "at": 1800000000, "now": 1800000000, "settings": "nnnn", "locale": "en"},
  "routes": [{"index": 0, "legs": [{"kind": "walk", "metres": 2000}]}, {"index": 1, "legs": [{"kind": "walk", "metres": 300}]}],
  "walks": [], "transfers": []}"""
check(hideLongWalks(search) == """["Over 1.5 km on foot",true]""")
```

`org.json` is part of Android, not of the JVM: add a copy of it (the `org.json:json` artifact) to your test's
classpath only. Your extension uses Android's own.

Mind the deadlines when you test: 200 ms for a walking model, 150 ms for the other rules, 400 ms for all of them
together, on a phone slower than your computer.

## 3. Try it in Kav

On a phone or an emulator with Kav 5 or newer:

1. `kavx bundle <folder>` writes a `.kavb`.
2. Copy it to the device: `adb push my-extension.kavb /sdcard/Download/`.
3. In Kav: Settings › Extensions › Sources › + › Add a bundle file, and pick it. Add.
4. Switch the extension on. It runs at once.
5. Watch `adb logcat -s KavNative KavNativeGuard KavExtHooks KavExt`, and the "Log" row on its page
   ([native.md](/docs/extensions/native), "Seeing logs").

A bundle file never updates. For the next build, remove the old source (its page, Remove) and add the new file.

To try the path your riders take, publish a GitHub Release with the `.kavb` on it and add the repository in Kav
(type `owner/repo`, or open the link `kavx link github owner/repo` prints). Then Check now picks up each new release.
`kavx verify <file.kavb>` reads a bundle as Kav will before you publish it.

</div>
