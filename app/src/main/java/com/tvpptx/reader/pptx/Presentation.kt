package com.tvpptx.reader.pptx

import android.util.Xml as AndroidXml
import org.w3c.dom.Element
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class Rel(val id: String, val type: String, val target: String, val external: Boolean)

/** The .pptx zip container: parts, relationships and a cache for shared XML parts. */
class PptxPackage(file: File) : Closeable {
    private val zip = ZipFile(file)
    private val lowerNames = HashMap<String, String>()
    private val xmlCache = HashMap<String, Element?>()
    private val relCache = HashMap<String, Map<String, Rel>>()

    init {
        for (e in zip.entries()) lowerNames[e.name.lowercase()] = e.name
    }

    private fun entry(path: String): ZipEntry? =
        zip.getEntry(path) ?: lowerNames[path.lowercase()]?.let { zip.getEntry(it) }

    fun has(path: String) = entry(path) != null

    fun bytes(path: String): ByteArray? =
        entry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

    fun xml(path: String, cache: Boolean = true): Element? {
        if (cache) xmlCache[path]?.let { return it }
        val e = entry(path) ?: return null
        val el = try {
            zip.getInputStream(e).use { Xml.parse(it) }
        } catch (t: Exception) {
            null
        }
        if (cache) xmlCache[path] = el
        return el
    }

    /** Reads only the root element's attributes; cheap way to check slide visibility. */
    fun rootAttr(path: String, name: String): String? {
        val e = entry(path) ?: return null
        return try {
            zip.getInputStream(e).use { input ->
                val p = AndroidXml.newPullParser()
                p.setInput(input, null)
                while (p.next() != XmlPullParser.START_TAG) Unit
                (0 until p.attributeCount).firstOrNull { p.getAttributeName(it).substringAfter(':') == name }
                    ?.let { p.getAttributeValue(it) }
            }
        } catch (t: Exception) {
            null
        }
    }

    fun rels(part: String): Map<String, Rel> = relCache.getOrPut(part) {
        val relsPath = if (part.isEmpty()) "_rels/.rels" else
            part.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" } +
                "_rels/" + part.substringAfterLast('/') + ".rels"
        val root = xml(relsPath, cache = false) ?: return@getOrPut emptyMap()
        root.children("Relationship").mapNotNull { r ->
            val id = r.attr("Id") ?: return@mapNotNull null
            val external = r.attr("TargetMode") == "External"
            val target = r.attr("Target") ?: ""
            id to Rel(id, r.attr("Type") ?: "", if (external) target else resolve(part, target), external)
        }.toMap()
    }

    override fun close() = zip.close()

    companion object {
        /**
         * Copies part [path] of [file] to [out], streaming, so a large video never sits in memory.
         * Opens its own zip, so it can run beside rendering. Returns false if the part isn't there.
         */
        fun extract(file: File, path: String, out: File): Boolean = ZipFile(file).use { zip ->
            val e = zip.getEntry(path) ?: zip.entries().asSequence().firstOrNull { it.name.equals(path, ignoreCase = true) }
                ?: return false
            if (out.isFile && e.size >= 0 && out.length() == e.size) return true
            val tmp = File(out.path + ".part")
            zip.getInputStream(e).use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
            tmp.renameTo(out)
        }

        fun resolve(base: String, target: String): String {
            val t = if ('%' in target) try {
                URLDecoder.decode(target.replace("+", "%2B"), "UTF-8")
            } catch (e: Exception) {
                target
            } else target
            if (t.startsWith("/")) return t.substring(1)
            val parts = base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
            for (seg in t.split('/')) when (seg) {
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                ".", "" -> Unit
                else -> parts.add(seg)
            }
            return parts.joinToString("/")
        }
    }
}

fun Map<String, Rel>.firstOfType(suffix: String): Rel? = values.firstOrNull { it.type.endsWith(suffix) }

class Theme(root: Element?, val rels: Map<String, Rel> = emptyMap()) {
    val colors = HashMap<String, Int>()
    var majorFont = "Calibri Light"
    var minorFont = "Calibri"
    val fillStyles: List<Element>
    val lineStyles: List<Element>
    val bgFillStyles: List<Element>

    init {
        val te = root?.child("themeElements")
        te?.child("clrScheme")?.kids()?.forEach { c ->
            val v = c.kids().firstOrNull() ?: return@forEach
            val hex = when (v.tag) {
                "srgbClr" -> v.attr("val")
                "sysClr" -> v.attr("lastClr") ?: if (v.attr("val") == "window") "FFFFFF" else "000000"
                else -> null
            }
            hex?.let { parseHex(it) }?.let { colors[c.tag] = it }
        }
        te?.child("fontScheme")?.let { fs ->
            fs.path("majorFont", "latin")?.attr("typeface")?.let { majorFont = it }
            fs.path("minorFont", "latin")?.attr("typeface")?.let { minorFont = it }
        }
        val fmt = te?.child("fmtScheme")
        fillStyles = fmt?.child("fillStyleLst")?.kids() ?: emptyList()
        lineStyles = fmt?.child("lnStyleLst")?.kids() ?: emptyList()
        bgFillStyles = fmt?.child("bgFillStyleLst")?.kids() ?: emptyList()
        // Office defaults so that decks with a broken theme still look reasonable.
        val defaults = mapOf(
            "dk1" to 0xFF000000.toInt(), "lt1" to 0xFFFFFFFF.toInt(), "dk2" to 0xFF44546A.toInt(),
            "lt2" to 0xFFE7E6E6.toInt(), "accent1" to 0xFF4472C4.toInt(), "accent2" to 0xFFED7D31.toInt(),
            "accent3" to 0xFFA5A5A5.toInt(), "accent4" to 0xFFFFC000.toInt(), "accent5" to 0xFF5B9BD5.toInt(),
            "accent6" to 0xFF70AD47.toInt(), "hlink" to 0xFF0563C1.toInt(), "folHlink" to 0xFF954F72.toInt()
        )
        for ((k, v) in defaults) colors.putIfAbsent(k, v)
    }

    fun font(name: String?): String? = when {
        name == null -> null
        name.startsWith("+mj") -> majorFont
        name.startsWith("+mn") -> minorFont
        else -> name
    }
}

/** A slide, layout or master: anything with a shape tree. */
open class Part(val path: String, val root: Element, val rels: Map<String, Rel>) {
    val spTree: Element? = root.path("cSld", "spTree")
    val bg: Element? = root.path("cSld", "bg")
    val shapes: List<Element> = spTree?.kids() ?: emptyList()
    val clrMapOverride: Map<String, String>? =
        root.path("clrMapOvr", "overrideClrMapping")?.let { attrMap(it) }
    val showMasterShapes: Boolean = root.bool("showMasterSp") != false

    fun findPlaceholder(ph: Element, byTypeOnly: Boolean): Element? {
        val idx = ph.attr("idx")
        val type = ph.attr("type")
        if (!byTypeOnly && idx != null) shapes.firstOrNull { phOf(it)?.attr("idx") == idx }?.let { return it }
        if (type != null) shapes.firstOrNull { phOf(it)?.attr("type") == type }?.let { return it }
        val nt = normalizedPhType(type)
        return shapes.firstOrNull { s -> phOf(s)?.let { normalizedPhType(it.attr("type")) == nt } == true }
    }
}

class MasterPart(path: String, root: Element, rels: Map<String, Rel>, val theme: Theme) : Part(path, root, rels) {
    val clrMap: Map<String, String> = root.child("clrMap")?.let { attrMap(it) } ?: DEFAULT_CLR_MAP
    val titleStyle: Element? = root.path("txStyles", "titleStyle")
    val bodyStyle: Element? = root.path("txStyles", "bodyStyle")
    val otherStyle: Element? = root.path("txStyles", "otherStyle")

    fun styleFor(phType: String?): Element? = when (normalizedPhType(phType)) {
        "title" -> titleStyle
        "body" -> bodyStyle
        else -> otherStyle
    }
}

class LayoutPart(path: String, root: Element, rels: Map<String, Rel>, val master: MasterPart) : Part(path, root, rels)

class SlidePart(
    path: String, root: Element, rels: Map<String, Rel>, val layout: LayoutPart, val number: Int
) : Part(path, root, rels) {
    val master get() = layout.master
    val clrMap: Map<String, String> get() = clrMapOverride ?: layout.clrMapOverride ?: master.clrMap
}

class Presentation(file: File) : Closeable {
    val pkg = PptxPackage(file)
    private val presPath: String = pkg.rels("").firstOfType("/officeDocument")?.target ?: "ppt/presentation.xml"
    private val root: Element = pkg.xml(presPath) ?: throw IllegalArgumentException("Not a PowerPoint file")
    val slideW: Long
    val slideH: Long
    val defaultTextStyle: Element? = root.child("defaultTextStyle")
    private val slidePaths: List<String>
    /** 0-based indices into the deck of slides that show in a slide show (hidden ones skipped). */
    val visibleSlides: List<Int>
    val tableStyles: Element? by lazy {
        pkg.rels(presPath).firstOfType("/tableStyles")?.let { pkg.xml(it.target) }
    }
    private val masters = HashMap<String, MasterPart>()
    private val layouts = HashMap<String, LayoutPart>()

    init {
        val sz = root.child("sldSz")
        slideW = sz?.long("cx")?.takeIf { it > 0 } ?: 9144000L
        slideH = sz?.long("cy")?.takeIf { it > 0 } ?: 6858000L
        val rels = pkg.rels(presPath)
        slidePaths = root.child("sldIdLst")?.children("sldId")?.mapNotNull { rels[it.rel("id")]?.target }
            ?.filter { pkg.has(it) } ?: emptyList()
        val visible = slidePaths.indices.filter { pkg.rootAttr(slidePaths[it], "show") != "0" }
        visibleSlides = visible.ifEmpty { slidePaths.indices.toList() }
    }

    val slideCount get() = slidePaths.size

    fun slide(index: Int): SlidePart {
        val path = slidePaths[index]
        val root = pkg.xml(path, cache = false) ?: throw IllegalStateException("Slide ${index + 1} is unreadable")
        val rels = pkg.rels(path)
        val layout = rels.firstOfType("/slideLayout")?.let { layout(it.target) } ?: emptyLayout()
        return SlidePart(path, root, rels, layout, index + 1)
    }

    private fun layout(path: String): LayoutPart = layouts.getOrPut(path) {
        val root = pkg.xml(path) ?: return emptyLayout()
        val rels = pkg.rels(path)
        val master = rels.firstOfType("/slideMaster")?.let { master(it.target) } ?: emptyMaster()
        LayoutPart(path, root, rels, master)
    }

    private fun master(path: String): MasterPart = masters.getOrPut(path) {
        val root = pkg.xml(path) ?: return emptyMaster()
        val rels = pkg.rels(path)
        val themePath = rels.firstOfType("/theme")?.target
        val theme = Theme(themePath?.let { pkg.xml(it) }, themePath?.let { pkg.rels(it) } ?: emptyMap())
        MasterPart(path, root, rels, theme)
    }

    private val blank by lazy { Xml.parse("<sld/>".byteInputStream()) }
    private fun emptyMaster() = masters.getOrPut("") { MasterPart("", blank, emptyMap(), Theme(null)) }
    private fun emptyLayout() = layouts.getOrPut("") { LayoutPart("", blank, emptyMap(), emptyMaster()) }

    override fun close() = pkg.close()
}

val DEFAULT_CLR_MAP = mapOf(
    "bg1" to "lt1", "tx1" to "dk1", "bg2" to "lt2", "tx2" to "dk2",
    "accent1" to "accent1", "accent2" to "accent2", "accent3" to "accent3", "accent4" to "accent4",
    "accent5" to "accent5", "accent6" to "accent6", "hlink" to "hlink", "folHlink" to "folHlink"
)

fun attrMap(el: Element): Map<String, String> {
    val out = HashMap<String, String>()
    val attrs = el.attributes
    for (i in 0 until attrs.length) {
        val a = attrs.item(i)
        if (a.nodeName.startsWith("xmlns")) continue
        out[a.localName ?: a.nodeName] = a.nodeValue
    }
    return out
}

/** The <p:ph> element of a shape, if it is a placeholder. */
fun phOf(shape: Element): Element? =
    shape.kids().firstOrNull { it.tag.startsWith("nv") }?.child("nvPr")?.child("ph")

fun normalizedPhType(type: String?): String = when (type) {
    "title", "ctrTitle" -> "title"
    "dt", "ftr", "sldNum", "hdr" -> type
    else -> "body"
}
