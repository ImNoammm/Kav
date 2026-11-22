package kav.kavapp.api;

import android.content.Context;

/**
 * What Kav gives a native extension. Plain types only: text, numbers, Android's own Context and View. Anything else
 * (location, notifications, the camera, files, the network) the extension does with Android itself: it runs inside
 * Kav, with Kav's permissions.
 */
public interface Kav {
    /** This API's version. A newer Kav keeps every method of an older one. 2: map, provide, home, onSettings, pickPlace, settingsScreen.
     *  3: KavUi's multi-line textField, iconButton and pages. 4: action, kavAction, part, addTicket, hook, deoptimize, kavClassLoader. */
    int API = 4;

    int apiVersion();

    /** The application's Context. */
    Context context();

    /** This extension's identity ("publisher/name") and Kav's language ("en" or "he"). */
    String id();

    String locale();

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
