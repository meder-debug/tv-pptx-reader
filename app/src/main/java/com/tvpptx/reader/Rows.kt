package com.tvpptx.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.io.File

/** One line in a list: a coloured badge, a title and a detail line. */
class Row(val title: String, val detail: String, val badge: String, val badgeColor: Int, val payload: Any?)

class RowAdapter(private val context: Context) : BaseAdapter() {
    var rows: List<Row> = emptyList()
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    override fun getCount() = rows.size
    override fun getItem(position: Int) = rows[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_row, parent, false)
        val row = rows[position]
        v.findViewById<TextView>(R.id.title).text = row.title
        v.findViewById<TextView>(R.id.detail).apply {
            text = row.detail
            visibility = if (row.detail.isEmpty()) View.GONE else View.VISIBLE
        }
        v.findViewById<TextView>(R.id.badge).apply {
            text = row.badge
            background = GradientDrawable().apply {
                setColor(row.badgeColor)
                cornerRadius = 8 * resources.displayMetrics.density
            }
        }
        return v
    }
}

object Badges {
    const val PPTX = 0xFFD24726.toInt()
    const val PDF = 0xFFC62828.toInt()
    const val FOLDER = 0xFF3D7BD9.toInt()
    const val DRIVE = 0xFF2E9E6A.toInt()
    const val OTHER = 0xFF5A6B7D.toInt()

    fun forFile(file: File): Pair<String, Int> = when (file.extension.lowercase()) {
        "pdf" -> "PDF" to PDF
        "ppsx", "ppsm" -> "PPSX" to PPTX
        else -> "PPTX" to PPTX
    }
}

fun fileDetail(context: Context, file: File, prefix: String? = null): String {
    val parts = ArrayList<String>()
    prefix?.let { parts.add(it) }
    parts.add(Formatter.formatShortFileSize(context, file.length()))
    parts.add(DateUtils.getRelativeTimeSpanString(file.lastModified(), System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString())
    return parts.joinToString("  ·  ")
}

fun qrBitmap(text: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val pixels = IntArray(matrix.width * matrix.height)
    for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
        pixels[y * matrix.width + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}
