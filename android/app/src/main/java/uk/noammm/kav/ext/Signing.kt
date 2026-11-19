package uk.noammm.kav.ext

import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

// Kav's own signature on its security block list (BlockList), the one signed file extensions still have. A signature
// covers "KAVEXT:<role>:1\n" and then the exact payload bytes, so a signature made for one role can't be shown as
// another's. ECDSA P-256 over SHA-256, DER, as `openssl dgst -sha256 -sign`
// makes it (the same primitive as SignedFile).
enum class Role(val key: String) {
    BLOCK_LIST("block-list"),
}

class TrustError(message: String) : Exception(message)

object Keys {
    // A key's fingerprint: the first 16 bytes of SHA-256 over its X.509 encoding, in lower-case hex. Also the publisher's
    // name in references (Ref.publisher), so two publishers can't share a namespace.
    fun fingerprint(spki: ByteArray): String = sha256(spki).copyOf(16).hex()

    fun fingerprint(spkiBase64: String): String = fingerprint(decode(spkiBase64) ?: throw TrustError("bad key"))

    fun publicKey(spki: ByteArray): PublicKey = runCatching {
        KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)).also {
            require((it as java.security.interfaces.ECPublicKey).params.order.bitLength() == 256)
        }
    }.getOrElse { throw TrustError("not a P-256 key") }

    fun signed(role: Role, payload: ByteArray): ByteArray = "KAVEXT:${role.key}:1\n".toByteArray() + payload

    fun verify(spki: ByteArray, role: Role, payload: ByteArray, signature: ByteArray): Boolean = runCatching {
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey(spki))
            update(signed(role, payload))
            verify(signature)
        }
    }.getOrDefault(false)

    fun decode(b64: String): ByteArray? = runCatching { Base64.getDecoder().decode(b64) }.getOrNull()
    fun encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
}

fun sha256(b: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(b)
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

// A signed file: {"kav":"signed","role":..,"payload":"<base64 of the exact bytes>","signatures":[{"key":"<X.509 base64>",
// "sig":"<base64 DER>"}]}. The payload is read only after a signature over it checks out.
class Envelope(val role: Role, val payload: ByteArray, val signatures: List<Pair<ByteArray, ByteArray>>) {
    // The keys whose signatures check out, by fingerprint.
    val signers: Map<String, ByteArray> by lazy {
        signatures.filter { (key, sig) -> Keys.verify(key, role, payload, sig) }.associate { (key, _) -> Keys.fingerprint(key) to key }
    }

    fun signedBy(fingerprint: String) = fingerprint in signers

    // The payload, when one of these keys signed it.
    fun payloadFrom(fingerprints: Collection<String>): JSONObject {
        if (fingerprints.none(::signedBy)) throw TrustError("${role.key} isn't signed by a key Kav trusts for it")
        return StrictJson.obj(payload, MAX_PAYLOAD)
    }

    companion object {
        const val MAX_PAYLOAD = 1 shl 20
        private const val MAX_SIGNATURES = 4

        fun parse(bytes: ByteArray, role: Role, max: Int = MAX_PAYLOAD * 2): Envelope {
            val o = StrictJson.obj(bytes, max)
            if (o.optString("kav") != "signed") throw TrustError("not a signed file")
            if (o.optString("role") != role.key) throw TrustError("signed for another use, wanted ${role.key}")
            val payload = Keys.decode(o.optString("payload")) ?: throw TrustError("bad payload")
            val list = o.optJSONArray("signatures") ?: throw TrustError("no signatures")
            if (list.length() == 0 || list.length() > MAX_SIGNATURES) throw TrustError("bad signatures")
            val sigs = (0 until list.length()).map { i ->
                val s = list.optJSONObject(i) ?: throw TrustError("bad signature")
                (Keys.decode(s.optString("key")) ?: throw TrustError("bad key")) to (Keys.decode(s.optString("sig")) ?: throw TrustError("bad signature"))
            }
            return Envelope(role, payload, sigs)
        }
    }
}

// JSON for signed things: UTF-8 only, no duplicate keys (org.json on Android keeps the last one silently, the JVM's throws,
// so two readers could see two different files), bounded size and depth. Checked by its own scan, then read by org.json.
object StrictJson {
    private const val MAX_DEPTH = 32

    fun obj(bytes: ByteArray, max: Int): JSONObject {
        val text = text(bytes, max)
        Scan(text).run { ws(); if (peek() != '{') bad("not an object"); value(0); ws(); if (i != s.length) bad("trailing data") }
        return runCatching { JSONObject(text) }.getOrElse { throw TrustError("bad JSON") }
    }

    private fun text(bytes: ByteArray, max: Int): String {
        if (bytes.size > max) throw TrustError("too large")
        val d = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { d.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrElse { throw TrustError("not UTF-8") }
    }

    private class Scan(val s: String) {
        var i = 0
        fun bad(why: String): Nothing = throw TrustError("bad JSON: $why")
        fun peek(): Char = if (i < s.length) s[i] else '\u0000'
        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }

        fun value(depth: Int) {
            if (depth > MAX_DEPTH) bad("too deep")
            ws()
            when (peek()) {
                '{' -> {
                    i++; ws()
                    val keys = HashSet<String>()
                    if (peek() == '}') { i++; return }
                    while (true) {
                        ws(); if (peek() != '"') bad("key")
                        if (!keys.add(string())) bad("duplicate key")
                        ws(); if (peek() != ':') bad("colon"); i++
                        value(depth + 1); ws()
                        when (peek()) { ',' -> i++; '}' -> { i++; return }; else -> bad("object") }
                    }
                }
                '[' -> {
                    i++; ws()
                    if (peek() == ']') { i++; return }
                    while (true) {
                        value(depth + 1); ws()
                        when (peek()) { ',' -> i++; ']' -> { i++; return }; else -> bad("array") }
                    }
                }
                '"' -> string()
                't' -> word("true")
                'f' -> word("false")
                'n' -> word("null")
                else -> number()
            }
        }

        fun word(w: String) { if (!s.startsWith(w, i)) bad("literal"); i += w.length }

        fun number() {
            val start = i
            fun digits() { val d = i; while (peek() in '0'..'9') i++; if (i == d) bad("number") }
            if (peek() == '-') i++
            if (peek() == '0') i++ else digits()
            if (peek() == '.') { i++; digits() }
            if (peek() == 'e' || peek() == 'E') { i++; if (peek() == '+' || peek() == '-') i++; digits() }
            if (i == start) bad("value")
        }

        fun string(): String {
            i++
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) bad("string")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c < ' ' -> bad("control character")
                    c == '\\' -> {
                        when (val e = s.getOrNull(i++)) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n')
                            'r' -> out.append('\r'); 't' -> out.append('\t')
                            'u' -> {
                                val h = s.substring(i, minOf(i + 4, s.length)).takeIf { it.length == 4 }?.toIntOrNull(16) ?: bad("escape")
                                out.append(h.toChar()); i += 4
                            }
                            else -> bad("escape")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }
    }
}

// A whole number a trust rule decides on: a JSON integer, never a string or a fraction that org.json would quietly turn
// into one ("1" and 1.0 are refused). Absent: the default.
internal fun JSONObject.whole(name: String, default: Long): Long = when (val v = opt(name)) {
    null -> default
    is Int -> v.toLong()
    is Long -> v
    else -> throw TrustError("$name isn't a whole number")
}

internal fun JSONObject.small(name: String, default: Int): Int =
    whole(name, default.toLong()).coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

internal fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it, null) }
