package app.portland.data

/**
 * On-device colour tagging, no AI: every thumbnail is bucketed pixel by pixel into a dozen named colours,
 * and the names that cover enough of the image are stored on the pin so "blue" finds every blue pin.
 */
object Colors {
    /** The searchable colour names. */
    private val names = setOf(
        "red", "orange", "yellow", "green", "teal", "blue", "purple", "pink", "brown", "black", "gray", "white",
    )

    private val aliases = mapOf(
        "grey" to "gray", "silver" to "gray", "violet" to "purple", "lavender" to "purple", "lilac" to "purple",
        "magenta" to "pink", "rose" to "pink", "cyan" to "teal", "turquoise" to "teal", "aqua" to "teal",
        "navy" to "blue", "beige" to "brown", "tan" to "brown", "gold" to "yellow", "golden" to "yellow",
        "crimson" to "red", "maroon" to "red", "scarlet" to "red", "lime" to "green", "olive" to "green",
    )

    /** "Blue", "grey", "golden" -> the stored name; null when [word] is not a colour. */
    fun canonical(word: String): String? = word.lowercase().let { if (it in names) it else aliases[it] }

    /** Colour names a query asks for, or null when any word in it is not a colour ("blue car" is a text search). */
    fun query(q: String): Set<String>? {
        val words = q.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return words.map { canonical(it) ?: return null }.toSet()
    }

    /** Names of the colours covering enough of [pixels] (ARGB), most dominant first. */
    fun of(pixels: IntArray): List<String> {
        val counts = HashMap<String, Int>()
        for (c in pixels) {
            if ((c ushr 24) < 128) continue // transparent pixels say nothing about the image
            val n = name((c shr 16) and 255, (c shr 8) and 255, c and 255)
            counts[n] = (counts[n] ?: 0) + 1
        }
        val total = counts.values.sum().takeIf { it > 0 } ?: return emptyList()
        return counts.entries
            // A small accent of colour is what the eye remembers; neutrals must dominate to count.
            .filter { (n, k) -> k.toFloat() / total >= if (n == "black" || n == "white" || n == "gray") 0.2f else 0.05f }
            .sortedByDescending { it.value }
            .map { it.key }
    }

    /** The bucket for one sRGB pixel, by hue, saturation and value. */
    internal fun name(r: Int, g: Int, b: Int): String {
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val v = max / 255f
        val s = if (max == 0) 0f else (max - min).toFloat() / max
        if (v < 0.18f) return "black"
        if (s < 0.16f || (s < 0.28f && v < 0.35f)) return when {
            v > 0.86f -> "white"
            v < 0.3f -> "black"
            else -> "gray"
        }
        val d = (max - min).toFloat()
        val h = when (max) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0) it + 360f else it }
        return when {
            h < 15f || h >= 345f -> if (v > 0.8f && s < 0.45f) "pink" else if (v < 0.5f) "brown" else "red"
            h < 45f -> if (v < 0.65f) "brown" else "orange"
            h < 68f -> if (v < 0.5f) "green" else "yellow" // dark yellow reads as olive
            h < 165f -> "green"
            h < 195f -> "teal"
            h < 255f -> "blue"
            h < 290f -> "purple"
            else -> "pink"
        }
    }
}
