package com.tvpptx.reader.pptx

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Small DOM helpers. OOXML uses many namespace prefixes, so lookups go by local name only. */
object Xml {
    private val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }

    fun parse(input: InputStream): Element = synchronized(factory) {
        factory.newDocumentBuilder()
    }.parse(input).documentElement
}

val Element.tag: String get() = localName ?: nodeName.substringAfter(':')

fun Element.kids(): List<Element> {
    val nodes = childNodes
    val out = ArrayList<Element>(nodes.length)
    for (i in 0 until nodes.length) {
        val n = nodes.item(i)
        if (n.nodeType == Node.ELEMENT_NODE) out.add(n as Element)
    }
    return out
}

fun Element.child(name: String): Element? {
    var n = firstChild
    while (n != null) {
        if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tag == name) return n
        n = n.nextSibling
    }
    return null
}

fun Element.children(name: String): List<Element> = kids().filter { it.tag == name }

/** Every element below this one named [name], in document order. */
fun Element.descendants(name: String): List<Element> {
    val out = ArrayList<Element>()
    fun walk(el: Element) {
        for (c in el.kids()) {
            if (c.tag == name) out.add(c)
            walk(c)
        }
    }
    walk(this)
    return out
}

/** Follows a chain of child names, e.g. el.path("cSld", "spTree"). */
fun Element.path(vararg names: String): Element? {
    var cur: Element? = this
    for (n in names) cur = cur?.child(n) ?: return null
    return cur
}

/** First child whose local name is one of [names]. */
fun Element.firstOf(vararg names: String): Element? {
    var n = firstChild
    while (n != null) {
        if (n.nodeType == Node.ELEMENT_NODE && (n as Element).tag in names) return n
        n = n.nextSibling
    }
    return null
}

/** Attribute by local name, ignoring any prefix (r:embed, r:id ...). */
fun Element.attr(name: String): String? {
    if (hasAttribute(name)) return getAttribute(name)
    val attrs = attributes
    for (i in 0 until attrs.length) {
        val a = attrs.item(i)
        val local = a.localName ?: a.nodeName.substringAfter(':')
        if (local == name) return a.nodeValue
    }
    return null
}

private const val REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"

/** A relationship attribute such as r:id or r:embed (an element may also carry a plain id). */
fun Element.rel(name: String = "id"): String? {
    val attrs = attributes
    for (i in 0 until attrs.length) {
        val a = attrs.item(i)
        if ((a.localName ?: a.nodeName.substringAfter(':')) == name && (a.namespaceURI == REL_NS || a.nodeName == "r:$name")) {
            return a.nodeValue
        }
    }
    return null
}

fun Element.int(name: String): Int? = attr(name)?.toDoubleOrNull()?.toInt()
fun Element.long(name: String): Long? = attr(name)?.toDoubleOrNull()?.toLong()

/** OOXML booleans are written as 1/0/true/false/on/off. */
fun Element.bool(name: String): Boolean? = when (attr(name)) {
    null -> null
    "1", "true", "on" -> true
    else -> false
}

fun Element.textContentTrim(): String = textContent?.trim() ?: ""
