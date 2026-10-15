package com.tvpptx.reader.pptx

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Draws the common chart types (bar/column, line, area, scatter, pie, doughnut) from the values
 * PowerPoint caches inside the chart part. It aims for a faithful read of the data, not a pixel copy.
 */
object ChartRenderer {
    private val TYPES = setOf(
        "barChart", "bar3DChart", "lineChart", "line3DChart", "areaChart", "area3DChart",
        "pieChart", "pie3DChart", "doughnutChart", "ofPieChart", "scatterChart", "radarChart", "bubbleChart"
    )

    private class Series(
        val name: String, val cats: List<String>, val vals: List<Double?>, val xs: List<Double?>?,
        val color: Int, val lineColor: Int, val pointColors: Map<Int, Int>, val marker: Boolean, val labels: Boolean
    )

    private class Group(val type: String, val el: Element, val series: List<Series>) {
        val horizontal get() = el.child("barDir")?.attr("val") == "bar"
        val grouping get() = el.child("grouping")?.attr("val") ?: "clustered"
        val isBar get() = type.startsWith("bar")
        val isPie get() = type.startsWith("pie") || type == "doughnutChart" || type == "ofPieChart"
    }

    fun draw(canvas: Canvas, space: Element, frame: RectF, ctx: DrawCtx) {
        val chart = space.child("chart") ?: return
        val k = ctx.k
        val colors = ctx.colors
        parseFill(space.child("spPr"), colors, emptyMap())?.let { fill ->
            val p = Path().apply { addRect(0f, 0f, frame.width(), frame.height(), Path.Direction.CW) }
            canvas.save(); canvas.translate(frame.left, frame.top)
            ctx.renderer.paintFill(canvas, p, fill, frame.width(), frame.height(), "norm")
            canvas.restore()
        }
        val defRPr = space.child("txPr")?.let { firstDescendant(it, "defRPr") }
        val textColor = defRPr?.let { colors.of(it.child("solidFill")) } ?: 0xFF595959.toInt()
        val textSize = (defRPr?.int("sz") ?: 1000) / 100f * 12700f * k
        val plotArea = chart.child("plotArea") ?: return
        val groups = plotArea.kids().filter { it.tag in TYPES }.mapIndexed { gi, g -> parseGroup(g, gi, colors) }
        val all = groups.flatMap { it.series }
        if (all.isEmpty()) return

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = textColor; this.textSize = textSize }
        var area = RectF(frame.left + textSize * 0.6f, frame.top + textSize * 0.6f, frame.right - textSize * 0.6f, frame.bottom - textSize * 0.6f)

        // Title
        val titleEl = chart.child("title")
        val deleted = chart.child("autoTitleDeleted")?.bool("val") == true
        var title = titleEl?.let { richText(it) }
        if (titleEl != null && title.isNullOrBlank() && all.size == 1) title = all[0].name
        if (titleEl == null && !deleted && all.size == 1 && groups[0].isPie) title = all[0].name
        if (!title.isNullOrBlank()) {
            val tSz = (titleEl?.let { firstDescendant(it, "defRPr")?.int("sz") ?: firstDescendant(it, "rPr")?.int("sz") } ?: 1800) / 100f * 12700f * k
            val tp = Paint(text).apply {
                this.textSize = tSz
                typeface = Typeface.DEFAULT
                textAlign = Paint.Align.CENTER
                color = titleEl?.let { firstDescendant(it, "defRPr")?.let { d -> colors.of(d.child("solidFill")) } } ?: textColor
            }
            canvas.drawText(ellipsize(title, tp, area.width()), area.centerX(), area.top + tSz, tp)
            area = RectF(area.left, area.top + tSz * 1.6f, area.right, area.bottom)
        }

        // Legend
        val legend = chart.child("legend")
        if (legend != null) {
            val pos = legend.child("legendPos")?.attr("val") ?: "r"
            val pie = groups[0].isPie
            val entries: List<Pair<String, Int>> = if (pie) {
                val s = groups[0].series[0]
                s.cats.indices.map { i -> s.cats[i] to (s.pointColors[i] ?: palette(i, colors)) }
            } else all.map { it.name to it.color }
            area = drawLegend(canvas, entries, pos, area, text)
        }

        if (groups[0].isPie) drawPie(canvas, groups[0], area, text, colors) else drawAxes(canvas, groups, plotArea, area, text, colors)
    }

    private fun parseGroup(g: Element, gi: Int, colors: ColorCtx): Group {
        val type = g.tag
        val vary = g.child("varyColors")?.bool("val") ?: false
        val isLine = type.startsWith("line") || type == "scatterChart" || type == "radarChart"
        val groupMarker = g.child("marker")?.bool("val") ?: true
        val groupLabels = g.child("dLbls")?.child("showVal")?.bool("val") == true
        val series = g.children("ser").mapIndexed { i, s ->
            val idx = s.child("idx")?.int("val") ?: (i + gi * 10)
            val name = s.child("tx")?.let { tx ->
                tx.child("strRef")?.let { strList(it).firstOrNull() } ?: tx.child("v")?.textContentTrim()
            } ?: "Series ${i + 1}"
            val spPr = s.child("spPr")
            val fillColor = spPr?.child("solidFill")?.let { colors.of(it) }
                ?: spPr?.child("gradFill")?.child("gsLst")?.child("gs")?.let { colors.of(it) }
            val lnColor = spPr?.child("ln")?.child("solidFill")?.let { colors.of(it) }
            val base = (if (isLine) lnColor ?: fillColor else fillColor ?: lnColor) ?: palette(idx, colors)
            val points = HashMap<Int, Int>()
            for (dPt in s.children("dPt")) {
                val pi = dPt.child("idx")?.int("val") ?: continue
                dPt.child("spPr")?.child("solidFill")?.let { colors.of(it) }?.let { points[pi] = it }
            }
            val vals = (s.child("val") ?: s.child("yVal"))?.let { numList(it) } ?: emptyList()
            if (vary) for (pi in vals.indices) points.putIfAbsent(pi, palette(pi, colors))
            val cats = (s.child("cat") ?: s.child("xVal"))?.let { strList(it) } ?: emptyList()
            val symbol = s.child("marker")?.child("symbol")?.attr("val")
            Series(
                name, cats.ifEmpty { vals.indices.map { (it + 1).toString() } }, vals,
                if (type == "scatterChart") s.child("xVal")?.let { numList(it) } else null,
                base, lnColor ?: base, points,
                groupMarker && symbol != "none" &&
                    (type != "scatterChart" || symbol != null || g.child("scatterStyle")?.attr("val")?.contains("arker") == true),
                s.child("dLbls")?.child("showVal")?.bool("val") ?: groupLabels
            )
        }
        return Group(type, g, series)
    }

    private fun drawLegend(canvas: Canvas, entries: List<Pair<String, Int>>, pos: String, area: RectF, text: Paint): RectF {
        val sw = text.textSize * 0.8f
        val gap = text.textSize * 0.5f
        val sq = Paint(Paint.ANTI_ALIAS_FLAG)
        val fm = text.fontMetrics
        if (pos == "r" || pos == "l" || pos == "tr") {
            val w = min(area.width() * 0.35f, (entries.maxOfOrNull { text.measureText(it.first) } ?: 0f) + sw + gap * 2)
            val lineH = text.textSize * 1.5f
            val total = lineH * entries.size
            var y = area.centerY() - total / 2
            val x = if (pos == "l") area.left else area.right - w
            for ((label, c) in entries) {
                sq.color = c
                canvas.drawRect(x, y + (lineH - sw) / 2, x + sw, y + (lineH + sw) / 2, sq)
                canvas.drawText(ellipsize(label, text, w - sw - gap), x + sw + gap, y + lineH / 2 - (fm.ascent + fm.descent) / 2, text)
                y += lineH
            }
            return if (pos == "l") RectF(area.left + w + gap, area.top, area.right, area.bottom)
            else RectF(area.left, area.top, area.right - w - gap, area.bottom)
        }
        // top / bottom: one centred row (wrapping is rare enough to ignore)
        val widths = entries.map { min(text.measureText(it.first), area.width() / max(1, entries.size)) + sw + gap * 2 }
        var x = area.centerX() - widths.sum() / 2
        val lineH = text.textSize * 1.6f
        val y = if (pos == "t") area.top else area.bottom - lineH
        entries.forEachIndexed { i, (label, c) ->
            sq.color = c
            canvas.drawRect(x, y + (lineH - sw) / 2, x + sw, y + (lineH + sw) / 2, sq)
            canvas.drawText(ellipsize(label, text, widths[i] - sw - gap * 2), x + sw + gap / 2, y + lineH / 2 - (fm.ascent + fm.descent) / 2, text)
            x += widths[i]
        }
        return if (pos == "t") RectF(area.left, area.top + lineH, area.right, area.bottom)
        else RectF(area.left, area.top, area.right, area.bottom - lineH)
    }

    private fun drawPie(canvas: Canvas, g: Group, area: RectF, text: Paint, colors: ColorCtx) {
        val s = g.series.firstOrNull() ?: return
        val vals = s.vals.map { max(0.0, it ?: 0.0) }
        val total = vals.sum()
        if (total <= 0) return
        val r = min(area.width(), area.height()) / 2 * 0.92f
        val oval = RectF(area.centerX() - r, area.centerY() - r, area.centerX() + r, area.centerY() + r)
        val hole = if (g.type == "doughnutChart") (g.el.child("holeSize")?.int("val") ?: 50).coerceIn(10, 90) / 100f else 0f
        var angle = (g.el.child("firstSliceAng")?.int("val") ?: 0) - 90f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val sep = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = WHITE; strokeWidth = max(1f, r / 120f) }
        val showPct = g.el.child("dLbls")?.child("showPercent")?.bool("val") == true
        val showVal = s.labels
        val label = Paint(text).apply { textAlign = Paint.Align.CENTER }
        vals.forEachIndexed { i, v ->
            val sweep = (v / total * 360).toFloat()
            p.color = s.pointColors[i] ?: palette(i, colors)
            val path = Path()
            if (hole > 0) {
                val inner = RectF(oval).apply { inset(r * (1 - hole), r * (1 - hole)) }
                path.arcTo(oval, angle, sweep, true)
                path.arcTo(inner, angle + sweep, -sweep, false)
                path.close()
            } else {
                path.moveTo(oval.centerX(), oval.centerY())
                path.arcTo(oval, angle, sweep, false)
                path.close()
            }
            canvas.drawPath(path, p)
            canvas.drawPath(path, sep)
            if ((showPct || showVal) && sweep > 8) {
                val mid = Math.toRadians((angle + sweep / 2).toDouble())
                val lr = if (hole > 0) r * (1 - (1 - hole) / 2) else r * 0.65f
                val tx = oval.centerX() + (lr * kotlin.math.cos(mid)).toFloat()
                val ty = oval.centerY() + (lr * kotlin.math.sin(mid)).toFloat()
                label.color = if (luminance(p.color) < 0.55f) WHITE else 0xFF262626.toInt()
                val str = if (showPct) "${Math.round(v / total * 100)}%" else fmt(v, null)
                canvas.drawText(str, tx, ty - (label.fontMetrics.ascent + label.fontMetrics.descent) / 2, label)
            }
            angle += sweep
        }
    }

    private fun drawAxes(canvas: Canvas, groups: List<Group>, plotArea: Element, area0: RectF, text: Paint, colors: ColorCtx) {
        val valAx = plotArea.children("valAx")
        val catAx = plotArea.child("catAx") ?: plotArea.child("dateAx")
        val scatter = groups.any { it.type == "scatterChart" }
        val horizontal = groups.any { it.isBar && it.horizontal }
        val pct = groups.any { it.grouping == "percentStacked" }
        val numFmt = valAx.firstOrNull()?.child("numFmt")?.attr("formatCode")?.let { if (pct && !it.contains("%")) "0%" else it }
            ?: if (pct) "0%" else null

        // Value range, honouring stacking
        var lo = 0.0
        var hi = 0.0
        for (g in groups) {
            val n = g.series.maxOfOrNull { it.vals.size } ?: 0
            if (g.grouping == "percentStacked") { hi = max(hi, 1.0); continue }
            if (g.grouping == "stacked" && (g.isBar || g.type.startsWith("area") || g.type.startsWith("line"))) {
                for (i in 0 until n) {
                    var pos = 0.0; var neg = 0.0
                    for (s in g.series) { val v = s.vals.getOrNull(i) ?: 0.0; if (v >= 0) pos += v else neg += v }
                    hi = max(hi, pos); lo = min(lo, neg)
                }
            } else for (s in g.series) for (v in s.vals) if (v != null) { hi = max(hi, v); lo = min(lo, v) }
        }
        val scaling = valAx.firstOrNull()?.child("scaling")
        scaling?.child("min")?.attr("val")?.toDoubleOrNull()?.let { lo = it }
        scaling?.child("max")?.attr("val")?.toDoubleOrNull()?.let { hi = it }
        if (hi <= lo) hi = lo + 1
        val step = niceStep((hi - lo) / 5)
        val vMin = if (scaling?.child("min") != null) lo else floor(lo / step) * step
        val vMax = if (scaling?.child("max") != null) hi else ceil(hi / step - 1e-9) * step

        val cats = groups.flatMap { it.series }.maxByOrNull { it.cats.size }?.cats ?: emptyList()
        val nCats = max(1, groups.flatMap { it.series }.maxOf { max(it.vals.size, it.cats.size) })
        val reversed = catAx?.child("scaling")?.child("orientation")?.attr("val") == "maxMin"
        val showValLabels = valAx.firstOrNull()?.child("delete")?.bool("val") != true
        val showCatLabels = catAx?.child("delete")?.bool("val") != true

        // Scatter X range
        var xLo = 0.0; var xHi = 1.0
        if (scatter) {
            val xsAll = groups.flatMap { g -> g.series.flatMap { it.xs ?: it.vals.indices.map { i -> (i + 1).toDouble() } } }.filterNotNull()
            if (xsAll.isNotEmpty()) { xLo = min(0.0, xsAll.min()); xHi = xsAll.max() }
            if (xHi <= xLo) xHi = xLo + 1
            val xs = niceStep((xHi - xLo) / 5); xLo = floor(xLo / xs) * xs; xHi = ceil(xHi / xs) * xs
        }

        val ticks = ArrayList<Double>()
        run { var v = vMin; var guard = 0; while (v <= vMax + step * 1e-6 && guard++ < 50) { ticks.add(v); v += step } }
        val tickLabels = ticks.map { fmt(it, numFmt) }
        val lineH = text.textSize * 1.4f
        val valLabelW = if (showValLabels) (tickLabels.maxOfOrNull { text.measureText(it) } ?: 0f) + text.textSize * 0.6f else 0f
        val catLabelW = if (showCatLabels && horizontal) min(area0.width() * 0.3f, (cats.maxOfOrNull { text.measureText(it) } ?: 0f) + text.textSize * 0.6f) else 0f
        val plot = if (horizontal) RectF(area0.left + catLabelW, area0.top, area0.right - text.textSize, area0.bottom - (if (showValLabels) lineH else 0f))
        else RectF(area0.left + valLabelW, area0.top + text.textSize * 0.5f, area0.right, area0.bottom - (if (showCatLabels) lineH else 0f))
        if (plot.width() <= 4 || plot.height() <= 4) return

        fun vPos(v: Double): Float = if (horizontal) (plot.left + (v - vMin) / (vMax - vMin) * plot.width()).toFloat()
        else (plot.bottom - (v - vMin) / (vMax - vMin) * plot.height()).toFloat()
        val catSpan = (if (horizontal) plot.height() else plot.width()) / nCats
        fun catStart(i: Int): Float {
            val idx = if (reversed) nCats - 1 - i else i
            return if (horizontal) plot.bottom - (idx + 1) * catSpan else plot.left + idx * catSpan
        }
        fun xPos(x: Double) = (plot.left + (x - xLo) / (xHi - xLo) * plot.width()).toFloat()

        val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFD9D9D9.toInt(); strokeWidth = max(1f, text.textSize / 14) }
        val hasGrid = valAx.firstOrNull()?.child("majorGridlines") != null
        val tl = Paint(text)
        ticks.forEachIndexed { i, t ->
            val pz = vPos(t)
            if (hasGrid) {
                if (horizontal) canvas.drawLine(pz, plot.top, pz, plot.bottom, grid) else canvas.drawLine(plot.left, pz, plot.right, pz, grid)
            }
            if (showValLabels) {
                if (horizontal) { tl.textAlign = Paint.Align.CENTER; canvas.drawText(tickLabels[i], pz, plot.bottom + lineH * 0.85f, tl) }
                else { tl.textAlign = Paint.Align.RIGHT; canvas.drawText(tickLabels[i], plot.left - text.textSize * 0.4f, pz + text.textSize * 0.35f, tl) }
            }
        }
        if (showCatLabels && !scatter) {
            val every = if (horizontal) max(1, ceil(lineH / catSpan).toInt())
            else max(1, ceil((cats.maxOfOrNull { text.measureText(it) } ?: 0f) * 1.1f / catSpan).toInt())
            for (i in cats.indices step every) {
                val s0 = catStart(i)
                if (horizontal) { tl.textAlign = Paint.Align.RIGHT; canvas.drawText(ellipsize(cats[i], tl, catLabelW - text.textSize * 0.4f), plot.left - text.textSize * 0.4f, s0 + catSpan / 2 + text.textSize * 0.35f, tl) }
                else { tl.textAlign = Paint.Align.CENTER; canvas.drawText(ellipsize(cats[i], tl, catSpan * every), s0 + catSpan / 2, plot.bottom + lineH * 0.85f, tl) }
            }
        } else if (scatter && showCatLabels) {
            val xs = niceStep((xHi - xLo) / 5)
            var x = xLo
            tl.textAlign = Paint.Align.CENTER
            while (x <= xHi + xs * 1e-6) { canvas.drawText(fmt(x, null), xPos(x), plot.bottom + lineH * 0.85f, tl); x += xs }
        }
        val axis = Paint(grid).apply { color = 0xFFBFBFBF.toInt() }
        val zero = vPos(max(vMin, min(vMax, 0.0)))
        if (horizontal) canvas.drawLine(zero, plot.top, zero, plot.bottom, axis) else canvas.drawLine(plot.left, zero, plot.right, zero, axis)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = max(1.5f, text.textSize / 5); strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
        val lbl = Paint(text).apply { textAlign = Paint.Align.CENTER }
        for (g in groups) {
            val n = g.series.size
            val totals = DoubleArray(nCats) { i -> g.series.sumOf { abs(it.vals.getOrNull(i) ?: 0.0) } }
            fun value(s: Series, i: Int): Double? {
                val v = s.vals.getOrNull(i) ?: return null
                return if (g.grouping == "percentStacked") (if (totals[i] > 0) v / totals[i] else 0.0) else v
            }
            when {
                g.isBar -> {
                    val gap = (g.el.child("gapWidth")?.int("val") ?: 150) / 100f
                    val stacked = g.grouping == "stacked" || g.grouping == "percentStacked"
                    val groupW = catSpan / (1 + gap)
                    val barW = if (stacked) groupW else groupW / n
                    for (i in 0 until nCats) {
                        var pos = 0.0; var neg = 0.0
                        g.series.forEachIndexed { si, s ->
                            val v = value(s, i) ?: return@forEachIndexed
                            val base: Double
                            val top: Double
                            if (stacked) { if (v >= 0) { base = pos; pos += v; top = pos } else { base = neg; neg += v; top = neg } }
                            else { base = 0.0; top = v }
                            val off = catStart(i) + (catSpan - groupW) / 2 + (if (stacked) 0f else si * barW)
                            fill.color = s.pointColors[i] ?: s.color
                            val a = vPos(base); val b = vPos(top)
                            val r = if (horizontal) RectF(min(a, b), off, max(a, b), off + barW) else RectF(off, min(a, b), off + barW, max(a, b))
                            canvas.drawRect(r, fill)
                            if (s.labels) {
                                lbl.color = text.color
                                val str = fmt(s.vals[i] ?: 0.0, if (g.grouping == "percentStacked") null else numFmt)
                                if (horizontal) canvas.drawText(str, r.right + text.textSize * 1.2f, r.centerY() + text.textSize * 0.35f, lbl)
                                else if (stacked) { lbl.color = if (luminance(fill.color) < 0.55f) WHITE else 0xFF262626.toInt(); canvas.drawText(str, r.centerX(), r.centerY() + text.textSize * 0.35f, lbl) }
                                else canvas.drawText(str, r.centerX(), r.top - text.textSize * 0.3f, lbl)
                            }
                        }
                    }
                }
                g.type.startsWith("line") || g.type.startsWith("area") || g.type == "scatterChart" || g.type == "radarChart" -> {
                    val stacked = g.grouping == "stacked" || g.grouping == "percentStacked"
                    val cum = DoubleArray(nCats)
                    for (s in g.series) {
                        val pts = ArrayList<Pair<Float, Float>>()
                        val baseLine = ArrayList<Pair<Float, Float>>()
                        for (i in 0 until nCats) {
                            val v = value(s, i) ?: continue
                            val x = if (g.type == "scatterChart") xPos(s.xs?.getOrNull(i) ?: (i + 1).toDouble()) else catStart(i) + catSpan / 2
                            val y0 = if (stacked) cum[i] else 0.0
                            val y = if (stacked) cum[i] + v else v
                            if (stacked) cum[i] = y
                            pts.add(x to vPos(y)); baseLine.add(x to vPos(y0))
                        }
                        if (pts.isEmpty()) continue
                        if (g.type.startsWith("area")) {
                            val path = Path()
                            path.moveTo(pts[0].first, pts[0].second)
                            for (pp in pts.drop(1)) path.lineTo(pp.first, pp.second)
                            for (pp in baseLine.asReversed()) path.lineTo(pp.first, pp.second)
                            path.close()
                            fill.color = s.color
                            canvas.drawPath(path, fill)
                        } else {
                            val noLine = g.type == "scatterChart" && g.el.child("scatterStyle")?.attr("val") == "marker"
                            if (!noLine) {
                                line.color = s.lineColor
                                val path = Path()
                                path.moveTo(pts[0].first, pts[0].second)
                                for (pp in pts.drop(1)) path.lineTo(pp.first, pp.second)
                                canvas.drawPath(path, line)
                            }
                            if (s.marker || noLine) {
                                fill.color = s.color
                                for (pp in pts) canvas.drawCircle(pp.first, pp.second, line.strokeWidth * 1.6f, fill)
                            }
                        }
                        if (s.labels) {
                            lbl.color = text.color
                            pts.forEachIndexed { i, pp -> s.vals.getOrNull(i)?.let { canvas.drawText(fmt(it, numFmt), pp.first, pp.second - text.textSize * 0.6f, lbl) } }
                        }
                    }
                }
            }
        }
    }

    private fun palette(i: Int, colors: ColorCtx): Int {
        val base = colors.scheme("accent${i % 6 + 1}") ?: 0xFF4472C4.toInt()
        return when ((i / 6) % 3) {
            1 -> blend(base, BLACK, 0.4f)
            2 -> blend(base, WHITE, 0.4f)
            else -> base
        }
    }

    private fun niceStep(raw: Double): Double {
        if (raw <= 0 || raw.isNaN()) return 1.0
        val mag = 10.0.pow(floor(log10(raw)))
        val n = raw / mag
        return mag * when {
            n <= 1 -> 1.0
            n <= 2 -> 2.0
            n <= 2.5 -> 2.5
            n <= 5 -> 5.0
            else -> 10.0
        }
    }

    private fun fmt(v: Double, code: String?): String {
        if (code != null && code.contains("%")) {
            val decimals = code.substringAfter('.', "").takeWhile { it == '0' }.length
            return String.format("%.${decimals}f%%", v * 100)
        }
        val decimals = code?.substringAfter('.', "")?.takeWhile { it == '0' }?.length
        if (decimals != null && code.contains('.')) return String.format("%,.${decimals}f", v)
        if (abs(v - Math.rint(v)) < 1e-9) return String.format("%,d", v.toLong())
        return if (abs(v) >= 100) String.format("%,.0f", v) else String.format("%.2f", v).trimEnd('0').trimEnd('.')
    }

    private fun ellipsize(s: String, p: Paint, w: Float): String {
        if (w <= 0) return ""
        if (p.measureText(s) <= w) return s
        var end = s.length
        while (end > 0 && p.measureText(s, 0, end) + p.measureText("…") > w) end--
        return if (end <= 0) "" else s.substring(0, end) + "…"
    }

    private fun richText(title: Element): String? {
        val rich = title.child("tx")?.child("rich") ?: return title.child("tx")?.child("strRef")?.let { strList(it).firstOrNull() }
        return rich.children("p").joinToString(" ") { p -> p.children("r").joinToString("") { it.child("t")?.textContent ?: "" } }.trim()
    }

    private fun firstDescendant(el: Element, name: String): Element? {
        for (c in el.kids()) {
            if (c.tag == name) return c
            firstDescendant(c, name)?.let { return it }
        }
        return null
    }

    private fun cache(ref: Element): Element? =
        firstDescendant(ref, "strCache") ?: firstDescendant(ref, "numCache") ?: firstDescendant(ref, "strLit")
            ?: firstDescendant(ref, "numLit") ?: firstDescendant(ref, "lvl")

    private fun strList(ref: Element): List<String> {
        val c = cache(ref) ?: return emptyList()
        val count = c.child("ptCount")?.int("val") ?: 0
        val pts = c.children("pt")
        val n = max(count, (pts.maxOfOrNull { it.int("idx") ?: 0 } ?: -1) + 1)
        val out = MutableList(n) { "" }
        for (pt in pts) { val i = pt.int("idx") ?: continue; if (i in 0 until n) out[i] = pt.child("v")?.textContent ?: "" }
        return out
    }

    private fun numList(ref: Element): List<Double?> {
        val c = cache(ref) ?: return emptyList()
        val count = c.child("ptCount")?.int("val") ?: 0
        val pts = c.children("pt")
        val n = max(count, (pts.maxOfOrNull { it.int("idx") ?: 0 } ?: -1) + 1)
        val out = MutableList<Double?>(n) { null }
        for (pt in pts) { val i = pt.int("idx") ?: continue; if (i in 0 until n) out[i] = pt.child("v")?.textContent?.trim()?.toDoubleOrNull() }
        return out
    }
}
