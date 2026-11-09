package org.kamremake.android

import android.system.Os

/** Bridge to the native KaM Remake engine (libkamhost.so -> libkamcore.so). All calls on the GL thread. */
internal object KaMNative {
    init {
        try {
            // gl4es settings (read when the engine loads libGL.so)
            Os.setenv("LIBGL_LOGSHADERERROR", "1", true)
        } catch (_: Exception) {
        }
        System.loadLibrary("kamhost")
    }

    @Volatile
    @JvmField
    var activity: KaMActivity? = null

    @JvmStatic external fun init(dataDir: String, width: Int, height: Int): Boolean
    @JvmStatic external fun frame()
    @JvmStatic external fun resize(width: Int, height: Int)
    @JvmStatic external fun setSafeInsets(left: Int, right: Int)
    @JvmStatic external fun pause()
    @JvmStatic external fun resume()
    @JvmStatic external fun shutdown()
    @JvmStatic external fun touch(action: Int, count: Int, x0: Float, y0: Float, x1: Float, y1: Float)
    @JvmStatic external fun mouse(action: Int, button: Int, x: Int, y: Int)
    @JvmStatic external fun wheel(steps: Int, x: Int, y: Int)
    @JvmStatic external fun key(action: Int, vk: Int, shift: Int)
    @JvmStatic external fun charInput(ch: Int)
    @JvmStatic external fun back()
    @JvmStatic external fun textInputActive(): Boolean
    @JvmStatic external fun videoFinished()
    @JvmStatic external fun inGame(): Boolean
    @JvmStatic external fun textInputBottom(): Int
    @JvmStatic external fun textFieldTapped(): Boolean
    @JvmStatic external fun selfTest(mode: String)

    // Called from native code (GL thread)
    @JvmStatic
    fun onShowMessage(title: String, text: String) {
        activity?.showMessage(title, text)
    }

    @JvmStatic
    fun onClipboardSet(text: String) {
        activity?.setClipboard(text)
    }

    @JvmStatic
    fun onClipboardGet(): String = activity?.getClipboard() ?: ""

    @JvmStatic
    fun onOpenURL(url: String) {
        activity?.openUrl(url)
    }

    @JvmStatic
    fun onQuit() {
        activity?.quit()
    }

    @JvmStatic
    fun onPlayVideo(path: String, volume: Int, stretch: Boolean) {
        activity?.playVideo(path, volume, stretch)
    }

    @JvmStatic
    fun onStopVideo() {
        activity?.stopVideo()
    }

    @JvmStatic
    fun onHaptic() {
        activity?.performHaptic()
    }
}
