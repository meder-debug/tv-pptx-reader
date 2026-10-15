package com.tvpptx.reader.pptx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.MetricAffectingSpan
import android.text.style.TabStopSpan
import org.w3c.dom.Element
import kotlin.math.max
import kotlin.math.min

/** Text look imposed below the list styles: a shape's <p:style><a:fontRef>, a table style's tcTxStyle. */
class TextDefaults(val color: Int?, val font: String?, val bold: Boolean? = null, val italic: Boolean? = null)

/**
 * Where a text body inherits from, most specific first: its own lstStyle, the layout and master
 * placeholder lstStyles, the master title/body/other style, the presentation default style.
 * [defaults] slot in after the first [defaultsAt] sources.
 */
class TextChain(val sources: List<Element>, val defaultsAt: Int = 1, val defaults: TextDefaults? = null)

class TextEnv(val colors: ColorCtx, val pxPerEmu: Float, val slideNumber: Int)

/** Paragraph-level property lookup for one list level. */
private class LevelProps(val chain: TextChain, level: Int, val pPr: Element?) {
    val entries = ArrayList<Pair<Element, Int>>()
    val pPrDefaults: Element? = pPr?.child("defRPr")

    init {
        chain.sources.forEachIndexed { i, s ->
            s.child("lvl${level + 1}pPr")?.let { entries.add(it to i) }
            s.child("defPPr")?.let { entries.add(it to i) }
        }
    }

    fun pAttr(name: String): String? = pPr?.attr(name) ?: entries.firstNotNullOfOrNull { it.first.attr(name) }

    fun pChild(vararg names: String): Element? =
        pPr?.firstOf(*names) ?: entries.firstNotNullOfOrNull { it.first.firstOf(*names) }

    inline fun <T> run(rPr: Element?, get: (Element) -> T?, def: (TextDefaults) -> T?): T? {
        if (rPr != null) get(rPr)?.let { return it }
        pPrDefaults?.let(get)?.let { return it }
        var defaultsDone = false
        for ((el, src) in entries) {
            if (!defaultsDone && src >= chain.defaultsAt) {
                defaultsDone = true
                chain.defaults?.let(def)?.let { return it }
            }
            el.child("defRPr")?.let(get)?.let { return it }
        }
        if (!defaultsDone) chain.defaults?.let(def)?.let { return it }
        return null
    }
}

/** All the character formatting of one run, applied through a single span. */
private class RunSpan(
    val size: Float, val typeface: Typeface, val scaleX: Float, val letterSpacing: Float,
    val shift: Float, val color: Int, val underline: Boolean, val strike: Boolean, val highlight: Int
) : MetricAffectingSpan() {
    override fun updateMeasureState(tp: TextPaint) = apply(tp)

    override fun updateDrawState(tp: TextPaint) {
        apply(tp)
        tp.color = color
        tp.isUnderlineText = underline
        tp.isStrikeThruText = strike
        if (highlight != 0) tp.bgColor = highlight
    }

    private fun apply(tp: TextPaint) {
        tp.textSize = size
        tp.typeface = typeface
        tp.textScaleX = scaleX
        tp.letterSpacing = letterSpacing
        if (shift != 0f) tp.baselineShift -= (shift * size * 1.5f).toInt()
    }
}

private class RunStyle(val size: Float, val typeface: Typeface, val font: String, val color: Int, val span: RunSpan, val caps: Boolean)

private class Bullet(val text: String, val paint: TextPaint, val x: Float, val width: Float)

private class ParaLayout(
    val layout: StaticLayout, val x: Float, val before: Float, val after: Float,
    val bullet: Bullet?, val leftAligned: Boolean
)

private class Laid(val paras: List<ParaLayout>) {
    val height: Float = paras.foldIndexed(0f) { i, acc, p ->
        acc + (if (i > 0) p.before else 0f) + p.layout.height + (if (i < paras.size - 1) p.after else 0f)
    }

    /** Paragraphs in [hidden] keep their place but aren't drawn, as PowerPoint does before they animate in. */
    fun draw(canvas: Canvas, left: Float, top: Float, hidden: Set<Int>) {
        var y = top
        paras.forEachIndexed { i, p ->
            if (i > 0) y += p.before
            if (i in hidden) {
                y += p.layout.height + p.after
                return@forEachIndexed
            }
            canvas.save()
            canvas.translate(left + p.x, y)
            p.layout.draw(canvas)
            p.bullet?.let { b ->
                val baseline = p.layout.getLineBaseline(0).toFloat()
                val bx = if (p.leftAligned) b.x else p.layout.getLineLeft(0) - b.width - b.paint.textSize * 0.3f
                canvas.drawText(b.text, bx, baseline, b.paint)
            }
            canvas.restore()
            y += p.layout.height + p.after
        }
    }
}

object TextRenderer {
    /**
     * Draws [txBody] into [box] (shape bounds in px). [bodyPrs] are bodyPr elements, most
     * specific first (slide, layout placeholder, master placeholder). Paragraph indices in [hidden]
     * are laid out but not drawn.
     */
    fun draw(
        canvas: Canvas, txBody: Element, box: RectF, bodyPrs: List<Element>, chain: TextChain, env: TextEnv,
        hidden: Set<Int> = emptySet()
    ) {
        val paras = txBody.children("p")
        val hasText = paras.any { p ->
            p.kids().any { (it.tag == "r" || it.tag == "fld") && !it.child("t")?.textContent.isNullOrEmpty() }
        }
        if (!hasText) return
        fun bAttr(name: String) = bodyPrs.firstNotNullOfOrNull { it.attr(name) }
        fun inset(name: String, def: Long) = (bAttr(name)?.toLongOrNull() ?: def) * env.pxPerEmu

        canvas.save()
        var frame = RectF(box)
        val vert = bAttr("vert") ?: "horz"
        if (vert != "horz") {
            canvas.rotate(if (vert == "vert270") -90f else 90f, box.centerX(), box.centerY())
            val cx = box.centerX(); val cy = box.centerY()
            frame = RectF(cx - box.height() / 2, cy - box.width() / 2, cx + box.height() / 2, cy + box.width() / 2)
        }
        val inner = RectF(
            frame.left + inset("lIns", 91440), frame.top + inset("tIns", 45720),
            frame.right - inset("rIns", 91440), frame.bottom - inset("bIns", 45720)
        )
        val width = max(inner.width(), 1f)
        val wrap = bAttr("wrap") != "none"
        val autofit = bodyPrs.firstNotNullOfOrNull { it.firstOf("normAutofit", "spAutoFit", "noAutofit") }
        val shrink = autofit?.tag == "normAutofit"
        var scale = if (shrink) (autofit?.int("fontScale") ?: 100000) / 100000f else 1f
        var lnRed = if (shrink) (autofit?.int("lnSpcReduction") ?: 0) / 100000f else 0f

        var laid = layoutAll(paras, width, wrap, scale, lnRed, chain, env)
        if (shrink) {
            var tries = 0
            while (laid.height > inner.height() * 1.03f && tries++ < 10) {
                scale *= 0.92f
                lnRed = min(0.2f, lnRed + 0.04f)
                laid = layoutAll(paras, width, wrap, scale, lnRed, chain, env)
            }
        }
        val top = when (bAttr("anchor")) {
            "ctr" -> inner.top + (inner.height() - laid.height) / 2
            "b" -> inner.bottom - laid.height
            else -> inner.top
        }
        laid.draw(canvas, inner.left, top, hidden)
        canvas.restore()
    }

    private fun layoutAll(
        paras: List<Element>, width: Float, wrap: Boolean, scale: Float, lnRed: Float, chain: TextChain, env: TextEnv
    ): Laid {
        val k = env.pxPerEmu
        val counters = HashMap<Int, Pair<String, Int>>()
        val out = ArrayList<ParaLayout>(paras.size)
        for (p in paras) {
            val pPr = p.child("pPr")
            val level = (pPr?.int("lvl") ?: 0).coerceIn(0, 8)
            val props = LevelProps(chain, level, pPr)
            val sb = SpannableStringBuilder()
            var first: RunStyle? = null
            var maxSize = 0f
            for (node in p.kids()) {
                val text = when (node.tag) {
                    "r" -> node.child("t")?.textContent ?: ""
                    "fld" -> if (node.attr("type") == "slidenum") env.slideNumber.toString() else node.child("t")?.textContent ?: ""
                    "br" -> "\n"
                    else -> continue
                }
                if (text.isEmpty()) continue
                val st = runStyle(node.child("rPr"), props, scale, env)
                val start = sb.length
                sb.append(if (st.caps) text.uppercase() else text)
                sb.setSpan(st.span, start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (first == null && node.tag != "br") first = st
                maxSize = max(maxSize, st.size)
            }
            val empty = first == null
            if (empty) {
                val st = runStyle(p.child("endParaRPr"), props, scale, env)
                sb.clear()
                sb.append(" ")
                sb.setSpan(st.span, 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                first = st
                maxSize = st.size
            }
            val f = first!!

            // Bullets and numbering.
            for (lv in counters.keys.filter { it > level }) counters.remove(lv)
            var bullet: Bullet? = null
            val buEl = props.pChild("buNone", "buChar", "buAutoNum", "buBlip")
            if (!empty && buEl != null && buEl.tag != "buNone") {
                val buFontEl = props.pChild("buFont", "buFontTx")
                val buFont = if (buFontEl?.tag == "buFont") env.colors.theme.font(buFontEl.attr("typeface")) else null
                val text = when (buEl.tag) {
                    "buChar" -> Fonts.bulletChar(buFont, buEl.attr("char") ?: "•")
                    "buAutoNum" -> {
                        val scheme = buEl.attr("type") ?: "arabicPeriod"
                        val prev = counters[level]
                        val n = if (prev != null && prev.first == scheme) prev.second + 1 else buEl.int("startAt") ?: 1
                        counters[level] = scheme to n
                        autoNumber(scheme, n)
                    }
                    else -> "•"
                }
                val szEl = props.pChild("buSzPct", "buSzPts", "buSzTx")
                val bSize = when (szEl?.tag) {
                    "buSzPct" -> f.size * (szEl.int("val") ?: 100000) / 100000f
                    "buSzPts" -> (szEl.int("val") ?: 1800) / 100f * 12700f * k * scale
                    else -> f.size
                }
                val clrEl = props.pChild("buClr", "buClrTx")
                val color = (if (clrEl?.tag == "buClr") env.colors.of(clrEl) else null) ?: f.color
                val symbolFont = buFont?.lowercase()?.let { it.startsWith("wingdings") || it == "symbol" } == true
                val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    textSize = bSize
                    this.color = color
                    typeface = if (buEl.tag == "buChar" && buFont != null && !symbolFont)
                        Fonts.typeface(buFont, false, false) else f.typeface
                }
                bullet = Bullet(text, paint, 0f, paint.measureText(text))
            } else if (!empty) {
                counters.remove(level)
            }

            // Indents (EMU -> px). Bullets hang in the space left by a negative indent.
            val marL = (props.pAttr("marL")?.toLongOrNull() ?: 0L) * k
            val indent = (props.pAttr("indent")?.toLongOrNull() ?: 0L) * k
            val bulletX = max(0f, marL + indent)
            val firstStart = if (bullet != null) {
                val gap = bullet.paint.textSize * 0.3f
                if (indent < 0 && bulletX + bullet.width + gap * 0.5f <= marL) marL else bulletX + bullet.width + gap
            } else max(0f, marL + indent)
            bullet = bullet?.let { Bullet(it.text, it.paint, bulletX, it.width) }
            sb.setSpan(
                LeadingMarginSpan.Standard(firstStart.toInt(), marL.toInt()), 0, sb.length,
                Spanned.SPAN_INCLUSIVE_INCLUSIVE
            )
            sb.setSpan(TabStopSpan.Standard(max(1, (914400 * k).toInt())), 0, sb.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)

            // Line and paragraph spacing. PowerPoint's single spacing is about 1.2x the font size.
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textSize = maxSize
                typeface = f.typeface
                color = f.color
            }
            val fm = paint.fontMetrics
            val natural = max(fm.descent - fm.ascent, 1f)
            var mult = 1.2f * maxSize / natural
            var add = 0f
            val lnSpc = props.pChild("lnSpc")
            lnSpc?.child("spcPct")?.int("val")?.let { mult *= max(0.4f, it / 100000f - lnRed) }
                ?: lnSpc?.child("spcPts")?.int("val")?.let {
                    mult = 1f
                    add = it / 100f * 12700f * k * scale - natural
                }
                ?: run { mult *= max(0.4f, 1f - lnRed) }
            fun spacing(el: Element?): Float = el?.let { s ->
                s.child("spcPts")?.int("val")?.let { it / 100f * 12700f * k * scale }
                    ?: s.child("spcPct")?.int("val")?.let { it / 100000f * 1.2f * maxSize }
            } ?: 0f

            val algn = props.pAttr("algn") ?: "l"
            val alignment = when (algn) {
                "ctr" -> Layout.Alignment.ALIGN_CENTER
                "r" -> Layout.Alignment.ALIGN_OPPOSITE
                else -> Layout.Alignment.ALIGN_NORMAL
            }
            var layoutWidth = width
            var x = 0f
            if (!wrap) {
                val desired = Layout.getDesiredWidth(sb, paint) + firstStart + 2f
                if (desired > width) {
                    layoutWidth = desired
                    x = when (algn) {
                        "ctr" -> (width - desired) / 2
                        "r" -> width - desired
                        else -> 0f
                    }
                }
            }
            val builder = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, max(1, layoutWidth.toInt()))
                .setAlignment(alignment)
                .setLineSpacing(add, mult)
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            if ((algn == "just" || algn == "dist") && Build.VERSION.SDK_INT >= 26) {
                builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)
            }
            out.add(
                ParaLayout(
                    builder.build(), x, spacing(props.pChild("spcBef")), spacing(props.pChild("spcAft")),
                    bullet, alignment == Layout.Alignment.ALIGN_NORMAL
                )
            )
        }
        return Laid(out)
    }

    private fun runStyle(rPr: Element?, props: LevelProps, scale: Float, env: TextEnv): RunStyle {
        val theme = env.colors.theme
        val sz = props.run(rPr, { it.int("sz") }, { null }) ?: 1800
        val bold = props.run(rPr, { it.bool("b") }, { it.bold }) ?: false
        val italic = props.run(rPr, { it.bool("i") }, { it.italic }) ?: false
        val u = props.run(rPr, { it.attr("u") }, { null })
        val strike = props.run(rPr, { it.attr("strike") }, { null })
        val baseline = props.run(rPr, { it.int("baseline") }, { null }) ?: 0
        val cap = props.run(rPr, { it.attr("cap") }, { null })
        val spc = props.run(rPr, { it.int("spc") }, { null }) ?: 0
        val font = theme.font(props.run(rPr, { it.child("latin")?.attr("typeface") }, { it.font }))
            ?: theme.minorFont
        var color = props.run(rPr, { textFill(it, env.colors) }, { it.color })
            ?: env.colors.scheme("tx1") ?: BLACK
        if (rPr?.child("hlinkClick") != null && rPr.firstOf(*FILL_TAGS) == null) {
            color = env.colors.scheme("hlink") ?: color
        }
        val highlight = rPr?.child("highlight")?.let { env.colors.of(it) } ?: 0
        var size = sz / 100f * 12700f * env.pxPerEmu * scale
        if (baseline != 0) size *= 0.67f
        val tf = Fonts.typeface(font, bold, italic)
        val span = RunSpan(
            size, tf, Fonts.widthFactor(font), if (size > 0) spc / 100f * 12700f * env.pxPerEmu * scale / size else 0f,
            baseline / 100000f, color, u != null && u != "none", strike != null && strike != "noStrike", highlight
        )
        return RunStyle(size, tf, font, color, span, cap == "all" || cap == "small")
    }

    private fun textFill(rPr: Element, colors: ColorCtx): Int? {
        val f = rPr.firstOf(*FILL_TAGS) ?: return null
        return when (f.tag) {
            "solidFill" -> colors.of(f)
            "gradFill" -> f.child("gsLst")?.child("gs")?.let { colors.of(it) }
            "noFill" -> 0
            else -> null
        }
    }

    private fun autoNumber(scheme: String, n: Int): String {
        val body = when {
            scheme.startsWith("romanUc") -> roman(n)
            scheme.startsWith("romanLc") -> roman(n).lowercase()
            scheme.startsWith("alphaUc") -> alpha(n)
            scheme.startsWith("alphaLc") -> alpha(n).lowercase()
            else -> n.toString()
        }
        return when {
            scheme.endsWith("ParenBoth") -> "($body)"
            scheme.endsWith("ParenR") -> "$body)"
            scheme.endsWith("Period") -> "$body."
            else -> body
        }
    }

    private fun roman(n0: Int): String {
        var n = n0.coerceIn(1, 3999)
        val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        val sb = StringBuilder()
        for (i in vals.indices) while (n >= vals[i]) { sb.append(syms[i]); n -= vals[i] }
        return sb.toString()
    }

    private fun alpha(n0: Int): String {
        var n = max(1, n0)
        val sb = StringBuilder()
        while (n > 0) { n--; sb.insert(0, ('A' + n % 26)); n /= 26 }
        return sb.toString()
    }
}
