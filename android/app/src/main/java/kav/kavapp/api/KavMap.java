package kav.kavapp.api;

import android.content.Context;
import android.graphics.Bitmap;

/**
 * Kav's maps for a native extension: markers, lines and areas it adds, a call when the rider taps one, and its own look
 * for Kav's stop marks. Plain types: places are latitude and longitude in degrees, colours ARGB, sizes in dp, icons
 * Android Bitmaps the extension draws. Callable from any thread.
 *
 * on: the map a shape shows on: "live" (the Live tab), "trip" (a trip's map), or null for both. id: the extension's own
 * name for it; adding with an id it used before replaces that shape.
 */
public interface KavMap {
    /** A marker: its icon, centred on the place, or a dot of the colour when icon is null. */
    void marker(String id, String on, double lat, double lon, int color, Bitmap icon);

    /** A line through points (lat, lon, lat, lon, ...). */
    void line(String id, String on, double[] points, int color, float widthDp);

    /** An area inside points (lat, lon, lat, lon, ...), filled, with an outline (0 for none). */
    void area(String id, String on, double[] points, int fill, int outline);

    void remove(String id);

    /** Every shape of this extension's, off every map. */
    void clear();

    /** Told when the rider taps one of its shapes. null stops it. */
    void onTap(Tapped tapped);

    /** Its look for Kav's own stop marks on the Live tab. null gives them back Kav's look. */
    void stopLook(StopLook look);

    interface Tapped {
        /** On the main thread. context: the screen's, for a sheet or dialog. */
        void tapped(String id, Context context);
    }

    interface StopLook {
        /**
         * A stop's look: stop is its reference ("il.mot/gtfs/stop/<code>"), as on a stop's card. null keeps Kav's look for
         * it. Asked once per stop and kept until stopLook is set again, on the main thread: keep it quick.
         */
        Look look(String stop, String name, double lat, double lon);
    }

    /** A stop mark: the dot's fill and ring, or an icon in its place when icon isn't null. */
    final class Look {
        public final int fill;
        public final int ring;
        public final Bitmap icon;

        public Look(int fill, int ring, Bitmap icon) {
            this.fill = fill;
            this.ring = ring;
            this.icon = icon;
        }
    }
}
