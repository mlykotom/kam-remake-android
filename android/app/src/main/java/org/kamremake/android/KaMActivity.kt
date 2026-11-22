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
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import kotlin.math.roundToInt

class KaMActivity : Activity() {
    private var view: KaMView? = null
    private lateinit var root: FrameLayout
    private var overlay: TextView? = null
    private lateinit var dataDir: String
    @Volatile private var engineRunning = false
    private var video: VideoOverlay? = null
    private var backCallback: OnBackInvokedCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Use the whole screen including the camera cutout area (API 28+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val cb = OnBackInvokedCallback { handleBack() }
            backCallback = cb
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
        }
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
        if (extraScale in 0.5f..2.0f) {
            prefs.edit().putFloat(PREF_UI_SCALE, extraScale).apply()
            intent.removeExtra(PREF_UI_SCALE)
        }
        val uiScale = prefs.getFloat(PREF_UI_SCALE, 1.0f)
        val extraCutoutLeft = intent.getIntExtra("cutout_left", -1)
        val extraCutoutRight = intent.getIntExtra("cutout_right", -1)
        val kamView = KaMView(this, dataDir, uiScale)
        if (extraCutoutLeft >= 0 || extraCutoutRight >= 0) {
            kamView.setCutoutInsets(maxOf(0, extraCutoutLeft), maxOf(0, extraCutoutRight))
        }
        view = kamView
        // Track the soft keyboard height and display cutout safe insets across API 26+
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            kamView.setImeHeight(insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            if (extraCutoutLeft < 0 && extraCutoutRight < 0) {
                val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout())
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
    private var importControlsContainer: LinearLayout? = null
    private var step1StatusLabel: TextView? = null
    private var step2DownloadBtn: Button? = null
    private var step2LocalExeBtn: Button? = null
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
            setPadding(px(28), px(16), px(28), px(16))
        }
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(22), px(16), px(22), px(16))
            setBackgroundColor(Color.rgb(30, 26, 22))
        }

        val title = TextView(this).apply {
            text = "KaM Remake — Game Data Setup"
            setTextColor(Color.rgb(235, 205, 140))
            textSize = 19f
        }
        val intro = TextView(this).apply {
            setTextColor(Color.rgb(215, 210, 200))
            textSize = 13f
            setPadding(0, px(6), 0, px(10))
            text =
                "No copyrighted game files are bundled. Own Knights and Merchants (GOG or Steam) to set up right on your device — no Windows PC required:"
        }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        importControlsContainer = controls

        // Step 1: Verify Original Game
        val step1Header = TextView(this).apply {
            setTextColor(Color.rgb(235, 205, 140))
            textSize = 13.5f
            text = "Step 1 — Verify Original Knights and Merchants"
        }
        step1StatusLabel = step1Header

        val step1Hint = TextView(this).apply {
            setTextColor(Color.rgb(170, 165, 155))
            textSize = 12f
            setPadding(0, px(2), 0, px(6))
            text = "On phone-only: buy on GOG.com in your browser, download the GOG offline backup .exe, and select it below (or select your Steam/GOG .zip or folder)."
        }

        val step1Row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val verifyFileBtn = Button(this).apply {
            text = "Verify GOG .exe / Game .zip"
            isAllCaps = false
            setTextColor(Color.rgb(25, 20, 14))
            setBackgroundColor(Color.rgb(218, 180, 104))
            setPadding(px(14), px(8), px(14), px(8))
            setOnClickListener { pickAnyFile(REQ_VERIFY_ORIGINAL_FILE) }
        }
        val verifyFolderBtn = Button(this).apply {
            text = "Verify Game Folder"
            isAllCaps = false
            setTextColor(Color.rgb(230, 210, 165))
            setBackgroundColor(Color.rgb(58, 50, 40))
            setPadding(px(14), px(8), px(14), px(8))
            setOnClickListener { pickFolder(REQ_VERIFY_ORIGINAL_FOLDER) }
        }
        step1Row.addView(verifyFileBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = px(10)
        })
        step1Row.addView(verifyFolderBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Step 2: Auto-Download & Extract KaM Remake Assets
        val step2Header = TextView(this).apply {
            setTextColor(Color.rgb(235, 205, 140))
            textSize = 13.5f
            setPadding(0, px(12), 0, px(6))
            text = "Step 2 — Install KaM Remake Assets (Built-In Extractor)"
        }
        val step2Row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val dlBtn = Button(this).apply {
            text = "Download & Install Remake (~679 MB)"
            isAllCaps = false
            setPadding(px(14), px(8), px(14), px(8))
            setOnClickListener { startRemakeAutoDownload() }
        }
        step2DownloadBtn = dlBtn

        val localExeBtn = Button(this).apply {
            text = "Or Select Remake .exe"
            isAllCaps = false
            setPadding(px(14), px(8), px(14), px(8))
            setOnClickListener { pickAnyFile(REQ_PICK_REMAKE_EXE) }
        }
        step2LocalExeBtn = localExeBtn

        step2Row.addView(dlBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = px(10)
        })
        step2Row.addView(localExeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Direct PC KaM Remake Import
        val directHeader = TextView(this).apply {
            setTextColor(Color.rgb(175, 170, 160))
            textSize = 12f
            setPadding(0, px(12), 0, px(6))
            text = "Already installed KaM Remake on PC? Import directly in one step:"
        }
        val directRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START
        }
        val zipBtn = Button(this).apply {
            text = "Import Remake .zip"
            isAllCaps = false
            setTextColor(Color.rgb(230, 210, 165))
            setBackgroundColor(Color.rgb(58, 50, 40))
            setPadding(px(14), px(8), px(14), px(8))
            setOnClickListener { pickAnyFile(REQ_PICK_ZIP) }
        }
        val folderBtn = Button(this).apply {
            text = "Select Remake Folder"
            isAllCaps = false
            setTextColor(Color.rgb(230, 210, 165))
            setBackgroundColor(Color.rgb(58, 50, 40))
            setPadding(px(14), px(8), px(14), px(8))
            setOnClickListener { pickFolder(REQ_PICK_FOLDER) }
        }
        directRow.addView(zipBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            marginEnd = px(10)
        })
        directRow.addView(folderBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        controls.addView(step1Header)
        controls.addView(step1Hint)
        controls.addView(step1Row)
        controls.addView(step2Header)
        controls.addView(step2Row)
        controls.addView(directHeader)
        controls.addView(directRow)

        val status = TextView(this).apply {
            setTextColor(Color.rgb(155, 150, 140))
            textSize = 12f
            setPadding(0, px(10), 0, 0)
            text = "Ready."
        }
        importStatusView = status

        card.addView(title)
        card.addView(intro)
        card.addView(controls)
        card.addView(status)
        container.addView(
            card,
            LinearLayout.LayoutParams(px(680).coerceAtMost(resources.displayMetrics.widthPixels - px(32)), LinearLayout.LayoutParams.WRAP_CONTENT)
        )
        scroll.addView(container, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        refreshSetupStepStates(dir)
    }

    private fun refreshSetupStepStates(dir: File) {
        val verified = DataInstaller.isOriginalGameVerified(dir)
        step1StatusLabel?.apply {
            if (verified) {
                text = "Step 1 — ✓ Original Knights and Merchants Verified"
                setTextColor(Color.rgb(135, 215, 140))
            } else {
                text = "Step 1 — Verify Original Knights and Merchants"
                setTextColor(Color.rgb(235, 205, 140))
            }
        }
        step2DownloadBtn?.apply {
            isEnabled = verified
            setTextColor(if (verified) Color.rgb(25, 20, 14) else Color.rgb(120, 115, 108))
            setBackgroundColor(if (verified) Color.rgb(218, 180, 104) else Color.rgb(45, 40, 35))
        }
        step2LocalExeBtn?.apply {
            isEnabled = verified
            setTextColor(if (verified) Color.rgb(230, 210, 165) else Color.rgb(120, 115, 108))
            setBackgroundColor(if (verified) Color.rgb(58, 50, 40) else Color.rgb(45, 40, 35))
        }
    }

    private fun pickAnyFile(reqCode: Int) {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
            @Suppress("DEPRECATION")
            startActivityForResult(intent, reqCode)
        } catch (e: Exception) {
            Toast.makeText(this, "No file picker available: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pickFolder(reqCode: Int) {
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            @Suppress("DEPRECATION")
            startActivityForResult(intent, reqCode)
        } catch (e: Exception) {
            Toast.makeText(this, "Folder picker not supported on this device: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun runSetupTask(initialStatus: String, task: (File, (String) -> Unit) -> Boolean) {
        val dir = targetInstallDir ?: return
        importControlsContainer?.visibility = android.view.View.GONE
        importStatusView?.apply {
            setTextColor(Color.rgb(235, 205, 140))
            textSize = 14f
            text = initialStatus
        }

        Thread({
            try {
                val updateStatus: (String) -> Unit = { msg ->
                    runOnUiThread { importStatusView?.text = msg }
                }
                val installedComplete = task(dir, updateStatus)
                runOnUiThread {
                    if (installedComplete) {
                        recreate()
                    } else {
                        importControlsContainer?.visibility = android.view.View.VISIBLE
                        refreshSetupStepStates(dir)
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    importControlsContainer?.visibility = android.view.View.VISIBLE
                    refreshSetupStepStates(dir)
                    importStatusView?.apply {
                        setTextColor(Color.rgb(255, 140, 130))
                        textSize = 13f
                        text = e.message ?: "Setup failed."
                    }
                }
            }
        }, "KaMDataSetup").start()
    }

    private fun startRemakeAutoDownload() {
        runSetupTask("Starting KaM Remake download…") { dir, status ->
            DataInstaller.downloadAndInstallRemake(dir, cacheDir, status)
            true
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return

        when (requestCode) {
            REQ_VERIFY_ORIGINAL_FILE -> {
                runSetupTask("Verifying selected game file…") { dir, status ->
                    when (DataInstaller.verifyOriginalFromFileUri(contentResolver, uri, cacheDir, status)) {
                        DataInstaller.ArchiveKind.ORIGINAL_KAM -> {
                            DataInstaller.markOriginalGameVerified(dir)
                            runOnUiThread {
                                importStatusView?.apply {
                                    setTextColor(Color.rgb(135, 215, 140))
                                    text = "Original Knights and Merchants verified! Now tap 'Download & Install Remake' in Step 2."
                                }
                            }
                            false
                        }
                        DataInstaller.ArchiveKind.KAM_REMAKE_DATA -> {
                            // If user selected a .zip of already-installed KaM Remake, import it directly;
                            // if they selected the Remake .exe before verifying original KaM, prompt for Step 1 first.
                            val header = ByteArray(2)
                            contentResolver.openInputStream(uri)?.use { it.read(header) }
                            val isZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                            if (isZip) {
                                DataInstaller.installFromZip(
                                    openZip = { contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open ZIP") },
                                    dir = dir,
                                    progress = { done, total ->
                                        val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
                                        status("Importing KaM Remake ZIP… $pct%")
                                    }
                                )
                                true
                            } else {
                                throw java.io.IOException(
                                    "That file is the KaM Remake installer. Please select your original Knights and Merchants (GOG setup .exe or Steam .zip/folder) in Step 1 first."
                                )
                            }
                        }
                        DataInstaller.ArchiveKind.UNKNOWN -> {
                            throw java.io.IOException(
                                "Could not recognize original Knights and Merchants files (e.g. gui.rx / units.rx).\n" +
                                    "Please select the GOG offline installer (.exe) or a .zip/folder of your Steam or GOG Knights and Merchants folder."
                            )
                        }
                    }
                }
            }
            REQ_VERIFY_ORIGINAL_FOLDER -> {
                runSetupTask("Checking selected folder…") { dir, status ->
                    when (DataInstaller.classifyDocumentTree(contentResolver, uri)) {
                        DataInstaller.ArchiveKind.ORIGINAL_KAM -> {
                            DataInstaller.markOriginalGameVerified(dir)
                            runOnUiThread {
                                importStatusView?.apply {
                                    setTextColor(Color.rgb(135, 215, 140))
                                    text = "Original Knights and Merchants folder verified! Now tap 'Download & Install Remake' in Step 2."
                                }
                            }
                            false
                        }
                        DataInstaller.ArchiveKind.KAM_REMAKE_DATA -> {
                            DataInstaller.installFromDocumentTree(contentResolver, uri, dir) { done, total ->
                                val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
                                status("Importing KaM Remake folder… $pct%")
                            }
                            true
                        }
                        DataInstaller.ArchiveKind.UNKNOWN -> {
                            throw java.io.IOException(
                                "Could not find original Knights and Merchants files (data/gfx/res/*.rx) in that folder."
                            )
                        }
                    }
                }
            }
            REQ_PICK_REMAKE_EXE -> {
                runSetupTask("Extracting KaM Remake installer…") { dir, status ->
                    DataInstaller.installFromRemakeExeUri(contentResolver, uri, dir, cacheDir, status)
                    true
                }
            }
            REQ_PICK_ZIP -> {
                runSetupTask("Importing KaM Remake ZIP… 0%") { dir, status ->
                    DataInstaller.installFromZip(
                        openZip = { contentResolver.openInputStream(uri) ?: throw java.io.IOException("Cannot open selected ZIP") },
                        dir = dir,
                        progress = { done, total ->
                            val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
                            status("Importing KaM Remake ZIP… $pct%")
                        }
                    )
                    true
                }
            }
            REQ_PICK_FOLDER -> {
                runSetupTask("Importing KaM Remake folder… 0%") { dir, status ->
                    DataInstaller.installFromDocumentTree(contentResolver, uri, dir) { done, total ->
                        val pct = if (total > 0) (done * 100 / total).coerceIn(0, 100) else 0
                        status("Importing KaM Remake folder… $pct%")
                    }
                    true
                }
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, root).let { c ->
            c.hide(WindowInsetsCompat.Type.systemBars())
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
            backCallback = null
        }
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

    private fun handleBack() {
        if (video?.isPlaying() == true) {
            video?.finish()
            return
        }
        view?.queueEvent(KaMNative::back)
    }

    @Deprecated("Deprecated in Java")
    public override fun onBackPressed() {
        handleBack()
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

    /** Called by the engine when the user adjusts 'Interface scale (%)' in Options */
    internal fun onEngineSetUiScale(scale: Float) {
        val clamped = scale.coerceIn(0.5f, 2.0f)
        runOnUiThread {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit()
                .putFloat(PREF_UI_SCALE, clamped)
                .apply()
            view?.setUiScale(clamped)
        }
    }

    companion object {
        private const val PREFS = "kam"
        private const val PREF_UI_SCALE = "ui_scale"
        private const val REQ_PICK_ZIP = 1001
        private const val REQ_PICK_FOLDER = 1002
        private const val REQ_VERIFY_ORIGINAL_FILE = 1003
        private const val REQ_VERIFY_ORIGINAL_FOLDER = 1004
        private const val REQ_PICK_REMAKE_EXE = 1005
    }
}
