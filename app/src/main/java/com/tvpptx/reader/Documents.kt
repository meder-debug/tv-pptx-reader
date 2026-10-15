package com.tvpptx.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tvpptx.reader.pptx.ImageCache
import com.tvpptx.reader.pptx.Presentation
import com.tvpptx.reader.pptx.Presets
import com.tvpptx.reader.pptx.SlideRenderer
import java.io.Closeable
import java.io.File

/** Something we can page through: a PowerPoint deck or a PDF. Rendering is single-threaded. */
interface SlideDocument : Closeable {
    val pageCount: Int
    /** Width / height of a page. */
    val aspect: Float
    fun render(page: Int, width: Int, height: Int): Bitmap
}

class PptxDocument(file: File, context: Context) : SlideDocument {
    private val pres = Presentation(file)
    private val renderer = SlideRenderer(pres, ImageCache(pres.pkg))

    init {
        Presets.load(context.assets)
        if (pres.slideCount == 0) {
            pres.close()
            throw IllegalArgumentException("This presentation has no slides")
        }
    }

    override val pageCount get() = pres.visibleSlides.size
    override val aspect get() = pres.slideW.toFloat() / pres.slideH

    @Synchronized
    override fun render(page: Int, width: Int, height: Int): Bitmap =
        renderer.render(pres.visibleSlides[page], width, height)

    override fun close() = pres.close()
}

class PdfDocument(file: File) : SlideDocument {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val pdf = PdfRenderer(fd)
    override val pageCount get() = pdf.pageCount
    override val aspect: Float = if (pdf.pageCount == 0) 16f / 9 else pdf.openPage(0).use { it.width.toFloat() / it.height }

    @Synchronized
    override fun render(page: Int, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        pdf.openPage(page).use { it.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY) }
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
