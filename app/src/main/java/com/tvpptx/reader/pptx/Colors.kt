package com.tvpptx.reader.pptx

import org.w3c.dom.Element
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

const val BLACK = 0xFF000000.toInt()
const val WHITE = 0xFFFFFFFF.toInt()

fun parseHex(hex: String): Int? {
    val h = hex.trim()
    if (h.length != 6) return null
    return h.toLongOrNull(16)?.let { (0xFF000000L or it).toInt() }
}

val COLOR_TAGS = arrayOf("srgbClr", "schemeClr", "sysClr", "prstClr", "scrgbClr", "hslClr")
val FILL_TAGS = arrayOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill", "grpFill")

/** Resolves DrawingML colors against a theme and color map. [phClr] fills in theme style templates. */
class ColorCtx(val theme: Theme, val clrMap: Map<String, String>, val phClr: Int? = null) {
    fun withPh(c: Int?) = if (c == phClr) this else ColorCtx(theme, clrMap, c)

    fun scheme(name: String): Int? {
        if (name == "phClr") return phClr
        val mapped = clrMap[name] ?: name
        return theme.colors[mapped] ?: theme.colors[name]
    }

    /** Color of the first color element under [parent] (e.g. a solidFill), or null. */
    fun of(parent: Element?): Int? = parent?.firstOf(*COLOR_TAGS)?.let { resolve(it) }

    fun resolve(el: Element): Int? {
        val base: Int = when (el.tag) {
            "srgbClr" -> el.attr("val")?.let { parseHex(it) }
            "schemeClr" -> el.attr("val")?.let { scheme(it) }
            "sysClr" -> el.attr("lastClr")?.let { parseHex(it) } ?: if (el.attr("val") == "window") WHITE else BLACK
            "prstClr" -> PRESET_COLORS[el.attr("val")?.lowercase()] ?: BLACK
            "scrgbClr" -> argb(
                1f, toSrgb(pct(el, "r")), toSrgb(pct(el, "g")), toSrgb(pct(el, "b"))
            )
            "hslClr" -> hslToColor(
                (el.int("hue") ?: 0) / 60000f / 360f, pct(el, "sat"), pct(el, "lum"), 1f
            )
            else -> null
        } ?: return null
        return applyMods(base, el)
    }

    private fun pct(el: Element, name: String) = (el.int(name) ?: 0) / 100000f
}

private fun argb(a: Float, r: Float, g: Float, b: Float): Int =
    (clamp(a) * 255 + .5f).toInt() shl 24 or ((clamp(r) * 255 + .5f).toInt() shl 16) or
        ((clamp(g) * 255 + .5f).toInt() shl 8) or (clamp(b) * 255 + .5f).toInt()

private fun clamp(v: Float) = v.coerceIn(0f, 1f)
private fun toLinear(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
private fun toSrgb(c: Float) = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1 / 2.4f) - 0.055f

private fun applyMods(base: Int, el: Element): Int {
    var a = (base ushr 24) / 255f
    var r = (base shr 16 and 0xFF) / 255f
    var g = (base shr 8 and 0xFF) / 255f
    var b = (base and 0xFF) / 255f
    for (m in el.kids()) {
        val v = (m.int("val") ?: 0) / 100000f
        when (m.tag) {
            "alpha" -> a = v
            "alphaMod" -> a *= v
            "alphaOff" -> a += v
            "tint" -> { r = toSrgb(1 - (1 - toLinear(r)) * v); g = toSrgb(1 - (1 - toLinear(g)) * v); b = toSrgb(1 - (1 - toLinear(b)) * v) }
            "shade" -> { r = toSrgb(toLinear(r) * v); g = toSrgb(toLinear(g) * v); b = toSrgb(toLinear(b) * v) }
            "inv" -> { r = 1 - r; g = 1 - g; b = 1 - b }
            "gray" -> { val l = 0.3f * r + 0.59f * g + 0.11f * b; r = l; g = l; b = l }
            "red" -> r = v
            "green" -> g = v
            "blue" -> b = v
            "redMod" -> r *= v
            "greenMod" -> g *= v
            "blueMod" -> b *= v
            "redOff" -> r += v
            "greenOff" -> g += v
            "blueOff" -> b += v
            "lumMod", "lumOff", "lum", "satMod", "satOff", "sat", "hueMod", "hueOff", "hue", "comp" -> {
                val hsl = rgbToHsl(clamp(r), clamp(g), clamp(b))
                when (m.tag) {
                    "lumMod" -> hsl[2] *= v
                    "lumOff" -> hsl[2] += v
                    "lum" -> hsl[2] = v
                    "satMod" -> hsl[1] *= v
                    "satOff" -> hsl[1] += v
                    "sat" -> hsl[1] = v
                    "hueMod" -> hsl[0] *= v
                    "hueOff" -> hsl[0] += (m.int("val") ?: 0) / 60000f / 360f
                    "hue" -> hsl[0] = (m.int("val") ?: 0) / 60000f / 360f
                    "comp" -> hsl[0] += 0.5f
                }
                val c = hslToColor(hsl[0], clamp(hsl[1]), clamp(hsl[2]), 1f)
                r = (c shr 16 and 0xFF) / 255f; g = (c shr 8 and 0xFF) / 255f; b = (c and 0xFF) / 255f
            }
        }
    }
    return argb(a, r, g, b)
}

private fun rgbToHsl(r: Float, g: Float, b: Float): FloatArray {
    val mx = max(r, max(g, b))
    val mn = min(r, min(g, b))
    val l = (mx + mn) / 2
    if (mx == mn) return floatArrayOf(0f, 0f, l)
    val d = mx - mn
    val s = if (l > 0.5f) d / (2 - mx - mn) else d / (mx + mn)
    val h = when (mx) {
        r -> ((g - b) / d + (if (g < b) 6 else 0)) / 6
        g -> ((b - r) / d + 2) / 6
        else -> ((r - g) / d + 4) / 6
    }
    return floatArrayOf(h, s, l)
}

private fun hslToColor(h0: Float, s: Float, l: Float, a: Float): Int {
    val h = ((h0 % 1f) + 1f) % 1f
    if (s == 0f) return argb(a, l, l, l)
    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    fun hue(t0: Float): Float {
        var t = t0
        if (t < 0) t += 1
        if (t > 1) t -= 1
        return when {
            t < 1f / 6 -> p + (q - p) * 6 * t
            t < 1f / 2 -> q
            t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
            else -> p
        }
    }
    return argb(a, hue(h + 1f / 3), hue(h), hue(h - 1f / 3))
}

/** Mixes two colors; t=0 gives [a], t=1 gives [b]. */
fun blend(a: Int, b: Int, t: Float): Int {
    fun ch(s: Int) = ((a shr s and 0xFF) * (1 - t) + (b shr s and 0xFF) * t).toInt().coerceIn(0, 255)
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

fun withAlpha(c: Int, alpha: Float): Int = ((c ushr 24) * alpha).toInt().coerceIn(0, 255) shl 24 or (c and 0xFFFFFF)

private val PRESET_COLORS = mapOf(
    "black" to 0x000000, "white" to 0xFFFFFF, "red" to 0xFF0000, "green" to 0x008000, "blue" to 0x0000FF,
    "yellow" to 0xFFFF00, "cyan" to 0x00FFFF, "aqua" to 0x00FFFF, "magenta" to 0xFF00FF, "fuchsia" to 0xFF00FF,
    "gray" to 0x808080, "grey" to 0x808080, "silver" to 0xC0C0C0, "maroon" to 0x800000, "navy" to 0x000080,
    "olive" to 0x808000, "purple" to 0x800080, "teal" to 0x008080, "orange" to 0xFFA500, "lime" to 0x00FF00,
    "dkblue" to 0x00008B, "dkgray" to 0xA9A9A9, "dkgrey" to 0xA9A9A9, "dkgreen" to 0x006400, "dkred" to 0x8B0000,
    "ltgray" to 0xD3D3D3, "ltgrey" to 0xD3D3D3, "ltblue" to 0xADD8E6, "ltgreen" to 0x90EE90, "gold" to 0xFFD700,
    "pink" to 0xFFC0CB, "brown" to 0xA52A2A, "indigo" to 0x4B0082, "violet" to 0xEE82EE, "tan" to 0xD2B48C,
    "beige" to 0xF5F5DC, "coral" to 0xFF7F50, "crimson" to 0xDC143C, "khaki" to 0xF0E68C, "salmon" to 0xFA8072,
    "skyblue" to 0x87CEEB, "steelblue" to 0x4682B4, "turquoise" to 0x40E0D0, "darkorange" to 0xFF8C00
).mapValues { (0xFF000000.toInt() or it.value) }

sealed class Fill {
    object None : Fill()
    object Group : Fill()
    class Solid(val color: Int) : Fill()
    class Gradient(val colors: IntArray, val stops: FloatArray, val angle: Float, val radial: Boolean) : Fill() {
        val first get() = colors.first()
    }
    class Picture(
        val image: String?, val blip: Element?, val srcRect: Element?, val tile: Element?,
        /** Duotone recolouring: the dark and light colours the image's luminance is mapped between. */
        val duotone: IntArray? = null
    ) : Fill()
}

/** Reads the fill child of [container] (spPr, bgPr, tcPr ...); null means "not specified, inherit". */
fun parseFill(container: Element?, ctx: ColorCtx, rels: Map<String, Rel>): Fill? =
    container?.firstOf(*FILL_TAGS)?.let { fillOf(it, ctx, rels) }

fun fillOf(f: Element, ctx: ColorCtx, rels: Map<String, Rel>): Fill? = when (f.tag) {
    "noFill" -> Fill.None
    "grpFill" -> Fill.Group
    "solidFill" -> ctx.of(f)?.let { Fill.Solid(it) } ?: Fill.None
    "gradFill" -> {
        val stops = f.child("gsLst")?.children("gs")?.mapNotNull { gs ->
            ctx.of(gs)?.let { c -> (gs.int("pos") ?: 0) / 100000f to c }
        }?.sortedBy { it.first } ?: emptyList()
        when (stops.size) {
            0 -> null
            1 -> Fill.Solid(stops[0].second)
            else -> Fill.Gradient(
                stops.map { it.second }.toIntArray(), stops.map { it.first }.toFloatArray(),
                (f.child("lin")?.int("ang") ?: 0) / 60000f, f.child("path") != null
            )
        }
    }
    "blipFill" -> {
        val blip = f.child("blip")
        val duo = blip?.child("duotone")?.kids()?.mapNotNull { ctx.resolve(it) }?.takeIf { it.size == 2 }?.toIntArray()
        Fill.Picture(blip?.rel("embed")?.let { rels[it]?.target }, blip, f.child("srcRect"), f.child("tile"), duo)
    }
    "pattFill" -> {
        val fg = ctx.of(f.child("fgClr")) ?: BLACK
        val bg = ctx.of(f.child("bgClr")) ?: WHITE
        val prst = f.attr("prst") ?: ""
        val amount = if (prst.startsWith("pct")) (prst.drop(3).toIntOrNull() ?: 50) / 100f else 0.5f
        Fill.Solid(blend(bg, fg, amount))
    }
    else -> null
}

/** Fill from a theme style reference such as <a:fillRef idx="1"><a:schemeClr val="accent1"/></a:fillRef>. */
fun themeFill(ref: Element, ctx: ColorCtx): Fill? {
    val idx = ref.int("idx") ?: return null
    if (idx == 0) return Fill.None
    val color = ctx.of(ref)
    val list = if (idx >= 1001) ctx.theme.bgFillStyles else ctx.theme.fillStyles
    val el = list.getOrNull(if (idx >= 1001) idx - 1001 else idx - 1)
        ?: return color?.let { Fill.Solid(it) }
    return fillOf(el, ctx.withPh(color), ctx.theme.rels)
}

class Stroke(
    val color: Int, val widthEmu: Float, val dash: String?,
    val head: Element?, val tail: Element?, val cap: String?
)

/**
 * Resolves an outline from [lns] (a:ln elements, most specific first) with an optional theme
 * <a:lnRef> fallback. Returns null when there is no visible line.
 */
fun resolveStroke(lns: List<Element>, lnRef: Element?, ctx: ColorCtx): Stroke? {
    val refColor = lnRef?.let { ctx.of(it) }
    val refLn = lnRef?.int("idx")?.takeIf { it > 0 }?.let { ctx.theme.lineStyles.getOrNull(it - 1) }
    val all = if (refLn != null) lns + refLn else lns
    val fillOwner = all.firstOrNull { it.firstOf("noFill", "solidFill", "gradFill", "pattFill") != null } ?: return null
    val fillEl = fillOwner.firstOf("noFill", "solidFill", "gradFill", "pattFill")!!
    val c = if (fillOwner === refLn) ctx.withPh(refColor) else ctx
    val color = when (fillEl.tag) {
        "solidFill" -> c.of(fillEl)
        "gradFill" -> fillEl.child("gsLst")?.child("gs")?.let { c.of(it) }
        "pattFill" -> c.of(fillEl.child("fgClr"))
        else -> null
    } ?: return null
    val w = all.firstNotNullOfOrNull { it.long("w") } ?: 9525L
    return Stroke(
        color, w.toFloat(),
        all.firstNotNullOfOrNull { it.child("prstDash")?.attr("val") },
        all.firstNotNullOfOrNull { it.child("headEnd") }?.takeIf { (it.attr("type") ?: "none") != "none" },
        all.firstNotNullOfOrNull { it.child("tailEnd") }?.takeIf { (it.attr("type") ?: "none") != "none" },
        all.firstNotNullOfOrNull { it.attr("cap") }
    )
}

fun dashIntervals(dash: String?, width: Float): FloatArray? {
    val pattern = when (dash) {
        null, "solid" -> return null
        "dot", "sysDot" -> floatArrayOf(1f, 1f)
        "dash" -> floatArrayOf(4f, 3f)
        "sysDash" -> floatArrayOf(3f, 1f)
        "lgDash" -> floatArrayOf(8f, 3f)
        "dashDot" -> floatArrayOf(4f, 3f, 1f, 3f)
        "lgDashDot" -> floatArrayOf(8f, 3f, 1f, 3f)
        "lgDashDotDot" -> floatArrayOf(8f, 3f, 1f, 3f, 1f, 3f)
        "sysDashDot" -> floatArrayOf(3f, 1f, 1f, 1f)
        "sysDashDotDot" -> floatArrayOf(3f, 1f, 1f, 1f, 1f, 1f)
        else -> floatArrayOf(4f, 3f)
    }
    val w = max(width, 1f)
    return FloatArray(pattern.size) { max(pattern[it] * w, 1f) }
}

fun luminance(c: Int): Float =
    (0.299f * (c shr 16 and 0xFF) + 0.587f * (c shr 8 and 0xFF) + 0.114f * (c and 0xFF)) / 255f
