package org.kamremake.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
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
        val extraCutoutLeft = intent.getIntExtra("cutout_left", -1)
        val extraCutoutRight = intent.getIntExtra("cutout_right", -1)
        val kamView = KaMView(this, dataDir, uiScale)
        if (extraCutoutLeft >= 0 || extraCutoutRight >= 0) {
            kamView.setCutoutInsets(maxOf(0, extraCutoutLeft), maxOf(0, extraCutoutRight))
        }
        view = kamView
        // Track the soft keyboard height and display cutout safe insets
        root.setOnApplyWindowInsetsListener { _, insets ->
            kamView.setImeHeight(insets.getInsets(WindowInsets.Type.ime()).bottom)
            if (extraCutoutLeft < 0 && extraCutoutRight < 0) {
                val cutout = insets.getInsets(WindowInsets.Type.displayCutout())
                kamView.setCutoutInsets(cutout.left, cutout.right)
            }
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

    private var importStatusView: TextView? = null
    private var importButtonsRow: LinearLayout? = null
    private var targetInstallDir: File? = null

    private fun showMissingData(dir: File) {
        targetInstallDir = dir
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).roundToInt()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(Color.rgb(18, 16, 14))
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(px(36), px(24), px(36), px(24))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(20), px(24), px(20))
            setBackgroundColor(Color.rgb(30, 26, 22))
        }

        val title = TextView(this).apply {
            text = "KaM Remake — Game Data Setup"
            setTextColor(Color.rgb(235, 205, 140))
            textSize = 20f
        }
        val body = TextView(this).apply {
            setTextColor(Color.rgb(220, 215, 205))
            textSize = 14f
            setPadding(0, px(10), 0, px(14))
            text =
                "This open-source engine does not include copyrighted Knights and Merchants game assets.\n\n" +
                "1. Purchase Knights and Merchants (e.g. on Steam or GOG).\n" +
                "2. Install KaM Remake on your PC from kamremake.com (using your owned game copy).\n" +
                "3. Compress your installed KaM Remake folder into a .zip file, copy it to this device, and import it below:"
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val zipBtn = Button(this).apply {
            text = "Import KaM Remake .zip"
            isAllCaps = false
            setTextColor(Color.rgb(25, 20, 14))
            setBackgroundColor(Color.rgb(218, 180, 104))
            setPadding(px(18), px(10), px(18), px(10))
            setOnClickListener { pickGameZip() }
        }
        val folderBtn = Button(this).apply {
            text = "Select Unpacked Folder"
            isAllCaps = false
            setTextColor(Color.rgb(230, 210, 165))
            setBackgroundColor(Color.rgb(58, 50, 40))
            setPadding(px(18), px(10), px(18), px(10))
            setOnClickListener { pickGameFolder() }
        }
        buttons.addView(zipBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = px(12)
        })
        buttons.addView(folderBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        importButtonsRow = buttons

        val status = TextView(this).apply {
            setTextColor(Color.rgb(160, 155, 145))
            textSize = 12f
            setPadding(0, px(12), 0, 0)
            text = "Or copy data, Campaigns, Maps, MapsMP, Music, Sounds, Tutorials via ADB to:\n${dir.absolutePath}"
        }
        importStatusView = status

        card.addView(title)
        card.addView(body)
        card.addView(buttons)
        card.addView(status)
        container.addView(
            card,
            LinearLayout.LayoutParams(px(640).coerceAtMost(resources.displayMetrics.widthPixels - px(48)), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        scroll.addView(container, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun pickGameZip() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_ZIP)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker available: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickGameFolder() {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_PICK_FOLDER)
        } catch (e: Exception) {
            Toast.makeText(this, "Folder picker not supported on this device: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val dir = targetInstallDir ?: return

        if (requestCode != REQ_PICK_ZIP && requestCode != REQ_PICK_FOLDER) return

        importButtonsRow?.visibility = android.view.View.GONE
        importStatusView?.apply {
            setTextColor(Color.rgb(235, 205, 140))
            textSize = 15f
            text = "Importing game data… 0%"
        }

        Thread({
            try {
                val onProg = DataInstaller.Progress { done, total ->
                    val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
                    runOnUiThread {
                        importStatusView?.text = "Importing game data… $pct%"
                    }
                }
                if (requestCode == REQ_PICK_ZIP) {
                    DataInstaller.installFromZip(
                        openZip = { contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open selected ZIP") },
                        dir = dir,
                        progress = onProg
                    )
                } else {
                    DataInstaller.installFromDocumentTree(contentResolver, uri, dir, onProg)
                }
                runOnUiThread(::recreate)
            } catch (e: Exception) {
                runOnUiThread {
                    importButtonsRow?.visibility = android.view.View.VISIBLE
                    importStatusView?.apply {
                        setTextColor(Color.rgb(255, 140, 130))
                        textSize = 13f
                        text = "Import failed: ${e.message}"
                    }
                }
            }
        }, "KaMDataUserImport").start()
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

    internal fun performHaptic() {
        runOnUiThread {
            try {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (vibrator != null && vibrator.hasVibrator()) {
                    val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                    } else {
                        VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE)
                    }
                    vibrator.vibrate(effect)
                    return@runOnUiThread
                }
            } catch (_: Exception) {
            }
            view?.performHapticFeedback(
                HapticFeedbackConstants.KEYBOARD_TAP,
                HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
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
        private const val REQ_PICK_ZIP = 1001
        private const val REQ_PICK_FOLDER = 1002
    }
}
