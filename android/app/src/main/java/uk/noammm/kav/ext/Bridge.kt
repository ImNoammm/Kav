package uk.noammm.kav.ext

import java.nio.ByteBuffer
import java.nio.ByteOrder

// The binary form of what the pipeline sends a route rule about a search and reads back (Pipeline, NativeRules). A
// message is little-endian 32-bit words: the magic, the word where the strings start, how many strings
// there are, then the message's own numbers, then the strings, each its length and its UTF-16 units two to a word. A
// string is given by its number in that list, -1 for none.
object Bridge {
    const val MAGIC = 0x3156414B // "KAV1"

    class Writer {
        private var words = IntArray(1024)
        private var n = 3
        private val strings = ArrayList<String>()
        private val index = HashMap<String, Int>()

        fun int(v: Int) = apply {
            if (n == words.size) words = words.copyOf(n * 2)
            words[n++] = v
        }

        fun long(v: Long) = int(v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt())

        fun str(s: String?) = int(if (s == null) -1 else index.getOrPut(s) { strings.add(s); strings.size - 1 })

        fun bytes(): ByteArray {
            val chars = strings.sumOf { 1 + (it.length + 1) / 2 }
            val b = ByteBuffer.allocate((n + chars) * 4).order(ByteOrder.LITTLE_ENDIAN)
            words[0] = MAGIC; words[1] = n; words[2] = strings.size
            for (i in 0 until n) b.putInt(words[i])
            for (s in strings) {
                b.putInt(s.length)
                for (c in s) b.putChar(c)
                if (s.length % 2 == 1) b.putChar(0.toChar())
            }
            return b.array()
        }
    }

    class Bad(message: String) : Exception(message)

    // An answer, read with every bound checked: a short or lying message is Bad, never a guess.
    class Reader(bytes: ByteArray) {
        private val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        private val total = bytes.size / 4
        private val end: Int
        private val strings: List<String>
        private var at = 3

        init {
            if (bytes.size % 4 != 0 || total < 3 || b.getInt(0) != MAGIC) throw Bad("not a bridge message")
            end = b.getInt(4)
            val count = b.getInt(8)
            if (end !in 3..total || count < 0 || count > total) throw Bad("bad header")
            var p = end
            strings = ArrayList<String>(count).also { list ->
                repeat(count) {
                    if (p >= total) throw Bad("strings cut short")
                    val len = b.getInt(p * 4)
                    if (len < 0 || len > 4096 || p + 1 + (len + 1) / 2 > total) throw Bad("bad string")
                    val sb = StringBuilder(len)
                    for (k in 0 until len) sb.append(b.getChar((p + 1) * 4 + k * 2))
                    list.add(sb.toString())
                    p += 1 + (len + 1) / 2
                }
            }
        }

        val left get() = end - at

        fun int(): Int {
            if (at >= end) throw Bad("answer cut short")
            return b.getInt(at++ * 4)
        }

        fun count(max: Int): Int = int().also { if (it < 0 || it > max) throw Bad("bad count $it") }

        fun str(): String? = int().let { if (it == -1) null else strings.getOrNull(it) ?: throw Bad("bad string number") }
    }
}
