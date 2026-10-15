package com.tvpptx.reader

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.RectF
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
                            val height = (width / doc.aspect).toInt()
                            val name = "${f.nameWithoutExtension}-${"%02d".format(i + 1)}"
                            fun save(bmp: Bitmap, suffix: String) = File(out, "$name$suffix.png").outputStream().use {
                                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                            }
                            save(doc.render(i, width, height), "")
                            log.append("  page ${i + 1}: ${SystemClock.elapsedRealtime() - t1} ms")
                            val info = doc.info(i)
                            if (info.steps > 0 || info.media.isNotEmpty()) {
                                log.append(", ${info.steps} steps, media ${info.media.map { "${it.spid}:${it.target}${if (it.video) " video" else ""} vol=${it.volume} loop=${it.loop} ${it.rect}" }}, cues ${info.cues.map { "${it.step}:${it.spid}:${if (it.play) "play" else "stop"}" }}")
                            }
                            log.append("\n")
                            // --ez steps true: every click of the build; --ef zoom 2: the centre magnified.
                            if (intent.getBooleanExtra("steps", false)) {
                                for (s in 0..info.steps) save(doc.render(i, width, height, s), "-s$s")
                            }
                            val zoom = intent.getFloatExtra("zoom", 0f)
                            if (zoom > 1f) {
                                val h = 0.5f / zoom
                                save(doc.render(i, width, height, region = RectF(0.5f - h, 0.5f - h, 0.5f + h, 0.5f + h)), "-z")
                            }
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
