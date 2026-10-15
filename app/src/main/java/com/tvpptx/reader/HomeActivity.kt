package com.tvpptx.reader

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import java.io.File

class HomeActivity : Activity(), UploadServer.Listener {
    private lateinit var list: ListView
    private lateinit var empty: TextView
    private lateinit var qr: ImageView
    private lateinit var url: TextView
    private lateinit var status: TextView
    private lateinit var adapter: RowAdapter
    private val handler = Handler(Looper.getMainLooper())
    private var shownUrl: String? = null

    private val refreshUrl = object : Runnable {
        override fun run() {
            updateUrl()
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        qr = findViewById(R.id.qr)
        url = findViewById(R.id.url)
        status = findViewById(R.id.status)
        adapter = RowAdapter(this)
        list.adapter = adapter
        findViewById<View>(R.id.browse).setOnClickListener {
            startActivity(Intent(this, BrowseActivity::class.java))
        }
        list.setOnItemClickListener { _, _, pos, _ ->
            (adapter.rows[pos].payload as? Library.Item)?.let { startActivity(ViewerActivity.intent(this, it.file)) }
        }
        list.setOnItemLongClickListener { _, _, pos, _ ->
            (adapter.rows[pos].payload as? Library.Item)?.let { confirmRemove(it) }
            true
        }
    }

    override fun onResume() {
        super.onResume()
        UploadServer.listener = this
        refresh()
        handler.post(refreshUrl)
        if (adapter.count > 0) list.requestFocus() else findViewById<View>(R.id.browse).requestFocus()
    }

    override fun onPause() {
        super.onPause()
        if (UploadServer.listener === this) UploadServer.listener = null
        handler.removeCallbacks(refreshUrl)
    }

    private fun refresh() {
        adapter.rows = Library.items(this).map { item ->
            val (badge, color) = Badges.forFile(item.file)
            val where = if (item.received) "From phone" else item.file.parent ?: ""
            Row(item.file.name, fileDetail(this, item.file, where), badge, color, item)
        }
        empty.visibility = if (adapter.count == 0) View.VISIBLE else View.GONE
    }

    private fun updateUrl() {
        val u = UploadServer.url()
        if (u == shownUrl) return
        shownUrl = u
        if (u == null) {
            url.text = "Not connected"
            qr.setImageDrawable(null)
            qr.visibility = View.INVISIBLE
            findViewById<TextView>(R.id.hint).text = "Connect the projector to Wi-Fi to send presentations from your phone."
        } else {
            url.text = u.removePrefix("http://")
            qr.setImageBitmap(qrBitmap(u, 360))
            qr.visibility = View.VISIBLE
            findViewById<TextView>(R.id.hint).text =
                "Phone on the same Wi-Fi: scan the code, pick a .pptx or .pdf and it opens here. The page also works as a clicker."
        }
    }

    private fun confirmRemove(item: Library.Item) {
        val action = if (item.received) "Delete" else "Remove from list"
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(item.file.name)
            .setMessage(if (item.received) "Delete this file from the projector?" else "Remove it from the recent list? The file itself stays where it is.")
            .setPositiveButton(action) { _, _ ->
                Library.remove(this, item)
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onProgress(name: String, percent: Int) {
        status.text = if (percent < 0) "Upload of $name failed" else "Receiving $name… $percent%"
    }

    override fun onReceived(file: File) {
        status.text = "Received ${file.name}"
        refresh()
    }
}
