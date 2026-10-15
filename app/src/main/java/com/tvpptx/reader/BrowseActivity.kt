package com.tvpptx.reader

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.storage.StorageManager
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Folder browser for the projector's storage and USB sticks, made for a D-pad. */
class BrowseActivity : Activity() {
    private class Root(val label: String, val dir: File, val badge: String, val color: Int)

    private object Picker

    private lateinit var list: ListView
    private lateinit var heading: TextView
    private lateinit var adapter: RowAdapter
    private var dir: File? = null
    private var roots: List<Root> = emptyList()
    /** Selected position in each parent, so BACK returns to where you were. */
    private val positions = ArrayList<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_browse)
        list = findViewById(R.id.list)
        heading = findViewById(R.id.heading)
        adapter = RowAdapter(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> onPick(adapter.rows[pos].payload, pos) }
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1)
        }
        show(null, 0)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        show(dir, list.selectedItemPosition.coerceAtLeast(0))
    }

    private fun onPick(payload: Any?, pos: Int) {
        when (payload) {
            is Root -> { positions.add(pos); show(payload.dir, 0) }
            is File -> if (payload.isDirectory) {
                positions.add(pos); show(payload, 0)
            } else {
                startActivity(ViewerActivity.intent(this, payload))
            }
            Picker -> openSystemPicker()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val d = dir
        if (d == null) {
            super.onBackPressed()
            return
        }
        val parent = if (roots.any { it.dir.absolutePath == d.absolutePath }) null else d.parentFile
        show(parent, positions.removeLastOrNull() ?: 0)
    }

    private fun show(target: File?, select: Int) {
        dir = target
        val rows = ArrayList<Row>()
        if (target == null) {
            heading.text = "Choose a location"
            roots = findRoots()
            for (r in roots) rows.add(Row(r.label, r.dir.absolutePath, r.badge, r.color, r))
            rows.add(Row("Other apps and cloud drives…", "Opens the system file picker", "MORE", Badges.OTHER, Picker))
        } else {
            heading.text = target.absolutePath
            val files = target.listFiles()
            if (files == null) {
                rows.add(Row("This folder can't be opened", "The app may not have permission to read it", "!", Badges.OTHER, null))
            } else {
                val shown = files.filter { !it.name.startsWith(".") && (it.isDirectory || Documents.isSupported(it.name)) }
                    .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
                for (f in shown) {
                    if (f.isDirectory) rows.add(Row(f.name, "", "DIR", Badges.FOLDER, f))
                    else {
                        val (b, c) = Badges.forFile(f)
                        rows.add(Row(f.name, fileDetail(this, f), b, c, f))
                    }
                }
                if (shown.isEmpty()) rows.add(Row("No presentations here", "Press BACK to go up", "–", Badges.OTHER, null))
            }
        }
        adapter.rows = rows
        list.setSelection(select.coerceIn(0, maxOf(0, rows.size - 1)))
        list.requestFocus()
    }

    private fun findRoots(): List<Root> {
        val out = ArrayList<Root>()
        val seen = HashSet<String>()
        fun add(label: String, dir: File?, badge: String, color: Int) {
            if (dir == null || !dir.exists()) return
            val key = dir.absolutePath
            if (seen.add(key)) out.add(Root(label, dir, badge, color))
        }
        add("Received from phone", Library.receivedDir(this), "PHONE", Badges.PPTX)
        @Suppress("DEPRECATION")
        add("Projector storage", Environment.getExternalStorageDirectory(), "MEM", Badges.FOLDER)
        // USB sticks and SD cards
        val sm = getSystemService(Context.STORAGE_SERVICE) as StorageManager
        for (v in sm.storageVolumes) {
            if (v.isPrimary) continue
            val path: File? = if (Build.VERSION.SDK_INT >= 30) v.directory else try {
                v.javaClass.getMethod("getPathFile").invoke(v) as? File
            } catch (e: Exception) {
                null
            }
            add(v.getDescription(this) ?: "USB drive", path, "USB", Badges.DRIVE)
        }
        File("/storage").listFiles()?.forEach { f ->
            if (f.name != "emulated" && f.name != "self" && f.isDirectory && f.canRead()) add("USB drive (${f.name})", f, "USB", Badges.DRIVE)
        }
        for (m in listOf("/mnt/usb", "/mnt/usbhost", "/mnt/media_rw", "/udisk")) {
            File(m).listFiles()?.forEach { f -> if (f.isDirectory && f.canRead()) add("USB drive (${f.name})", f, "USB", Badges.DRIVE) }
        }
        return out
    }

    private fun openSystemPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
                    "application/pdf", "application/octet-stream"
                )
            )
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, 2)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "This projector has no system file picker", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (requestCode == 2 && resultCode == RESULT_OK) {
            startActivity(Intent(this, ViewerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }
}
