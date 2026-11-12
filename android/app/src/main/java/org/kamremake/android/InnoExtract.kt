package org.kamremake.android

import java.io.File
import java.io.IOException

/** JNI bridge to native libinnoextract_jni.so for listing and unpacking Inno Setup (.exe) archives on-device */
internal object InnoExtract {
    fun interface FileCallback {
        fun onFile(line: String, count: Int)
    }

    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            synchronized(this) {
                if (!loaded) {
                    System.loadLibrary("innoextract_jni")
                    loaded = true
                }
            }
        }
    }

    fun listFiles(exeFile: File): List<String>? {
        ensureLoaded()
        return nativeListFiles(exeFile.absolutePath)?.toList()
    }

    fun listFilesPath(path: String): List<String>? {
        ensureLoaded()
        return nativeListFiles(path)?.toList()
    }

    @Throws(IOException::class)
    fun extract(
        exeFile: File,
        destDir: File,
        includes: Array<String>? = null,
        onFile: FileCallback? = null
    ) {
        ensureLoaded()
        val err = nativeExtract(exeFile.absolutePath, destDir.absolutePath, includes, onFile)
        if (err != null) {
            throw IOException(err)
        }
    }

    @JvmStatic
    private external fun nativeListFiles(exePath: String): Array<String>?

    @JvmStatic
    private external fun nativeExtract(
        exePath: String,
        destDir: String,
        includes: Array<String>?,
        callback: FileCallback?
    ): String?
}
