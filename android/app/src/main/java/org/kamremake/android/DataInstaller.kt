package org.kamremake.android

import android.content.res.AssetManager
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader

/** Extracts game data bundled into the APK (assets/kamdata) to the app data folder on first launch */
internal object DataInstaller {
    fun interface Progress {
        fun onProgress(done: Long, total: Long)
    }

    const val MARKER = ".kamdata_installed"

    fun hasBundledData(am: AssetManager): Boolean =
        try {
            am.open("kamdata.lst").use { true }
        } catch (_: IOException) {
            false
        }

    fun isInstalled(dir: File): Boolean = File(dir, MARKER).exists()

    @Throws(IOException::class)
    fun install(am: AssetManager, dir: File, progress: Progress) {
        install(am::open, dir, progress)
    }

    @Throws(IOException::class)
    fun install(openAsset: (String) -> InputStream, dir: File, progress: Progress) {
        // kamdata.lst: "<size>\t<game path>\t<asset path>" (duplicates share one asset)
        val files = ArrayList<Array<String>>()
        var total = 0L
        BufferedReader(InputStreamReader(openAsset("kamdata.lst"), Charsets.UTF_8)).use { r ->
            var line: String?
            while (r.readLine().also { line = it } != null) {
                val p = line!!.split("\t")
                if (p.size < 3) continue
                total += p[0].toLong()
                files.add(arrayOf(p[1], p[2]))
            }
        }
        val buf = ByteArray(1 shl 20)
        var done = 0L
        for (f in files) {
            val out = File(dir, f[0])
            val parent = out.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                throw IOException("Can't create $parent")
            }
            openAsset("kamdata/${f[1]}").use { input ->
                FileOutputStream(out).use { os ->
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        os.write(buf, 0, n)
                        done += n
                    }
                }
            }
            progress.onProgress(done, total)
        }
        File(dir, MARKER).createNewFile()
    }
}
