---
title: "The words a rider meets"
editLink: false
---

<div v-pre>

# The words a rider meets

When you tell riders how to use your extension, use Kav's own names for things. These are the exact words, in English
and Hebrew, taken from Kav 5's code (`ui/ExtensionsScreen.kt`, `ui/ExtensionParts.kt`, `ui/ExtSlots.kt`,
`ui/ExtPages.kt`, `ui/ExtHiddenRoutes.kt`, `ui/DirectionsOnline.kt`). **If the app and this page ever differ, the app
is right**: wording changes between versions, so quote a switch's name, and do not build a manual on a whole
sentence.

## Where things are

A rider reaches everything by one path: **Settings**, then **Extensions**. That screen lists each source with its
extensions, each with its own switch. A tap on an extension opens its page.

| English | Hebrew | Where, or when |
|---|---|---|
| Extensions | תוספים | the entry in Kav's Settings, and the screen's title |
| Add a source | הוספת מקור | the plus at the top, and the sheet it opens: "A GitHub repository whose releases carry a Kav bundle." |
| Sources | מקורות | a row on the Extensions screen: every source, with Check now and Add a bundle file |
| Check now | בדיקה עכשיו | asks GitHub for every source's latest release now |
| Add a bundle file | הוספת קובץ חבילה | adds a `.kavb` from the phone as a source of its own |
| Options | אפשרויות | on the Extensions screen: the security block list, safe mode, About extensions |
| Security block list | רשימת חסימה אבטחתית | the switch there, on by default |
| Safe mode | מצב בטוח | the switch there: Kav starts with every extension off |
| Its extensions come in off: you switch on the ones you want. | התוספים שלו נוספים כבויים: אתם מפעילים את אלה שאתם רוצים. | on the sheet that adds a source |
| What it does for you | מה הוא עושה בשבילכם | section of an extension's page: a planner and route rules, one switch each |
| Its screens and look | המסכים והמראה שלו | section: its pages, tab, Home screen and theme |
| Its settings | ההגדרות שלו | section: the settings your manifest declares, filled in right there |
| It can | התוסף יכול | section: what it is and does (the lines below); a card's row ends in its switch |
| Log | יומן | a row that opens its log: its `Kav.log` lines and its failures |
| Report an issue | דיווח על בעיה | on an extension's page and a source's page |
| Remove | הסרה | on a source's page: removes the source, its extensions and their settings |

## The switches

| English | Hebrew | For |
|---|---|---|
| Plan my trips with it | תכנון הנסיעות שלי איתו | `plan.provide` |
| Time my walks with it | חישוב זמני ההליכה שלי איתו | `walk.time`. Starts as Required |
| Judge my transfers with it | בדיקת ההחלפות שלי איתו | `transfer.policy`. Starts as Required |
| Filter my routes with it | סינון המסלולים שלי איתו | `results.filter`. Starts optional |
| Order Recommended with it | סידור "מומלץ" איתו | `results.rank` |
| Show its tags on routes | הצגת התגיות שלו על מסלולים | `results.annotate` |
| Required | חובה | under a walking model, a transfer rule or a filter that is on: "Without it, a search shows no routes" |
| Use it as my Home screen | שימוש בו כמסך הבית שלי | `home.body`, with "Kav's Home shows if it fails" |
| Use its look | שימוש במראה שלו | `theme`, with "Settings keep Kav's own look" |

A page marked `"tab": true` gets a switch named after the page: "Show &lt;page title> as a tab" ("הצגת &lt;page title>
כלשונית"). Every page that opens on its own gets a row "Open &lt;page title>" ("פתיחת &lt;page title>"). A stop board
(`board.provide`) has no switch of its own: its stops are there whenever the extension is on.

A card shows once the extension is on. Its switch is at the end of its row under "It can" (the row "Can add a card to
your Home screen", say), for the rider who wants it off.

## The lines under "It can"

These are the lines of [what-riders-see.md](/docs/extensions/what-riders-see), in Kav's words. Where a line ends with a colon, Kav
adds the permissions or what is changed.

| English | Hebrew | Line |
|---|---|---|
| Trip planner | מתכנן מסלולים | `plan.provide` |
| Provides timetables and stop boards | מספק לוחות זמנים ולוחות תחנה | `board.provide` |
| Walking model | מודל הליכה | `walk.time` |
| Transfer rule | כלל החלפות | `transfer.policy` |
| Route filter | מסנן מסלולים | `results.filter` |
| Ranker | מדרג מסלולים | `results.rank` |
| Route tags | תגיות למסלולים | `results.annotate` |
| Adds its own screens | מוסיף מסכים משלו | key `page.provide` |
| Adds a tab you can pin | מוסיף לשונית שאפשר להצמיד | key `tab` |
| Can replace your Home screen | יכול להחליף את מסך הבית שלכם | key `home.body` |
| Changes how Kav looks | משנה את המראה של Kav | key `theme` |
| Draws on Kav's maps | מצייר על המפות של Kav | `adds`: `map` |
| Sends notifications | שולח התראות | `adds`: `notifications` |
| Uses your location | משתמש במיקום שלכם | `adds`: `location` |
| Has settings of its own | יש לו הגדרות משלו | `adds`: `settings` |
| Can add a card to your Home screen | יכול להוסיף כרטיס למסך הבית שלכם | key `slot.home` |
| Can add a line to your trip results | יכול להוסיף שורה לתוצאות החיפוש שלכם | key `slot.results` |
| Can add a part to a trip's page | יכול להוסיף חלק לעמוד של נסיעה | key `slot.trip` |
| Can add a row to stop boards | יכול להוסיף שורה ללוחות תחנה | key `slot.stop` |
| Sees your trip searches | רואה את חיפושי המסלולים שלכם | key `searches` |
| Changes your results: | משנה את התוצאות שלכם: | key `results`, followed by what changes |
| Keeps its own data on your phone | שומר מידע משלו בטלפון שלכם | key `data` |
| Runs its own Android code: full access | מריץ קוד Android משלו: גישה מלאה | key `native` |
| Asks for: | מבקש הרשאות: | key `permissions` |

After "Changes your results:" Kav lists what changes: "walk times" (זמני הליכה), "which changes you can make" (אילו
החלפות אפשר לעשות), "which routes show" (אילו מסלולים מוצגים), "their order" (הסדר שלהם), "tags on routes" (תגיות על
מסלולים).

Kav also has shorter forms of some lines: "Its own screens" (מסכים משלו), "A tab you can pin" (לשונית שאפשר להצמיד),
"Home card" (כרטיס במסך הבית), "Line on trip results" (שורה בתוצאות החיפוש), "Part on a trip's page" (חלק בעמוד של
נסיעה), "Row on stop boards" (שורה בלוחות תחנה), "Timetables and stop boards" (לוחות זמנים ולוחות תחנה), "Keeps data
on this phone" (שומר מידע בטלפון הזה).

Beside the lines: "Third-party. Not made or reviewed by Kav." ("צד שלישי. Kav לא יצרה ולא בחנה אותו."), or "Made by
Kav." ("נוצר על ידי Kav.") for Kav's own source. Your reason follows a line after "From the publisher:" ("מהמפרסם:").

A card or page of yours on Kav's screens shows under "by &lt;its name>" ("מאת &lt;its name>").

## When something of yours hides routes or does not run

| English | Hebrew | Where, or when |
|---|---|---|
| &lt;n> routes hidden by &lt;name> | &lt;n> מסלולים הוסתרו על ידי &lt;name> | under a search's routes, with a "Show" (הצגה) button; "1 route hidden by &lt;name>" for one |
| Showing &lt;n> hidden routes | מוצגים גם &lt;n> מסלולים שהוסתרו | after the rider tapped Show |
| Shown without &lt;name>: it didn't answer in time. | המסלולים מוצגים בלי &lt;name>, כי הוא לא ענה בזמן. | under a search, when an optional rule was skipped |
| Shown without &lt;name>: Kav couldn't ask it about these routes. | המסלולים מוצגים בלי &lt;name>, כי Kav לא הצליחה לשאול אותו על המסלולים האלה. | under a search, when what Kav would send an optional rule is more than one call carries |
| Can't use your walking rules (&lt;name>) right now, so no routes are shown. | אי אפשר להשתמש כרגע בכללי ההליכה שלכם (&lt;name>), ולכן לא מוצגים מסלולים. | a required rule failed; also "your transfer rules", "your route filter". With "Try again" and "Show routes without this check" |
| &lt;name> couldn't plan this trip. | &lt;name> לא הצליח לתכנן את הנסיעה הזו. | the planner failed; Kav's own planner isn't asked instead |
| &lt;name> answered something Kav can't read as routes. | &lt;name> החזיר תשובה ש-Kav לא יכולה לקרוא כמסלולים. | the planner's answer was refused |
| Kav turned it off | Kav כיבתה אותו | on the extension's page, with "It made Kav close twice" and a "Turn on" button |
| It made Kav close | הוא גרם ל-Kav להיסגר | on the Extensions screen, under that extension |

Under "&lt;n> routes hidden by &lt;name>" Kav puts the reasons your filter or transfer rule gave, in a small line, as you
wrote them. With two extensions it names both ("by &lt;one> and &lt;other>"); with more, "by &lt;n> extensions" ("על ידי &lt;n>
תוספים").

A card whose code fails shows nothing at all, not a message.

</div>
