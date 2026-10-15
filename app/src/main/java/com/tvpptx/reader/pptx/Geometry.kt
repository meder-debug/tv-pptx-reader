package com.tvpptx.reader.pptx

import android.content.res.AssetManager
import android.graphics.Path
import android.graphics.RectF
import org.w3c.dom.Element
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** One sub-path of a shape. [fill] is the DrawingML path fill mode: norm, none, darken, lighten ... */
class GeomPath(val path: Path, val fill: String, val stroke: Boolean)

class Geometry(val paths: List<GeomPath>, val textRect: RectF?)

/**
 * The 187 preset shapes from ECMA-376 (assets/presets.txt, converted from presetShapeDefinitions.xml)
 * and custom geometry, both written as a small line-based program:
 *   v/g name fmla   adjust value / guide       r l t r b   text rectangle
 *   p w h fill stroke   start a path            m/l/q/c points, a wR hR stAng swAng, z
 */
object Presets {
    @Volatile
    private var defs: Map<String, List<String>>? = null

    fun load(assets: AssetManager) {
        if (defs != null) return
        synchronized(this) {
            if (defs != null) return
            val map = HashMap<String, List<String>>()
            var name: String? = null
            var cur = ArrayList<String>()
            assets.open("presets.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("@")) {
                        name?.let { map[it] = cur }
                        name = line.substring(1)
                        cur = ArrayList()
                    } else if (line.isNotBlank()) cur.add(line)
                }
            }
            name?.let { map[it] = cur }
            defs = map
        }
    }

    fun get(name: String): List<String>? = defs?.get(name)
}

/** Builds the geometry for a shape's spPr (prstGeom or custGeom) at [w] x [h] pixels. */
fun buildGeometry(spPr: Element?, w: Float, h: Float): Geometry {
    val cust = spPr?.child("custGeom")
    val prst = spPr?.child("prstGeom")
    val program: List<String>
    val overrides = HashMap<String, String>()
    if (cust != null) {
        program = custProgram(cust)
    } else {
        val name = prst?.attr("prst") ?: "rect"
        program = Presets.get(name) ?: Presets.get("rect") ?: RECT_PROGRAM
        prst?.child("avLst")?.children("gd")?.forEach { gd ->
            val n = gd.attr("name"); val f = gd.attr("fmla")
            if (n != null && f != null) overrides[n] = f
        }
    }
    return runProgram(program, overrides, w, h)
}

private val RECT_PROGRAM = listOf("p - - norm 1", "m l t", "l r t", "l r b", "l l b", "z")

private fun custProgram(cust: Element): List<String> {
    val out = ArrayList<String>()
    cust.child("avLst")?.children("gd")?.forEach { out.add("v ${it.attr("name")} ${it.attr("fmla")}") }
    cust.child("gdLst")?.children("gd")?.forEach { out.add("g ${it.attr("name")} ${it.attr("fmla")}") }
    cust.child("rect")?.let { out.add("r ${it.attr("l")} ${it.attr("t")} ${it.attr("r")} ${it.attr("b")}") }
    cust.child("pathLst")?.children("path")?.forEach { p ->
        out.add("p ${p.attr("w") ?: "-"} ${p.attr("h") ?: "-"} ${p.attr("fill") ?: "norm"} ${if (p.attr("stroke") == "false" || p.attr("stroke") == "0") "0" else "1"}")
        for (cmd in p.kids()) {
            val pts = cmd.children("pt").joinToString(" ") { "${it.attr("x")} ${it.attr("y")}" }
            when (cmd.tag) {
                "moveTo" -> out.add("m $pts")
                "lnTo" -> out.add("l $pts")
                "quadBezTo" -> out.add("q $pts")
                "cubicBezTo" -> out.add("c $pts")
                "arcTo" -> out.add("a ${cmd.attr("wR")} ${cmd.attr("hR")} ${cmd.attr("stAng")} ${cmd.attr("swAng")}")
                "close" -> out.add("z")
            }
        }
    }
    return out
}

private fun runProgram(program: List<String>, overrides: Map<String, String>, w: Float, h: Float): Geometry {
    val g = Guides(w.toDouble(), h.toDouble())
    val paths = ArrayList<GeomPath>()
    var textRect: RectF? = null
    var path: Path? = null
    var fill = "norm"
    var stroke = true
    var sx = 1.0
    var sy = 1.0
    var lastX = 0.0
    var lastY = 0.0
    fun finish() {
        path?.let { paths.add(GeomPath(it, fill, stroke)) }
    }
    for (line in program) {
        val t = line.split(' ').filter { it.isNotEmpty() }
        if (t.isEmpty()) continue
        when (t[0]) {
            "v" -> g.set(t[1], overrides[t[1]]?.split(' ')?.filter { it.isNotEmpty() } ?: t.subList(2, t.size))
            "g" -> g.set(t[1], t.subList(2, t.size))
            "r" -> textRect = RectF(g.f(t[1]), g.f(t[2]), g.f(t[3]), g.f(t[4]))
            "p" -> {
                finish()
                path = Path()
                val pw = t[1].toDoubleOrNull()
                val ph = t[2].toDoubleOrNull()
                sx = if (pw != null && pw > 0) w / pw else 1.0
                sy = if (ph != null && ph > 0) h / ph else 1.0
                fill = t[3]
                stroke = t[4] == "1"
            }
            "m", "l", "q", "c" -> {
                val p = path ?: Path().also { path = it }
                val xs = DoubleArray((t.size - 1) / 2) { g.v(t[1 + it * 2]) * sx }
                val ys = DoubleArray((t.size - 1) / 2) { g.v(t[2 + it * 2]) * sy }
                if (xs.isEmpty()) continue
                when (t[0]) {
                    "m" -> p.moveTo(xs[0].toFloat(), ys[0].toFloat())
                    "l" -> p.lineTo(xs[0].toFloat(), ys[0].toFloat())
                    "q" -> if (xs.size >= 2) p.quadTo(xs[0].toFloat(), ys[0].toFloat(), xs[1].toFloat(), ys[1].toFloat())
                    "c" -> if (xs.size >= 3) p.cubicTo(
                        xs[0].toFloat(), ys[0].toFloat(), xs[1].toFloat(), ys[1].toFloat(), xs[2].toFloat(), ys[2].toFloat()
                    )
                }
                lastX = xs.last(); lastY = ys.last()
            }
            "a" -> {
                val p = path ?: Path().also { path = it }
                val rx = g.v(t[1]) * sx
                val ry = g.v(t[2]) * sy
                val st = g.v(t[3]) / 60000.0
                val sw = g.v(t[4]) / 60000.0
                val end = arc(p, lastX, lastY, rx, ry, st, sw)
                lastX = end.first; lastY = end.second
            }
            "z" -> path?.close()
        }
    }
    finish()
    return Geometry(paths, textRect)
}

/** DrawingML arcTo: angles are visual angles on the ellipse; Skia wants parametric ones. */
private fun arc(p: Path, x0: Double, y0: Double, rx: Double, ry: Double, stDeg: Double, swDeg: Double): Pair<Double, Double> {
    if (rx <= 0.0 || ry <= 0.0) return x0 to y0
    fun param(deg: Double): Double {
        val a = deg * PI / 180
        return atan2(rx * sin(a), ry * cos(a)) * 180 / PI
    }
    val t1 = param(stDeg)
    var sweep = param(stDeg + swDeg) - t1
    if (swDeg > 0 && sweep < 0) sweep += 360
    if (swDeg < 0 && sweep > 0) sweep -= 360
    if (abs(swDeg) >= 359.999) sweep = if (swDeg > 0) 360.0 else -360.0
    val r1 = t1 * PI / 180
    val cx = x0 - rx * cos(r1)
    val cy = y0 - ry * sin(r1)
    val oval = RectF((cx - rx).toFloat(), (cy - ry).toFloat(), (cx + rx).toFloat(), (cy + ry).toFloat())
    if (abs(sweep) > 180) {
        p.arcTo(oval, t1.toFloat(), (sweep / 2).toFloat(), false)
        p.arcTo(oval, (t1 + sweep / 2).toFloat(), (sweep / 2).toFloat(), false)
    } else {
        p.arcTo(oval, t1.toFloat(), sweep.toFloat(), false)
    }
    val r2 = (t1 + sweep) * PI / 180
    return (cx + rx * cos(r2)) to (cy + ry * sin(r2))
}

/** Shape guide evaluation (ECMA-376 20.1.9.11). Angles are in 60000ths of a degree. */
private class Guides(w: Double, h: Double) {
    private val vals = HashMap<String, Double>(64)

    init {
        val ss = min(w, h)
        val ls = max(w, h)
        vals["l"] = 0.0; vals["t"] = 0.0; vals["r"] = w; vals["b"] = h; vals["w"] = w; vals["h"] = h
        vals["hc"] = w / 2; vals["vc"] = h / 2; vals["ss"] = ss; vals["ls"] = ls
        for (d in intArrayOf(2, 3, 4, 5, 6, 8, 10, 12, 16, 32)) {
            vals["wd$d"] = w / d; vals["hd$d"] = h / d; vals["ssd$d"] = ss / d
        }
        vals["cd2"] = 10800000.0; vals["cd4"] = 5400000.0; vals["cd8"] = 2700000.0
        vals["3cd4"] = 16200000.0; vals["3cd8"] = 8100000.0; vals["5cd8"] = 13500000.0; vals["7cd8"] = 18900000.0
    }

    fun v(tok: String): Double = vals[tok] ?: tok.toDoubleOrNull() ?: 0.0
    fun f(tok: String): Float = v(tok).toFloat()

    fun set(name: String, fmla: List<String>) {
        vals[name] = eval(fmla)
    }

    private fun eval(t: List<String>): Double {
        if (t.isEmpty()) return 0.0
        val a = if (t.size > 1) v(t[1]) else 0.0
        val b = if (t.size > 2) v(t[2]) else 0.0
        val c = if (t.size > 3) v(t[3]) else 0.0
        fun rad(x: Double) = x / 60000.0 * PI / 180
        return when (t[0]) {
            "val" -> a
            "*/" -> if (c == 0.0) 0.0 else a * b / c
            "+-" -> a + b - c
            "+/" -> if (c == 0.0) 0.0 else (a + b) / c
            "?:" -> if (a > 0) b else c
            "abs" -> abs(a)
            "at2" -> atan2(b, a) * 180 / PI * 60000
            "cat2" -> a * cos(atan2(c, b))
            "sat2" -> a * sin(atan2(c, b))
            "cos" -> a * cos(rad(b))
            "sin" -> a * sin(rad(b))
            "tan" -> a * tan(rad(b))
            "max" -> max(a, b)
            "min" -> min(a, b)
            "mod" -> sqrt(a * a + b * b + c * c)
            "pin" -> if (b < a) a else if (b > c) c else b
            "sqrt" -> sqrt(max(a, 0.0))
            else -> 0.0
        }
    }
}
