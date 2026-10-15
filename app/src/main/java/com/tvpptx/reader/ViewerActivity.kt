package com.tvpptx.reader

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.LruCache
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

class ViewerActivity : Activity(), UploadServer.Remote {
    companion object {
        private const val EXTRA_PATH = "path"
        private var hintShown = false

        fun intent(context: Context, file: File): Intent =
            Intent(context, ViewerActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath)
    }

    private lateinit var slideView: SlideView
    private lateinit var progress: ProgressBar
    private lateinit var info: TextView
    private lateinit var hint: TextView
    private lateinit var message: TextView
    private lateinit var grid: GridView
    private val thumbAdapter = ThumbAdapter()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()

    private var doc: SlideDocument? = null
    private var title = ""
    private var index = 0
    private var generation = 0
    private val slides = LruCache<Int, Bitmap>(4)
    private val thumbs = LruCache<Int, Bitmap>(150)
    private val failed = HashSet<Int>()
    private val thumbQueue = ArrayDeque<Int>()
    private var busy = false
    private var renderW = 1280
    private var renderH = 720
    private var centerDown = false
    private var longPressed = false
    private var digits = ""

    private val hideInfo = Runnable { info.animate().alpha(0f).setDuration(300).start() }
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(400).start() }
    private val showSpinner = Runnable { progress.visibility = View.VISIBLE }
    private val jumpToDigits = Runnable {
        digits.toIntOrNull()?.let { go(it - 1) }
        digits = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildUi()
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hideGrid()
        load(intent)
    }

    override fun onResume() {
        super.onResume()
        UploadServer.remote = this
        hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        if (UploadServer.remote === this) UploadServer.remote = null
    }

    override fun onDestroy() {
        super.onDestroy()
        generation++
        closeDoc()
        worker.shutdown()
        main.removeCallbacksAndMessages(null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        thumbs.evictAll()
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) slides.trimToSize(1)
    }

    // ---- UI ------------------------------------------------------------------------------

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun pill(size: Float) = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = size
        setBackgroundResource(R.drawable.pill_bg)
        setPadding(dp(18), dp(8), dp(18), dp(8))
        alpha = 0f
    }

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        slideView = SlideView(this)
        root.addView(slideView, FrameLayout.LayoutParams(-1, -1))
        progress = ProgressBar(this).apply { isIndeterminate = true; visibility = View.GONE }
        root.addView(progress, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        message = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(dp(48), dp(24), dp(48), dp(24))
            visibility = View.GONE
        }
        root.addView(message, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        info = pill(18f)
        root.addView(info, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(24), dp(20)) })
        hint = pill(16f).apply { text = "◀ ▶  change slide      hold OK  all slides      BACK  exit" }
        root.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(0, 0, 0, dp(20)) })
        grid = GridView(this).apply {
            numColumns = 4
            horizontalSpacing = dp(14)
            verticalSpacing = dp(14)
            setPadding(dp(32), dp(28), dp(32), dp(28))
            clipToPadding = false
            setBackgroundColor(0xF20F1419.toInt())
            selector = getDrawable(R.drawable.grid_selector)
            setDrawSelectorOnTop(true)
            visibility = View.GONE
            adapter = thumbAdapter
            setOnItemClickListener { _, _, pos, _ -> hideGrid(); go(pos) }
        }
        root.addView(grid, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (e.x < slideView.width / 3f) prev() else next()
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy)) { if (vx < 0) next() else prev(); return true }
                return false
            }
            override fun onLongPress(e: MotionEvent) = showGrid()
        })
        slideView.setOnTouchListener { _, e -> gestures.onTouchEvent(e) }
    }

    // ---- documents -----------------------------------------------------------------------

    private fun load(intent: Intent) {
        val gen = ++generation
        closeDoc()
        slideView.show(null, false)
        message.visibility = View.GONE
        progress.visibility = View.VISIBLE
        val path = intent.getStringExtra(EXTRA_PATH)
        val uri = intent.data
        worker.execute {
            try {
                val f = when {
                    path != null -> File(path)
                    uri?.scheme == "file" -> File(uri.path ?: "")
                    uri != null -> importUri(uri)
                    else -> throw IllegalArgumentException("Nothing to open")
                }
                if (!f.isFile) throw IllegalArgumentException("The file is no longer there:\n${f.name}")
                val d = Documents.open(f, this)
                main.post {
                    if (gen != generation || isDestroyed) worker.execute { d.close() } else onOpened(f, d)
                }
            } catch (t: Throwable) {
                main.post { if (gen == generation) showError(t) }
            }
        }
    }

    private fun onOpened(f: File, d: SlideDocument) {
        doc = d
        title = f.name
        index = 0
        if (!f.absolutePath.startsWith(cacheDir.absolutePath)) Library.recordOpened(this, f)
        slideView.post {
            computeRenderSize()
            pump()
        }
        showInfo()
        if (!hintShown) {
            hintShown = true
            hint.alpha = 1f
            main.postDelayed(hideHint, 5000)
        }
    }

    private fun closeDoc() {
        val d = doc
        doc = null
        slides.evictAll()
        thumbs.evictAll()
        failed.clear()
        thumbQueue.clear()
        if (d != null) worker.execute { d.close() }
    }

    private fun importUri(uri: Uri): File {
        var name = "presentation.pptx"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it }
        }
        if (!Documents.isSupported(name)) {
            name += if (contentResolver.getType(uri) == "application/pdf") ".pdf" else ".pptx"
        }
        val dir = File(cacheDir, "imports").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, name.replace('/', '_'))
        contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } }
            ?: throw IllegalArgumentException("Couldn't read the file")
        return out
    }

    private fun showError(t: Throwable) {
        progress.visibility = View.GONE
        message.visibility = View.VISIBLE
        val why = when (t) {
            is OutOfMemoryError -> "The presentation is too large for this device's memory."
            is java.util.zip.ZipException -> "This doesn't look like a valid PowerPoint file."
            else -> t.message ?: "Something went wrong."
        }
        message.text = "Couldn't open this file.\n\n$why\n\nPress BACK to return."
    }

    private fun computeRenderSize() {
        val d = doc ?: return
        val vw = slideView.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val vh = slideView.height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        var w: Float
        var h: Float
        if (vw.toFloat() / vh > d.aspect) { h = vh.toFloat(); w = h * d.aspect } else { w = vw.toFloat(); h = w / d.aspect }
        val cap = min(1f, 1920f / maxOf(w, h))
        w *= cap; h *= cap
        renderW = maxOf(1, w.toInt())
        renderH = maxOf(1, h.toInt())
    }

    /** Renders what's needed next, one page at a time: the current slide, its neighbours, then thumbnails. */
    private fun pump() {
        val d = doc ?: return
        if (busy) return
        val n = d.pageCount
        val gen = generation
        val page = listOf(index, index + 1, index - 1, index + 2).firstOrNull {
            it in 0 until n && slides.get(it) == null && it !in failed
        }
        if (page != null) {
            busy = true
            val w = renderW
            val h = renderH
            worker.execute {
                val bmp = try { d.render(page, w, h) } catch (t: Throwable) { null }
                main.post {
                    busy = false
                    if (gen == generation) {
                        if (bmp != null) slides.put(page, bmp) else failed.add(page)
                        if (page == index) display(true)
                    }
                    pump()
                }
            }
            return
        }
        if (grid.visibility != View.VISIBLE) return
        val first = grid.firstVisiblePosition - 4
        val last = grid.lastVisiblePosition + 4
        var t: Int? = null
        while (thumbQueue.isNotEmpty()) {
            val c = thumbQueue.removeFirst()
            if (c in first..last && c < n && thumbs.get(c) == null) { t = c; break }
        }
        val thumb = t ?: return
        busy = true
        val tw = 320
        val th = maxOf(1, (tw / d.aspect).toInt())
        worker.execute {
            val bmp = try { d.render(thumb, tw, th) } catch (e: Throwable) { null }
            main.post {
                busy = false
                if (gen == generation && bmp != null) {
                    thumbs.put(thumb, bmp)
                    val child = grid.getChildAt(thumb - grid.firstVisiblePosition)
                    (child?.tag as? ThumbHolder)?.takeIf { it.position == thumb }?.image?.setImageBitmap(bmp)
                }
                pump()
            }
        }
    }

    private fun display(animate: Boolean) {
        val bmp = slides.get(index) ?: if (index in failed) errorBitmap() else null
        if (bmp != null) {
            main.removeCallbacks(showSpinner)
            progress.visibility = View.GONE
            slideView.show(bmp, animate)
        } else {
            main.removeCallbacks(showSpinner)
            main.postDelayed(showSpinner, 200)
        }
    }

    private fun errorBitmap(): Bitmap {
        val bmp = Bitmap.createBitmap(renderW, renderH, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF1E2630.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = renderH / 20f; textAlign = Paint.Align.CENTER }
        c.drawText("This slide couldn't be shown", renderW / 2f, renderH / 2f, p)
        return bmp
    }

    // ---- navigation ----------------------------------------------------------------------

    private fun next() = go(index + 1)
    private fun prev() = go(index - 1)

    private fun go(target: Int) {
        val d = doc ?: return
        val t = target.coerceIn(0, d.pageCount - 1)
        if (t != index) {
            index = t
            display(true)
            pump()
        }
        showInfo()
    }

    private fun showInfo() {
        val d = doc ?: return
        info.text = if (digits.isNotEmpty()) "Go to slide $digits" else "${index + 1} / ${d.pageCount}"
        info.animate().cancel()
        info.alpha = 1f
        main.removeCallbacks(hideInfo)
        main.postDelayed(hideInfo, 2500)
    }

    private fun showGrid() {
        val d = doc ?: return
        thumbQueue.clear()
        val cellW = (grid.width.takeIf { it > 0 } ?: slideView.width) - dp(64) - dp(14) * 3
        thumbAdapter.itemHeight = maxOf(dp(60), (cellW / 4 / d.aspect).toInt())
        grid.visibility = View.VISIBLE
        thumbAdapter.notifyDataSetChanged()
        grid.setSelection(index)
        grid.requestFocus()
    }

    private fun hideGrid() {
        if (grid.visibility != View.VISIBLE) return
        grid.visibility = View.GONE
        thumbQueue.clear()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (grid.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) { hideGrid(); return true }
            return super.onKeyDown(keyCode, event)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_N -> { next(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_P -> { prev(); return true }
            KeyEvent.KEYCODE_MOVE_HOME -> { go(0); return true }
            KeyEvent.KEYCODE_MOVE_END -> { go(Int.MAX_VALUE); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TAB -> { showGrid(); return true }
        }
        if (keyCode in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 && doc != null) {
            digits = (digits + (keyCode - KeyEvent.KEYCODE_0)).takeLast(4)
            showInfo()
            main.removeCallbacks(jumpToDigits)
            main.postDelayed(jumpToDigits, 1300)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * OK: a short press goes to the next slide, holding it opens the slide grid. Handled before the
     * views see it, so the release that follows a long press can't also pick a slide in the grid.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val confirm = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!confirm || (grid.visibility == View.VISIBLE && !centerDown)) return super.dispatchKeyEvent(event)
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                centerDown = true
                longPressed = false
            } else if (centerDown && !longPressed &&
                (event.isLongPress || event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout())
            ) {
                longPressed = true
                showGrid()
            }
            KeyEvent.ACTION_UP -> {
                if (centerDown && !longPressed) next()
                centerDown = false
                longPressed = false
            }
        }
        return true
    }

    // ---- phone remote --------------------------------------------------------------------

    override fun command(cmd: String) {
        when {
            cmd == "next" -> next()
            cmd == "prev" -> prev()
            cmd == "first" -> go(0)
            cmd == "last" -> go(Int.MAX_VALUE)
            cmd.startsWith("goto:") -> cmd.removePrefix("goto:").toIntOrNull()?.let { go(it - 1) }
        }
    }

    override fun state(): JSONObject = JSONObject()
        .put("presenting", doc != null)
        .put("slide", index + 1)
        .put("total", doc?.pageCount ?: 0)
        .put("title", title)

    // ---- overview grid -------------------------------------------------------------------

    private class ThumbHolder(val image: ImageView, val label: TextView) {
        var position = -1
    }

    private inner class ThumbAdapter : BaseAdapter() {
        var itemHeight = 160

        override fun getCount() = doc?.pageCount ?: 0
        override fun getItem(position: Int) = position
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val holder: ThumbHolder
            val view: View
            if (convertView == null) {
                val frame = FrameLayout(this@ViewerActivity)
                val image = ImageView(this@ViewerActivity).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setBackgroundColor(0xFF26303B.toInt())
                }
                frame.addView(image, FrameLayout.LayoutParams(-1, -1))
                val label = TextView(this@ViewerActivity).apply {
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setBackgroundColor(0xB3000000.toInt())
                    setPadding(dp(8), dp(2), dp(8), dp(2))
                }
                frame.addView(label, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START))
                holder = ThumbHolder(image, label)
                frame.tag = holder
                view = frame
            } else {
                view = convertView
                holder = convertView.tag as ThumbHolder
            }
            view.layoutParams = (view.layoutParams ?: ViewGroup.LayoutParams(-1, itemHeight)).apply { height = itemHeight }
            holder.position = position
            holder.label.text = (position + 1).toString()
            val bmp = thumbs.get(position) ?: slides.get(position)
            holder.image.setImageBitmap(bmp)
            if (thumbs.get(position) == null && slides.get(position) == null) {
                thumbQueue.remove(position)
                thumbQueue.addFirst(position)
                main.post { pump() }
            }
            return view
        }
    }
}

/** Shows one slide bitmap letterboxed on black, cross-fading when it changes. */
class SlideView(context: Context) : View(context) {
    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var fade = 1f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    fun show(bmp: Bitmap?, animate: Boolean) {
        if (bmp === current) return
        animator?.cancel()
        previous = if (animate && bmp != null) current else null
        current = bmp
        if (previous != null) {
            fade = 0f
            animator = ValueAnimator.ofFloat(0f, 1f).setDuration(220).apply {
                addUpdateListener { fade = it.animatedValue as Float; invalidate() }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) { previous = null; fade = 1f; invalidate() }
                })
                start()
            }
        } else {
            fade = 1f
        }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        previous?.let { draw(canvas, it, 255) }
        current?.let { draw(canvas, it, (fade * 255).toInt()) }
    }

    private fun draw(canvas: Canvas, b: Bitmap, alpha: Int) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val s = min(vw / b.width, vh / b.height)
        val w = b.width * s
        val h = b.height * s
        dst.set((vw - w) / 2, (vh - h) / 2, (vw + w) / 2, (vh + h) / 2)
        paint.alpha = alpha
        canvas.drawBitmap(b, null, dst, paint)
    }
}
