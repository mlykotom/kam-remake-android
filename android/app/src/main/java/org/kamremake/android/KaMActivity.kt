package org.kamremake.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.roundToInt

class KaMActivity : Activity() {
    private var view: KaMView? = null
    private lateinit var root: FrameLayout
    private var overlay: TextView? = null
    private lateinit var dataDir: String
    @Volatile private var engineRunning = false
    private var video: VideoOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Use the whole screen including the camera cutout area
        val lp = window.attributes
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        window.attributes = lp
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        KaMNative.activity = this

        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        setContentView(root)
        hideSystemBars()

        val dir = getExternalFilesDir(null)!!
        dataDir = "${dir.absolutePath}/"

        // APK with bundled game data: extract it on first launch
        if (!DataInstaller.isInstalled(dir) && DataInstaller.hasBundledData(assets)) {
            installBundledData(dir)
            return
        }

        if (!File(dir, "data/defines/houses.dat").exists()) {
            showMissingData(dir)
            return
        }

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // Optional launch parameter (adb shell am start ... --ef ui_scale 1.5), same as the settings slider
        val extraScale = intent.getFloatExtra(PREF_UI_SCALE, -1f)
        if (extraScale in 0.5f..2.0f) prefs.edit().putFloat(PREF_UI_SCALE, extraScale).apply()
        val uiScale = prefs.getFloat(PREF_UI_SCALE, 1.0f)
        val kamView = KaMView(this, dataDir, uiScale)
        view = kamView
        // Track the soft keyboard height to keep text fields visible above it
        root.setOnApplyWindowInsetsListener { _, insets ->
            kamView.setImeHeight(insets.getInsets(WindowInsets.Type.ime()).bottom)
            insets
        }
        root.addView(
            kamView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )

        overlay = TextView(this).apply {
            text = "Knights and Merchants\nloading…"
            setTextColor(Color.rgb(230, 210, 160))
            textSize = 22f
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(
            overlay,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        kamView.requestFocus()
    }

    private fun installBundledData(dir: File) {
        val t = TextView(this).apply {
            setTextColor(Color.rgb(230, 210, 160))
            textSize = 20f
            gravity = Gravity.CENTER
            text = "Knights and Merchants\nInstalling game data…"
        }
        root.addView(
            t,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        )
        Thread({
            try {
                DataInstaller.install(assets, dir) { done, total ->
                    runOnUiThread {
                        t.text = String.format(
                            "Knights and Merchants\nInstalling game data… %d%%",
                            if (total > 0) done * 100 / total else 0
                        )
                    }
                }
                runOnUiThread(::recreate)
            } catch (e: Exception) {
                runOnUiThread { t.text = "Failed to install game data:\n${e.message}" }
            }
        }, "KaMDataInstall").start()
    }

    private fun showMissingData(dir: File) {
        val t = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(48, 48, 48, 48)
            text = "Game data not found.\n\nCopy the KaM Remake game folder (data, Campaigns, Maps, MapsMP, Music, Sounds, Tutorials) to:\n\n" +
                "${dir.absolutePath}\n\nand start the app again."
        }
        root.addView(t)
    }

    private fun hideSystemBars() {
        window.insetsController?.let { c ->
            c.hide(WindowInsets.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        view?.let { v ->
            if (engineRunning) v.queueEvent(KaMNative::pause)
            v.onPause()
        }
    }

    override fun onResume() {
        super.onResume()
        view?.let { v ->
            v.onResume()
            if (engineRunning) v.queueEvent(KaMNative::resume)
        }
        hideSystemBars()
    }

    override fun onDestroy() {
        view?.exit()
        video?.release()
        if (KaMNative.activity === this) KaMNative.activity = null
        super.onDestroy()
    }

    // ---------- Called by the view / native code ----------

    internal fun onEngineStarted() {
        engineRunning = true
        // Content smoke test: adb shell am start -n org.kamremake.android/.KaMActivity --es kam_selftest campaigns
        val test = intent.getStringExtra("kam_selftest")
        if (test != null) view?.queueEvent { KaMNative.selfTest(test) }
        runOnUiThread {
            overlay?.let {
                root.removeView(it)
                overlay = null
            }
        }
    }

    internal fun onEngineFailed() {
        runOnUiThread {
            overlay?.text = "Failed to start the game.\nSee Logs folder in $dataDir"
        }
    }

    internal fun showMessage(title: String, text: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(text)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    internal fun setClipboard(text: String) {
        runOnUiThread {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("KaM", text))
        }
    }

    internal fun getClipboard(): String {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) return ""
        val s = clip.getItemAt(0).coerceToText(this)
        return s?.toString() ?: ""
    }

    internal fun openUrl(url: String) {
        runOnUiThread {
            try {
                val i = if (url.startsWith("/")) {
                    // Local file (e.g. map PDF): share through FileProvider
                    val f = File(url)
                    val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
                    val ext = MimeTypeMap.getFileExtensionFromUrl(f.name).lowercase()
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, mime ?: "*/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                } else {
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                }
                startActivity(i)
            } catch (_: Exception) {
                Toast.makeText(this, "Can't open $url", Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun quit() {
        engineRunning = false
        runOnUiThread(::finishAndRemoveTask)
    }

    internal fun playVideo(path: String, volume: Int, stretch: Boolean) {
        runOnUiThread {
            val v = video ?: VideoOverlay(this, root).also { video = it }
            v.play(path, volume, stretch) {
                view?.queueEvent(KaMNative::videoFinished)
                view?.requestFocus()
            }
        }
    }

    internal fun stopVideo() {
        runOnUiThread {
            video?.takeIf { it.isPlaying() }?.finish()
        }
    }

    @Deprecated("Deprecated in Java")
    public override fun onBackPressed() {
        if (video?.isPlaying() == true) {
            video?.finish()
            return
        }
        view?.queueEvent(KaMNative::back)
    }

    internal fun setSoftKeyboardVisible(visible: Boolean) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        val v = view ?: return
        if (visible) {
            v.requestFocus()
            imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(v.windowToken, 0)
        }
    }

    /** Android-only settings (three finger tap): interface scale */
    internal fun showPortSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val current = prefs.getFloat(PREF_UI_SCALE, 1.0f)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 24)
        }
        val label = TextView(this).apply {
            text = String.format("Interface scale: %.2f", current)
        }
        val bar = SeekBar(this).apply {
            // 0.5 .. 2.0 in 0.05 steps. Above 1.0 only the in-game interface grows, menus keep their design size
            max = 30
            progress = ((current - 0.5f) / 0.05f).roundToInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, user: Boolean) {
                    label.text = String.format("Interface scale: %.2f", 0.5f + p * 0.05f)
                }

                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        box.addView(label)
        box.addView(bar)

        AlertDialog.Builder(this)
            .setTitle("KaM Remake for Android")
            .setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val scale = 0.5f + bar.progress * 0.05f
                prefs.edit().putFloat(PREF_UI_SCALE, scale).apply()
                view?.setUiScale(scale)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        private const val PREFS = "kam"
        private const val PREF_UI_SCALE = "ui_scale"
    }
}
