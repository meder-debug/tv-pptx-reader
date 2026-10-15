package com.tvpptx.reader.pptx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.w3c.dom.Element
import kotlin.math.max
import kotlin.math.min

/** Draws <a:tbl>, including the built-in table styles (header row, banded rows ...). */
object TableRenderer {
    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"

    /** A table-style part (wholeTbl, firstRow, band1H ...) and the cell block it covers. */
    private class StylePart(val el: Element, val r0: Int, val r1: Int, val c0: Int, val c1: Int)

    fun draw(canvas: Canvas, tbl: Element, frame: RectF, part: Part, ctx: DrawCtx) {
        val k = ctx.k
        val cols = tbl.child("tblGrid")?.children("gridCol")?.map { (it.long("w") ?: 0L) * k } ?: return
        val rows = tbl.children("tr")
        if (cols.isEmpty() || rows.isEmpty()) return
        val xs = FloatArray(cols.size + 1).also { a -> a[0] = frame.left; for (i in cols.indices) a[i + 1] = a[i] + cols[i] }
        val ys = FloatArray(rows.size + 1).also { a ->
            a[0] = frame.top
            for (i in rows.indices) a[i + 1] = a[i] + (rows[i].long("h") ?: 0L) * k
        }
        val nR = rows.size
        val nC = cols.size
        val tblPr = tbl.child("tblPr")
        val tblStyle = findStyle(tblPr, ctx)
        val firstRow = tblPr?.bool("firstRow") == true
        val lastRow = tblPr?.bool("lastRow") == true
        val firstCol = tblPr?.bool("firstCol") == true
        val lastCol = tblPr?.bool("lastCol") == true
        val bandRow = tblPr?.bool("bandRow") == true
        val bandCol = tblPr?.bool("bandCol") == true

        fun partsFor(r: Int, c: Int): List<StylePart> {
            if (tblStyle == null) return emptyList()
            val out = ArrayList<StylePart>()
            fun add(name: String, r0: Int, r1: Int, c0: Int, c1: Int) {
                tblStyle.child(name)?.let { out.add(StylePart(it, r0, r1, c0, c1)) }
            }
            add("wholeTbl", 0, nR - 1, 0, nC - 1)
            val bodyR = r - (if (firstRow) 1 else 0)
            if (bandRow && bodyR >= 0 && !(lastRow && r == nR - 1)) add(if (bodyR % 2 == 0) "band1H" else "band2H", r, r, 0, nC - 1)
            val bodyC = c - (if (firstCol) 1 else 0)
            if (bandCol && bodyC >= 0 && !(lastCol && c == nC - 1)) add(if (bodyC % 2 == 0) "band1V" else "band2V", 0, nR - 1, c, c)
            if (firstCol && c == 0) add("firstCol", 0, nR - 1, 0, 0)
            if (lastCol && c == nC - 1) add("lastCol", 0, nR - 1, nC - 1, nC - 1)
            if (firstRow && r == 0) add("firstRow", 0, 0, 0, nC - 1)
            if (lastRow && r == nR - 1) add("lastRow", nR - 1, nR - 1, 0, nC - 1)
            return out.asReversed() // most specific first
        }

        class Cell(val tc: Element, val rect: RectF, val parts: List<StylePart>, val r: Int, val c: Int, val r1: Int, val c1: Int)
        val cells = ArrayList<Cell>()
        rows.forEachIndexed { r, tr ->
            var c = 0
            for (tc in tr.children("tc")) {
                if (c >= nC) break
                if (tc.bool("hMerge") == true || tc.bool("vMerge") == true) { c++; continue }
                val c1 = min(nC - 1, c + (tc.int("gridSpan") ?: 1) - 1)
                val r1 = min(nR - 1, r + (tc.int("rowSpan") ?: 1) - 1)
                cells.add(Cell(tc, RectF(xs[c], ys[r], xs[c1 + 1], ys[r1 + 1]), partsFor(r, c), r, c, r1, c1))
                c = c1 + 1
            }
        }

        val path = Path()
        // Fills
        for (cell in cells) {
            val tcPr = cell.tc.child("tcPr")
            val fill = parseFill(tcPr, ctx.colors, part.rels) ?: cell.parts.firstNotNullOfOrNull { sp ->
                val tcStyle = sp.el.child("tcStyle")
                tcStyle?.child("fill")?.let { parseFill(it, ctx.colors, part.rels) }
                    ?: tcStyle?.child("fillRef")?.let { themeFill(it, ctx.colors) }
            }
            if (fill != null && fill !== Fill.None) {
                path.reset()
                path.addRect(0f, 0f, cell.rect.width(), cell.rect.height(), Path.Direction.CW)
                canvas.save()
                canvas.translate(cell.rect.left, cell.rect.top)
                ctx.renderer.paintFill(canvas, path, fill, cell.rect.width(), cell.rect.height(), "norm")
                canvas.restore()
            }
        }
        // Text
        val master = ctx.slide.master
        for (cell in cells) {
            val txBody = cell.tc.child("txBody") ?: continue
            val tcPr = cell.tc.child("tcPr")
            val bodyPr = tbl.ownerDocument.createElementNS(A_NS, "a:bodyPr").apply {
                setAttribute("lIns", tcPr?.attr("marL") ?: "91440")
                setAttribute("rIns", tcPr?.attr("marR") ?: "91440")
                setAttribute("tIns", tcPr?.attr("marT") ?: "45720")
                setAttribute("bIns", tcPr?.attr("marB") ?: "45720")
                setAttribute("anchor", tcPr?.attr("anchor") ?: "t")
                if (tcPr?.attr("vert") != null) setAttribute("vert", tcPr.attr("vert"))
            }
            val txStyles = cell.parts.mapNotNull { it.el.child("tcTxStyle") }
            val color = txStyles.firstNotNullOfOrNull { ts ->
                ts.firstOf(*COLOR_TAGS)?.let { ctx.colors.resolve(it) } ?: ts.child("fontRef")?.let { ctx.colors.of(it) }
            }
            val defaults = TextDefaults(
                color, txStyles.firstNotNullOfOrNull { it.child("fontRef")?.attr("idx") }?.let { if (it == "major") "+mj-lt" else "+mn-lt" },
                txStyles.firstNotNullOfOrNull { it.bool("b") }, txStyles.firstNotNullOfOrNull { it.bool("i") }
            )
            val sources = listOfNotNull(txBody.child("lstStyle"), master.otherStyle, ctx.pres.defaultTextStyle)
            val own = if (txBody.child("lstStyle") != null) 1 else 0
            TextRenderer.draw(canvas, txBody, cell.rect, listOf(bodyPr), TextChain(sources, own, defaults), ctx.env)
        }
        // Borders
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        for (cell in cells) {
            val tcPr = cell.tc.child("tcPr")
            fun edge(direct: String, outer: String, inner: String, isOuter: (StylePart) -> Boolean): Stroke? {
                tcPr?.child(direct)?.let { ln -> return resolveStroke(listOf(ln), null, ctx.colors) }
                for (sp in cell.parts) {
                    val bdr = sp.el.child("tcStyle")?.child("tcBdr") ?: continue
                    val e = bdr.child(if (isOuter(sp)) outer else inner) ?: continue
                    return resolveStroke(listOfNotNull(e.child("ln")), e.child("lnRef"), ctx.colors)
                }
                return null
            }
            val r = cell.rect
            fun line(s: Stroke?, x0: Float, y0: Float, x1: Float, y1: Float) {
                s ?: return
                paint.color = s.color
                paint.strokeWidth = max(1f, s.widthEmu * k)
                canvas.drawLine(x0, y0, x1, y1, paint)
            }
            line(edge("lnL", "left", "insideV") { cell.c == it.c0 }, r.left, r.top, r.left, r.bottom)
            line(edge("lnR", "right", "insideV") { cell.c1 == it.c1 }, r.right, r.top, r.right, r.bottom)
            line(edge("lnT", "top", "insideH") { cell.r == it.r0 }, r.left, r.top, r.right, r.top)
            line(edge("lnB", "bottom", "insideH") { cell.r1 == it.r1 }, r.left, r.bottom, r.right, r.bottom)
        }
    }

    private fun findStyle(tblPr: Element?, ctx: DrawCtx): Element? {
        tblPr?.child("tableStyle")?.let { return it }
        val id = tblPr?.child("tableStyleId")?.textContentTrim() ?: return null
        ctx.pres.tableStyles?.children("tblStyle")?.firstOrNull { it.attr("styleId") == id }?.let { return it }
        return builtIn(id)
    }

    // PowerPoint knows its built-in table styles by id, so files don't always carry their definitions.
    private val MEDIUM_2 = mapOf(
        "{5C22544A-7EE6-4342-B048-85BDC9FD1C3A}" to "accent1", "{073A0DAA-6AF3-43AB-8588-CEC1D06C72B9}" to "dk1",
        "{21E4AEA4-8DFA-4A89-87EB-49C32662AFE8}" to "accent2", "{F5AB1C69-6EDB-4FF4-983F-18BD219EF322}" to "accent3",
        "{00A15C55-8517-42AA-B614-E9B94910E393}" to "accent4", "{7DF18680-E054-41AD-8BC1-D1AEF772440D}" to "accent5",
        "{93296810-A885-4BE3-A3E7-6D5BEEA58F35}" to "accent6"
    )
    private const val NO_STYLE_NO_GRID = "{2D5ABB26-0587-4C30-8999-92F81FD0307C}"
    private const val NO_STYLE_GRID = "{5940675A-B579-460E-94D1-54222C63F5DA}"
    private val builtIns = HashMap<String, Element?>()

    private fun builtIn(id: String): Element? = synchronized(builtIns) {
        builtIns.getOrPut(id) {
            val xml = when (id) {
                NO_STYLE_NO_GRID -> return@getOrPut null
                NO_STYLE_GRID -> noStyleGrid()
                else -> medium2(MEDIUM_2[id] ?: "accent1") // unknown built-ins: PowerPoint's default look
            }
            Xml.parse(xml.byteInputStream())
        }
    }

    private fun ln(w: Int, clr: String) = """<a:ln w="$w"><a:solidFill><a:schemeClr val="$clr"/></a:solidFill></a:ln>"""

    private fun noStyleGrid(): String {
        val l = ln(12700, "tx1")
        return """<a:tblStyle xmlns:a="$A_NS"><a:wholeTbl><a:tcTxStyle><a:schemeClr val="tx1"/></a:tcTxStyle><a:tcStyle><a:tcBdr>
            <a:left>$l</a:left><a:right>$l</a:right><a:top>$l</a:top><a:bottom>$l</a:bottom><a:insideH>$l</a:insideH><a:insideV>$l</a:insideV>
            </a:tcBdr><a:fill><a:noFill/></a:fill></a:tcStyle></a:wholeTbl></a:tblStyle>"""
    }

    private fun medium2(c: String): String {
        val l = ln(12700, "lt1")
        val strong = """<a:tcTxStyle b="on"><a:fontRef idx="minor"/><a:schemeClr val="lt1"/></a:tcTxStyle>"""
        val solid = """<a:fill><a:solidFill><a:schemeClr val="$c"/></a:solidFill></a:fill>"""
        val band = """<a:tcStyle><a:tcBdr/><a:fill><a:solidFill><a:schemeClr val="$c"><a:tint val="40000"/></a:schemeClr></a:solidFill></a:fill></a:tcStyle>"""
        return """<a:tblStyle xmlns:a="$A_NS">
            <a:wholeTbl><a:tcTxStyle><a:fontRef idx="minor"/><a:schemeClr val="dk1"/></a:tcTxStyle><a:tcStyle><a:tcBdr>
              <a:left>$l</a:left><a:right>$l</a:right><a:top>$l</a:top><a:bottom>$l</a:bottom><a:insideH>$l</a:insideH><a:insideV>$l</a:insideV>
              </a:tcBdr><a:fill><a:solidFill><a:schemeClr val="$c"><a:tint val="20000"/></a:schemeClr></a:solidFill></a:fill></a:tcStyle></a:wholeTbl>
            <a:band1H>$band</a:band1H><a:band1V>$band</a:band1V>
            <a:lastCol>$strong<a:tcStyle><a:tcBdr/>$solid</a:tcStyle></a:lastCol>
            <a:firstCol>$strong<a:tcStyle><a:tcBdr/>$solid</a:tcStyle></a:firstCol>
            <a:lastRow>$strong<a:tcStyle><a:tcBdr><a:top>${ln(38100, "lt1")}</a:top></a:tcBdr>$solid</a:tcStyle></a:lastRow>
            <a:firstRow>$strong<a:tcStyle><a:tcBdr><a:bottom>${ln(38100, "lt1")}</a:bottom></a:tcBdr>$solid</a:tcStyle></a:firstRow>
            </a:tblStyle>"""
    }
}
