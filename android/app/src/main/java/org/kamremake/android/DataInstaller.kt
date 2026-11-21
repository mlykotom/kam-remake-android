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
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Handles all game data installation flows:
 * 1. Bundled APK assets (`KAM_BUNDLE_DATA=1`)
 * 2. Phone-only setup: verify original Knights and Merchants (GOG .exe / Steam .zip or folder),
 *    then auto-download & extract official KaM Remake r16397 assets natively via `libinnoextract_jni.so`
 * 3. Direct import of an existing KaM Remake `.zip` or unpacked folder
 */
internal object DataInstaller {
    fun interface Progress {
        fun onProgress(done: Long, total: Long)
    }

    fun interface StatusCallback {
        fun onStatus(message: String)
    }

    enum class ArchiveKind {
        ORIGINAL_KAM,
        KAM_REMAKE_DATA,
        UNKNOWN
    }

    const val MARKER = ".kamdata_installed"
    const val ORIGINAL_VERIFIED_MARKER = ".kam_orig_verified"
    const val REQUIRED_FILE = "data/defines/houses.dat"
    const val OFFICIAL_REMAKE_DOWNLOAD_URL = "https://release.knightsprovince.com/index.php/s/9xdF69oCWmJXW59/download"

    private val GAME_DIRS = mapOf(
        "data" to "data",
        "campaigns" to "Campaigns",
        "maps" to "Maps",
        "mapsmp" to "MapsMP",
        "music" to "Music",
        "sounds" to "Sounds",
        "tutorials" to "Tutorials"
    )

    private val ORIGINAL_RX_MARKERS = setOf(
        "gui.rx",
        "units.rx",
        "houses.rx",
        "trees.rx"
    )

    private val ORIGINAL_EXE_MARKERS = setOf(
        "km_tpr.exe",
        "tpr.exe",
        "knights.exe"
    )

    fun hasBundledData(am: AssetManager): Boolean =
        try {
            am.open("kamdata.lst").use { true }
        } catch (_: IOException) {
            false
        }

    fun isInstalled(dir: File): Boolean = File(dir, MARKER).exists()

    fun hasValidGameData(dir: File): Boolean = File(dir, REQUIRED_FILE).isFile

    fun isOriginalGameVerified(dir: File): Boolean =
        File(dir, ORIGINAL_VERIFIED_MARKER).exists() || hasValidGameData(dir)

    fun markOriginalGameVerified(dir: File) {
        if (!dir.isDirectory) dir.mkdirs()
        File(dir, ORIGINAL_VERIFIED_MARKER).createNewFile()
    }

    /**
     * Distinguishes original Knights and Merchants (GOG / Steam / CD) from KaM Remake data.
     * - KaM Remake contains remastered `.rxa` / `.rxx` sprite archives + `data/defines/houses.dat`.
     * - Original KaM (TSK / TPR) contains classic `.rx` sprite archives (`gui.rx`, `units.rx`, etc.)
     *   or original executables (`KM_TPR.exe`, `Knights.exe`) without Remake `.rxa` archives.
     */
    internal fun classifyPathList(paths: Iterable<String>): ArchiveKind {
        var hasHousesDat = false
        var hasRemakeSprites = false
        var hasRemakeExe = false
        var hasOriginalRx = false
        var hasOriginalExe = false

        for (raw in paths) {
            val norm = normalizeZipPath(raw).lowercase()
            val base = norm.substringAfterLast('/')
            if (norm.endsWith(REQUIRED_FILE) || norm.endsWith("data/houses.dat")) {
                hasHousesDat = true
            }
            if (base.endsWith(".rxa") || base.endsWith(".rxx")) {
                hasRemakeSprites = true
            }
            if (base == "kam_remake.exe") {
                hasRemakeExe = true
            }
            if (base in ORIGINAL_RX_MARKERS) {
                hasOriginalRx = true
            }
            if (base in ORIGINAL_EXE_MARKERS) {
                hasOriginalExe = true
            }
        }

        return when {
            hasHousesDat && hasRemakeSprites -> ArchiveKind.KAM_REMAKE_DATA
            !hasRemakeSprites && !hasRemakeExe && (hasOriginalRx || (hasOriginalExe && hasHousesDat)) -> ArchiveKind.ORIGINAL_KAM
            else -> ArchiveKind.UNKNOWN
        }
    }

    /** Verifies original Knights and Merchants from a GOG Inno Setup .exe or .zip archive */
    @Throws(IOException::class)
    fun verifyOriginalFromFileUri(
        cr: ContentResolver,
        uri: Uri,
        cacheDir: File,
        status: StatusCallback
    ): ArchiveKind {
        val header = ByteArray(4)
        val read = cr.openInputStream(uri)?.use { it.read(header) } ?: throw IOException("Cannot open selected file.")
        if (read < 2) throw IOException("Selected file is empty.")

        // ZIP magic: PK\x03\x04
        if (read >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
            status.onStatus("Inspecting ZIP archive…")
            val paths = ArrayList<String>()
            ZipInputStream(BufferedInputStream(cr.openInputStream(uri)!!)).use { zis ->
                while (true) {
                    val e = zis.nextEntry ?: break
                    if (!e.isDirectory) paths.add(e.name)
                    zis.closeEntry()
                }
            }
            return classifyPathList(paths)
        }

        // Windows PE / Inno Setup .exe magic: MZ
        if (header[0] == 0x4D.toByte() && header[1] == 0x5A.toByte()) {
            status.onStatus("Inspecting Inno Setup installer (.exe)…")
            // Try direct file descriptor first (zero-copy, <0.2s)
            var listed: List<String>? = null
            try {
                cr.openFileDescriptor(uri, "r")?.use { pfd ->
                    val fdPath = "/proc/self/fd/${pfd.fd}"
                    if (File(fdPath).canRead()) {
                        listed = InnoExtract.listFilesPath(fdPath)
                    }
                }
            } catch (_: Exception) {
                // Fall back to cache copy below if SELinux blocks /proc/self/fd reopen
            }

            if (listed == null) {
                val tmpExe = copyUriToTempFile(cr, uri, cacheDir, "verify_installer.exe", status, "Reading installer")
                try {
                    listed = InnoExtract.listFiles(tmpExe)
                } finally {
                    tmpExe.delete()
                }
            }

            val entries = listed ?: throw IOException(
                "Could not read this .exe as an Inno Setup installer.\n" +
                    "Please select the GOG offline backup installer (.exe), or a .zip / folder of your Steam or GOG installation."
            )
            return classifyPathList(entries)
        }

        throw IOException("Unsupported file format. Please select a GOG installer (.exe) or a .zip archive.")
    }

    /** Inspects a DocumentTree folder to see whether it is Original KaM or already-installed KaM Remake */
    fun classifyDocumentTree(cr: ContentResolver, treeUri: Uri): ArchiveKind {
        val rootDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val paths = ArrayList<String>()

        fun walk(parentDocId: String, relPrefix: String, depth: Int) {
            if (depth > 4) return
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
                        val top = rel.substringBefore('/').lowercase()
                        if (top == "data" || top == "app" || depth == 0) {
                            walk(docId, rel, depth + 1)
                        }
                    } else {
                        paths.add(rel)
                    }
                }
            }
        }

        walk(rootDocId, "", 0)
        return classifyPathList(paths)
    }

    /**
     * Downloads the official KaM Remake r16397 package and extracts game data on-device.
     * Strictly requires original Knights and Merchants ownership to be verified first.
     */
    @Throws(IOException::class)
    fun downloadAndInstallRemake(
        dir: File,
        cacheDir: File,
        status: StatusCallback,
        downloadUrl: String = OFFICIAL_REMAKE_DOWNLOAD_URL
    ) {
        if (!isOriginalGameVerified(dir)) {
            throw IOException("Please verify your original Knights and Merchants game in Step 1 first.")
        }
        if (!cacheDir.isDirectory) cacheDir.mkdirs()
        val tmpPart = File(cacheDir, "kam_remake_pkg.exe.part")
        val tmpExe = File(cacheDir, "kam_remake_pkg.exe")
        tmpPart.delete()
        tmpExe.delete()

        try {
            status.onStatus("Connecting to official KaM Remake mirror…")
            var currentUrl = requireHttpsUrl(downloadUrl)
            var conn: HttpURLConnection? = null
            for (hop in 0..5) {
                val c = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 25_000
                    readTimeout = 45_000
                    setRequestProperty("User-Agent", "KaMRemake-Android/1.0")
                }
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw IOException("Redirect missing Location header")
                    currentUrl = resolveHttpsRedirect(currentUrl, loc)
                    c.disconnect()
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    c.disconnect()
                    throw IOException("Download failed with HTTP $code.")
                }
                conn = c
                break
            }
            val activeConn = conn ?: throw IOException("Too many HTTP redirects.")
            try {
                val totalBytes = activeConn.contentLengthLong
                val buf = ByteArray(1 shl 17)
                var downloaded = 0L
                var lastUiUpdateMs = 0L
                activeConn.inputStream.use { input ->
                    FileOutputStream(tmpPart).use { os ->
                        var n: Int
                        while (input.read(buf).also { n = it } > 0) {
                            os.write(buf, 0, n)
                            downloaded += n
                            val now = System.currentTimeMillis()
                            if (now - lastUiUpdateMs >= 200L) {
                                lastUiUpdateMs = now
                                val dlMb = downloaded / (1024 * 1024)
                                if (totalBytes > 0) {
                                    val totMb = totalBytes / (1024 * 1024)
                                    val pct = (downloaded * 100 / totalBytes).coerceIn(0, 100)
                                    status.onStatus("Downloading KaM Remake assets… $dlMb / $totMb MB ($pct%)")
                                } else {
                                    status.onStatus("Downloading KaM Remake assets… $dlMb MB")
                                }
                            }
                        }
                    }
                }
            } finally {
                activeConn.disconnect()
            }

            if (!tmpPart.renameTo(tmpExe)) {
                tmpPart.copyTo(tmpExe, overwrite = true)
                tmpPart.delete()
            }
            installFromRemakeExeFile(tmpExe, dir, status)
        } finally {
            tmpPart.delete()
            tmpExe.delete()
        }
    }

    /** Extracts a user-selected local KaM Remake .exe installer directly on-device */
    @Throws(IOException::class)
    fun installFromRemakeExeUri(
        cr: ContentResolver,
        uri: Uri,
        dir: File,
        cacheDir: File,
        status: StatusCallback
    ) {
        if (!isOriginalGameVerified(dir)) {
            throw IOException("Please verify your original Knights and Merchants game in Step 1 first.")
        }
        val tmpExe = copyUriToTempFile(cr, uri, cacheDir, "local_remake_installer.exe", status, "Preparing installer")
        try {
            installFromRemakeExeFile(tmpExe, dir, status)
        } finally {
            tmpExe.delete()
        }
    }

    @Throws(IOException::class)
    internal fun installFromRemakeExeFile(exeFile: File, dir: File, status: StatusCallback) {
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Cannot create target data directory: $dir")
        }
        val entries = InnoExtract.listFiles(exeFile)
            ?: throw IOException("Selected file is not a valid Inno Setup (.exe) archive.")
        if (classifyPathList(entries) != ArchiveKind.KAM_REMAKE_DATA) {
            throw IOException(
                "This installer does not contain KaM Remake assets.\n" +
                    "Make sure you selected the KaM Remake Full installer (.exe) rather than the original game installer."
            )
        }

        val totalExpected = entries.size.coerceAtLeast(1)
        val stageDir = File(dir, "_inno_stage")
        stageDir.deleteRecursively()
        if (!stageDir.mkdirs()) {
            throw IOException("Cannot create temporary staging folder.")
        }

        try {
            status.onStatus("Extracting KaM Remake assets… 0%")
            var lastUiMs = 0L
            InnoExtract.extract(exeFile, stageDir, includes = null) { _, count ->
                val now = System.currentTimeMillis()
                if (now - lastUiMs >= 150L || count >= totalExpected) {
                    lastUiMs = now
                    val pct = (count * 100 / totalExpected).coerceIn(0, 99)
                    status.onStatus("Extracting KaM Remake assets… $pct% ($count / $totalExpected files)")
                }
            }

            // Inno Setup puts files under stageDir/app/
            val appRoot = File(stageDir, "app").takeIf { it.isDirectory } ?: stageDir
            val children = appRoot.listFiles() ?: emptyArray()
            for (child in children) {
                if (!child.isDirectory) continue
                val canonicalName = GAME_DIRS[child.name.lowercase()] ?: continue
                val targetSub = File(dir, canonicalName)
                targetSub.deleteRecursively()
                if (!child.renameTo(targetSub)) {
                    child.copyRecursively(targetSub, overwrite = true)
                }
            }
        } finally {
            stageDir.deleteRecursively()
        }

        if (!hasValidGameData(dir)) {
            throw IOException("Extraction finished, but '$REQUIRED_FILE' was not found.")
        }
        File(dir, MARKER).createNewFile()
    }

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
     * Imports game data from a user-created .zip of an installed KaM Remake folder.
     * Automatically strips any wrapper folder prefix (e.g. "KaM Remake/" or "app/") and
     * canonicalizes top-level directory casing for Android's case-sensitive filesystem.
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
                "Please select a ZIP of an installed KaM Remake folder, or use Step 1 + Step 2 to install directly on your device."
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

    @Throws(IOException::class)
    private fun copyUriToTempFile(
        cr: ContentResolver,
        uri: Uri,
        cacheDir: File,
        tempName: String,
        status: StatusCallback,
        label: String
    ): File {
        if (!cacheDir.isDirectory) cacheDir.mkdirs()
        val out = File(cacheDir, tempName)
        out.delete()
        val buf = ByteArray(1 shl 18)
        var copied = 0L
        var lastMs = 0L
        val input = cr.openInputStream(uri) ?: throw IOException("Cannot read selected file.")
        input.use { ins ->
            FileOutputStream(out).use { os ->
                var n: Int
                while (ins.read(buf).also { n = it } > 0) {
                    os.write(buf, 0, n)
                    copied += n
                    val now = System.currentTimeMillis()
                    if (now - lastMs >= 200L) {
                        lastMs = now
                        status.onStatus("$label… ${copied / (1024 * 1024)} MB")
                    }
                }
            }
        }
        return out
    }

    internal fun requireHttpsUrl(rawUrl: String): String {
        val parsed = URL(rawUrl)
        if (!parsed.protocol.equals("https", ignoreCase = true)) {
            throw IOException("Refusing non-HTTPS download URL: ${parsed.protocol}")
        }
        return parsed.toString()
    }

    internal fun resolveHttpsRedirect(currentUrl: String, location: String): String {
        val resolved = URL(URL(currentUrl), location)
        if (!resolved.protocol.equals("https", ignoreCase = true)) {
            throw IOException("Refusing non-HTTPS redirect to ${resolved.protocol}")
        }
        return resolved.toString()
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
