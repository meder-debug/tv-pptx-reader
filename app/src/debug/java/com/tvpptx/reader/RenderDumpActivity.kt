package com.tvpptx.reader

import android.app.Activity
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import java.io.File

/** Debug-only: renders each deck in <external files>/decks into <external files>/dump as PNGs. */
class RenderDumpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { textSize = 18f; setPadding(40, 40, 40, 40) }
        setContentView(status)
        val base = getExternalFilesDir(null)!!
        val src = File(base, "decks")
        val out = File(base, "dump").apply { deleteRecursively(); mkdirs() }
        val width = intent.getIntExtra("width", 960)
        val only = intent.getStringExtra("only")
        Thread {
            val log = StringBuilder()
            val files = src.listFiles()?.filter { Documents.isSupported(it.name) && (only == null || it.name.contains(only)) }?.sortedBy { it.name } ?: emptyList()
            for (f in files) {
                try {
                    val t0 = SystemClock.elapsedRealtime()
                    Documents.open(f, this).use { doc ->
                        log.append("${f.name}: ${doc.pageCount} pages, open ${SystemClock.elapsedRealtime() - t0} ms\n")
                        for (i in 0 until minOf(doc.pageCount, 60)) {
                            val t1 = SystemClock.elapsedRealtime()
                            val bmp = doc.render(i, width, (width / doc.aspect).toInt())
                            File(out, "${f.nameWithoutExtension}-${"%02d".format(i + 1)}.png").outputStream().use {
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            log.append("  page ${i + 1}: ${SystemClock.elapsedRealtime() - t1} ms\n")
                        }
                    }
                } catch (t: Throwable) {
                    log.append("${f.name}: FAILED ${t}\n${t.stackTrace.take(6).joinToString("\n") { "    at $it" }}\n")
                }
                runOnUiThread { status.text = log.toString() }
            }
            File(out, "done.txt").writeText(log.toString())
            runOnUiThread { status.text = log.toString() + "\nDONE" }
        }.start()
    }
}
