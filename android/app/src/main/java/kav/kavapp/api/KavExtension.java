package kav.kavapp.api;

/**
 * The class a native extension names in its manifest ("native": {"dex": "classes.dex", "entry": "your.Entry"}).
 * Kav makes it with its public no-argument constructor and calls {@link #start} once each time Kav starts, on a
 * background thread. Everything here is kept un-renamed in Kav's release builds and only ever grows.
 */
public interface KavExtension {
    void start(Kav kav) throws Exception;
}
