package com.tvpptx.reader.pptx

import android.graphics.RectF
import org.w3c.dom.Element

/** What a slide's click animations keep hidden at one point of its build. Ids are cNvPr ids. */
class ShowState(
    private val shapes: Set<String>, private val fills: Set<String>, private val paras: Map<String, Set<Int>>
) {
    fun hidesShape(id: String?) = id != null && id in shapes
    /** The shape's fill and outline are hidden but its text may not be ("animate attached shape"). */
    fun hidesFill(id: String?) = id != null && id in fills
    fun hiddenParas(id: String?): Set<Int> = id?.let { paras[it] } ?: emptySet()

    companion object {
        val ALL = ShowState(emptySet(), emptySet(), emptyMap())
    }
}

/** A video or sound on a slide. [target] is its part in the package; [rect] is in slide fractions. */
class MediaClip(
    val spid: String, val target: String, val external: Boolean, val video: Boolean, val rect: RectF,
    val volume: Float, val loop: Boolean
)

/** When the build reaches [step], start ([play]) or stop the media of shape [spid]. */
class MediaCue(val step: Int, val spid: String, val play: Boolean)

/**
 * A slide's click build, read from <p:timing>: each press of OK is one step. Entrance effects keep
 * their shape or paragraph hidden until their step, exits hide it from theirs; emphasis and motion
 * aren't played. Effects a click on some shape triggers, and media that nothing starts, become extra
 * steps at the end, since a remote can't click a shape.
 */
class SlideTimeline(
    val steps: Int, private val states: List<ShowState>, val cues: List<MediaCue>, val media: List<MediaClip>
) {
    fun state(step: Int): ShowState = states[step.coerceIn(0, steps)]

    companion object {
        val NONE = SlideTimeline(0, listOf(ShowState.ALL), emptyList(), emptyList())
    }
}

object Timing {
    private const val MAX_STEPS = 400

    private class Event(val step: Int, val key: String, val visible: Boolean)

    fun read(slide: SlidePart, slideW: Long, slideH: Long): SlideTimeline {
        val media = findMedia(slide, slideW, slideH)
        val timing = slide.root.child("timing")
            ?: slide.root.children("AlternateContent").firstNotNullOfOrNull { ac ->
                ac.kids().firstNotNullOfOrNull { it.child("timing") }
            }
        if (timing == null && media.isEmpty()) return SlideTimeline.NONE
        val root = timing?.path("tnLst", "par", "cTn")?.child("childTnLst")

        val events = ArrayList<Event>()
        val cues = ArrayList<MediaCue>()
        var step = 0

        // One click group: a top-level <p:par> of a sequence. Groups with nothing we can show are
        // dropped, so every press of OK changes something.
        fun group(par: Element, click: Boolean, interactive: Boolean = false) {
            if (step >= MAX_STEPS) return
            val at = if (click) step + 1 else step
            val before = events.size + cues.size
            for (effect in par.descendants("cTn")) {
                val cls = effect.attr("presetClass") ?: continue
                val tgt = effect.descendants("spTgt").firstOrNull() ?: continue
                val id = tgt.attr("spid") ?: continue
                when (cls) {
                    "entr", "exit" -> for (key in keys(tgt, id)) events.add(Event(at, key, cls == "entr"))
                    "mediacall" -> {
                        // PowerPoint pairs a video's play click with a click-the-video-to-pause
                        // trigger; once something plays it, that trigger isn't a step of its own.
                        if (interactive && cues.any { it.spid == id && it.play }) continue
                        val cmd = effect.descendants("cmd").firstOrNull()?.attr("cmd") ?: ""
                        when {
                            cmd.startsWith("playFrom") || cmd == "togglePause" || cmd == "resume" -> cues.add(MediaCue(at, id, true))
                            cmd == "stop" || cmd == "pause" -> cues.add(MediaCue(at, id, false))
                        }
                    }
                }
            }
            if (click && events.size + cues.size > before) step = at
        }

        val seqs = root?.children("seq") ?: emptyList()
        fun seqOf(type: String) = seqs.filter { it.child("cTn")?.attr("nodeType") == type }
        for (seq in seqOf("mainSeq")) {
            for (par in seq.child("cTn")?.child("childTnLst")?.children("par") ?: emptyList()) {
                // A group starts on a click unless its first effect is "with/after previous".
                val first = par.descendants("cTn").firstOrNull { it.attr("nodeType")?.endsWith("Effect") == true }
                group(par, first?.attr("nodeType") != "withEffect" && first?.attr("nodeType") != "afterEffect")
            }
        }
        for (seq in seqOf("interactiveSeq")) {
            for (par in seq.child("cTn")?.child("childTnLst")?.children("par") ?: emptyList()) group(par, true, interactive = true)
        }
        for (clip in media) {
            if (step < MAX_STEPS && cues.none { it.spid == clip.spid && it.play }) cues.add(MediaCue(++step, clip.spid, true))
        }

        // A target starts hidden when the first thing that happens to it is an entrance.
        val initial = LinkedHashMap<String, Boolean>()
        for (e in events) initial.putIfAbsent(e.key, !e.visible)
        val states = (0..step).map { s ->
            val visible = HashMap(initial)
            for (e in events) if (e.step <= s) visible[e.key] = e.visible
            stateOf(visible.filterValues { !it }.keys)
        }
        return SlideTimeline(step, states, cues, mediaWithSettings(media, root))
    }

    private fun keys(tgt: Element, id: String): List<String> {
        val pRg = tgt.child("txEl")?.child("pRg")
        if (pRg != null) {
            val st = pRg.int("st") ?: 0
            val end = (pRg.int("end") ?: st).coerceIn(st, st + 500)
            return (st..end).map { "p:$id:$it" }
        }
        if (tgt.child("bg") != null) return listOf("b:$id")
        // Charts and SmartArt built piece by piece (graphicEl, subSp) appear whole, on their first piece.
        return listOf("s:$id")
    }

    private fun stateOf(hidden: Collection<String>): ShowState {
        if (hidden.isEmpty()) return ShowState.ALL
        val shapes = HashSet<String>()
        val fills = HashSet<String>()
        val paras = HashMap<String, HashSet<Int>>()
        for (key in hidden) {
            val parts = key.split(':')
            when (parts[0]) {
                "s" -> shapes.add(parts[1])
                "b" -> fills.add(parts[1])
                "p" -> paras.getOrPut(parts[1]) { HashSet() }.add(parts[2].toInt())
            }
        }
        return ShowState(shapes, fills, paras)
    }

    /** Volume and looping come from the <p:video>/<p:audio> nodes beside the sequences. */
    private fun mediaWithSettings(media: List<MediaClip>, root: Element?): List<MediaClip> {
        if (root == null || media.isEmpty()) return media
        val nodes = (root.children("video") + root.children("audio")).mapNotNull { it.child("cMediaNode") }
        return media.map { clip ->
            val node = nodes.firstOrNull { it.descendants("spTgt").firstOrNull()?.attr("spid") == clip.spid }
                ?: return@map clip
            val volume = if (node.bool("mute") == true) 0f else (node.int("vol") ?: 100000) / 100000f
            val loop = node.child("cTn")?.attr("repeatCount") == "indefinite"
            MediaClip(clip.spid, clip.target, clip.external, clip.video, clip.rect, volume.coerceIn(0f, 1f), loop)
        }
    }

    /** Pictures that carry a video or sound, with their frames in slide fractions. */
    private fun findMedia(slide: SlidePart, slideW: Long, slideH: Long): List<MediaClip> {
        val out = ArrayList<MediaClip>()
        fun walk(shapes: List<Element>, xf: Xf) {
            for (el in shapes) when (el.tag) {
                "grpSp" -> walk(el.kids(), groupChildXf(el.child("grpSpPr")?.child("xfrm"), xf))
                "AlternateContent" -> (el.child("Choice") ?: el.child("Fallback"))?.let { walk(it.kids(), xf) }
                "pic" -> mediaOf(el, slide, xf, slideW, slideH)?.let { out.add(it) }
            }
        }
        walk(slide.shapes, Xf.IDENTITY)
        return out
    }

    private fun mediaOf(pic: Element, slide: SlidePart, xf: Xf, slideW: Long, slideH: Long): MediaClip? {
        val nv = pic.child("nvPicPr") ?: return null
        val id = nv.child("cNvPr")?.attr("id") ?: return null
        val nvPr = nv.child("nvPr") ?: return null
        val file = nvPr.firstOf("videoFile", "quickTimeFile", "audioFile", "wavAudioFile") ?: return null
        // PowerPoint 2010+ keeps the embedded copy in <p14:media r:embed>; the a:videoFile link may point outside.
        val embedded = nvPr.child("extLst")?.kids()?.firstNotNullOfOrNull { it.child("media")?.rel("embed") }
        val rel = listOfNotNull(embedded, file.rel("link"), file.rel("embed"))
            .mapNotNull { slide.rels[it] }
            .sortedBy { it.external }
            .firstOrNull() ?: return null
        val xfrm = pic.child("spPr")?.child("xfrm")
        val off = xfrm?.child("off")
        val ext = xfrm?.child("ext")
        val rect = if (off != null && ext != null) {
            val x = xf.x(off.long("x")?.toDouble() ?: 0.0)
            val y = xf.y(off.long("y")?.toDouble() ?: 0.0)
            val w = (ext.long("cx")?.toDouble() ?: 0.0) * xf.sx
            val h = (ext.long("cy")?.toDouble() ?: 0.0) * xf.sy
            RectF((x / slideW).toFloat(), (y / slideH).toFloat(), ((x + w) / slideW).toFloat(), ((y + h) / slideH).toFloat())
        } else RectF(0f, 0f, 1f, 1f)
        val video = file.tag == "videoFile" || file.tag == "quickTimeFile"
        return MediaClip(id, rel.target, rel.external, video, rect, 1f, false)
    }
}
