package org.kamremake.android

import android.net.Uri
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/** Fullscreen video player for game intros and campaign videos. Tap skips the video. */
internal class VideoOverlay(
    private val activity: KaMActivity,
    private val root: FrameLayout
) {
    private var vlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var layout: VLCVideoLayout? = null
    private var onDone: Runnable? = null

    fun isPlaying(): Boolean = layout != null

    fun play(path: String, volume: Int, stretch: Boolean, onDone: Runnable) {
        stop(false)
        this.onDone = onDone
        val file = File(path)
        if (!file.exists()) {
            finish()
            return
        }
        val libVlc = vlc ?: LibVLC(activity, arrayListOf("--no-osd")).also { vlc = it }
        val videoLayout = VLCVideoLayout(activity).apply {
            setBackgroundColor(0xFF000000.toInt())
            setOnClickListener { finish() }
        }
        layout = videoLayout
        root.addView(
            videoLayout,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        val mediaPlayer = MediaPlayer(libVlc)
        player = mediaPlayer
        mediaPlayer.attachViews(videoLayout, null, false, false)
        mediaPlayer.videoScale = if (stretch) MediaPlayer.ScaleType.SURFACE_FILL else MediaPlayer.ScaleType.SURFACE_BEST_FIT
        val media = Media(libVlc, Uri.fromFile(file))
        mediaPlayer.media = media
        media.release()
        mediaPlayer.setEventListener { e ->
            if (e.type == MediaPlayer.Event.EndReached || e.type == MediaPlayer.Event.EncounteredError) {
                activity.runOnUiThread(::finish)
            }
        }
        mediaPlayer.play()
        mediaPlayer.volume = volume.coerceIn(0, 100)
    }

    /** Ends playback and reports completion to the game */
    fun finish() {
        stop(true)
    }

    private fun stop(notify: Boolean) {
        player?.let { p ->
            p.setEventListener(null)
            p.stop()
            p.detachViews()
            p.release()
        }
        player = null
        layout?.let { l ->
            root.removeView(l)
        }
        layout = null
        val r = onDone
        onDone = null
        if (notify && r != null) r.run()
    }

    fun release() {
        stop(false)
        vlc?.release()
        vlc = null
    }
}
