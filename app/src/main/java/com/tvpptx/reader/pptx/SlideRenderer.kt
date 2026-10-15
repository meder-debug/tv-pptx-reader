package com.tvpptx.reader.pptx

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import org.w3c.dom.Element
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Maps a group's child coordinate space (EMU) onto slide coordinates (EMU). */
class Xf(val ox: Double, val oy: Double, val sx: Double, val sy: Double) {
    fun x(v: Double) = ox + v * sx
    fun y(v: Double) = oy + v * sy

    companion object {
        val IDENTITY = Xf(0.0, 0.0, 1.0, 1.0)
    }
}

/** Shared state for drawing one slide. */
class DrawCtx(
    val slide: SlidePart, val colors: ColorCtx, val k: Float, val pres: Presentation, val renderer: SlideRenderer
) {
    val env = TextEnv(colors, k, slide.number)
    var background: Fill? = null
}

class SlideRenderer(private val pres: Presentation, private val images: ImageCache) {
    /** Pixels per EMU for the slide being drawn. */
    private var kNow = 1f

    fun render(index: Int, width: Int, height: Int): Bitmap {
        val slide = pres.slide(index)
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val k = min(width.toFloat() / pres.slideW, height.toFloat() / pres.slideH)
        val ctx = DrawCtx(slide, ColorCtx(slide.master.theme, slide.clrMap), k, pres, this)
        kNow = k
        drawBackground(canvas, ctx, width.toFloat(), height.toFloat())
        val master = slide.master
        val layout = slide.layout
        if (slide.showMasterShapes && layout.showMasterShapes) drawTree(canvas, master.shapes, master, ctx, Xf.IDENTITY, null)
        if (slide.showMasterShapes) drawTree(canvas, layout.shapes, layout, ctx, Xf.IDENTITY, null)
        drawTree(canvas, slide.shapes, slide, ctx, Xf.IDENTITY, null)
        return bmp
    }

    private fun drawBackground(canvas: Canvas, ctx: DrawCtx, w: Float, h: Float) {
        canvas.drawColor(WHITE)
        val slide = ctx.slide
        val owner: Part = listOf(slide, slide.layout, slide.master).firstOrNull { it.bg != null } ?: return
        val bg = owner.bg!!
        val fill = bg.child("bgPr")?.let { parseFill(it, ctx.colors, owner.rels) }
            ?: bg.child("bgRef")?.let { themeFill(it, ctx.colors) }
            ?: return
        ctx.background = fill
        val path = Path().apply { addRect(0f, 0f, w, h, Path.Direction.CW) }
        paintFill(canvas, path, fill, w, h, "norm")
    }

    fun drawTree(canvas: Canvas, shapes: List<Element>, part: Part, ctx: DrawCtx, xf: Xf, groupFill: Fill?) {
        for (el in shapes) {
            try {
                drawElement(canvas, el, part, ctx, xf, groupFill)
            } catch (e: Exception) {
                // One odd shape should never take down the whole slide.
            } catch (e: OutOfMemoryError) {
                images.trim()
            }
        }
    }

    private fun drawElement(canvas: Canvas, el: Element, part: Part, ctx: DrawCtx, xf: Xf, groupFill: Fill?) {
        when (el.tag) {
            "sp", "cxnSp" -> drawShape(canvas, el, part, ctx, xf, groupFill)
            "pic" -> drawPicture(canvas, el, part, ctx, xf)
            "grpSp" -> drawGroup(canvas, el, part, ctx, xf, groupFill)
            "graphicFrame" -> drawGraphicFrame(canvas, el, part, ctx, xf)
            "AlternateContent" -> {
                val content = el.child("Fallback")?.kids()?.takeIf { it.isNotEmpty() } ?: el.child("Choice")?.kids()
                content?.let { drawTree(canvas, it, part, ctx, xf, groupFill) }
            }
        }
    }

    private fun isHidden(el: Element) =
        el.kids().firstOrNull { it.tag.startsWith("nv") }?.child("cNvPr")?.bool("hidden") == true

    /** Layout and master shapes a slide placeholder inherits from, most specific first. */
    private fun inherited(part: Part, ph: Element?): List<Pair<Element, Part>> {
        if (ph == null || part !is SlidePart) return emptyList()
        val out = ArrayList<Pair<Element, Part>>(2)
        val lay = part.layout.findPlaceholder(ph, byTypeOnly = false)
        lay?.let { out.add(it to part.layout) }
        part.master.findPlaceholder(lay?.let { phOf(it) } ?: ph, byTypeOnly = true)?.let { out.add(it to part.master) }
        return out
    }

    private fun rectOf(xfrm: Element?, xf: Xf, k: Float): RectF? {
        val off = xfrm?.child("off") ?: return null
        val ext = xfrm.child("ext")
        val x = off.long("x")?.toDouble() ?: 0.0
        val y = off.long("y")?.toDouble() ?: 0.0
        val cx = ext?.long("cx")?.toDouble() ?: 0.0
        val cy = ext?.long("cy")?.toDouble() ?: 0.0
        val l = xf.x(x) * k
        val t = xf.y(y) * k
        return RectF(l.toFloat(), t.toFloat(), (l + cx * xf.sx * k).toFloat(), (t + cy * xf.sy * k).toFloat())
    }

    private inline fun withTransform(canvas: Canvas, xfrm: Element?, r: RectF, flipContent: Boolean, body: () -> Unit) {
        canvas.save()
        val rot = (xfrm?.int("rot") ?: 0) / 60000f
        if (rot != 0f) canvas.rotate(rot, r.centerX(), r.centerY())
        if (flipContent) {
            val fh = xfrm?.bool("flipH") == true
            val fv = xfrm?.bool("flipV") == true
            if (fh || fv) canvas.scale(if (fh) -1f else 1f, if (fv) -1f else 1f, r.centerX(), r.centerY())
        }
        body()
        canvas.restore()
    }

    private fun drawShape(canvas: Canvas, el: Element, part: Part, ctx: DrawCtx, xf: Xf, groupFill: Fill?) {
        if (isHidden(el)) return
        val ph = phOf(el)
        if (ph != null && part !is SlidePart) return // layout/master placeholders are prompts, never shown
        val chain = listOf(el to part) + inherited(part, ph)
        val spPrs = chain.mapNotNull { (s, p) -> s.child("spPr")?.let { it to p } }
        val xfrm = spPrs.firstNotNullOfOrNull { it.first.child("xfrm")?.takeIf { x -> x.child("off") != null } }
        val r = rectOf(xfrm, xf, ctx.k) ?: return
        val geomSpPr = spPrs.firstOrNull { it.first.firstOf("prstGeom", "custGeom") != null }?.first
        val style = chain.firstNotNullOfOrNull { it.first.child("style") }

        var fill: Fill? = spPrs.firstNotNullOfOrNull { (sp, p) -> parseFill(sp, ctx.colors, p.rels) }
        if (fill == null && el.bool("useBgFill") == true) fill = ctx.background
        if (fill == null) fill = style?.child("fillRef")?.let { themeFill(it, ctx.colors) }
        if (fill === Fill.Group) fill = groupFill
        val stroke = resolveStroke(spPrs.mapNotNull { it.first.child("ln") }, style?.child("lnRef"), ctx.colors)

        val w = r.width()
        val h = r.height()
        val geom = buildGeometry(geomSpPr, w, h)
        withTransform(canvas, xfrm, r, flipContent = false) {
            canvas.save()
            val fh = xfrm?.bool("flipH") == true
            val fv = xfrm?.bool("flipV") == true
            if (fh || fv) canvas.scale(if (fh) -1f else 1f, if (fv) -1f else 1f, r.centerX(), r.centerY())
            canvas.translate(r.left, r.top)
            if (fill != null && fill !== Fill.None) {
                for (gp in geom.paths) if (gp.fill != "none") paintFill(canvas, gp.path, fill, w, h, gp.fill)
            }
            if (stroke != null) drawStroke(canvas, geom, stroke, ctx.k)
            canvas.restore()

            val txBody = el.child("txBody")
            if (txBody != null) {
                val txXfrm = el.child("txXfrm")
                val box = rectOf(txXfrm, xf, ctx.k)
                    ?: geom.textRect?.let { RectF(it.left + r.left, it.top + r.top, it.right + r.left, it.bottom + r.top) }
                    ?: r
                val bodyPrs = listOfNotNull(txBody.child("bodyPr")) +
                    chain.drop(1).mapNotNull { it.first.child("txBody")?.child("bodyPr") }
                TextRenderer.draw(canvas, txBody, box, bodyPrs, textChain(el, ph, chain, style, ctx), ctx.env)
            }
        }
    }

    private fun textChain(el: Element, ph: Element?, chain: List<Pair<Element, Part>>, style: Element?, ctx: DrawCtx): TextChain {
        val sources = ArrayList<Element>()
        el.child("txBody")?.child("lstStyle")?.let { sources.add(it) }
        val own = sources.size
        for ((s, _) in chain.drop(1)) s.child("txBody")?.child("lstStyle")?.let { sources.add(it) }
        val master = ctx.slide.master
        val masterStyle = if (ph != null) master.styleFor(ph.attr("type")) else master.otherStyle
        masterStyle?.let { sources.add(it) }
        ctx.pres.defaultTextStyle?.let { sources.add(it) }
        val fontRef = style?.child("fontRef")
        val defaults = fontRef?.let {
            TextDefaults(ctx.colors.of(it), if (it.attr("idx") == "major") "+mj-lt" else "+mn-lt")
        }
        return TextChain(sources, own, defaults)
    }

    private fun drawPicture(canvas: Canvas, el: Element, part: Part, ctx: DrawCtx, xf: Xf) {
        if (isHidden(el)) return
        val ph = phOf(el)
        if (ph != null && part !is SlidePart) return
        val chain = listOf(el to part) + inherited(part, ph)
        val spPrs = chain.mapNotNull { it.first.child("spPr") }
        val xfrm = spPrs.firstNotNullOfOrNull { it.child("xfrm")?.takeIf { x -> x.child("off") != null } }
        val r = rectOf(xfrm, xf, ctx.k) ?: return
        val blipFill = el.child("blipFill") ?: return
        val fill = fillOf(blipFill, ctx.colors, part.rels) as? Fill.Picture ?: return
        val geomSpPr = spPrs.firstOrNull { it.firstOf("prstGeom", "custGeom") != null }
        val geom = buildGeometry(geomSpPr, r.width(), r.height())
        val stroke = resolveStroke(spPrs.mapNotNull { it.child("ln") }, el.child("style")?.child("lnRef"), ctx.colors)
        withTransform(canvas, xfrm, r, flipContent = true) {
            canvas.translate(r.left, r.top)
            val clip = Path()
            for (gp in geom.paths) if (gp.fill != "none") clip.addPath(gp.path)
            paintFill(canvas, clip, fill, r.width(), r.height(), "norm")
            if (stroke != null) drawStroke(canvas, geom, stroke, ctx.k)
        }
    }

    private fun drawGroup(canvas: Canvas, el: Element, part: Part, ctx: DrawCtx, xf: Xf, groupFill: Fill?) {
        if (isHidden(el)) return
        val grpSpPr = el.child("grpSpPr")
        val xfrm = grpSpPr?.child("xfrm")
        val r = rectOf(xfrm, xf, ctx.k)
        var childXf = xf
        if (xfrm != null) {
            val off = xfrm.child("off"); val ext = xfrm.child("ext")
            val chOff = xfrm.child("chOff"); val chExt = xfrm.child("chExt")
            val ox = off?.long("x")?.toDouble() ?: 0.0
            val oy = off?.long("y")?.toDouble() ?: 0.0
            val cx = ext?.long("cx")?.toDouble() ?: 0.0
            val cy = ext?.long("cy")?.toDouble() ?: 0.0
            val chx = chOff?.long("x")?.toDouble() ?: ox
            val chy = chOff?.long("y")?.toDouble() ?: oy
            val chcx = chExt?.long("cx")?.toDouble()?.takeIf { it > 0 } ?: cx
            val chcy = chExt?.long("cy")?.toDouble()?.takeIf { it > 0 } ?: cy
            val sx = if (chcx > 0) cx / chcx else 1.0
            val sy = if (chcy > 0) cy / chcy else 1.0
            // child -> group parent space: X = ox + (x - chx) * sx, then parent -> slide via xf
            childXf = Xf(xf.x(ox - chx * sx), xf.y(oy - chy * sy), xf.sx * sx, xf.sy * sy)
        }
        val fill = parseFill(grpSpPr, ctx.colors, part.rels)?.takeIf { it !== Fill.Group } ?: groupFill
        if (r != null) {
            withTransform(canvas, xfrm, r, flipContent = true) {
                drawTree(canvas, el.kids(), part, ctx, childXf, fill)
            }
        } else {
            drawTree(canvas, el.kids(), part, ctx, childXf, fill)
        }
    }

    private fun drawGraphicFrame(canvas: Canvas, el: Element, part: Part, ctx: DrawCtx, xf: Xf) {
        if (isHidden(el)) return
        if (phOf(el) != null && part !is SlidePart) return
        val r = rectOf(el.child("xfrm"), xf, ctx.k) ?: return
        val data = el.path("graphic", "graphicData") ?: return
        val uri = data.attr("uri") ?: ""
        data.child("tbl")?.let { tbl ->
            TableRenderer.draw(canvas, tbl, r, part, ctx)
            return
        }
        when {
            uri.endsWith("/chart") -> {
                val target = data.child("chart")?.rel("id")?.let { part.rels[it]?.target } ?: return
                val chart = ctx.pres.pkg.xml(target, cache = false) ?: return
                ChartRenderer.draw(canvas, chart, r, ctx)
            }
            uri.endsWith("/diagram") -> drawSmartArt(canvas, data, part, ctx, xf, r)
            else -> {
                // OLE objects and the like carry a preview picture.
                val pic = findDescendant(data, "pic") ?: return
                drawPicture(canvas, pic, part, ctx, xf)
            }
        }
    }

    /** SmartArt keeps a pre-laid-out drawing (dsp:drawing) next to its data model. */
    private fun drawSmartArt(canvas: Canvas, data: Element, part: Part, ctx: DrawCtx, xf: Xf, frame: RectF) {
        val pkg = ctx.pres.pkg
        val dm = data.child("relIds")?.rel("dm")?.let { part.rels[it]?.target }?.let { pkg.xml(it, cache = false) }
        val drawingRelId = dm?.let { findDescendant(it, "dataModelExt")?.attr("relId") }
        val drawingPath = drawingRelId?.let { part.rels[it]?.target }
            ?: part.rels.values.firstOrNull { it.type.endsWith("/diagramDrawing") }?.target
            ?: return
        val drawing = pkg.xml(drawingPath, cache = false) ?: return
        val tree = drawing.child("spTree") ?: return
        val dPart = Part(drawingPath, drawing, pkg.rels(drawingPath))
        // Drawing coordinates are relative to the graphic frame.
        val childXf = Xf(frame.left / ctx.k.toDouble(), frame.top / ctx.k.toDouble(), xf.sx, xf.sy)
        drawTree(canvas, tree.kids(), dPart, ctx, childXf, null)
    }

    private fun findDescendant(el: Element, name: String): Element? {
        for (c in el.kids()) {
            if (c.tag == name) return c
            findDescendant(c, name)?.let { return it }
        }
        return null
    }

    // ---- painting -------------------------------------------------------------------------

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    fun paintFill(canvas: Canvas, path: Path, fill: Fill, w: Float, h: Float, mode: String) {
        val p = fillPaint
        p.reset()
        p.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
        p.style = Paint.Style.FILL
        when (fill) {
            is Fill.Solid -> {
                p.color = shade(fill.color, mode)
                canvas.drawPath(path, p)
            }
            is Fill.Gradient -> {
                val colors = IntArray(fill.colors.size) { shade(fill.colors[it], mode) }
                p.shader = if (fill.radial) {
                    RadialGradient(w / 2, h / 2, max(1f, hypot(w, h) / 2), colors, fill.stops, Shader.TileMode.CLAMP)
                } else {
                    val a = Math.toRadians(fill.angle.toDouble())
                    val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
                    val half = (abs(w * dx) + abs(h * dy)) / 2
                    LinearGradient(
                        w / 2 - dx * half, h / 2 - dy * half, w / 2 + dx * half, h / 2 + dy * half,
                        colors, fill.stops, Shader.TileMode.CLAMP
                    )
                }
                canvas.drawPath(path, p)
            }
            is Fill.Picture -> drawImageFill(canvas, path, fill, w, h)
            else -> Unit
        }
    }

    private fun shade(c: Int, mode: String): Int = when (mode) {
        "darken" -> blend(c, BLACK and 0xFFFFFF or (c and 0xFF000000.toInt()), 0.4f)
        "darkenLess" -> blend(c, BLACK and 0xFFFFFF or (c and 0xFF000000.toInt()), 0.2f)
        "lighten" -> blend(c, 0xFFFFFF or (c and 0xFF000000.toInt()), 0.4f)
        "lightenLess" -> blend(c, 0xFFFFFF or (c and 0xFF000000.toInt()), 0.2f)
        else -> c
    }

    private fun drawImageFill(canvas: Canvas, path: Path, fill: Fill.Picture, w: Float, h: Float) {
        val image = fill.image ?: return
        val src = fill.srcRect
        val l = (src?.int("l") ?: 0) / 100000f
        val t = (src?.int("t") ?: 0) / 100000f
        val rr = (src?.int("r") ?: 0) / 100000f
        val b = (src?.int("b") ?: 0) / 100000f
        val visW = max(0.01f, 1f - l - rr)
        val visH = max(0.01f, 1f - t - b)
        val bmp = (if (fill.tile != null) images.get(image, 1, 1) else images.get(image, (w / visW).toInt(), (h / visH).toInt()))
            ?: return
        val p = fillPaint
        p.reset()
        p.flags = Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG
        val blip = fill.blip
        blip?.child("alphaModFix")?.int("amt")?.let { p.alpha = (it / 100000f * 255).toInt().coerceIn(0, 255) }
        val duo = fill.duotone
        if (duo != null) {
            // Map luminance onto the dark..light colour pair.
            val m = FloatArray(20)
            for ((row, shift) in intArrayOf(16, 8, 0).withIndex()) {
                val c1 = (duo[0] shr shift and 0xFF).toFloat()
                val d = ((duo[1] shr shift and 0xFF) - c1) / 255f
                m[row * 5] = d * 0.299f; m[row * 5 + 1] = d * 0.587f; m[row * 5 + 2] = d * 0.114f; m[row * 5 + 4] = c1
            }
            m[18] = 1f
            p.colorFilter = ColorMatrixColorFilter(ColorMatrix(m))
        } else if (blip?.child("grayscl") != null) {
            p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.save()
        if (!path.isEmpty) canvas.clipPath(path)
        val tile = fill.tile
        if (tile != null) {
            // Tiles are laid at the image's natural size (96 dpi) times the tile scale.
            val shader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            val natural = images.naturalSize(image)
            val scale = 9525f * kNow * (natural?.let { it[0].toFloat() / bmp.width } ?: 1f)
            shader.setLocalMatrix(Matrix().apply {
                setScale(scale * (tile.int("sx") ?: 100000) / 100000f, scale * (tile.int("sy") ?: 100000) / 100000f)
                postTranslate((tile.long("tx") ?: 0L) * kNow, (tile.long("ty") ?: 0L) * kNow)
            })
            p.shader = shader
            canvas.drawRect(0f, 0f, w, h, p)
        } else {
            val bw = bmp.width; val bh = bmp.height
            val srcRect = Rect((l * bw).toInt(), (t * bh).toInt(), ((1 - rr) * bw).toInt(), ((1 - b) * bh).toInt())
            canvas.drawBitmap(bmp, srcRect, RectF(0f, 0f, w, h), p)
        }
        canvas.restore()
    }

    fun drawStroke(canvas: Canvas, geom: Geometry, stroke: Stroke, k: Float) {
        val p = strokePaint
        val width = max(stroke.widthEmu * k, 1f)
        p.reset()
        p.isAntiAlias = true
        p.style = Paint.Style.STROKE
        p.color = stroke.color
        p.strokeWidth = width
        p.strokeJoin = Paint.Join.ROUND
        p.strokeCap = when (stroke.cap) {
            "rnd" -> Paint.Cap.ROUND
            "sq" -> Paint.Cap.SQUARE
            else -> Paint.Cap.BUTT
        }
        dashIntervals(stroke.dash, width)?.let { p.pathEffect = DashPathEffect(it, 0f) }
        var first: Path? = null
        for (gp in geom.paths) if (gp.stroke) {
            canvas.drawPath(gp.path, p)
            if (first == null) first = gp.path
        }
        val path = first ?: return
        if (stroke.head == null && stroke.tail == null) return
        val pm = PathMeasure(path, false)
        val pos = FloatArray(2)
        val tan = FloatArray(2)
        stroke.head?.let { head ->
            if (pm.length > 0 && pm.getPosTan(0f, pos, tan)) arrowHead(canvas, head, pos[0], pos[1], -tan[0], -tan[1], width, stroke.color)
        }
        stroke.tail?.let { tail ->
            val m = PathMeasure(path, false)
            var found = false
            do {
                if (m.length > 0) found = m.getPosTan(m.length, pos, tan)
            } while (m.nextContour())
            if (found) arrowHead(canvas, tail, pos[0], pos[1], tan[0], tan[1], width, stroke.color)
        }
    }

    /** Draws a line end pointing along (dx, dy) with its tip at (x, y). */
    private fun arrowHead(canvas: Canvas, end: Element, x: Float, y: Float, dx: Float, dy: Float, lineW: Float, color: Int) {
        fun size(v: String?) = when (v) {
            "sm" -> 2f
            "lg" -> 5f
            else -> 3f
        }
        val base = max(lineW, 1.5f)
        val len = size(end.attr("len")) * base
        val wid = size(end.attr("w")) * base
        val px = -dy; val py = dx
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val path = Path()
        when (end.attr("type")) {
            "oval" -> {
                canvas.drawOval(x - dx * len / 2 - wid / 2, y - dy * len / 2 - wid / 2, x - dx * len / 2 + wid / 2, y - dy * len / 2 + wid / 2, p)
                return
            }
            "diamond" -> {
                path.moveTo(x, y)
                path.lineTo(x - dx * len / 2 + px * wid / 2, y - dy * len / 2 + py * wid / 2)
                path.lineTo(x - dx * len, y - dy * len)
                path.lineTo(x - dx * len / 2 - px * wid / 2, y - dy * len / 2 - py * wid / 2)
                path.close()
            }
            "arrow" -> {
                p.style = Paint.Style.STROKE
                p.strokeWidth = base
                p.strokeCap = Paint.Cap.ROUND
                path.moveTo(x - dx * len + px * wid / 2, y - dy * len + py * wid / 2)
                path.lineTo(x, y)
                path.lineTo(x - dx * len - px * wid / 2, y - dy * len - py * wid / 2)
            }
            "stealth" -> {
                path.moveTo(x, y)
                path.lineTo(x - dx * len + px * wid / 2, y - dy * len + py * wid / 2)
                path.lineTo(x - dx * len * 0.6f, y - dy * len * 0.6f)
                path.lineTo(x - dx * len - px * wid / 2, y - dy * len - py * wid / 2)
                path.close()
            }
            else -> {
                path.moveTo(x, y)
                path.lineTo(x - dx * len + px * wid / 2, y - dy * len + py * wid / 2)
                path.lineTo(x - dx * len - px * wid / 2, y - dy * len - py * wid / 2)
                path.close()
            }
        }
        canvas.drawPath(path, p)
    }
}
