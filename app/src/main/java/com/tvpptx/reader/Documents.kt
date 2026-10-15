package com.tvpptx.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tvpptx.reader.pptx.ImageCache
import com.tvpptx.reader.pptx.MediaClip
import com.tvpptx.reader.pptx.MediaCue
import com.tvpptx.reader.pptx.PptxPackage
import com.tvpptx.reader.pptx.Presentation
import com.tvpptx.reader.pptx.Presets
import com.tvpptx.reader.pptx.ShowState
import com.tvpptx.reader.pptx.SlideRenderer
import com.tvpptx.reader.pptx.SlideTimeline
import com.tvpptx.reader.pptx.Timing
import java.io.Closeable
import java.io.File
import kotlin.math.min

/** What the viewer needs to know about a page: how many clicks it builds in, and its media. */
class PageInfo(val steps: Int = 0, val media: List<MediaClip> = emptyList(), val cues: List<MediaCue> = emptyList()) {
    companion object {
        val STATIC = PageInfo()
    }
}

/** Something we can page through: a PowerPoint deck or a PDF. Rendering is single-threaded. */
interface SlideDocument : Closeable {
    val pageCount: Int
    /** Width / height of a page. */
    val aspect: Float

    /** Reads the page, so call it off the main thread. */
    fun info(page: Int): PageInfo = PageInfo.STATIC

    /**
     * Renders [page] as it looks after [step] clicks of its build ([ALL_STEPS]: everything shown,
     * as in a thumbnail). A [region] in page fractions renders just that part, filling the bitmap.
     */
    fun render(page: Int, width: Int, height: Int, step: Int = ALL_STEPS, region: RectF? = null): Bitmap

    /** Copies a media clip out to [dir] and returns the file, or null. Safe beside [render]. */
    fun extractMedia(clip: MediaClip, dir: File): File? = null

    companion object {
        const val ALL_STEPS = -1
    }
}

class PptxDocument(private val file: File, context: Context) : SlideDocument {
    private val pres = Presentation(file)
    private val renderer = SlideRenderer(pres, ImageCache(pres.pkg))
    private val timelines = HashMap<Int, SlideTimeline>()

    init {
        Presets.load(context.assets)
        if (pres.slideCount == 0) {
            pres.close()
            throw IllegalArgumentException("This presentation has no slides")
        }
    }

    override val pageCount get() = pres.visibleSlides.size
    override val aspect get() = pres.slideW.toFloat() / pres.slideH

    private fun timeline(page: Int): SlideTimeline = timelines.getOrPut(page) {
        try {
            Timing.read(pres.slide(pres.visibleSlides[page]), pres.slideW, pres.slideH)
        } catch (e: Exception) {
            SlideTimeline.NONE // a build we can't read just shows the whole slide
        }
    }

    @Synchronized
    override fun info(page: Int): PageInfo = timeline(page).let { PageInfo(it.steps, it.media, it.cues) }

    @Synchronized
    override fun render(page: Int, width: Int, height: Int, step: Int, region: RectF?): Bitmap {
        val show = if (step == SlideDocument.ALL_STEPS) ShowState.ALL else timeline(page).state(step)
        return renderer.render(pres.visibleSlides[page], width, height, show, region)
    }

    override fun extractMedia(clip: MediaClip, dir: File): File? {
        if (clip.external) return null
        val out = File(dir, "${clip.target.hashCode().toUInt()}.${clip.target.substringAfterLast('.', "bin")}")
        return out.takeIf { PptxPackage.extract(file, clip.target, out) }
    }

    override fun close() = pres.close()
}

class PdfDocument(file: File) : SlideDocument {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val pdf = PdfRenderer(fd)
    override val pageCount get() = pdf.pageCount
    override val aspect: Float = if (pdf.pageCount == 0) 16f / 9 else pdf.openPage(0).use { it.width.toFloat() / it.height }

    @Synchronized
    override fun render(page: Int, width: Int, height: Int, step: Int, region: RectF?): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        pdf.openPage(page).use { p ->
            val transform = region?.let {
                val s = min(width / (p.width * it.width()), height / (p.height * it.height()))
                Matrix().apply {
                    setScale(s, s)
                    postTranslate(-it.left * p.width * s, -it.top * p.height * s)
                }
            }
            p.render(bmp, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        return bmp
    }

    override fun close() {
        pdf.close()
        fd.close()
    }
}

object Documents {
    val PPTX_EXT = setOf("pptx", "ppsx", "pptm", "ppsm", "potx", "potm")
    val SUPPORTED_EXT = PPTX_EXT + "pdf"

    fun isSupported(name: String) = name.substringAfterLast('.', "").lowercase() in SUPPORTED_EXT

    fun open(file: File, context: Context): SlideDocument {
        val ext = file.extension.lowercase()
        return when {
            ext == "pdf" -> PdfDocument(file)
            ext == "ppt" || ext == "pps" -> throw IllegalArgumentException(
                "Old .ppt files aren't supported.\nSave the presentation as .pptx (or PDF) and try again."
            )
            else -> PptxDocument(file, context)
        }
    }
}
