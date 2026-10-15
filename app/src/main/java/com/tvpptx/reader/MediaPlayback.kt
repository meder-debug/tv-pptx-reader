package com.tvpptx.reader

import android.content.Context
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import com.tvpptx.reader.pptx.MediaClip
import java.io.File

/**
 * Plays a slide's videos and sounds. A video plays in a TextureView laid over its frame on the
 * slide (the slide bitmap already shows its poster picture underneath); a sound has no view.
 * Main thread only.
 */
class MediaPlayback(private val context: Context, private val layer: FrameLayout, private val onError: (String) -> Unit) {
    private inner class Item(val clip: MediaClip, val file: File) {
        var player: MediaPlayer? = null
        var view: TextureView? = null
        var surface: Surface? = null

        fun start(surface: Surface?) {
            val p = MediaPlayer()
            player = p
            try {
                p.setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(if (clip.video) AudioAttributes.CONTENT_TYPE_MOVIE else AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build())
                p.setDataSource(file.path)
                surface?.let { p.setSurface(it) }
                p.setVolume(clip.volume, clip.volume)
                p.isLooping = clip.loop
                p.setOnPreparedListener { it.start() }
                p.setOnInfoListener { _, what, _ ->
                    // Show the video once it has a picture, so it doesn't flash black over the poster.
                    if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) view?.alpha = 1f
                    false
                }
                p.setOnErrorListener { _, _, _ ->
                    release()
                    onError(if (clip.video) "This video can't be played on this device." else "This sound can't be played on this device.")
                    true
                }
                p.prepareAsync()
            } catch (e: Exception) {
                release()
                onError(if (clip.video) "This video can't be played on this device." else "This sound can't be played on this device.")
            }
        }

        fun release() {
            player?.release()
            player = null
            surface?.release()
            surface = null
            view?.let { layer.removeView(it) }
            view = null
            if (items[clip.spid] === this) items.remove(clip.spid)
        }
    }

    private val items = HashMap<String, Item>()
    private var frame = RectF()
    private var region = RectF(0f, 0f, 1f, 1f)

    val active get() = items.isNotEmpty()

    fun play(clip: MediaClip, file: File) {
        items[clip.spid]?.release()
        val item = Item(clip, file)
        items[clip.spid] = item
        if (!clip.video) {
            item.start(null)
            return
        }
        val view = TextureView(context).apply {
            alpha = 0f
            pivotX = 0f
            pivotY = 0f
        }
        view.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                if (items[clip.spid] !== item) return
                val s = Surface(st)
                item.surface = s
                item.start(s)
            }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        item.view = view
        layer.addView(view, FrameLayout.LayoutParams(1, 1))
        place(item)
    }

    fun stop(spid: String) {
        items[spid]?.release()
    }

    fun stopAll() {
        for (item in items.values.toList()) item.release()
    }

    fun togglePause() {
        for (item in items.values) item.player?.let { p ->
            try {
                if (p.isPlaying) p.pause() else p.start()
            } catch (e: IllegalStateException) {
                // Not prepared yet.
            }
        }
    }

    /** Where the slide is on screen ([frame], px) and which part of it is showing ([region], fractions). */
    fun layout(frame: RectF, region: RectF) {
        if (frame == this.frame && region == this.region) return
        this.frame = RectF(frame)
        this.region = RectF(region)
        layer.clipBounds = Rect(frame.left.toInt(), frame.top.toInt(), frame.right.toInt(), frame.bottom.toInt())
        for (item in items.values) place(item)
    }

    private fun place(item: Item) {
        val v = item.view ?: return
        val r = item.clip.rect
        val w = maxOf(1, (r.width() * frame.width()).toInt())
        val h = maxOf(1, (r.height() * frame.height()).toInt())
        val lp = v.layoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            v.layoutParams = lp
        }
        // Zoomed in, the video is magnified and moved with the slide.
        val zoom = 1f / region.width().coerceAtLeast(0.01f)
        v.scaleX = zoom
        v.scaleY = zoom
        v.x = frame.left + (r.left - region.left) * zoom * frame.width()
        v.y = frame.top + (r.top - region.top) * zoom * frame.height()
    }
}
