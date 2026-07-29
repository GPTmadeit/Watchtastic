package com.watchtastic.update

/**
 * A dotted release version, compared numerically rather than as text.
 *
 * String comparison would rank "1.10.0" below "1.9.0", which is exactly the point at
 * which an update channel silently stops offering updates.
 */
data class Version(val parts: List<Int>, val raw: String) : Comparable<Version> {

    override fun compareTo(other: Version): Int {
        val width = maxOf(parts.size, other.parts.size)
        for (index in 0 until width) {
            val mine = parts.getOrElse(index) { 0 }
            val theirs = other.parts.getOrElse(index) { 0 }
            if (mine != theirs) return mine.compareTo(theirs)
        }
        return 0
    }

    override fun toString(): String = raw

    companion object {
        private val VERSION_IN_NAME = Regex("""(\d+(?:\.\d+)+)""")

        fun parse(text: String): Version? {
            val cleaned = text.trim()
            if (!cleaned.matches(Regex("""\d+(\.\d+)*"""))) return null
            val parts = cleaned.split('.').mapNotNull { it.toIntOrNull() }
            return if (parts.isEmpty()) null else Version(parts, cleaned)
        }

        /** Pulls the version out of names like `Watchtastic-1.2.0-release.apk`. */
        fun fromFileName(fileName: String): Version? =
            VERSION_IN_NAME.find(fileName)?.groupValues?.get(1)?.let { parse(it) }
    }
}
