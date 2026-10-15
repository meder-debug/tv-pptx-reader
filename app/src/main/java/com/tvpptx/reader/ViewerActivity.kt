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
import android.media.AudioManager
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
import com.tvpptx.reader.pptx.MediaClip
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.min

class ViewerActivity : Activity(), UploadServer.Remote {
    companion object {
        private const val EXTRA_PATH = "path"
        /** The step of a slide whose build hasn't been read yet, entered from the next slide: its last. */
        private const val LAST = Int.MAX_VALUE
        private val ZOOM_LEVELS = floatArrayOf(2f, 3f, 4f)
        private const val HINT = "◀ ▶  change slide      ▲  zoom      hold OK  all slides      BACK  exit"
        private const val ZOOM_HINT = "◀ ▲ ▼ ▶  move      OK  zoom in      BACK  zoom out"
        private var hintShown = false

        fun intent(context: Context, file: File): Intent =
            Intent(context, ViewerActivity::class.java).putExtra(EXTRA_PATH, file.absolutePath)
    }

    private lateinit var slideView: SlideView
    private lateinit var mediaLayer: FrameLayout
    private lateinit var media: MediaPlayback
    private lateinit var progress: ProgressBar
    private lateinit var info: TextView
    private lateinit var hint: TextView
    private lateinit var message: TextView
    private lateinit var grid: GridView
    private lateinit var mediaDir: File
    private val thumbAdapter = ThumbAdapter()
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    /** Copies videos out of the deck, so a big one doesn't hold up rendering. */
    private val mediaWorker = Executors.newSingleThreadExecutor()

    private var doc: SlideDocument? = null
    private var title = ""
    private var index = 0
    /** How many clicks of the current slide's build are showing (LAST until its build is known). */
    private var step = 0
    private var generation = 0
    private val pages = HashMap<Int, PageInfo>()
    private val slides = LruCache<Long, Bitmap>(5)
    private val thumbs = LruCache<Int, Bitmap>(150)
    private val failed = HashSet<Long>()
    private val thumbQueue = ArrayDeque<Int>()
    private var busy = false
    private var renderW = 1280
    private var renderH = 720
    private var centerDown = false
    private var longPressed = false
    private var digits = ""
    /** The build moved forward onto [step], so its media cues still have to run. */
    private var cuesDue = false
    /** Part of the slide to render sharp for the zoomed view, and a counter that outdates older requests. */
    private var sharpWanted: RectF? = null
    private var sharpSeq = 0

    private val hideInfo = Runnable { info.animate().alpha(0f).setDuration(300).start() }
    private val hideHint = Runnable { hint.animate().alpha(0f).setDuration(400).start() }
    private val showSpinner = Runnable { progress.visibility = View.VISIBLE }
    private val jumpToDigits = Runnable {
        digits.toIntOrNull()?.let { go(it - 1) }
        digits = ""
    }

    private fun key(page: Int, step: Int) = page.toLong() * 100_000 + step

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC // the volume keys set the videos' volume
        mediaDir = File(cacheDir, "media")
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
        media.stopAll()
    }

    override fun onDestroy() {
        super.onDestroy()
        generation++
        closeDoc()
        worker.shutdown()
        mediaWorker.shutdown()
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
        mediaLayer = FrameLayout(this)
        root.addView(mediaLayer, FrameLayout.LayoutParams(-1, -1))
        media = MediaPlayback(this, mediaLayer) { notice(it) }
        slideView.onTransform = { frame, region -> media.layout(frame, region) }
        slideView.onSettled = { requestSharp() }
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
        hint = pill(16f).apply { text = HINT }
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
                if (slideView.zoomed) return true
                if (e.x < slideView.width / 3f) prev() else next()
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (!slideView.zoomed) return false
                slideView.dragBy(dx, dy)
                return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (slideView.zoomed) return true
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
        mediaWorker.execute {
            mediaDir.deleteRecursively()
            mediaDir.mkdirs()
        }
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
        step = 0
        cuesDue = true
        slideView.aspect = d.aspect
        if (!f.absolutePath.startsWith(cacheDir.absolutePath)) Library.recordOpened(this, f)
        slideView.post {
            computeRenderSize()
            pump()
        }
        showInfo()
        if (!hintShown) {
            hintShown = true
            showHint(HINT, 5000)
        }
    }

    private fun closeDoc() {
        val d = doc
        doc = null
        media.stopAll()
        endZoom(false)
        pages.clear()
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

    /**
     * Renders what's needed next, one page at a time: a sharp copy of the zoomed-in part, the
     * current slide, its next click, its neighbours, then thumbnails.
     */
    private fun pump() {
        val d = doc ?: return
        if (busy) return
        val gen = generation
        sharpWanted?.let { renderSharp(d, it, gen); return }
        val n = d.pageCount
        val want = ArrayList<Pair<Int, Int>>(5)
        want.add(index to step)
        pages[index]?.let { if (step < it.steps) want.add(index to step + 1) }
        want.add(index + 1 to 0)
        want.add(index - 1 to LAST)
        want.add(index + 2 to 0)
        val todo = want.firstOrNull { (p, s) -> p in 0 until n && needs(p, s) }
        if (todo != null) {
            renderPage(d, todo.first, todo.second, gen)
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

    private fun needs(page: Int, s: Int): Boolean {
        val p = pages[page] ?: return true
        val k = key(page, if (s == LAST) p.steps else s)
        return slides.get(k) == null && k !in failed
    }

    /** Renders [page] after [s] clicks, reading its build first if this is the first time. */
    private fun renderPage(d: SlideDocument, page: Int, s: Int, gen: Int) {
        busy = true
        val known = pages[page]
        val w = renderW
        val h = renderH
        worker.execute {
            val p = known ?: try { d.info(page) } catch (t: Throwable) { PageInfo.STATIC }
            val st = if (s == LAST) p.steps else s.coerceAtMost(p.steps)
            val bmp = try { d.render(page, w, h, st) } catch (t: Throwable) { null }
            main.post {
                busy = false
                if (gen == generation) {
                    if (known == null) onPageInfo(page, p)
                    if (bmp != null) slides.put(key(page, st), bmp) else failed.add(key(page, st))
                    if (page == index && st == step) display(true)
                }
                pump()
            }
        }
    }

    private fun onPageInfo(page: Int, p: PageInfo) {
        pages[page] = p
        if (page == index) {
            if (step > p.steps) step = p.steps
            runCues()
        }
        if (page == index || page == index + 1) prepareMedia(p)
    }

    private fun renderSharp(d: SlideDocument, region: RectF, gen: Int) {
        busy = true
        sharpWanted = null
        val seq = sharpSeq
        val page = index
        val st = step
        val w = renderW
        val h = renderH
        worker.execute {
            val bmp = try { d.render(page, w, h, st, region) } catch (t: Throwable) { null }
            main.post {
                busy = false
                if (gen == generation && seq == sharpSeq && page == index && st == step && slideView.zoomed) {
                    slideView.setSharp(bmp, region)
                }
                pump()
            }
        }
    }

    private fun display(animate: Boolean) {
        val k = key(index, step)
        val bmp = if (step == LAST) null else slides.get(k) ?: if (k in failed) errorBitmap() else null
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

    /** The next click of the build, or the next slide once it's all shown. */
    private fun next() {
        val d = doc ?: return
        val p = pages[index]
        when {
            p != null && step < p.steps -> setStep(step + 1)
            index < d.pageCount - 1 -> go(index + 1)
            else -> showInfo()
        }
    }

    /** Takes back the last click, or goes to the previous slide fully built, as PowerPoint does. */
    private fun prev() {
        if (doc == null) return
        when {
            pages[index] != null && step in 1 until LAST -> setStep(step - 1)
            index > 0 -> go(index - 1, LAST)
            else -> showInfo()
        }
    }

    private fun setStep(s: Int) {
        endZoom(false)
        val p = pages[index]
        if (s < step) p?.cues?.filter { it.play && it.step in (s + 1)..step }?.forEach { media.stop(it.spid) }
        cuesDue = s > step
        step = s
        display(true)
        runCues()
        pump()
    }

    private fun go(target: Int, s: Int = 0) {
        val d = doc ?: return
        val t = target.coerceIn(0, d.pageCount - 1)
        if (t != index) {
            endZoom(false)
            media.stopAll()
            index = t
            step = if (s == LAST) pages[t]?.steps ?: LAST else s
            cuesDue = s != LAST
            display(true)
            runCues()
            pages[t]?.let { prepareMedia(it) }
            pages[t + 1]?.let { prepareMedia(it) }
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

    /** A short message in the corner pill, e.g. about a video that can't play. */
    private fun notice(text: String) {
        info.text = text
        info.animate().cancel()
        info.alpha = 1f
        main.removeCallbacks(hideInfo)
        main.postDelayed(hideInfo, 4000)
    }

    private fun showHint(text: String, ms: Long) {
        hint.text = text
        hint.animate().cancel()
        hint.alpha = 1f
        main.removeCallbacks(hideHint)
        main.postDelayed(hideHint, ms)
    }

    private fun showGrid() {
        val d = doc ?: return
        endZoom(false)
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

    // ---- media ---------------------------------------------------------------------------

    /** Starts or stops the media that the current step's clicks call for. */
    private fun runCues() {
        val p = pages[index] ?: return
        if (!cuesDue || step == LAST) return
        cuesDue = false
        for (c in p.cues) {
            if (c.step != step) continue
            if (!c.play) { media.stop(c.spid); continue }
            p.media.firstOrNull { it.spid == c.spid }?.let { startMedia(it, c.step) }
        }
    }

    private fun startMedia(clip: MediaClip, at: Int) {
        val d = doc ?: return
        val what = if (clip.video) "video" else "sound"
        if (clip.external) {
            notice("This $what is linked from outside the presentation, so it can't be played.")
            return
        }
        val gen = generation
        val page = index
        mediaWorker.execute {
            val f = try { d.extractMedia(clip, mediaDir) } catch (t: Throwable) { null }
            main.post {
                if (gen != generation || page != index || step == LAST || step < at) return@post
                if (f == null) notice("This $what couldn't be read from the presentation.") else media.play(clip, f)
            }
        }
    }

    /** Copies a slide's media out of the deck ahead of time, so it starts at once when its click comes. */
    private fun prepareMedia(p: PageInfo) {
        val d = doc ?: return
        for (clip in p.media) {
            if (!clip.external) mediaWorker.execute { try { d.extractMedia(clip, mediaDir) } catch (t: Throwable) { } }
        }
    }

    // ---- zoom ----------------------------------------------------------------------------

    private fun startZoom() {
        if (doc == null || step == LAST || slides.get(key(index, step)) == null) return
        slideView.zoomTo(ZOOM_LEVELS[0])
        showZoom()
        showHint(ZOOM_HINT, 5000)
    }

    private fun zoomIn() {
        val z = slideView.zoom
        ZOOM_LEVELS.firstOrNull { it > z + 0.01f }?.let { slideView.zoomTo(it) }
        showZoom()
    }

    private fun zoomOut() {
        val z = slideView.zoom
        val out = ZOOM_LEVELS.lastOrNull { it < z - 0.01f }
        if (out == null) {
            endZoom(true)
            showInfo()
        } else {
            slideView.zoomTo(out)
            showZoom()
        }
    }

    private fun endZoom(animate: Boolean) {
        if (!slideView.zoomed) return
        sharpSeq++
        sharpWanted = null
        slideView.resetZoom(animate)
        hideHint.run()
        hideInfo.run() // the "Zoom 3×" pill
    }

    private fun showZoom() = notice("Zoom ${slideView.zoom.toInt()}×")

    private fun requestSharp() {
        if (doc == null || !slideView.zoomed) return
        sharpSeq++
        sharpWanted = slideView.targetRegion()
        pump()
    }

    // ---- keys ----------------------------------------------------------------------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (grid.visibility == View.VISIBLE) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) { hideGrid(); return true }
            return super.onKeyDown(keyCode, event)
        }
        if (slideView.zoomed) {
            // While zoomed the arrows move around the slide and BACK steps out; other keys (a
            // clicker's page keys, digits) leave zoom and do their usual job below.
            val move = 1f / 3
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { slideView.panBy(-move, 0f); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { slideView.panBy(move, 0f); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { slideView.panBy(0f, -move); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { slideView.panBy(0f, move); return true }
                KeyEvent.KEYCODE_BACK -> { zoomOut(); return true }
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { startZoom(); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_CHANNEL_DOWN,
            KeyEvent.KEYCODE_N -> { next(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_CHANNEL_UP,
            KeyEvent.KEYCODE_P -> { prev(); return true }
            KeyEvent.KEYCODE_MOVE_HOME -> { go(0); return true }
            KeyEvent.KEYCODE_MOVE_END -> { go(Int.MAX_VALUE); return true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TAB -> { showGrid(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                if (media.active) { media.togglePause(); return true }
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
     * OK: a short press goes to the next click or slide (zoomed in, it zooms in more), holding it
     * opens the slide grid. Handled before the views see it, so the release that follows a long
     * press can't also pick a slide in the grid.
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
                if (centerDown && !longPressed) { if (slideView.zoomed) zoomIn() else next() }
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
            cmd == "playpause" -> media.togglePause()
            cmd.startsWith("goto:") -> cmd.removePrefix("goto:").toIntOrNull()?.let { go(it - 1) }
        }
    }

    override fun state(): JSONObject = JSONObject()
        .put("presenting", doc != null)
        .put("slide", index + 1)
        .put("total", doc?.pageCount ?: 0)
        .put("title", title)
        .put("media", media.active)

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
            val bmp = thumbs.get(position) ?: slides.get(key(position, pages[position]?.steps ?: 0))
            holder.image.setImageBitmap(bmp)
            if (thumbs.get(position) == null) {
                thumbQueue.remove(position)
                thumbQueue.addFirst(position)
                main.post { pump() }
            }
            return view
        }
    }
}

/**
 * Shows one slide bitmap letterboxed on black, cross-fading when it changes. Zoomed in, it shows a
 * part of the slide magnified: at once from the slide bitmap, then sharp once the activity hands
 * over a render of just that part ([setSharp]).
 */
class SlideView(context: Context) : View(context) {
    companion object {
        private const val MAX_ZOOM = 4f
    }

    private var current: Bitmap? = null
    private var previous: Bitmap? = null
    private var fade = 1f
    private var animator: ValueAnimator? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()
    private val frame = RectF()

    /** Width / height of the slides. */
    var aspect = 16f / 9

    // The visible part of the slide: magnification z around centre (cx, cy), in slide fractions.
    // t* are where it's heading; the drawn values animate towards them.
    private var z = 1f
    private var cx = 0.5f
    private var cy = 0.5f
    private var tz = 1f
    private var tcx = 0.5f
    private var tcy = 0.5f
    private var mover: ValueAnimator? = null
    private var sharp: Bitmap? = null
    private val sharpRegion = RectF()

    /** Called when the view has stopped moving while zoomed in. */
    var onSettled: (() -> Unit)? = null
    /** Called as the slide moves on screen, with its frame (px) and the visible region (fractions). */
    var onTransform: ((RectF, RectF) -> Unit)? = null
    private val settle = Runnable { if (tz > 1f) onSettled?.invoke() }

    val zoomed get() = tz > 1f
    val zoom get() = tz

    fun show(bmp: Bitmap?, animate: Boolean) {
        if (bmp === current) return
        animator?.cancel()
        sharp = null
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

    /** A sharp render of [region] (slide fractions) to draw over the magnified bitmap. */
    fun setSharp(bmp: Bitmap?, region: RectF?) {
        sharp = bmp
        region?.let { sharpRegion.set(it) }
        invalidate()
    }

    /** The part of the slide that shows once the view stops moving. */
    fun targetRegion(): RectF = regionOf(tz, tcx, tcy)

    fun zoomTo(level: Float) = moveTo(level.coerceIn(1f, MAX_ZOOM), tcx, tcy, true)

    /** Moves the view by a fraction of what's visible. */
    fun panBy(dx: Float, dy: Float) = moveTo(tz, tcx + dx / tz, tcy + dy / tz, true)

    /** Follows a finger: screen pixels, no animation. */
    fun dragBy(px: Float, py: Float) {
        if (frame.width() <= 0f) return
        moveTo(tz, tcx + px / (frame.width() * tz), tcy + py / (frame.height() * tz), false)
    }

    fun resetZoom(animate: Boolean) = moveTo(1f, 0.5f, 0.5f, animate)

    private fun regionOf(z: Float, cx: Float, cy: Float): RectF {
        val half = 0.5f / z
        return RectF(cx - half, cy - half, cx + half, cy + half)
    }

    private fun moveTo(level: Float, x: Float, y: Float, animate: Boolean) {
        val half = 0.5f / level
        tz = level
        tcx = x.coerceIn(half, 1f - half)
        tcy = y.coerceIn(half, 1f - half)
        if (level <= 1f) sharp = null
        mover?.cancel()
        removeCallbacks(settle)
        if (!animate) {
            z = tz; cx = tcx; cy = tcy
            invalidate()
            postDelayed(settle, 150)
            return
        }
        val z0 = z; val x0 = cx; val y0 = cy
        mover = ValueAnimator.ofFloat(0f, 1f).setDuration(180).apply {
            addUpdateListener {
                val t = it.animatedValue as Float
                z = z0 + (tz - z0) * t
                cx = x0 + (tcx - x0) * t
                cy = y0 + (tcy - y0) * t
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    removeCallbacks(settle)
                    postDelayed(settle, 120)
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        val vw = width.toFloat()
        val vh = height.toFloat()
        val fw: Float
        val fh: Float
        if (vw / vh > aspect) { fh = vh; fw = vh * aspect } else { fw = vw; fh = vw / aspect }
        frame.set((vw - fw) / 2, (vh - fh) / 2, (vw + fw) / 2, (vh + fh) / 2)
        val region = regionOf(z, cx, cy)
        onTransform?.invoke(frame, region)
        if (z <= 1.001f) {
            previous?.let { draw(canvas, it, 255, frame) }
            current?.let { draw(canvas, it, (fade * 255).toInt(), frame) }
            return
        }
        canvas.save()
        canvas.clipRect(frame)
        current?.let { draw(canvas, it, 255, map(0f, 0f, 1f, 1f, region)) }
        sharp?.let { draw(canvas, it, 255, map(sharpRegion.left, sharpRegion.top, sharpRegion.right, sharpRegion.bottom, region)) }
        canvas.restore()
    }

    /** Screen rect of the slide-fraction rect (l, t, r, b) when [region] fills the frame. */
    private fun map(l: Float, t: Float, r: Float, b: Float, region: RectF): RectF {
        val sx = frame.width() / region.width()
        val sy = frame.height() / region.height()
        dst.set(frame.left + (l - region.left) * sx, frame.top + (t - region.top) * sy,
            frame.left + (r - region.left) * sx, frame.top + (b - region.top) * sy)
        return dst
    }

    private fun draw(canvas: Canvas, b: Bitmap, alpha: Int, to: RectF) {
        paint.alpha = alpha
        canvas.drawBitmap(b, null, to, paint)
    }
}
