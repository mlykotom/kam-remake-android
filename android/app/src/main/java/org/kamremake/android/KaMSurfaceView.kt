package org.kamremake.android

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLSurface
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.ArrayDeque

/**
 * SurfaceView with its own GLES2 render thread (instead of GLSurfaceView).
 * The game engine runs on this thread and needs a large stack: the desktop build uses 8 MB with 32-bit
 * frames and some algorithms (e.g. terrain flood fill on big maps) recurse deeply. GLSurfaceView's thread
 * has the default ~1 MB stack which can't be changed.
 * The EGL context is kept while the app is paused, so textures survive going to background.
 */
@Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
internal abstract class KaMSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {
    private val lock = Object()
    private val events = ArrayDeque<Runnable>()
    private var surfaceHolder: SurfaceHolder? = null
    private var hasSurface = false
    private var surfaceReleased = true
    private var paused = false
    private var exiting = false
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var sizeChanged = false

    init {
        holder.addCallback(this)
        Thread(null, ::renderLoop, "KaMRender", RENDER_THREAD_STACK).start()
    }

    /** Called on the render thread when the surface size is known or changed */
    protected abstract fun onSurfaceChanged(width: Int, height: Int)

    /** Called on the render thread for every frame */
    protected abstract fun onDrawFrame()

    fun queueEvent(r: Runnable) {
        synchronized(lock) {
            events.add(r)
            lock.notifyAll()
        }
    }

    fun onPause() {
        synchronized(lock) {
            paused = true
            lock.notifyAll()
        }
    }

    /**
     * Pauses frame rendering and runs [pauseAction] on the render thread, waiting up to [timeoutMs]
     * so background auto-save and settings persistence complete before Activity.onPause() returns.
     */
    fun pauseAndWait(pauseAction: Runnable?, timeoutMs: Long = 2500L) {
        var done = pauseAction == null
        synchronized(lock) {
            paused = true
            if (pauseAction != null && !exiting) {
                events.add(Runnable {
                    try {
                        pauseAction.run()
                    } finally {
                        synchronized(lock) {
                            done = true
                            lock.notifyAll()
                        }
                    }
                })
            } else {
                done = true
            }
            lock.notifyAll()
            val deadline = android.os.SystemClock.uptimeMillis() + timeoutMs
            while (!done && !exiting) {
                val remaining = deadline - android.os.SystemClock.uptimeMillis()
                if (remaining <= 0L) break
                try {
                    lock.wait(remaining)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
    }

    fun onResume() {
        synchronized(lock) {
            paused = false
            lock.notifyAll()
        }
    }

    fun exit() {
        synchronized(lock) {
            exiting = true
            lock.notifyAll()
        }
    }

    // ---------- SurfaceHolder.Callback (UI thread) ----------

    override fun surfaceCreated(h: SurfaceHolder) {
        synchronized(lock) {
            surfaceHolder = h
            hasSurface = true
            lock.notifyAll()
        }
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, w: Int, hgt: Int) {
        synchronized(lock) {
            surfaceWidth = w
            surfaceHeight = hgt
            sizeChanged = true
            lock.notifyAll()
        }
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        synchronized(lock) {
            hasSurface = false
            lock.notifyAll()
            // The render thread must stop using the surface before it goes away
            while (!surfaceReleased && !exiting) {
                try {
                    lock.wait()
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    // ---------- Render thread ----------

    private fun renderLoop() {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val ver = IntArray(2)
        EGL14.eglInitialize(display, ver, 0, ver, 1)
        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_STENCIL_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        val config = configs[0]
        val context = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0
        )
        var surface: EGLSurface = EGL14.EGL_NO_SURFACE

        while (true) {
            var event: Runnable? = null
            var draw = false
            var resize = false
            var w = 0
            var h = 0
            var sh: SurfaceHolder? = null
            synchronized(lock) {
                if (exiting) break
                // Release the window surface when it's gone (context is kept)
                if (!hasSurface && surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context)
                    EGL14.eglDestroySurface(display, surface)
                    surface = EGL14.EGL_NO_SURFACE
                }
                if (surface == EGL14.EGL_NO_SURFACE && !surfaceReleased) {
                    surfaceReleased = true
                    lock.notifyAll()
                }
                if (hasSurface && surface == EGL14.EGL_NO_SURFACE) {
                    sh = surfaceHolder
                    surfaceReleased = false
                }
                if (!events.isEmpty()) {
                    event = events.poll()
                } else if (hasSurface && !paused && surfaceWidth > 0) {
                    draw = true
                    if (sizeChanged) {
                        resize = true
                        sizeChanged = false
                        w = surfaceWidth
                        h = surfaceHeight
                    }
                } else if (sh == null) {
                    try {
                        lock.wait()
                    } catch (_: InterruptedException) {
                        break
                    }
                    continue
                }
            }

            if (sh != null) {
                val created = EGL14.eglCreateWindowSurface(display, config, sh.surface, intArrayOf(EGL14.EGL_NONE), 0)
                if (created == null || created == EGL14.EGL_NO_SURFACE) {
                    Log.e(TAG, "eglCreateWindowSurface failed: ${EGL14.eglGetError()}")
                    surface = EGL14.EGL_NO_SURFACE
                } else {
                    surface = created
                    EGL14.eglMakeCurrent(display, surface, surface, context)
                }
            }

            if (event != null) {
                // Input and lifecycle events are delivered to the engine, which needs the GL context current
                if (EGL14.eglGetCurrentContext() == EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, context)
                }
                event.run()
                continue
            }

            if (draw && surface != EGL14.EGL_NO_SURFACE) {
                if (resize) onSurfaceChanged(w, h)
                onDrawFrame()
                EGL14.eglSwapBuffers(display, surface)
            }
        }

        synchronized(lock) {
            surfaceReleased = true
            lock.notifyAll()
        }
    }

    companion object {
        private const val TAG = "KaMRender"
        private const val RENDER_THREAD_STACK = 128L shl 20
    }
}
