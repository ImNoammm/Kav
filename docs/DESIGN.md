# Kav design rules

How Kav looks, reads and moves. Every screen of Kav is held to these rules, and so is every screen an extension
builds from Kav's parts.

Each rule has three parts: the rule, **the test** (someone holding a screenshot or a screen recording can say pass or
fail), and **in code** (the component, token or pattern that gives it, and what not to build by hand). Names in
`code` are in `android/app/src/main/java/uk/noammm/kav/ui/` unless a path is given.

Before you build: read the sections for what you are touching. Before you report: run the
[checklist](#16-the-checklist) over every screen you touched, in English and in Hebrew.

## Contents

1. [The principles](#1-the-principles)
2. [Text](#2-text)
3. [Titles and headers](#3-titles-and-headers)
4. [Motion](#4-motion)
5. [Loading and empty states](#5-loading-and-empty-states)
6. [Back and getting around](#6-back-and-getting-around)
7. [Spacing and alignment](#7-spacing-and-alignment)
8. [Corners, icons and color](#8-corners-icons-and-color)
9. [One of each](#9-one-of-each)
10. [Settings](#10-settings)
11. [Hebrew and right-to-left](#11-hebrew-and-right-to-left)
12. [The map](#12-the-map)
13. [Sheets, popups and messages](#13-sheets-popups-and-messages)
14. [Other services and extensions](#14-other-services-and-extensions)
15. [When two rules pull against each other](#15-when-two-rules-pull-against-each-other)
16. [The checklist](#16-the-checklist)

## 1. The principles

Every rule below comes from one of these. When a case isn't covered, decide by them.

1. **Every word earns its place.** A text is on the screen because it tells the rider something the screen doesn't
   already say. Anything else is noise, however true it is.
2. **Every change is one movement.** Nothing appears, disappears, moves or changes size without Kav's motion, and
   each change is a single move from start to end.
3. **The rider never watches Kav work.** What is known shows at once; the rest is fetched behind it. Waiting, when it
   can't be avoided, is a quiet pulse, never a sentence about loading.
4. **Back retraces the rider's steps.** One step at a time, to the place they came from, as they left it.
5. **One of each.** One component for each job, one token for each size, corner and color. Two things that do the same
   job look the same on every screen.
6. **Hebrew is a first language.** The layout, the icons and the words are made for right-to-left, not flipped or
   translated at the end.
7. **What was removed stays removed.** A redesign or a merge never brings back something that was taken out, and never
   drops something that was asked for.

## 2. Text

### 2.1 Nothing is said twice

A title that repeats its container, its only child or its neighbor is one title too many. So is any text that repeats
what another text, an icon or a control on the same screen already says.

**The test.** Cover the text with a finger. If the screen still tells the rider the same thing, the text fails.

Fails:

- A section header directly above another header, with nothing between them.
- A section header above a single row or card whose own title names the same thing ("Photos" over the one switch
  "Photos of stops and places").
- A page whose first section header repeats the page's title (a page titled Paying that opens with a "Paying" header).
- A sheet, popup or card whose first line of content repeats its own title.
- A group header over children that each have a header of their own.
- A subtitle that restates its title in more words, or says what the control's state already shows.
- The same fact in two places on one screen: the same list under two headings, the same name on two cards, the same
  line of a trip twice.
- A label that only says whose something is or what kind of thing it is, when the place it sits already says so (a
  "by" label on an extension's own card, the word "source" on the Sources page, a count nobody needs).

Right: on Privacy and sharing, "Link expires after" sits alone over its control, with the explanation behind its (i).

**In code.** A switch that stands alone is its own title: put it in a `ListGroup` with no header, and give its
explanation to the row (`ToggleRow(info = …)`, `ListRow(info = …)`). Use a header (`SectionHeader`, `InfoHeader`) only
as section 3.1 allows.

### 2.2 As short as it can be

Say it in the fewest words that are still clear. Never a paragraph on a screen.

**The test.** Count. These are limits, not targets:

| Text | Limit |
|---|---|
| A row's title, a button, a chip, a tab | Fits on one line in English and in Hebrew on a 360 dp wide screen. Four words or fewer. |
| A row's subtitle | One line, about 45 characters. |
| A section header | Three words or fewer. |
| A note that is always visible (`NoteCard`) | One sentence, about 80 characters, and only for something the rider must know right now (see 2.4). |
| An explanation behind an (i) | Two sentences, about 140 characters. |
| An (i) that says what is sent to whom | Three sentences, about 220 characters. |
| A sheet or a popup | A title and at most three lines of one sentence each. |
| An empty state, an error | One sentence, about 60 characters, and one action. |

Whatever is longer than its limit is cut, not moved: if the rider needs the long version, it lives on Kav's site and
the screen has a "Full details" link to it (as the ticket setup does). Text a license requires in full (map credits)
is on a sheet of its own, behind one row.

How to cut: drop how it works inside, drop the history, drop numbers the rider can't act on, drop what the control
already shows, drop "Kav" as the subject when it is obvious. Keep what changes the rider's choice.

### 2.3 Explanations wait behind an (i)

Anything that explains (what a setting does, why something is asked, where data goes) is behind a small (i), closed
until the rider taps it. Open, it is a quiet card, never loose gray text on the page.

**The test.** Open the screen fresh. Any sentence visible that explains instead of naming or stating is a fail,
unless 2.4 allows it. Any explanation drawn as bare text outside a card is a fail.

**In code.** `InfoHeader(title, info)` for a section; `ListRow(info = …)` / `ToggleRow(info = …)` for a row that stands
alone. Both use the same `InfoButton` and open the same `InfoNote`. Never draw your own (i), and never put an
explanation in a `ListGroup` footer, a row subtitle or a bare `Text`. Each (i) is closed every time its page opens.

### 2.4 What may stay visible

Text that is not behind an (i) must be one of:

- a name: a title, a label, a value;
- a state: what something is set to, a live status ("Connected · 5,214 buses live"), a size, a time;
- something the rider must know before acting and couldn't guess: a cost ("Up to 6 MB a day"), a loss that can't be
  undone ("Importing replaces what is here"), a thing that needs them now ("Action required").

A switch never explains what off means, and never explains what on means when its title says it. No "Off: …" lines.

**The test.** For each visible sentence, name which of the three it is. If you can't, it goes behind the (i) or out.

### 2.5 What the rider needs to decide is kept

Short does not mean silent. Where a choice sends something to someone (a search, a photo, a planner, a share link),
the (i) says, in this order and nothing more: what is sent, to whom, and when. Not how the server stores it, not the
protocol, not the schedule of retries.

**The test.** Read the (i) of every choice on a privacy or source screen. It names the data and the receiver. It
fits the limit in 2.2.

### 2.6 Nothing is cut off

No text ends in "…" and no text is clipped by its box, in either language, at the default font size and one size up.

**The test.** Look for an ellipsis or a letter cut by an edge. Either is a fail. So is a segmented control whose
labels were shrunk until they are hard to read: shorten the word.

**In code.** Titles are written to fit (2.2); descriptions wrap (`subtitleMaxLines = Int.MAX_VALUE` on a `ListRow`, or
open on a tap). Text from outside Kav (an extension's description, a stop's name, an alert) always wraps or opens in
full: it is never held to one line. `maxLines = 1` with an ellipsis is only a safety net for text Kav wrote.

### 2.7 Wording

- Plain words a rider uses. No names of code, servers' insides, keys or file formats, unless the screen is for people
  who run their own server.
- Short labels: "Log", not "Its log". No "its", "your" or "my" where the place already says whose.
- No quote marks around text that came from elsewhere, and no label saying it did, when its place shows it.
- A label names the thing or the action. A button says what it does ("Download"), not "OK".
- American English: color, favorite, center, gray, canceled.
- The same thing has the same name everywhere: one word for a stop, a line, a trip, a route.
- A status names the service that is really being used.
- Raw errors are never shown. An error says what went wrong in the rider's terms and what to do, in one sentence.
- Times and lengths are written one way: `hhmm()`, `dur()`, `durMinutes()`, `distanceLabel()`. "now" under a minute.
  A time today has no day beside it.

## 3. Titles and headers

### 3.1 One title per thing

Every screen, section, card, sheet and popup has at most one title, and each level of the screen has exactly one
style.

| Level | Style | Component |
|---|---|---|
| A tab's own name | `K.type.largeTitle` | `ScreenHeader(title)` |
| A pushed screen, a sheet | `K.type.title` | `ScreenHeader(title, back = …)`, `SheetHeader` |
| A card's title | `K.type.headline` | first line of a `KavCard` |
| A section over its card | `K.type.header` | `SectionHeader`, `InfoHeader` |
| A row | `K.type.body` | `ListRow` |

A section has a header only when:

- its card has two or more rows, and the header names what they share in words none of them uses; or
- it holds one control that has no words of its own (a segmented control, a slider, a picker, a preview).

A single switch or a single row that opens something has no header: the row is the title.

**The test.** For each header on the screenshot: is the next thing under it a second header (fail), or a single row
that says the same (fail)? Is any title style used for something that is not that level (fail)?

### 3.2 A title looks like a title, and sits with what it names

A title is told from the content under it by its style, never by size alone, and it is closer to its own card than to
the one above.

**The test.** Squint: the header is nearer the card below it (`K.gap2`) than the card above (`K.gap6`), and it starts
on the same line as the rows' text.

**In code.** `SectionHeader` and `InfoHeader` already do both. Don't build a header from `Text`, and don't wrap one in
extra padding.

### 3.3 A title names the thing shown

A page that shows one item names that item at its top, in its header or in its first card (the source's name, the
stop's name), never only the kind of thing it is. A title never flashes: it is right in the first frame, or the
screen waits for it.

## 4. Motion

### 4.1 Every visible change moves

If the rider can see it change, it changes with Kav's motion. Nothing snaps.

**The test.** Record the screen and step through the frames around each change. Any element that is absent in one
frame and fully there in the next, or at one place and then another, fails.

| What changes | In code |
|---|---|
| Something appears or leaves in place | `AnimatedVisibility` with `expandVertically(spring(1f, 500f)) + fadeIn(tween(200, delayMillis = 60))` and `shrinkVertically(spring(1f, 500f)) + fadeOut(tween(120))` |
| A box changes size | `Modifier.animateContentSize(spring(dampingRatio = 1f, stiffness = 500f))` |
| Words change in place | `ChangingText` |
| A number or a time steps | `RollingText`; a live time: `LiveText` |
| One content replaces another | `AnimatedContent` with a fade (`tween(220, delayMillis = 60)` in, `tween(150)` out) and a `SizeTransform` on the same spring |
| A row comes, goes or moves in a list | `Modifier.listMotion()` on keyed items (`uniqueKeys`); a first appearance in a column: `popIn` |
| Something moves within its parent | `Modifier.glide()` |
| A color changes | `animateColorAsState(…, tween(150 to 250))`; a switch's glyph: `toggleInk` |
| A page is pushed or popped | `forward()` / `backward()`; between chips: `sideways(next)` |
| A screen over the tabs | `modalIn()` / `modalOut()` |
| A sheet | `BottomSheet` |
| The look, the glass, the accent | `K.restyle { … }` |
| The language | `T.switchTo` (inside `LanguageSwitch`) |
| A press | the component's own (`KavButton` scales to .97) |

Use these and nothing else. `AnimatedVisibility` or `animateContentSize` with no spec (the library's default) is a
fail: it moves at a different speed from everything around it.

### 4.2 One movement

A change is a single move: one curve from start to end.

**The test.** In the recording, each change:

- runs once, without stopping, restarting or changing speed part of the way;
- shows no in-between state that is neither the start nor the end (a flash of another color, an old title, an empty
  box, the screen underneath);
- moves everything that changes together at the same time on the same curve, not one part after another;
- bends to a new goal if the goal changes while it runs, instead of starting again from rest;
- ends where it stays: nothing settles and then jumps.

**In code.** One animation spec per change, shared by its parts. Don't chain two animations for one action. Don't
ease a size in two nested places (an inner `animateContentSize` inside an outer one runs twice as slow as its
neighbors). A value that arrives while a move runs is fed to the same `Animatable`.

### 4.3 Nothing arrives late

What belongs to a screen is on it in the first frame of its opening. Icons come with their labels, a title with its
page, a card whole.

**The test.** Step through the frames after a screen opens. Anything that shows up after the opening move has ended,
and wasn't asked for by a tap, fails, unless it is data that can't be known yet and its place was already held (4.4).

**In code.** Keep icons as vectors or preloaded bitmaps, not fetched. Read what the screen needs before it is shown
(`MapWarmUp`, `warmStops`, `warmLines`). What truly comes later comes through `Arriving(already)` or the motions in
4.1, into room that was kept for it.

### 4.4 Changing one thing never pushes another

Choosing, loading and refreshing don't move what the rider didn't touch.

**The test.** Tap a chip, a filter, a segment; let data refresh. Nothing but the touched control and what it controls
changes place or size.

**In code.** Room is kept for what may appear: `KavChip(check = true)` always holds its tick's place; figures use
`TextStyle.tabular`; a value that comes later has its width or a placeholder of the final size; plates in a column
share `linePlateWidth`. An element that shows in several states (the live mark beside "now" and beside "3 min") sits
at the same spot in all of them.

### 4.5 Motion never costs a frame

No animation may drop frames or delay a tap. Interface motion is under 400 ms; only the map's camera and the loading
pulse run longer. See 12.1 for the map.

## 5. Loading and empty states

### 5.1 Show what is known, fetch behind it

Open with what the phone already has (the timetable on the phone, the last answer, the cache), then bring the fresh
values in with the motions of 4.1. Start asking before it is needed: when a screen that leads to it opens, not when
the rider taps.

**The test.** Open the screen with a slow connection. It shows real content at once. Fail: a screen that is blank or
shows a loading state when the phone already had something true to show.

### 5.2 Never a sentence about loading

No line of text whose job is to say that Kav is working ("Checking times…", "Preparing the link…", "Asking …").

In order of choice:

1. Nothing: the content is simply there (5.1).
2. The control's own state: `KavButton(busy = true)`, a `Spinner` on the tile that is loading, the meter that waves
   (`CrowdLevel`).
3. A whole screen or a whole card with nothing yet: `LoadingPulse` / `LoadingBlock` / `LoadingScreen`, centered,
   with one short label.

**The test.** Search the screen for words like loading, checking, asking, preparing, looking, with or without "…".
Outside a `LoadingPulse`, each is a fail. A loading state drawn as a gray box, or a spinner in the middle of a page,
is a fail.

### 5.3 Waiting always ends

Every loading state ends in content, in an empty state or in an error with a way to try again. None can stay forever.

### 5.4 Honest while it loads

A scheduled time is gray with the no-signal mark, a live one green with the signal mark (`K.realtime`). Never show a
guess as live, an old value as fresh, or a default (a white chip, a "0 min") that is then corrected. If it isn't known
yet, hold its place.

### 5.5 Empty and failed

One sentence saying what is missing, in the place where the content would be, and one action when there is one. Not
shown while the answer is still on its way (keep it null until known, as Home's nearby card does). A screen that is
empty because something is turned off shows the switch that turns it on.

## 6. Back and getting around

### 6.1 Back undoes the last step, and only that

The Back gesture and every back button go one step back, in this order: the keyboard, then a menu, popup or sheet,
then an edit mode or a selection, then an inner page, then the screen, then the tab, then leaving Kav.

**The test.** On every screen, sheet, popup and step of a flow, do Back and note where it lands:

- it never does nothing;
- it never closes two things at once;
- it lands on the screen that was showing before, with the same scroll, tab and choices (never on a tab's first page,
  never on another flow, never out of Kav while something of Kav's is open);
- the header's back button and the gesture land on the same place;
- what closes, closes with the reverse of how it opened.

### 6.2 Where you came from is where you return

A thing opened from somewhere (a stop from an alert, a line from a trip, Settings from a tab, a picker from a form)
returns there, not to the place it would normally live.

**In code.**

- Anything that covers the screen composes its own `androidx.activity.compose.BackHandler(enabled = isOpen) { close() }`
  inside itself, enabled only while it is open. The handler composed last wins, so the top thing answers first.
- Sheets use `BottomSheet` (it has its handler and closes with its move). Popups use `Popup`.
- A header's Back on a page that can have inner steps calls the back dispatcher
  (`LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher?.onBackPressed()`), as Settings does, so it
  can never differ from the gesture.
- Crossing tabs: `model.rememberReturn(tab)` before opening, so `backFromLine()` / `backFromStation()` return to the
  screen that asked.
- An error never navigates. It is shown where the rider is.
- Another app opened from Kav is started so that its Back returns to Kav.

### 6.3 A tap opens the thing tapped

One tap, one result, and it is the thing named: Street View opens Street View, a link opens the exact trip, a row
about a line opens that line. No screen in between that asks what the rider just said. Two different buttons never
open the same place.

### 6.4 Places keep their state; open things close

A tab left and returned to is where it was (its page, scroll and map). Things the rider unfolded on a page (an (i), an
expanded card) are closed the next time the page opens. The keyboard closes when its job is done.

## 7. Spacing and alignment

### 7.1 Spacing comes from the scale

Gaps are `K.gap1` to `K.gap8` (4, 8, 12, 16, 20, 24, 32 dp). A screen's side margin and a card's inner padding are
`K.gap4`. Sections are `K.gap6` apart; a header is `K.gap2` over its card; related controls are `K.gap2` or `K.gap3`
apart.

**The test.** In the code, a literal `N.dp` used as padding or a gap where a `K.gap` exists is a fail. On the
screenshot, two gaps that play the same role but differ are a fail.

### 7.2 Things line up

- Text starts on one line down the page: headers, rows' titles and card text share a start edge.
- Things of the same kind share a column: times end on one edge, plates have one width, marks sit in one place.
- What is centered is centered to the pixel between its neighbors, up and down and side to side.
- An icon and the text beside it share a center line.

**The test.** Lay a ruler along each edge and center line of the screenshot. Anything a pixel or two off its
neighbors fails.

### 7.3 Nothing floats, nothing crowds

Every control lives in a card or a group with what it belongs to; none sits bare on the page between cards. Nothing
touches the edge of its card or its neighbor. Items in a line of tags are separated by " · ". No band of empty page
taller than `K.gap8` between blocks.

### 7.4 Inside the safe area

Nothing is drawn under the status bar, the top bar, the tab bar, the system's bar or the keyboard, on any screen,
sheet, popup or picker, including ones an extension opens.

**In code.** `LocalBottomBarInset`, `bottomCover()`, `statusBarsPadding()`; `BottomSheet` and `Popup` already keep
inside. Touch targets are at least 48 dp (`KavIconButton`, `minimumInteractiveComponentSize()`).

## 8. Corners, icons and color

### 8.1 Corners

| Shape | Token |
|---|---|
| Cards, grouped lists, panels | `K.rCard` (22 dp) |
| Sheets | `K.rSheet` (28 dp) |
| Tiles, photos, pictures, notes, small blocks inside a card | `K.rTile` (12 dp) |
| Line plates | `K.rBadge` (8 dp) |
| Every control: buttons, chips, segmented controls, fields, icon buttons | `K.capsule` |

A picture of anything (an extension, a source, an app, a place, a stop) is a rounded square, clipped so no edge or
background shows at its corners. Circles are only for icon buttons, dots, the switch's thumb and the rider's mark.
A corner inside another is the smaller token.

**The test.** A `RoundedCornerShape(N.dp)` with a literal where a token fits is a fail. Two controls of one kind with
different corners on one screen is a fail.

**In code.** `Modifier.panel()` for a card, `KavCard`, `ListGroup`, `Modifier.groupItem()` for lazy lists.

### 8.2 Icons

- One set: Material's Rounded icons (`Icons.Rounded`, `Icons.AutoMirrored.Rounded`), and Kav's own drawn marks
  (`ModeGlyph`, `WalkGlyph`, `AgencyMark`, `TabGlyph`).
- Sizes: 18 dp in a chip or beside text, 20 dp at a row's end, 24 dp in an icon button or at a row's start.
- An action everyone knows (back, close, share, settings, add, refresh, report, Street View, reset) is an icon button
  with no words: `KavIconButton(icon, label, …)`, the label spoken, not shown. Never a worded button and an icon for
  the same action side by side.
- An action not everyone knows has words: `KavButton(label, icon = …)`.
- Another app or service is shown by its own real icon, never a redrawn one.
- One meaning, one icon, everywhere (the signal mark for live, the same mark struck through for scheduled).
- No icon as decoration: an icon either tells the rows apart or it goes.
- An icon never overlaps text or another icon, and comes with its label (4.3).
- Icons that point or face a way mirror in Hebrew (11.2).

### 8.3 Color

- Colors come from `K` only: `K.bg`, `K.surface`, `K.surfaceRaised`, `K.fill`, `K.separator`, `K.text`,
  `K.textSecondary`, `K.textTertiary`. A `Color(0x…)` in a screen is a fail. The only exceptions are operators' and
  modes' own colors (`ModePlate`) and what is drawn on a map (`MapInk`).
- `K.accent` is for what can be tapped and what is chosen, nothing else. Accent as text is `K.accentInk`.
- `K.realtime` (green) is for a time or place measured live, nothing else. Scheduled is `K.scheduled`. A warning is
  `K.problem`, an error or a destructive action `K.critical`.
- One state, one color, on every screen: a time that is green on a card is green on the board and on the map.
- A color is right from the first frame. Nothing shows in a default color and then turns.
- Surfaces: a card is `Modifier.panel()`; something floating over a map or a list is `Modifier.glassSurface()`
  (`seeThrough()` over the live map). Don't build a see-through background by hand. A surface never changes between
  glass and solid while it is on screen, and pressing it doesn't change how see-through it is.
- Every look is checked: dark, OLED and light. Kav's own screens (Settings, reports, navigation) keep Kav's look under
  a theme.

## 9. One of each

Each job has one component. Use it; don't build a second.

| Job | Component |
|---|---|
| Main, secondary, in-text, destructive button | `KavButton` with `ButtonStyle` (one `Primary` per screen or sheet) |
| Round icon button, Back | `KavIconButton`, `BackButton` |
| A choice among a few | `KavChip`; exclusive, two to five: `KavSegmented` |
| On or off | `KavSwitch` inside `ToggleRow` / `SwitchRow` |
| Rows that belong together | `ListGroup { row { ListRow(…) } }`; in a lazy list `Modifier.groupItem()` |
| A card | `KavCard` |
| A screen's, a sheet's, a section's title | `ScreenHeader`, `SheetHeader`, `SectionHeader` / `InfoHeader` |
| An explanation | `InfoHeader`, `ListRow(info = …)` |
| A note that must be seen | `NoteCard` |
| A text field | `KavField` |
| A sheet, a popup | `BottomSheet`, `Popup` |
| A line's number | `LineBadge` / `LinePlateBadge` |
| A stop's or a place's picture | `StopGlyphOrPhoto`, `PlaceGlyphOrPhoto` |
| Loading | `LoadingPulse`, `LoadingBlock`, `LoadingScreen`, `Spinner`, `DownloadBar` |
| A live time | `LiveText` |

**Same thing, same look.** A departure, a line, a stop, a time, a search field or a result is drawn by the same code
wherever it shows, so it can't differ between the card and the page, between Stops and Lines, between one planner and
another.

**The test.** Put two screens that show the same kind of thing side by side. Any difference in size, color, corner,
order or wording that the rider can see, and that carries no meaning, fails.

**Riders know other transit apps.** Where Kav shows the same thing they do (results, departures, a trip), it keeps
the order and the amount they are used to. A row or a line they don't have needs a reason.

## 10. Settings

### 10.1 The first page

Each row is a part of Kav: its name, and under it what it is set to now (a state, not a description). It opens its
own page. Order: what a rider changes most first (trips, paying, sources, look, map, privacy), then extensions and
Kav itself. Something that needs the rider now comes before everything (an update that is waiting; "Action required"
in `K.problem`).

A part with fewer than three controls doesn't get a row of its own: it joins the part it is closest to.

### 10.2 A page

- Sections follow 3.1. Most-used first.
- The control fits the choice: on or off is a `ToggleRow`; two to five exclusive options a `KavSegmented`; options
  that need a line each are radio rows in one `ListGroup`; more than that is a row that opens a sheet.
- In every list of options the default comes first, and the same options keep the same order on every page.
- A setting that depends on another sits right under it and shows only while the other is on, opening with 4.1.
- A setting that can't apply right now is hidden, not shown disabled. One that is disabled says why beside it.
- A preview is on screen together with the options it previews, right next to them, and changes with them,
  animated. A preview of one control sits directly under that control.
- A change takes effect at once. A Save button only where something must be tried first (a server, a proxy).
- Text follows section 2: no "Off: …", no paragraph, explanations behind the (i).

**The test.** For each page: any header that fails 3.1; any switch whose subtitle describes the switch; any option
shown that can't be used; any preview that scrolls away from its options; any explanation visible without a tap.

## 11. Hebrew and right-to-left

### 11.1 Layout

Use start and end, never left and right: `padding(start = …)`, `Alignment.Start`, `Arrangement.Start`. A row's order
flips by itself; don't flip it by hand. In a list of options the first is at the start in both languages.

Drawn by hand on a `Canvas`: mirror with `mirrorX()` or the `layoutDirection`, as `Modifier.groupItem` and
`LoadingPulse` do. A move that goes "forward" goes toward the end (`sideways()` already does).

**The test.** Switch Kav to Hebrew and compare with the English screenshot: it is the mirror image, except for the
map, photos, numbers, clocks and Latin names.

### 11.2 Icons

Anything that points, faces or moves in a direction is mirrored in Hebrew: arrows, chevrons, Back, a list's bullets,
an "open in" arrow, a walking or running figure, a vehicle seen from the side. Use `Icons.AutoMirrored.Rounded.*`
where Material has one; for a drawn mark, flip it with the layout direction. Clocks, checks, pluses, logos and the
map stay as they are.

### 11.3 Words

- Every text is written in both languages: `T("English", "עברית")`. A count: `T.count(…)`.
- Hebrew is written as Hebrew, not translated: everyday words, short, the way an Israeli would write it from scratch.
  Actions as a noun or an infinitive ("הורדה", "מחיקה"), the rider addressed in the plural ("אתם"). Kav takes
  feminine verbs ("Kav שומרת").
- English reads as English too, not as a translation of the Hebrew.
- A one-letter Hebrew prefix before a Latin word takes a hyphen: "ב-Kav", "מ-Google".
- Latin text, versions and addresses inside Hebrew are wrapped with `T.ltr()`; text from outside Kav with `T.iso()`,
  so neither can reorder the sentence.
- Hebrew abbreviations use the Hebrew marks: "דק׳", "ק״מ".
- The names of stops, the map's names and alerts each follow their own language setting, not Kav's.
- Check the Hebrew on a screenshot, not in the code: it is where text gets cut and where order goes wrong.

## 12. The map

### 12.1 Smooth first

The map never drops frames: not while moving, zooming, tilting, opening a line or a vehicle, or while vehicles
update. An animation that can't hold the frame rate is cut for what is far or off screen first, never dropped for
everything and never left to stutter.

**The test.** Record a pan, a pinch and a tap on a vehicle on a real phone. A visible hitch fails.

### 12.2 The camera makes one move, and only when asked

- One camera move per action, on one timeline: no move sideways and then a zoom, no speeding up and slowing down
  along the way. A new goal bends the move that is running.
- The camera moves only for something the rider did. Opening an (i), a card growing, data refreshing and a bus
  updating don't move the map.
- Closing what was opened returns the map to where the rider had it.
- The map never pulls back to the rider's location while they are looking elsewhere.
- Dragging on the map moves the map; only dragging a card moves the card.
- Pinch and fling feel like the maps app riders know: the zoom stays under the fingers and carries on after they
  lift.

### 12.3 What is on the map

- Marks fade in and out together as the zoom crosses their level. They never blink one by one, and old ones leave
  when new ones come.
- Marks stay upright and stay on their spot while the map turns, tilts and zooms.
- Vehicles glide between positions. They never jump, including when a group splits or merges.
- A glow has its line's color and is steady.
- The rider is the same arrow on every map.
- No tile shows black or blank while the map is on screen (`MapWarmUp`, `MapStills`).
- Names stay readable: nothing Kav draws hides a street's name, and buildings don't cover names or vehicles.
- Colors on the map come from `MapInk`. Panels over it are glass (8.3).
- The only words Kav writes on a map are its credit line (`MapAttribution`): Kav's own map's fades a few seconds after
  the map shows, and one a provider's license keeps stays. The full credits are in Settings.

## 13. Sheets, popups and messages

- A choice or a detail from the screen the rider is on: `BottomSheet` with a `SheetHeader`. A question that needs an
  answer: `Popup`. A whole flow: a screen that slides over the tabs (`modalIn()`).
- A sheet or a popup is never taller than the screen and never under the status bar. Its buttons stay in view while
  the rest scrolls.
- Text follows 2.2: a title and at most three one-sentence lines. Details are a link.
- One main button. The way out (Close, Later, Cancel) is always there.
- Nothing pops up in the middle of what the rider is doing. A prompt that Kav starts on its own shows when Kav opens.
- A popup goes away when what it was about ends.
- A toast is one short sentence confirming what the rider just did. Never an error they must act on, never an
  explanation.
- Each permission is asked when the rider turns on the thing that needs it, with one line saying why.

## 14. Other services and extensions

### 14.1 It feels like Kav

A feature that comes from another app, a server or an extension is shown with Kav's components, in Kav's words, with
Kav's motion and Kav's Back. The rider can't tell where Kav ends.

- One switch per feature. Turning it on asks for everything it needs, one after the other. It counts as on only when
  it has all of it, and off means none of it shows anywhere.
- Something it needs that was taken away later shows as "Action required" where the rider will see it, never as a
  feature that silently vanished.
- No bouncing between apps: the other app opens on the exact screen needed, and the rider is brought back to Kav when
  it is done. Back from it returns to Kav.
- What Kav can find out by itself is never asked of the rider.
- The other service's states are shown in the rider's words (a ticket is "Valid until 18:47", not "code ready").
- Nothing of Kav's covers what the rider needs from the other app, and nothing of Kav's shows over it by mistake.

### 14.2 Extensions' own screens

An extension's page is built from Kav's parts and is held to every rule here: one title, short text, an (i) for
explanations, Back one step, nothing cut, nothing under the status bar, Kav's motion.

On Kav's own screens about extensions:

- An extension is a row: its picture (a rounded square), its name, and one line in Kav's words of what it adds. Its
  own description is shown whole, never cut (2.6).
- What it can do is a short list of a few words each (`CanDo`), not sentences. Third-party is said once, in one line.
- A page for something that is off shows its switch first, and its options once it is on.
- Rows of one card have one separator style. There is no separator between cards.
- No label that says who made a thing on that thing's own row.

## 15. When two rules pull against each other

- **Short text against telling the rider what is sent.** Both: the screen stays short, and the (i) says what is sent
  to whom in three sentences at most (2.5). Never drop the fact; drop the detail around it.
- **Explanations closed against content the rider came for.** Explanations are closed. What the rider opened the
  screen to read (what's new in an update, an alert's text, a ticket) is open.
- **Everything animates against a smooth map.** On the map the frame rate wins (12.1). Animate what is near, place
  what is far.
- **Everything animates against instant.** Instant means no waiting for data, not no motion. The content is there at
  once and its change still moves, in under 400 ms, without blocking a tap.
- **Nothing shown as loading against honest data.** Show what is truly known, marked for what it is (scheduled, not
  live). Never a made-up or stale value to fill the gap (5.4).
- **Icon-only buttons against clear buttons.** An action everyone knows is an icon. One that a rider would have to
  guess has words (8.2).
- **No double titles against every section needing a title.** One title each: a control with no words of its own
  gets a header, a control with words doesn't (3.1).
- **Hide what isn't ready against showing why nothing is there.** Hide the options, show the switch (5.5, 14.2).
- **Tabs keep their place against things closing when you leave.** Where the rider is stays; what they unfolded
  closes (6.4).
- **Matching what riders know against these rules.** Other apps set the order and the amount of what is shown. These
  rules set how it looks, reads and moves.
- **A redesign against what was decided.** A decision about one screen (this was removed, this stays a real button,
  this goes first) outlives a redesign of that screen. Check the decisions before changing a screen, and list what you
  removed or added when you report.

## 16. The checklist

Run it on every screen, sheet and popup you touched, on a real device, before you report. Every answer must be yes.

Text

1. Covering any one text on the screen, does the screen lose something? (2.1)
2. Is there no header directly over another header, or over a single row that says the same? (3.1)
3. Is every explanation behind an (i), closed, and within its limit? (2.2, 2.3)
4. Can you say of every visible sentence that it is a name, a state, or something the rider must know before
   acting? (2.4)
5. Is no text cut, clipped or ended with "…", in English and in Hebrew? (2.6)
6. Does the Hebrew read as written by an Israeli, and the English as American English? (2.7, 11.3)

Motion and loading

7. In a recording, does every change move, with Kav's specs, as one movement? (4.1, 4.2)
8. Is everything there in the first frame, with nothing arriving late or flashing? (4.3)
9. Does choosing or refreshing leave everything else where it was? (4.4)
10. Is there no sentence about loading, and does every wait end? (5.2, 5.3)

Getting around

11. Does Back, by gesture and by button, go exactly one step to where the rider came from, from every state of the
    screen? (6.1, 6.2)
12. Does every tap open the thing it names? (6.3)

Look

13. Do gaps, corners and colors all come from `K`, with no literal where a token fits? (7.1, 8.1, 8.3)
14. Do edges and centers line up, with nothing bare on the page and nothing under a bar or the keyboard? (7.2 to 7.4)
15. Is every part one of Kav's components, and does it look the same as on the screens beside it? (9)
16. Are icons from one set, the right size, mirrored in Hebrew where they point, and never doubling a worded
    button? (8.2, 11.2)
17. In Hebrew, is the screen the mirror of the English one? (11.1)
18. Did you look at it in the dark and the light look? (8.3)

History

19. Is everything that was removed from this screen before still gone, and everything that was asked for still
    there? (principle 7)
20. On a map: no dropped frames, one camera move per action, nothing blinking or jumping? (12)
