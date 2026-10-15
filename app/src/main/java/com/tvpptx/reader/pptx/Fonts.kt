package com.tvpptx.reader.pptx

import android.graphics.Typeface

/**
 * Maps Office font names onto the fonts an Android projector actually has (Roboto, Noto Serif,
 * Droid Sans Mono ...). [widthFactor] narrows/widens glyphs so lines wrap where PowerPoint wraps them.
 */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    private val SERIF = listOf(
        "times", "georgia", "cambria", "garamond", "antiqua", "palatino", "baskerville", "bodoni", "didot",
        "minion", "constantia", "rockwell", "bookman", "schoolbook", "goudy", "perpetua", "sitka", "merriweather",
        "playfair", "lora", "noto serif", "caslon", "charter", "trajan", "century", "elephant", "bell mt",
        "californian", "cooper", "high tower", "lucida bright", "mongolian", "sylfaen", "batang", "mincho", "simsun"
    )
    private val MONO = listOf("courier", "consolas", "mono", "lucida console", "menlo", "fixedsys", "code")
    private val WIDTH = linkedMapOf(
        "calibri" to 0.88f, "candara" to 0.92f, "corbel" to 0.9f, "segoe" to 0.98f, "aptos" to 0.97f,
        "arial narrow" to 1f, "arial black" to 1.05f, "arial" to 1f, "helvetica" to 1f, "verdana" to 1.12f,
        "tahoma" to 0.98f, "trebuchet" to 0.98f, "century gothic" to 1.1f, "gill sans" to 0.92f,
        "franklin" to 0.92f, "lato" to 0.97f, "open sans" to 1.05f, "montserrat" to 1.12f, "poppins" to 1.1f,
        "source sans" to 0.92f, "inter" to 1.03f, "futura" to 1.02f, "myriad" to 0.92f, "tw cen" to 0.85f,
        "bahnschrift" to 0.95f, "abadi" to 0.92f, "dubai" to 0.95f, "nunito" to 1.02f, "quicksand" to 1.02f,
        "times" to 0.85f, "georgia" to 0.97f, "cambria" to 0.9f, "garamond" to 0.82f, "book antiqua" to 0.9f,
        "palatino" to 0.9f, "consolas" to 0.92f, "courier" to 1f, "impact" to 0.9f
    )

    fun widthFactor(name: String?): Float {
        val n = name?.lowercase() ?: return 1f
        for ((k, v) in WIDTH) if (n.contains(k)) return v
        return if (SERIF.any { n.contains(it) }) 0.9f else 1f
    }

    private fun family(name: String?): String {
        val n = name?.lowercase() ?: return "sans-serif"
        return when {
            MONO.any { n.contains(it) } -> "monospace"
            n.contains("comic") -> "casual"
            n.contains("script") || n.contains("brush") || n.contains("hand") -> "cursive"
            SERIF.any { n.contains(it) } && !n.contains("gothic") -> "serif"
            n.contains("narrow") || n.contains("condensed") || n.contains("compressed") || n.contains("impact") ||
                n.contains("oswald") || n.contains("league gothic") -> "sans-serif-condensed"
            n.contains("light") || n.contains("thin") -> "sans-serif-light"
            n.contains("black") || n.contains("heavy") || n.contains("extrabold") -> "sans-serif-black"
            n.contains("semibold") || n.contains("medium") || n.contains("demi") -> "sans-serif-medium"
            else -> "sans-serif"
        }
    }

    fun typeface(name: String?, bold: Boolean, italic: Boolean): Typeface {
        val fam = family(name)
        val style = when {
            bold && italic -> Typeface.BOLD_ITALIC
            bold -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        val key = "$fam/$style"
        return synchronized(cache) {
            cache.getOrPut(key) { Typeface.create(fam, style) }
        }
    }

    private val WINGDINGS = mapOf(
        'l' to '●', 'n' to '■', 'q' to '❑', 'u' to '◆', 'v' to '❖', 'w' to '⬥', '§' to '▪', '¨' to '◻',
        'Ø' to '➢', 'ü' to '✔', 'û' to '✘', 'à' to '➔', 'è' to '➔', 'ð' to '⇨', 'Ÿ' to '•', 'o' to '◻',
        'p' to '◻', 'r' to '❒', 's' to '♦', 't' to '◆', 'ª' to '✶', '«' to '★', 'J' to '☺', 'F' to '☞',
        'Ö' to '➢', 'Þ' to '➔', 'ß' to '⇦', 'Ü' to '➢', 'Ú' to '➢', 'ä' to '➤', 'ê' to '➔'
    )

    /** Translates symbol-font bullet characters (Wingdings, Symbol) to plain Unicode. */
    fun bulletChar(font: String?, ch: String): String {
        if (ch.isEmpty()) return "•"
        var c = ch[0]
        if (c.code in 0xF000..0xF0FF) c = (c.code - 0xF000).toChar()
        val f = font?.lowercase() ?: ""
        return when {
            f.startsWith("wingdings") -> (WINGDINGS[c] ?: '•').toString()
            f == "symbol" -> if (c == '·' || c == '·') "•" else if (c == 'Þ') "⇒" else c.toString()
            c.code in 0xF000..0xF0FF -> "•"
            else -> ch
        }
    }
}
