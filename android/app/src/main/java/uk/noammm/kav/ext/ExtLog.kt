package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// Each extension's log on disk (ext/net/<identity's key>.json), the newest last: the lines its code wrote (Kav.log)
// and the failures of its code, the last 200, on its page in Settings.
object ExtLog {
    private const val KEEP = 200

    @Volatile private var dir: File? = null

    fun init(d: File) { dir = d }

    private fun file(identity: String) = File(File(dir ?: throw IllegalStateException("no extensions"), "net"), Registry.stateKey(identity) + ".json")

    @Synchronized fun add(identity: String, entry: JSONObject) {
        val f = file(identity)
        val list = read(identity).toMutableList().apply { add(entry) }.takeLast(KEEP)
        f.parentFile?.mkdirs()
        f.writeText(JSONArray(list).toString())
    }

    @Synchronized fun read(identity: String): List<JSONObject> =
        runCatching { JSONArray(file(identity).readText()).objects() }.getOrDefault(emptyList())
}
