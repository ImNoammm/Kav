package kav.kavapp.api;

import android.content.Context;

/**
 * What Kav gives a native extension. Plain types only: text, numbers, Android's own Context and View. Anything else
 * (location, notifications, the camera, files, the network) the extension does with Android itself: it runs inside
 * Kav, with Kav's permissions.
 */
public interface Kav {
    /** This API's version. A newer Kav keeps every method of an older one. 2: map, provide, home, onSettings, pickPlace, settingsScreen.
     *  3: KavUi's multi-line textField, iconButton and pages. 4: action, kavAction, part, addTicket, hook, hooked, deoptimize, kavClassLoader. 5: prefs, trips, onTrip, widget, refreshWidget, stops, vehicles, departures, addRides. */
    int API = 5;

    int apiVersion();

    /** The application's Context. */
    Context context();

    /** This extension's identity ("publisher/name") and Kav's language ("en" or "he"). */
    String id();

    String locale();

    /** API 5. Rider display settings as JSON: stopNames ("en" or "he"), clock (12 or 24), and locale. */
    default String prefs() { return "{}"; }

    /** API 5. The rider's recent trips, newest first, as Kav's Recent trips list has them: a JSON array of {"from", "to",
     *  "stopovers", "at", "planner"}. Each place is {"name", "detail", "lat", "lon"}; from is null for a trip from where
     *  the rider was; at is when it was searched, in seconds since 1970; planner is the extension that planned it
     *  ("ext:<publisher>/<dataset>"), "" for Kav's. null until the rider allows it on the extension's page. Needs a
     *  contribution with capability "trips.history"; without it, it throws. */
    default String trips() { return null; }

    /** API 5. Told about the trip the rider follows (Start on a trip's page), as JSON text: {"event", "trip", "from",
     *  "to", "step", "reason"}. event: "start", "step" (the rider moved on to another step), "replan" (a new route on
     *  the way) or "end" (reason "arrived", or "ended" when the rider ended it before). A trip already under way is told
     *  at once, as "start". Only while the rider allows it on the extension's page; on a thread of Kav's, never the main
     *  one. null stops it. Needs a contribution with capability "trips.follow"; without it, it throws. */
    default void onTrip(TripListener listener) {}

    /** API 5. A home screen widget's content, for a widget the manifest declares in "widgets" (with a contribution with
     *  capability "widget.provide"; without it, it throws). Kav owns the widgets on the home screen; the rider places one
     *  and chooses this one for it. Kav asks for its content when it shows, refreshes or the rider opens Kav, on a thread
     *  of Kav's, never the main one, and draws it in its own look. null stops it. */
    default void widget(String id, Widget widget) {}

    /** API 5. Asks Kav to ask a widget of this extension for its content again now (its data changed). From any thread. */
    default void refreshWidget(String id) {}

    /** API 5. Stops from Kav's timetable on the phone. query, as JSON text: {"near": [lat, lon], "name", "mode", "code",
     *  "limit"}, each optional; the stops that match all given, nearest first with near, else by name, at most limit (20
     *  by default, 200 at most). mode is the GTFS route type of a stop's lines (a train station 2). Answers a JSON array
     *  of {"stop", "code", "name", "city", "lat", "lon", "mode", "platform"}, with "metres" when near is given; null
     *  until the timetable has loaded. From any thread. */
    default String stops(String query) { return null; }

    /** API 5. The live vehicles Kav has, as its Live tab and a line's page show them. query: {"line": "<number>"}, every
     *  vehicle on the routes with that number, or {"near": [lat, lon], "km"}, those around a place; both, the line's
     *  nearest the place first. Answers a JSON array of {"key", "plate", "route", "line", "to", "agency", "mode", "lat",
     *  "lon", "bearing", "speed", "at"}, with "metres" when near is given; null when
     *  Kav's live source can't be reached. It asks the network: never on the main thread, where it throws. */
    default String vehicles(String query) { return null; }

    /** API 5. A stop's next departures, as its board in Kav shows them now. query: {"stop": "il.mot/gtfs/stop/<code>",
     *  "limit"} (20 by default, 100 at most). Answers a JSON array of {"line", "to", "mode", "platform", "at", "live",
     *  "cancelled"}, times in seconds since 1970, live null for one with no live time; null when Kav doesn't know the
     *  stop or its timetable hasn't loaded. It asks the network: never on the main thread, where it throws. */
    default String departures(String query) { return null; }

    /** API 5. Rides already over, into Kav's ride history (Settings › Paying › Ride history, Home's "This month"): a JSON
     *  array of {"key", "kind", "line", "from", "to", "at", "riders", "band", "price"}, kind "BUS", "TRAIN" or
     *  "LIGHT_RAIL", at in milliseconds, band the fare's reach in km, price in agorot. The same key again updates that ride. Kav keeps the key as
     *  "ext:<id()>:<key>", as with addTicket. A ride that isn't right throws. From any thread. */
    default void addRides(String rides) {}

    /**
     * A card on one of Kav's screens: "home", "results" (a line under each route of a search's results), "trip" or
     * "stop" (the manifest declares it as a contribution "slot.home", "slot.results", "slot.trip" or "slot.stop"). Kav
     * asks the card for a view each time the screen shows it.
     */
    void card(String on, Card card);

    /** A screen of its own, for a page the manifest declares (opened from its page in Settings › Extensions). */
    void screen(String page, Screen screen);

    /** Its Home body, for a manifest that declares "home.body" and the page "home": shown when the rider chooses it as
     *  their Home. A tab is a screen whose page the manifest marks "tab": true. */
    void home(Screen screen);

    /** Kav's maps: its markers, lines and areas, and its look for Kav's stops. */
    KavMap map();

    /**
     * A hook Kav calls, for a contribution the manifest declares. Input and answer are JSON text:
     *   route rules ("walk.time", "transfer.policy", "results.filter", "results.rank", "results.annotate"): the search,
     *     {"query", "routes", "walks", "transfers"}; the answer is an array, one item for each walk, transfer or route.
     *   "plan.provide": {"from": [lat, lon], "to", "at", "way", "modes", "settings"}; the answer is a plan (Plan v2), or
     *     null for no routes.
     *   "board.stops": {} answered with {"stops", "lines"}; "board.departures": {"stop", "from", "limit"} answered with
     *     an array of departures.
     * Called on a thread of Kav's, never the main one, within the hook's deadline.
     */
    void provide(String name, Hook hook);

    /** Its settings as the rider set them, as JSON text ({} when it declares none). */
    String settings();

    /** Told each time the rider changes one of its settings, with all of them as settings() gives them, on a thread of
     *  Kav's, never the main one. null stops it. */
    void onSettings(SettingsChanged changed);

    /** Kav's own place search over the screen, the one the rider plans trips with (suggestions, their saved places, a pick on
     *  the map): told the place chosen, as JSON text ({"name", "lat", "lon"}), or null when the rider closed it. Called
     *  from any thread; told on the main thread. */
    void pickPlace(Picked picked);

    /** Its own settings view, for pickers and looks Kav doesn't have: shown on its page in Settings › Extensions, under
     *  the settings its manifest declares (or alone, with none declared), each time the page shows. args: {}. */
    void settingsScreen(Screen screen);

    /** A line in its log (Settings › Extensions, its page). */
    void log(String text);

    /** Kav's own look: Kav's components as views, and its theme as values. */
    KavUi ui();

    // API 4. Each of these needs "api": 4 in the manifest, and the contribution it names; without them it throws.

    /** Take over an action (manifest: a contribution with capability "action.<name>"): Kav calls it instead of its own, on
     *  the main thread with the screen's Activity. args: JSON text. Return true when you handled it, false to let the next
     *  extension, then Kav, do its own. null removes it. With several on one action, the last switched on goes first. */
    void action(String name, Action action);

    /** Run Kav's own handling of an action yourself, from inside your own handler too: no extension is asked. On the main
     *  thread. */
    void kavAction(String name, Context activity, String args);

    /** Add a view before or after one of Kav's screen parts, or replace it (manifest: capability "part.<name>"). where:
     *  "before", "after" or "replace". The view is asked for each time the part shows, on the main thread; about: JSON
     *  text of what the part shows. Returning null from a replace shows Kav's own. null removes it. */
    void part(String name, String where, Card card);

    /** A ticket into Kav's own tickets and ride history, as JSON text (docs/native.md has its fields). From any thread. The
     *  same key again updates it. It stays when the extension is switched off or removed, until it ends. */
    void addTicket(String ticket);

    /** Hook one of Kav's (or Android's) methods (manifest: capability "hooks"). before runs first and may set a result to
     *  skip the original; after runs after it and may change the result. Either may be null. Returns an Unhook, or throws
     *  when the method can't be hooked. Every hook is removed when the extension is switched off or blocked. */
    Unhook hook(java.lang.reflect.Member method, MethodHook before, MethodHook after);

    /** The same, by the function's name, as the manifest's "hooks" lists it: className, method ("<init>" for a
     *  constructor) and its parameter types ("int", "java.lang.String", "int[]"). When this Kav has no such function,
     *  or can't hook it, nothing is hooked and nothing is thrown: Kav writes it in the extension's log and says on its
     *  row that it needs an update for this Kav. hooked tells whether it took. */
    Unhook hook(String className, String method, String[] params, MethodHook before, MethodHook after);

    /** Whether this extension has a hook on that function now. */
    boolean hooked(String className, String method, String... params);

    /** A method that may have inlined a small one you hooked (R8 inlines small methods in Kav's release builds), run as
     *  written again so your hook is reached. True when it was done. */
    boolean deoptimize(java.lang.reflect.Member method);

    /** Kav's own ClassLoader, to find its classes by name. */
    ClassLoader kavClassLoader();

    /** A card's view, made each time its screen shows it. about: what the screen shows, as JSON text: the trip, or the
     *  result's route ({"trip": the route as route rules get it}), or the stop ({"stop": "<ref>", "name": "..."}); {} on
     *  Home. A results card that has nothing to say for a route makes no view (null). */
    interface Card {
        android.view.View create(Context context, String about);
    }

    /** A screen's view. args: the page's arguments, as JSON text. */
    interface Screen {
        android.view.View create(Context context, String args);
    }

    /** API 5. A trip event (onTrip), as JSON text. */
    interface TripListener {
        void trip(String event);
    }

    /** API 5. A home screen widget. content: its content as JSON text (docs/native.md has its fields), asked with args,
     *  {"widget", "rows", "width", "height"}: rows is how many fit, width and height its size in dp. Answer within 3
     *  seconds; Kav shows the last content it showed until then, and when it fails. tapped: a tap on a part whose action
     *  is {"call": "<name>"}, told {"widget", "call", "row"}; Kav asks for the content again after it. */
    interface Widget {
        String content(String args) throws Exception;

        default void tapped(String tap) throws Exception {}
    }

    /** Its settings, all of them, as JSON text, after the rider changed one. */
    interface SettingsChanged {
        void changed(String settings);
    }

    /** The place the rider chose, as JSON text, or null. */
    interface Picked {
        void picked(String place);
    }

    /** A hook's answer to its input, both JSON text. */
    interface Hook {
        String call(String input) throws Exception;
    }

    /** An action taken over: true when it was handled. activity: the screen's. */
    interface Action {
        boolean run(Context activity, String args);
    }

    /** Told each call of a hooked method, before or after the original runs. */
    interface MethodHook {
        void call(Call call) throws Throwable;
    }

    /** One call of a hooked method. Setting a result or an error in before skips the original. */
    interface Call {
        /** The object the method was called on; null for a static method. */
        Object thisObject();

        /** Its arguments, which before may change. */
        Object[] args();

        Object result();

        void setResult(Object result);

        Throwable error();

        void setError(Throwable error);

        /** The original method, with these arguments, without the hooks. */
        Object callOriginal(Object... args) throws Throwable;
    }

    interface Unhook {
        void unhook();
    }
}
