package org.kamremake.android

import android.app.backup.BackupAgent
import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.FullBackupDataOutput
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.File

/**
 * Custom Auto Backup agent (`android:fullBackupOnly="true"`) that backs up settings,
 * campaign progress (`SavesCMP/`), and the newest savegame bundles (`Saves/`, `SavesMP/`)
 * up to Android's backup quota (`25 MB` on Google Drive cloud backup; `2 GB` on
 * device-to-device transfer) without ever deleting local saves or tripping `onQuotaExceeded`.
 */
class KaMBackupAgent : BackupAgent() {

    override fun onBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) {
        // Unused: android:fullBackupOnly="true" uses file-based Auto Backup (onFullBackup).
    }

    override fun onRestore(
        data: BackupDataInput?,
        appVersionCode: Int,
        newState: ParcelFileDescriptor?
    ) {
        // Unused: android:fullBackupOnly="true" uses file-based Auto Backup restore.
    }

    override fun onFullBackup(data: FullBackupDataOutput) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) data.transportFlags else 0
        val isDeviceTransfer = (flags and FLAG_DEVICE_TO_DEVICE_TRANSFER) != 0
        val sharedPrefsDir = File(applicationInfo.dataDir, "shared_prefs")
        val externalDir = getExternalFilesDir(null)

        val plan = selectBackupPlan(
            sharedPrefsDir = sharedPrefsDir,
            externalFilesDir = externalDir,
            quotaBytes = data.quota,
            isDeviceTransfer = isDeviceTransfer
        )

        for (file in plan.files) {
            fullBackupFile(file, data)
        }

        Log.i(
            TAG,
            "Backed up ${plan.files.size} files (${plan.includedSaveBundles}/${plan.totalSaveBundles} saves, " +
                "${plan.estimatedBytes} / ${plan.budgetBytes} bytes, d2d=$isDeviceTransfer)"
        )
    }

    override fun onQuotaExceeded(backupDataBytes: Long, quotaBytes: Long) {
        Log.w(TAG, "Backup quota exceeded: $backupDataBytes > $quotaBytes bytes")
    }

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        Log.i(TAG, "Auto Backup restore finished")
    }

    internal data class SaveBundle(
        val name: String,
        val isMultiplayer: Boolean,
        val lastModifiedMs: Long,
        val files: List<File>,
        val estimatedBytes: Long
    )

    internal data class BackupPlan(
        val files: List<File>,
        val estimatedBytes: Long,
        val budgetBytes: Long,
        val includedSaveBundles: Int,
        val totalSaveBundles: Int
    )

    companion object {
        private const val TAG = "KaMBackupAgent"

        internal const val DEFAULT_CLOUD_QUOTA_BYTES = 25L * 1024L * 1024L
        internal const val DEFAULT_D2D_QUOTA_BYTES = 2L * 1024L * 1024L * 1024L
        internal const val MAX_SAFETY_HEADROOM_BYTES = 1536L * 1024L // 1.5 MB headroom below 25 MB cap
        internal const val TAR_OVERHEAD_PER_FILE_BYTES = 1024L       // ustar header + 512B block padding

        internal const val SETTINGS_XML = "KaM Remake Settings.xml"
        internal const val SERVER_SETTINGS_INI = "KaM Remake Server Settings.ini"
        internal const val SAVES_CMP_DIR = "SavesCMP"
        internal const val SAVES_SP_DIR = "Saves"
        internal const val SAVES_MP_DIR = "SavesMP"

        private val TRANSIENT_SAVE_DIRS = setOf("basesave", "crashreport")

        internal fun computeBudgetBytes(quotaBytes: Long, isDeviceTransfer: Boolean): Long {
            val rawQuota = when {
                quotaBytes > 0L -> quotaBytes
                isDeviceTransfer -> DEFAULT_D2D_QUOTA_BYTES
                else -> DEFAULT_CLOUD_QUOTA_BYTES
            }
            val reserve = minOf(MAX_SAFETY_HEADROOM_BYTES, rawQuota / 10L)
            return (rawQuota - reserve).coerceAtLeast(0L)
        }

        internal fun estimateEntryBytes(file: File): Long =
            file.length().coerceAtLeast(0L) + TAR_OVERHEAD_PER_FILE_BYTES

        private fun isAllowedSaveFile(fileName: String): Boolean {
            val lower = fileName.lowercase()
            // Explicitly skip .spt (2-8 MB Replay Viewer fast-seek cache) and .rng debug logs
            if (lower.endsWith(".spt") || lower.endsWith(".rng")) return false
            return lower.endsWith(".sav") ||
                lower.endsWith(".bas") ||
                lower.endsWith(".rpl") ||
                lower.endsWith(".sloc") ||
                lower.endsWith(".sav.txt")
        }

        private fun isTransientSaveDir(dirName: String): Boolean {
            val lower = dirName.lowercase()
            return lower in TRANSIENT_SAVE_DIRS || lower.startsWith("basesave_thread_")
        }

        internal fun collectSaveBundles(externalFilesDir: File?): List<SaveBundle> {
            if (externalFilesDir == null || !externalFilesDir.isDirectory) return emptyList()
            val bundles = ArrayList<SaveBundle>()

            val roots = listOf(
                File(externalFilesDir, SAVES_SP_DIR) to false,
                File(externalFilesDir, SAVES_MP_DIR) to true
            )

            for ((rootDir, isMp) in roots) {
                val subdirs = rootDir.listFiles() ?: continue
                for (dir in subdirs) {
                    if (!dir.isDirectory || isTransientSaveDir(dir.name)) continue
                    val children = dir.listFiles()?.filter { it.isFile } ?: continue
                    val saveBaseLower = dir.name.lowercase()

                    val savFile = children.firstOrNull { it.name.lowercase() == "$saveBaseLower.sav" }
                    val basFile = children.firstOrNull { it.name.lowercase() == "$saveBaseLower.bas" }
                    val rplFile = children.firstOrNull { it.name.lowercase() == "$saveBaseLower.rpl" }
                    if (savFile == null || basFile == null || rplFile == null) continue

                    val allowedFiles = children
                        .filter { isAllowedSaveFile(it.name) }
                        .sortedBy { it.name }
                    if (allowedFiles.isEmpty()) continue

                    val lastModified = maxOf(savFile.lastModified(), basFile.lastModified(), rplFile.lastModified())
                    val estBytes = allowedFiles.sumOf(::estimateEntryBytes)
                    bundles.add(
                        SaveBundle(
                            name = dir.name,
                            isMultiplayer = isMp,
                            lastModifiedMs = lastModified,
                            files = allowedFiles,
                            estimatedBytes = estBytes
                        )
                    )
                }
            }

            // Newest-first (LRU eviction of oldest saves when approaching the 25 MB cloud cap)
            return bundles.sortedWith(
                compareByDescending<SaveBundle> { it.lastModifiedMs }
                    .thenBy { it.isMultiplayer }
                    .thenBy { it.name.lowercase() }
            )
        }

        internal fun selectBackupPlan(
            sharedPrefsDir: File?,
            externalFilesDir: File?,
            quotaBytes: Long,
            isDeviceTransfer: Boolean = false
        ): BackupPlan {
            val budget = computeBudgetBytes(quotaBytes, isDeviceTransfer)
            val selected = ArrayList<File>()
            var usedBytes = 0L

            fun tryAddFile(file: File): Boolean {
                if (!file.isFile) return false
                val cost = estimateEntryBytes(file)
                if (usedBytes + cost > budget) return false
                selected.add(file)
                usedBytes += cost
                return true
            }

            // 1. Always prioritize SharedPreferences, XML/INI settings, and campaign unlock progress (SavesCMP/)
            sharedPrefsDir
                ?.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".xml", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?.forEach(::tryAddFile)

            if (externalFilesDir != null && externalFilesDir.isDirectory) {
                tryAddFile(File(externalFilesDir, SETTINGS_XML))
                tryAddFile(File(externalFilesDir, SERVER_SETTINGS_INI))

                File(externalFilesDir, SAVES_CMP_DIR)
                    .listFiles()
                    ?.filter { it.isFile && it.name.endsWith(".xml", ignoreCase = true) }
                    ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
                    ?.forEach(::tryAddFile)
            }

            // 2. Pack complete savegame bundles newest-first until the byte budget is full
            val allBundles = collectSaveBundles(externalFilesDir)
            var includedBundles = 0
            for (bundle in allBundles) {
                if (usedBytes + bundle.estimatedBytes <= budget) {
                    selected.addAll(bundle.files)
                    usedBytes += bundle.estimatedBytes
                    includedBundles++
                }
            }

            return BackupPlan(
                files = selected,
                estimatedBytes = usedBytes,
                budgetBytes = budget,
                includedSaveBundles = includedBundles,
                totalSaveBundles = allBundles.size
            )
        }
    }
}
