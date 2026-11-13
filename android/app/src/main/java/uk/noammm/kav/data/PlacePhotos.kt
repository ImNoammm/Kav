package uk.noammm.kav.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

// A place's photo: the one Google Maps shows first when the place is opened (GoogleMaps.photoOf), never Street View,
// small for a row and large when opened.
// Kept on the phone, and so is a place Google has none of, so a place is asked about once in a long while. Asked only
// while photos are on (the UI checks).
object PlacePhotos {
    private const val THUMB_PX = 240
    private const val FULL_W = 1024
    private const val FULL_H = 640
    private const val KEEP_DAYS = 60L
    private const val KEEP_NONE_DAYS = 7L

    private var dir: File? = null
    private val gate = Semaphore(2)
    private val thumbs = object : LruCache<String, Bitmap>(8 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    // A folder of its own for each change in how a photo is found, so places with none under the last way are asked
    // again: the earlier folders are dropped.
    fun init(ctx: Context) {
        dir = File(ctx.cacheDir, "place-photos-3").apply { mkdirs() }
        Thread { listOf("place-photos", "place-photos-2").forEach { File(ctx.cacheDir, it).deleteRecursively() } }.start()
    }

    // A pinned spot or typed coordinates name no place Google could show.
    fun worthAsking(p: Transit.Place): Boolean {
        val name = p.name.trim()
        return name.isNotEmpty() && !COORDINATES.matches(name) && name !in UNNAMED
    }

    // One file per place: Google's id for it where known, else its name and where it is to about 10 m.
    fun key(p: Transit.Place): String {
        val id = p.googleId ?: String.format(Locale.US, "%s|%.4f|%.4f", p.name.trim().lowercase(), p.lat, p.lon)
        return MessageDigest.getInstance("SHA-1").digest(id.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun thumbNow(p: Transit.Place): Bitmap? = thumbs.get(key(p))

    // The place's photo from the phone alone (never Google), kept in memory for thumbNow, off the main thread.
    fun fromDisk(p: Transit.Place) {
        val folder = dir ?: return
        val k = key(p)
        if (thumbs.get(k) != null) return
        val file = File(folder, "$k.jpg")
        if (file.exists() && System.currentTimeMillis() - file.lastModified() < KEEP_DAYS * 86_400_000L)
            BitmapFactory.decodeFile(file.path)?.let { thumbs.put(k, it) }
    }

    // The place's photo, small; null where Google has none of it. Throws when Google couldn't be asked. wait: how long
    // to hold off before asking Google (not the phone), so a row only passing by while a search is typed asks nothing.
    suspend fun thumb(p: Transit.Place, wait: Long = 0): Bitmap? = withContext(Dispatchers.IO) {
        val k = key(p)
        thumbs.get(k)?.let { return@withContext it }
        photo(p, k, wait)?.also { thumbs.put(k, it) }
    }

    // The same photo, as large as a dialog shows it; null if the small one never came.
    suspend fun full(p: Transit.Place): Bitmap? = withContext(Dispatchers.IO) {
        val folder = dir ?: return@withContext null
        val k = key(p)
        val url = File(folder, "$k.url").takeIf { it.exists() }?.readText() ?: return@withContext null
        val file = File(folder, "${k}_full.jpg")
        if (!file.exists()) file.writeBytes(get(sized(url, FULL_W, FULL_H)))
        BitmapFactory.decodeFile(file.path)
    }

    private suspend fun photo(p: Transit.Place, k: String, wait: Long): Bitmap? {
        val folder = dir ?: throw IOException("Place photos are not initialized")
        val file = File(folder, "$k.jpg")
        val none = File(folder, "$k.none")
        val now = System.currentTimeMillis()
        if (none.exists() && now - none.lastModified() < KEEP_NONE_DAYS * 86_400_000L) return null
        if (file.exists() && now - file.lastModified() < KEEP_DAYS * 86_400_000L) BitmapFactory.decodeFile(file.path)?.let { return it }
        if (wait > 0) delay(wait)
        return gate.withPermit {
            val url = GoogleMaps.photoOf(p.name, p.lat, p.lon, p.googleId)
            if (url == null) {
                none.writeText("")
                return@withPermit null
            }
            file.writeBytes(get(sized(url, THUMB_PX, THUMB_PX)))
            File(folder, "$k.url").writeText(url)
            File(folder, "${k}_full.jpg").delete()
            none.delete()
            BitmapFactory.decodeFile(file.path) ?: throw IOException("Google sent no image")
        }
    }

    // Google's photo at the size wanted, cropped to it, by its size suffix.
    internal fun sized(url: String, w: Int, h: Int): String =
        if (SUFFIX.containsMatchIn(url)) url.replace(SUFFIX, "=w$w-h$h-p-k-no") else url

    private val SUFFIX = Regex("=w\\d+-h\\d+[^/?]*$")
    private val COORDINATES = Regex("-?\\d{1,3}\\.\\d+,\\s*-?\\d{1,3}\\.\\d+")
    private val UNNAMED = setOf("Pinned location", "מיקום מסומן", "Current location", "המיקום הנוכחי", "My location", "המיקום שלי")

    private fun get(url: String): ByteArray {
        // A slow network gets its time: a photo isn't waited on as typing is.
        val c = (RiderProxy.open(URL(url)) as HttpURLConnection).apply { connectTimeout = 20_000; readTimeout = 20_000 }
        try {
            if (c.responseCode != 200) throw IOException("Place photo: HTTP ${c.responseCode}")
            if (c.contentType?.startsWith("image/") != true) throw IOException("Google sent no image")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }
}
