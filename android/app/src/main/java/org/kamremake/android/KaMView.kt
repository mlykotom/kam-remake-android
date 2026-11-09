package org.kamremake.android

import android.os.SystemClock
import android.text.InputType
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fullscreen GL surface the game renders into.
 * The surface buffer has a fixed "virtual" size (the game's screen resolution) that the hardware
 * scaler stretches to the display, so the original UI keeps its proportions and stays readable.
 */
internal class KaMView(
    private val activity: KaMActivity,
    private val dataDir: String,
    private var uiScale: Float
) : KaMSurfaceView(activity) {

    @Volatile private var virtW = 0
    @Volatile private var virtH = 0
    @Volatile private var inGame = false
    @Volatile private var touchScaleX = 1f
    @Volatile private var touchScaleY = 1f
    @Volatile private var cutoutLeftPx = 0
    @Volatile private var cutoutRightPx = 0
    @Volatile private var virtSafeLeft = 0
    @Volatile private var virtSafeRight = 0
    private var engineStarted = false
    private var engineFailed = false
    private var textInputShown = false
    @Volatile private var textInputBottom = -1
    private var imeHeight = 0
    private var lastTextInputCheck = 0L

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    /**
     * Game resolution for the given display size. The smaller UI dimension fits the base UI size, scaled by user preference.
     * Menus are designed for 1024x720 and never get less; in game (map + sidebar) the UI can be made up to 2x larger.
     */
    private fun computeVirtualSize(w: Int, h: Int) {
        val vs = calculateVirtualSize(w, h, uiScale, inGame, cutoutLeftPx, cutoutRightPx)
        virtW = vs.width
        virtH = vs.height
        touchScaleX = vs.touchScaleX
        touchScaleY = vs.touchScaleY
        virtSafeLeft = (cutoutLeftPx * touchScaleX).roundToInt()
        virtSafeRight = (cutoutRightPx * touchScaleX).roundToInt()
    }

    private fun applyVirtualSize() {
        if (width <= 0) return
        val oldW = virtW
        val oldH = virtH
        computeVirtualSize(width, height)
        if (virtW != oldW || virtH != oldH) {
            holder.setFixedSize(virtW, virtH)
        } else {
            val sl = virtSafeLeft
            val sr = virtSafeRight
            queueEvent { KaMNative.setSafeInsets(sl, sr) }
        }
    }

    fun setUiScale(scale: Float) {
        uiScale = scale
        applyVirtualSize()
    }

    fun setCutoutInsets(leftPx: Int, rightPx: Int) {
        if (leftPx == cutoutLeftPx && rightPx == cutoutRightPx) return
        cutoutLeftPx = leftPx
        cutoutRightPx = rightPx
        applyVirtualSize()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        computeVirtualSize(w, h)
        holder.setFixedSize(virtW, virtH)
    }

    // ---------- Renderer (GL thread) ----------

    override fun onSurfaceChanged(width: Int, height: Int) {
        if (engineFailed) return
        KaMNative.setSafeInsets(virtSafeLeft, virtSafeRight)
        if (!engineStarted) {
            engineStarted = true
            if (!KaMNative.init(dataDir, width, height)) {
                engineFailed = true
                activity.onEngineFailed()
                return
            }
            activity.onEngineStarted()
        } else {
            KaMNative.resize(width, height)
        }
    }

    override fun onDrawFrame() {
        if (!engineStarted || engineFailed) return
        KaMNative.frame()

        val ig = KaMNative.inGame()
        if (ig != inGame) {
            inGame = ig
            activity.runOnUiThread(::applyVirtualSize)
        }

        val now = SystemClock.uptimeMillis()
        if (now - lastTextInputCheck > 200) {
            lastTextInputCheck = now
            val active = KaMNative.textInputActive()
            // Show the keyboard only when the user tapped a text field, not when the game focuses
            // a field by itself (e.g. lobby chat). Hide it as soon as no field has focus
            if (KaMNative.textFieldTapped() && active) {
                textInputShown = true
                activity.runOnUiThread { activity.setSoftKeyboardVisible(true) }
            } else if (!active && textInputShown) {
                textInputShown = false
                activity.runOnUiThread { activity.setSoftKeyboardVisible(false) }
            }
            val bottom = if (active) KaMNative.textInputBottom() else -1
            if (bottom != textInputBottom) {
                textInputBottom = bottom
                activity.runOnUiThread(::updateKeyboardShift)
            }
        }
    }

    // ---------- Soft keyboard: keep the focused text field visible ----------

    fun setImeHeight(h: Int) {
        imeHeight = h
        updateKeyboardShift()
    }

    /** Moves the game picture up while the keyboard covers the focused text field (UI thread) */
    private fun updateKeyboardShift() {
        var shift = 0f
        if (imeHeight > 0 && textInputBottom >= 0 && touchScaleY > 0) {
            val fieldBottomPx = textInputBottom / touchScaleY + 16 * resources.displayMetrics.density
            val visible = (height - imeHeight).toFloat()
            shift = max(0f, fieldBottomPx - visible)
        }
        translationY = -shift
    }

    // ---------- Input (UI thread -> GL thread) ----------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) return handleMouse(e)

        val action = e.actionMasked
        val count = e.pointerCount
        val x0 = e.getX(0) * touchScaleX
        val y0 = e.getY(0) * touchScaleY
        val x1 = if (count > 1) e.getX(1) * touchScaleX else x0
        val y1 = if (count > 1) e.getY(1) * touchScaleY else y0
        val a = when (action) {
            MotionEvent.ACTION_DOWN -> 0
            MotionEvent.ACTION_MOVE -> 1
            MotionEvent.ACTION_UP -> 2
            MotionEvent.ACTION_CANCEL -> 3
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (count == 3) {
                    activity.runOnUiThread(activity::showPortSettings)
                    return true
                }
                5
            }
            MotionEvent.ACTION_POINTER_UP -> 6
            else -> return true
        }
        queueEvent { KaMNative.touch(a, count, x0, y0, x1, y1) }
        return true
    }

    private var lastMouseButtons = 0

    private fun handleMouse(e: MotionEvent): Boolean {
        val x = (e.x * touchScaleX).roundToInt()
        val y = (e.y * touchScaleY).roundToInt()
        val buttons = e.buttonState
        val changed = buttons xor lastMouseButtons
        for (m in MOUSE_BUTTON_MAP) {
            if ((changed and m[0]) != 0) {
                val act = if ((buttons and m[0]) != 0) 0 else 2
                val btn = m[1]
                queueEvent { KaMNative.mouse(act, btn, x, y) }
            }
        }
        lastMouseButtons = buttons
        if (changed == 0) queueEvent { KaMNative.mouse(1, 0, x, y) }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) {
            val x = (e.x * touchScaleX).roundToInt()
            val y = (e.y * touchScaleY).roundToInt()
            when (e.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE -> {
                    queueEvent { KaMNative.mouse(1, 0, x, y) }
                    return true
                }
                MotionEvent.ACTION_SCROLL -> {
                    val steps = e.getAxisValue(MotionEvent.AXIS_VSCROLL).roundToInt()
                    if (steps != 0) queueEvent { KaMNative.wheel(steps, x, y) }
                    return true
                }
                MotionEvent.ACTION_BUTTON_PRESS,
                MotionEvent.ACTION_BUTTON_RELEASE -> return handleMouse(e)
            }
        }
        return super.onGenericMotionEvent(e)
    }

    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            @Suppress("DEPRECATION")
            activity.onBackPressed()
            return true
        }
        val vk = KeyMap.toVK(keyCode)
        val shift = KeyMap.shiftFlags(e)
        if (vk != 0) {
            queueEvent { KaMNative.key(0, vk, shift) }
        }
        val ch = e.unicodeChar
        if (ch >= 32) {
            queueEvent { KaMNative.charInput(ch) }
        } else if (keyCode == KeyEvent.KEYCODE_DEL) {
            queueEvent { KaMNative.charInput(8) }
        } else if (keyCode == KeyEvent.KEYCODE_ENTER) {
            queueEvent { KaMNative.charInput(13) }
        }
        return vk != 0 || ch != 0 || super.onKeyDown(keyCode, e)
    }

    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return true
        val vk = KeyMap.toVK(keyCode)
        if (vk != 0) {
            val shift = KeyMap.shiftFlags(e)
            queueEvent { KaMNative.key(1, vk, shift) }
            return true
        }
        return super.onKeyUp(keyCode, e)
    }

    // ---------- Soft keyboard ----------

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
                val s = text.toString()
                var i = 0
                while (i < s.length) {
                    val cp = s.codePointAt(i)
                    i += Character.charCount(cp)
                    queueEvent { KaMNative.charInput(cp) }
                }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                for (i in 0 until beforeLength) pressKey(8)
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                pressKey(13)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) return onKeyDown(event.keyCode, event)
                if (event.action == KeyEvent.ACTION_UP) return onKeyUp(event.keyCode, event)
                return true
            }
        }
    }

    private fun pressKey(vk: Int) {
        queueEvent {
            KaMNative.key(0, vk, 0)
            KaMNative.charInput(vk)
            KaMNative.key(1, vk, 0)
        }
    }

    internal data class VirtualSize(
        val width: Int,
        val height: Int,
        val touchScaleX: Float,
        val touchScaleY: Float
    )

    companion object {
        // Minimal game resolution the original UI is designed for
        const val BASE_WIDTH = 1024
        const val BASE_HEIGHT = 720
        // Smallest game resolution while playing (sidebar must still fit)
        // Message, message log and chat panels start at the sidebar edge: 224 + 600 px
        const val MIN_GAME_WIDTH = 830
        const val MIN_GAME_HEIGHT = 400 // Sidebar scrolls vertically when it does not fit

        private val MOUSE_BUTTON_MAP = arrayOf(
            intArrayOf(MotionEvent.BUTTON_PRIMARY, 0),
            intArrayOf(MotionEvent.BUTTON_SECONDARY, 1),
            intArrayOf(MotionEvent.BUTTON_TERTIARY, 2)
        )

        fun calculateVirtualSize(
            w: Int,
            h: Int,
            uiScale: Float,
            inGame: Boolean,
            cutoutLeftPx: Int = 0,
            cutoutRightPx: Int = 0
        ): VirtualSize {
            var scale = min(h.toFloat() / BASE_HEIGHT, w.toFloat() / BASE_WIDTH) * uiScale
            scale = max(scale, 0.25f)
            val safePhysW = max(1, w - cutoutLeftPx - cutoutRightPx)
            val minW = if (inGame) ceil(MIN_GAME_WIDTH.toDouble() * w / safePhysW).toInt() else BASE_WIDTH
            val minH = if (inGame) MIN_GAME_HEIGHT else BASE_HEIGHT
            var vw = max(minW, (w / scale).roundToInt())
            var vh = max(minH, (h / scale).roundToInt())
            // Keep display aspect ratio
            val aspect = w.toFloat() / h
            if (vw.toFloat() / vh < aspect) {
                vw = (vh * aspect).roundToInt()
            } else {
                vh = (vw / aspect).roundToInt()
            }
            val evenW = vw and 1.inv()
            val evenH = vh and 1.inv()
            return VirtualSize(
                width = evenW,
                height = evenH,
                touchScaleX = evenW.toFloat() / w,
                touchScaleY = evenH.toFloat() / h
            )
        }
    }
}
