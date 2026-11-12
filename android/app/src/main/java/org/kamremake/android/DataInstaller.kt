package org.kamremake.android

import android.content.ContentResolver
import android.content.res.AssetManager
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/** Extracts bundled APK game data or imports user-provided KaM Remake data (.zip or folder) */
internal object DataInstaller {
    fun interface Progress {
        fun onProgress(done: Long, total: Long)
    }

    const val MARKER = ".kamdata_installed"
    const val REQUIRED_FILE = "data/defines/houses.dat"

    private val GAME_DIRS = mapOf(
        "data" to "data",
        "campaigns" to "Campaigns",
        "maps" to "Maps",
        "mapsmp" to "MapsMP",
        "music" to "Music",
        "sounds" to "Sounds",
        "tutorials" to "Tutorials"
    )

    fun hasBundledData(am: AssetManager): Boolean =
        try {
            am.open("kamdata.lst").use { true }
        } catch (_: IOException) {
            false
        }

    fun isInstalled(dir: File): Boolean = File(dir, MARKER).exists()

    fun hasValidGameData(dir: File): Boolean = File(dir, REQUIRED_FILE).isFile

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
            val out = safeOutputFile(dir, f[0])
            ensureParentDir(out)
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

    /**
     * Imports game data from a user-created .zip of an installed KaM Remake folder
     * (or an `innoextract`-extracted installer directory). Automatically strips any
     * wrapper folder prefix (e.g. "KaM Remake/" or "app/") and canonicalizes top-level
     * directory casing for Android's case-sensitive filesystem.
     */
    @Throws(IOException::class)
    fun installFromZip(openZip: () -> InputStream, dir: File, progress: Progress) {
        var detectedPrefix: String? = null
        var totalEntries = 0L

        // Pass 1: locate data/defines/houses.dat to detect root prefix and count entries
        ZipInputStream(BufferedInputStream(openZip())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    totalEntries++
                    val norm = normalizeZipPath(entry.name)
                    if (norm.lowercase().endsWith(REQUIRED_FILE)) {
                        val prefixLen = norm.length - REQUIRED_FILE.length
                        val candidate = norm.substring(0, prefixLen)
                        if (candidate.isEmpty() || candidate.endsWith("/")) {
                            detectedPrefix = candidate
                        }
                    }
                }
                zis.closeEntry()
            }
        }

        val prefix = detectedPrefix ?: throw IOException(
            "Could not find '$REQUIRED_FILE' inside the selected ZIP.\n\n" +
                "Please zip your installed KaM Remake folder (not the raw installer .exe or the unmodded original game alone)."
        )

        // Pass 2: extract matching game asset folders
        val buf = ByteArray(1 shl 18)
        var processedEntries = 0L
        var extractedFiles = 0
        ZipInputStream(BufferedInputStream(openZip())).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (!entry.isDirectory) {
                    processedEntries++
                    val norm = normalizeZipPath(entry.name)
                    val mapped = mapRelativeGamePath(norm, prefix)
                    if (mapped != null) {
                        val out = safeOutputFile(dir, mapped)
                        ensureParentDir(out)
                        FileOutputStream(out).use { os ->
                            var n: Int
                            while (zis.read(buf).also { n = it } > 0) {
                                os.write(buf, 0, n)
                            }
                        }
                        extractedFiles++
                    }
                    progress.onProgress(processedEntries, totalEntries.coerceAtLeast(1L))
                }
                zis.closeEntry()
            }
        }

        if (extractedFiles == 0 || !hasValidGameData(dir)) {
            throw IOException("Extraction finished, but '$REQUIRED_FILE' is still missing.")
        }
        File(dir, MARKER).createNewFile()
    }

    /** Imports game data from a DocumentTree URI selected via ACTION_OPEN_DOCUMENT_TREE */
    @Throws(IOException::class)
    fun installFromDocumentTree(cr: ContentResolver, treeUri: Uri, dir: File, progress: Progress) {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val entries = ArrayList<Pair<Uri, String>>()

        fun collectChildren(parentDocId: String, relPrefix: String) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            cr.query(childrenUri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val docId = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    val rel = if (relPrefix.isEmpty()) name else "$relPrefix/$name"
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // Only recurse into relevant top-level folders (or 1 wrapper level like "app/")
                        val top = rel.substringBefore('/').lowercase()
                        if (GAME_DIRS.containsKey(top) || (!rel.contains('/') && top == "app")) {
                            collectChildren(docId, rel)
                        }
                    } else {
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        entries.add(docUri to rel)
                    }
                }
            }
        }

        collectChildren(rootDocId, "")
        val housesEntry = entries.firstOrNull { it.second.lowercase().endsWith(REQUIRED_FILE) }
            ?: throw IOException(
                "Could not find '$REQUIRED_FILE' in the selected folder.\n\n" +
                    "Please select the installed KaM Remake directory containing data, Campaigns, Maps, Sounds, etc."
            )

        val normSample = normalizeZipPath(housesEntry.second)
        val prefix = normSample.substring(0, normSample.length - REQUIRED_FILE.length)
        val filtered = entries.mapNotNull { (uri, rel) ->
            val mapped = mapRelativeGamePath(normalizeZipPath(rel), prefix)
            if (mapped != null) uri to mapped else null
        }

        val total = filtered.size.toLong().coerceAtLeast(1L)
        val buf = ByteArray(1 shl 17)
        var done = 0L
        for ((docUri, mapped) in filtered) {
            val out = safeOutputFile(dir, mapped)
            ensureParentDir(out)
            val input = cr.openInputStream(docUri) ?: throw IOException("Cannot read $mapped")
            input.use { ins ->
                FileOutputStream(out).use { os ->
                    var n: Int
                    while (ins.read(buf).also { n = it } > 0) {
                        os.write(buf, 0, n)
                    }
                }
            }
            done++
            progress.onProgress(done, total)
        }

        if (!hasValidGameData(dir)) {
            throw IOException("Import finished, but '$REQUIRED_FILE' is missing.")
        }
        File(dir, MARKER).createNewFile()
    }

    internal fun normalizeZipPath(raw: String): String =
        raw.replace('\\', '/').trimStart('/')

    internal fun mapRelativeGamePath(normPath: String, prefix: String): String? {
        if (prefix.isNotEmpty() && !normPath.startsWith(prefix, ignoreCase = true)) return null
        val rel = normPath.substring(prefix.length).trimStart('/')
        val slash = rel.indexOf('/')
        if (slash <= 0 || slash == rel.length - 1) return null
        val topDir = rel.substring(0, slash)
        val canonicalTop = GAME_DIRS[topDir.lowercase()] ?: return null
        val rest = rel.substring(slash + 1)
        if (rest.isEmpty()) return null
        return "$canonicalTop/$rest"
    }

    @Throws(IOException::class)
    private fun safeOutputFile(baseDir: File, relPath: String): File {
        val canonicalBase = baseDir.canonicalFile
        val out = File(canonicalBase, relPath).canonicalFile
        val basePrefix = canonicalBase.path + File.separator
        if (!out.path.startsWith(basePrefix)) {
            throw IOException("Blocked unsafe path outside target directory: $relPath")
        }
        return out
    }

    @Throws(IOException::class)
    private fun ensureParentDir(out: File) {
        val parent = out.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw IOException("Can't create directory: $parent")
        }
    }
}
