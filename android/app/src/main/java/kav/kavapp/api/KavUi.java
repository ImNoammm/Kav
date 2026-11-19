package kav.kavapp.api;

import android.content.Context;
import android.view.View;

/**
 * Kav's own components as ordinary views, drawn by Kav: they follow Kav's theme, dark mode, right-to-left and text
 * size by themselves. Put them in any layout. And Kav's theme as values, for views an extension draws itself.
 * Every method must be called on the main thread.
 */
public interface KavUi {
    /** A button: primary is the one main action of a screen; otherwise a quiet one. */
    View button(Context context, String label, boolean primary, Runnable onClick);

    /** A card: Kav's panel around the extension's own content. */
    View card(Context context, View content);

    /** A list row: icon (one of Kav's icon names, or null), title, subtitle (or null), and a chevron when onClick isn't null. */
    View row(Context context, String icon, String title, String subtitle, Runnable onClick);

    /** A row with a switch. */
    View switchRow(Context context, String title, String subtitle, boolean on, Changed<Boolean> onChange);

    /** A text field, with its placeholder. */
    View textField(Context context, String value, String placeholder, Changed<String> onChange);

    /** A text field of one line, or of several when multiline (Enter makes a new line and the field grows as it fills).
     *  action: the keyboard's key on a one-line field, "next" (on to the next field), "done", "search" or "go". API 3. */
    View textField(Context context, String value, String placeholder, boolean multiline, String action, Changed<String> onChange);

    /** A round button with one of Kav's icon names, and its label for screen readers. API 3. */
    View iconButton(Context context, String icon, String label, Runnable onClick);

    /** A frame showing one view at a time, moving to another with Kav's own transition: forward slides the next one in,
     *  back the one before. API 3. */
    Pages pages(Context context, View first);

    /** The views of pages(): view() is the frame to put in a layout. */
    interface Pages {
        View view();
        void forward(View next);
        void back(View previous);
    }

    /** A chip, lit when selected. */
    View chip(Context context, String label, boolean selected, Runnable onClick);

    /** A section header. */
    View header(Context context, String text);

    /** A sheet over the screen with a title and the extension's content. */
    void sheet(Context context, String title, View content);

    /** A dialog with a title, a text, and a confirm button (and Cancel). */
    void dialog(Context context, String title, String text, String confirm, Runnable onConfirm);

    /** A whole screen in Kav's look from a block tree, as JSON text ({"title", "sections": [{"blocks": [...]}]}). */
    View blocks(Context context, String page);

    /** A colour of Kav's theme now, as ARGB: bg, surface, surfaceRaised, fill, fillStrong, separator, text,
     *  textSecondary, textTertiary, surface2, surface3, surface4, borderStrong, sunken, routeIdle, problem, critical,
     *  realtime, accent. 0 for a name Kav doesn't know. */
    int color(String role);

    /** A size of Kav's in dp: rCard, rSheet, rTile, rBadge, gap1 to gap8. 0 for a name Kav doesn't know. */
    float dp(String name);

    /** A text style's size in sp: largeTitle, title, headline, body, subhead, caption. 0 for a name Kav doesn't know. */
    float textSize(String style);

    /** A value that changed. */
    interface Changed<T> {
        void changed(T value);
    }
}
