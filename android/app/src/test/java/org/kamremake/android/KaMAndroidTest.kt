package org.kamremake.android

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class KaMAndroidTest {

    @Test
    fun keyMapLettersDigitsAndFunctionKeys() {
        assertEquals('A'.code, KeyMap.toVK(KeyEvent.KEYCODE_A))
        assertEquals('M'.code, KeyMap.toVK(KeyEvent.KEYCODE_M))
        assertEquals('Z'.code, KeyMap.toVK(KeyEvent.KEYCODE_Z))

        assertEquals('0'.code, KeyMap.toVK(KeyEvent.KEYCODE_0))
        assertEquals('5'.code, KeyMap.toVK(KeyEvent.KEYCODE_5))
        assertEquals('9'.code, KeyMap.toVK(KeyEvent.KEYCODE_9))

        assertEquals(112, KeyMap.toVK(KeyEvent.KEYCODE_F1))
        assertEquals(123, KeyMap.toVK(KeyEvent.KEYCODE_F12))

        assertEquals(96, KeyMap.toVK(KeyEvent.KEYCODE_NUMPAD_0))
        assertEquals(105, KeyMap.toVK(KeyEvent.KEYCODE_NUMPAD_9))
    }

    @Test
    fun keyMapSpecialAndPunctuationKeys() {
        assertEquals(8, KeyMap.toVK(KeyEvent.KEYCODE_DEL))
        assertEquals(9, KeyMap.toVK(KeyEvent.KEYCODE_TAB))
        assertEquals(13, KeyMap.toVK(KeyEvent.KEYCODE_ENTER))
        assertEquals(13, KeyMap.toVK(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(16, KeyMap.toVK(KeyEvent.KEYCODE_SHIFT_LEFT))
        assertEquals(16, KeyMap.toVK(KeyEvent.KEYCODE_SHIFT_RIGHT))
        assertEquals(17, KeyMap.toVK(KeyEvent.KEYCODE_CTRL_LEFT))
        assertEquals(17, KeyMap.toVK(KeyEvent.KEYCODE_CTRL_RIGHT))
        assertEquals(18, KeyMap.toVK(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(18, KeyMap.toVK(KeyEvent.KEYCODE_ALT_RIGHT))
        assertEquals(27, KeyMap.toVK(KeyEvent.KEYCODE_ESCAPE))
        assertEquals(32, KeyMap.toVK(KeyEvent.KEYCODE_SPACE))
        assertEquals(37, KeyMap.toVK(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(38, KeyMap.toVK(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(39, KeyMap.toVK(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(40, KeyMap.toVK(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(186, KeyMap.toVK(KeyEvent.KEYCODE_SEMICOLON))
        assertEquals(222, KeyMap.toVK(KeyEvent.KEYCODE_APOSTROPHE))
        assertEquals(0, KeyMap.toVK(KeyEvent.KEYCODE_UNKNOWN))
        assertEquals(0, KeyMap.toVK(KeyEvent.KEYCODE_VOLUME_UP))
    }

    @Test
    fun keyMapShiftFlags() {
        assertEquals(0, KeyMap.shiftFlags(shift = false, ctrl = false, alt = false))
        assertEquals(1, KeyMap.shiftFlags(shift = true, ctrl = false, alt = false))
        assertEquals(2, KeyMap.shiftFlags(shift = false, ctrl = true, alt = false))
        assertEquals(4, KeyMap.shiftFlags(shift = false, ctrl = false, alt = true))
        assertEquals(7, KeyMap.shiftFlags(shift = true, ctrl = true, alt = true))
    }

    @Test
    fun dataInstallerExtractsFilesAndWritesMarker() {
        val tempDir = Files.createTempDirectory("kam_test_data").toFile()
        try {
            assertFalse(DataInstaller.isInstalled(tempDir))

            val file1Bytes = "houses data content".toByteArray(Charsets.UTF_8)
            val file2Bytes = ByteArray(4096) { (it % 251).toByte() }
            val manifest = buildString {
                append("${file1Bytes.size}\tdata/defines/houses.dat\ta0\n")
                append("${file2Bytes.size}\tCampaigns/TSK/map1.map\ta1\n")
                append("${file1Bytes.size}\tdata/defines/houses_copy.dat\ta0\n")
                append("invalid_line\n")
            }.toByteArray(Charsets.UTF_8)

            val assets = mapOf(
                "kamdata.lst" to manifest,
                "kamdata/a0" to file1Bytes,
                "kamdata/a1" to file2Bytes
            )
            val progressEvents = mutableListOf<Pair<Long, Long>>()

            DataInstaller.install(
                openAsset = { path ->
                    val bytes = assets[path] ?: throw FileNotFoundException(path)
                    ByteArrayInputStream(bytes)
                },
                dir = tempDir,
                progress = { done, total -> progressEvents.add(done to total) }
            )

            assertTrue(DataInstaller.isInstalled(tempDir))
            assertArrayEquals(file1Bytes, File(tempDir, "data/defines/houses.dat").readBytes())
            assertArrayEquals(file2Bytes, File(tempDir, "Campaigns/TSK/map1.map").readBytes())
            assertArrayEquals(file1Bytes, File(tempDir, "data/defines/houses_copy.dat").readBytes())

            val expectedTotal = (file1Bytes.size * 2 + file2Bytes.size).toLong()
            assertEquals(3, progressEvents.size)
            assertEquals(expectedTotal, progressEvents.last().first)
            assertEquals(expectedTotal, progressEvents.last().second)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun dataInstallerImportsFromZipWithWrapperFolderAndCanonicalizesCase() {
        val tempDir = Files.createTempDirectory("kam_test_zip").toFile()
        try {
            val housesBytes = "houses-zip-data".toByteArray(Charsets.UTF_8)
            val mapBytes = "campaign-map-data".toByteArray(Charsets.UTF_8)
            val mpMapBytes = "mp-map-data".toByteArray(Charsets.UTF_8)

            val zipBuf = ByteArrayOutputStream()
            ZipOutputStream(zipBuf).use { zos ->
                fun put(name: String, content: ByteArray) {
                    zos.putNextEntry(ZipEntry(name))
                    zos.write(content)
                    zos.closeEntry()
                }
                put("KaM Remake\\DATA\\defines\\houses.dat", housesBytes)
                put("KaM Remake/campaigns/TSK/TSK01.map", mapBytes)
                put("KaM Remake/mapsmp/Bridge/Bridge.map", mpMapBytes)
                put("KaM Remake/KaM_Remake.exe", "ignored-exe".toByteArray(Charsets.UTF_8))
            }
            val zipBytes = zipBuf.toByteArray()

            DataInstaller.installFromZip(
                openZip = { ByteArrayInputStream(zipBytes) },
                dir = tempDir,
                progress = { _, _ -> }
            )

            assertTrue(DataInstaller.isInstalled(tempDir))
            assertTrue(DataInstaller.hasValidGameData(tempDir))
            assertArrayEquals(housesBytes, File(tempDir, "data/defines/houses.dat").readBytes())
            assertArrayEquals(mapBytes, File(tempDir, "Campaigns/TSK/TSK01.map").readBytes())
            assertArrayEquals(mpMapBytes, File(tempDir, "MapsMP/Bridge/Bridge.map").readBytes())
            assertFalse(File(tempDir, "KaM_Remake.exe").exists())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test(expected = IOException::class)
    fun dataInstallerRejectsZipWithoutHousesDat() {
        val tempDir = Files.createTempDirectory("kam_test_bad_zip").toFile()
        try {
            val zipBuf = ByteArrayOutputStream()
            ZipOutputStream(zipBuf).use { zos ->
                zos.putNextEntry(ZipEntry("random/readme.txt"))
                zos.write("hello".toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
            val zipBytes = zipBuf.toByteArray()
            DataInstaller.installFromZip(
                openZip = { ByteArrayInputStream(zipBytes) },
                dir = tempDir,
                progress = { _, _ -> }
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun classifyPathListDistinguishesOriginalKaMFromRemakeAndUnknown() {
        val gogOriginal = listOf(
            "app/KM_TPR.exe",
            "app/data/defines/houses.dat",
            "app/data/gfx/res/gui.rx",
            "app/data/gfx/res/units.rx"
        )
        assertEquals(DataInstaller.ArchiveKind.ORIGINAL_KAM, DataInstaller.classifyPathList(gogOriginal))

        val remakeArchive = listOf(
            "app/KaM_Remake.exe",
            "app/data/defines/houses.dat",
            "app/data/Sprites/Units.rxa",
            "app/data/Sprites/GUI.rxx"
        )
        assertEquals(DataInstaller.ArchiveKind.KAM_REMAKE_DATA, DataInstaller.classifyPathList(remakeArchive))

        val unrelated = listOf("docs/readme.txt", "setup.ini")
        assertEquals(DataInstaller.ArchiveKind.UNKNOWN, DataInstaller.classifyPathList(unrelated))
    }

    @Test(expected = IOException::class)
    fun downloadRemakeRequiresOriginalGameVerificationFirst() {
        val tempDir = Files.createTempDirectory("kam_test_unverified").toFile()
        val tempCache = Files.createTempDirectory("kam_test_cache").toFile()
        try {
            assertFalse(DataInstaller.isOriginalGameVerified(tempDir))
            DataInstaller.downloadAndInstallRemake(
                dir = tempDir,
                cacheDir = tempCache,
                status = {},
                downloadUrl = "http://127.0.0.1:1/unused"
            )
        } finally {
            tempDir.deleteRecursively()
            tempCache.deleteRecursively()
        }
    }

    @Test
    fun httpsRedirectHelperAllowsHttpsAndBlocksProtocolDowngrade() {
        assertEquals(
            "https://example.com/files/pkg.exe",
            DataInstaller.resolveHttpsRedirect("https://example.com/dl", "/files/pkg.exe")
        )
        assertEquals(
            "https://cdn.example.com/pkg.exe",
            DataInstaller.resolveHttpsRedirect("https://example.com/dl", "https://cdn.example.com/pkg.exe")
        )
        var rejectedDowngrade = false
        try {
            DataInstaller.resolveHttpsRedirect("https://example.com/dl", "http://evil.example.com/pkg.exe")
        } catch (_: IOException) {
            rejectedDowngrade = true
        }
        assertTrue(rejectedDowngrade)

        var rejectedHttpStart = false
        try {
            DataInstaller.requireHttpsUrl("http://example.com/pkg.exe")
        } catch (_: IOException) {
            rejectedHttpStart = true
        }
        assertTrue(rejectedHttpStart)
    }

    @Test
    fun calculateVirtualSizeRespectsMenuAndInGameBounds() {
        // 1920x1080 in menu at scale 1.0 -> height is base 720, width scaled by 16:9 -> 1280x720
        val menuDefault = KaMView.calculateVirtualSize(w = 1920, h = 1080, uiScale = 1.0f, inGame = false)
        assertEquals(1280, menuDefault.width)
        assertEquals(720, menuDefault.height)
        assertEquals(1280f / 1920f, menuDefault.touchScaleX, 1e-5f)
        assertEquals(720f / 1080f, menuDefault.touchScaleY, 1e-5f)

        // Menu at scale 1.5 still keeps at least BASE_WIDTH x BASE_HEIGHT (1024x720)
        val menuScaled = KaMView.calculateVirtualSize(w = 1920, h = 1080, uiScale = 1.5f, inGame = false)
        assertTrue(menuScaled.width >= KaMView.BASE_WIDTH)
        assertTrue(menuScaled.height >= KaMView.BASE_HEIGHT)
        assertEquals(0, menuScaled.width and 1)
        assertEquals(0, menuScaled.height and 1)

        // In-game at scale 1.5 allows smaller virtual height (larger UI) down to MIN_GAME_HEIGHT
        val gameScaled = KaMView.calculateVirtualSize(w = 1920, h = 1080, uiScale = 1.5f, inGame = true)
        assertTrue(gameScaled.height < KaMView.BASE_HEIGHT)
        assertTrue(gameScaled.width >= KaMView.MIN_GAME_WIDTH)
        assertTrue(gameScaled.height >= KaMView.MIN_GAME_HEIGHT)
        assertEquals(0, gameScaled.width and 1)
        assertEquals(0, gameScaled.height and 1)

        // With a left cutout inset in-game at max scale 2.0, safe virtual width still fits MIN_GAME_WIDTH
        val cutoutLeftPx = 120
        val gameCutout = KaMView.calculateVirtualSize(
            w = 1920,
            h = 1080,
            uiScale = 2.0f,
            inGame = true,
            cutoutLeftPx = cutoutLeftPx,
            cutoutRightPx = 0
        )
        val virtSafeLeft = kotlin.math.round(cutoutLeftPx * gameCutout.touchScaleX).toInt()
        assertTrue(gameCutout.width - virtSafeLeft >= KaMView.MIN_GAME_WIDTH - 1)
    }

    @Test
    fun calculateGestureExclusionBoundsProtectsSidebarOnlyDuringGameplay() {
        // Menus / fullscreen pages must not exclude system back gestures
        assertTrue(
            KaMView.calculateGestureExclusionBounds(
                viewWidth = 2400,
                viewHeight = 1080,
                inGame = false,
                virtToolbarWidth = 280,
                touchScaleX = 1280f / 2400f
            ).isEmpty()
        )
        assertTrue(
            KaMView.calculateGestureExclusionBounds(
                viewWidth = 2400,
                viewHeight = 1080,
                inGame = true,
                virtToolbarWidth = 0,
                touchScaleX = 0.5f
            ).isEmpty()
        )

        // Gameplay with 125% sidebar (280 virt px) + left cutout (60 virt px = 340 virt px)
        // at touchScaleX = 0.5 -> physical exclusion covers [0, 0, 680, 1080], leaving right edge free for Back
        val exclusions = KaMView.calculateGestureExclusionBounds(
            viewWidth = 2400,
            viewHeight = 1080,
            inGame = true,
            virtToolbarWidth = 340,
            touchScaleX = 0.5f
        )
        assertEquals(1, exclusions.size)
        assertEquals(KaMView.ExclusionBounds(0, 0, 680, 1080), exclusions[0])

        // Clamps safely to viewWidth on narrow / extreme scale inputs
        val clamped = KaMView.calculateGestureExclusionBounds(
            viewWidth = 800,
            viewHeight = 600,
            inGame = true,
            virtToolbarWidth = 500,
            touchScaleX = 0.25f
        )
        assertEquals(KaMView.ExclusionBounds(0, 0, 800, 600), clamped.single())
    }

    @Test
    fun kamNativeJniSignaturesMatchNativeHost() {
        // Load class without running static init (which loads libkamhost.so)
        val cls = Class.forName("org.kamremake.android.KaMNative", false, javaClass.classLoader)

        fun assertStaticMethod(name: String, returnType: Class<*>, vararg params: Class<*>, expectNative: Boolean) {
            val m = cls.getDeclaredMethod(name, *params)
            assertEquals("Return type of $name", returnType, m.returnType)
            assertTrue("$name must be static for JNI", Modifier.isStatic(m.modifiers))
            assertEquals("$name native modifier", expectNative, Modifier.isNative(m.modifiers))
        }

        // JNI exports in kamhost.c
        assertStaticMethod("init", Boolean::class.javaPrimitiveType!!, String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("frame", Void.TYPE, expectNative = true)
        assertStaticMethod("resize", Void.TYPE, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("setSafeInsets", Void.TYPE, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("setUiScale", Void.TYPE, Float::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("pause", Void.TYPE, expectNative = true)
        assertStaticMethod("resume", Void.TYPE, expectNative = true)
        assertStaticMethod("shutdown", Void.TYPE, expectNative = true)
        assertStaticMethod(
            "touch",
            Void.TYPE,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            Float::class.javaPrimitiveType!!,
            expectNative = true
        )
        assertStaticMethod("mouse", Void.TYPE, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("wheel", Void.TYPE, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("key", Void.TYPE, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("charInput", Void.TYPE, Int::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("back", Void.TYPE, expectNative = true)
        assertStaticMethod("textInputActive", Boolean::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("videoFinished", Void.TYPE, expectNative = true)
        assertStaticMethod("inGame", Boolean::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("toolbarWidth", Int::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("textInputBottom", Int::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("textFieldTapped", Boolean::class.javaPrimitiveType!!, expectNative = true)
        assertStaticMethod("selfTest", Void.TYPE, String::class.java, expectNative = true)

        // Static callbacks looked up in JNI_OnLoad via GetStaticMethodID
        assertStaticMethod("onShowMessage", Void.TYPE, String::class.java, String::class.java, expectNative = false)
        assertStaticMethod("onClipboardSet", Void.TYPE, String::class.java, expectNative = false)
        assertStaticMethod("onClipboardGet", String::class.java, expectNative = false)
        assertStaticMethod("onOpenURL", Void.TYPE, String::class.java, expectNative = false)
        assertStaticMethod("onQuit", Void.TYPE, expectNative = false)
        assertStaticMethod("onPlayVideo", Void.TYPE, String::class.java, Int::class.javaPrimitiveType!!, Boolean::class.javaPrimitiveType!!, expectNative = false)
        assertStaticMethod("onStopVideo", Void.TYPE, expectNative = false)
        assertStaticMethod("onHaptic", Void.TYPE, expectNative = false)
        assertStaticMethod("onSetUiScale", Void.TYPE, Float::class.javaPrimitiveType!!, expectNative = false)
    }

    @Test
    fun backupAgentPacksEssentialsAndNewestSaveBundlesUnderQuotaWithoutDeletingLocalFiles() {
        val root = Files.createTempDirectory("kam_test_backup").toFile()
        try {
            val sharedPrefs = File(root, "shared_prefs").apply { mkdirs() }
            val ext = File(root, "external").apply { mkdirs() }

            val prefFile = File(sharedPrefs, "kam.xml").apply { writeText("<map><float name=\"ui_scale\" value=\"1.25\" /></map>") }
            val settingsXml = File(ext, KaMBackupAgent.SETTINGS_XML).apply { writeText("<Settings/>") }
            val serverIni = File(ext, KaMBackupAgent.SERVER_SETTINGS_INI).apply { writeText("[Server]\n") }
            val cmpDir = File(ext, KaMBackupAgent.SAVES_CMP_DIR).apply { mkdirs() }
            val tskXml = File(cmpDir, "TSK.xml").apply { writeText("<Campaign Unlocked=\"7\"/>") }

            // Game assets & logs must never be selected for backup
            File(ext, "data/sprites").apply { mkdirs() }
            val spriteAsset = File(ext, "data/sprites/gui.rxa").apply { writeBytes(ByteArray(32_768)) }
            File(ext, "Logs").apply { mkdirs() }
            val logFile = File(ext, "Logs/KaM.log").apply { writeText("engine log") }

            fun writeSave(
                parentName: String,
                saveName: String,
                payloadBytes: Int,
                mtimeMs: Long,
                withSptBytes: Int = 0,
                withSloc: Boolean = false
            ): File {
                val dir = File(File(ext, parentName), saveName).apply { mkdirs() }
                val chunk = payloadBytes / 3
                val sav = File(dir, "$saveName.sav").apply {
                    writeBytes(ByteArray(chunk) { 1 })
                    setLastModified(mtimeMs)
                }
                val bas = File(dir, "$saveName.bas").apply {
                    writeBytes(ByteArray(chunk) { 2 })
                    setLastModified(mtimeMs)
                }
                val rpl = File(dir, "$saveName.rpl").apply {
                    writeBytes(ByteArray(payloadBytes - 2 * chunk) { 3 })
                    setLastModified(mtimeMs)
                }
                if (withSloc) {
                    File(dir, "$saveName.sloc").apply {
                        writeBytes(ByteArray(64) { 4 })
                        setLastModified(mtimeMs)
                    }
                }
                if (withSptBytes > 0) {
                    // Bulky .spt seek cache + .rng debug log must be excluded even on backed-up saves
                    File(dir, "$saveName.spt").apply {
                        writeBytes(ByteArray(withSptBytes) { 9 })
                        setLastModified(mtimeMs)
                    }
                    File(dir, "$saveName.rng").apply {
                        writeText("rng trace")
                        setLastModified(mtimeMs)
                    }
                }
                assertTrue(sav.isFile && bas.isFile && rpl.isFile)
                return dir
            }

            // Transient / incomplete folders must be ignored even if newest
            writeSave(KaMBackupAgent.SAVES_SP_DIR, "basesave", payloadBytes = 9_000, mtimeMs = 900_000L)
            writeSave(KaMBackupAgent.SAVES_SP_DIR, "basesave_thread_1", payloadBytes = 9_000, mtimeMs = 900_000L)
            writeSave(KaMBackupAgent.SAVES_SP_DIR, "crashreport", payloadBytes = 9_000, mtimeMs = 900_000L)
            File(ext, "Saves/Incomplete").apply {
                mkdirs()
                File(this, "Incomplete.sav").writeBytes(ByteArray(4_000))
            }

            // Four real saves (~30 KB raw + 3 KB tar overhead = ~33 KB each) with distinct timestamps
            val oldSaveDir = writeSave(KaMBackupAgent.SAVES_SP_DIR, "Save 1", payloadBytes = 30_000, mtimeMs = 100_000L)
            val midSaveDir = writeSave(KaMBackupAgent.SAVES_SP_DIR, "Save 2", payloadBytes = 30_000, mtimeMs = 200_000L)
            val mpSaveDir = writeSave(KaMBackupAgent.SAVES_MP_DIR, "Coop 1", payloadBytes = 30_000, mtimeMs = 300_000L, withSloc = true)
            val newestSuspendDir = writeSave(
                KaMBackupAgent.SAVES_SP_DIR,
                "autosave_suspend",
                payloadBytes = 30_000,
                mtimeMs = 400_000L,
                withSptBytes = 500_000 // 500 KB .spt would blow the 90 KB quota if not excluded!
            )

            // Tight 90 KB quota -> 81 KB budget: fits essentials (~4 KB) + newest 2 saves (~67 KB), evicting Save 2 & Save 1
            val plan = KaMBackupAgent.selectBackupPlan(
                sharedPrefsDir = sharedPrefs,
                externalFilesDir = ext,
                quotaBytes = 90_000L,
                isDeviceTransfer = false
            )

            assertEquals(4, plan.totalSaveBundles)
            assertEquals(2, plan.includedSaveBundles)
            assertTrue(plan.estimatedBytes <= plan.budgetBytes)

            val paths = plan.files.map { it.canonicalPath }.toSet()
            // Essentials are always included
            assertTrue(prefFile.canonicalPath in paths)
            assertTrue(settingsXml.canonicalPath in paths)
            assertTrue(serverIni.canonicalPath in paths)
            assertTrue(tskXml.canonicalPath in paths)

            // Game assets, logs, and .spt/.rng caches are excluded
            assertFalse(spriteAsset.canonicalPath in paths)
            assertFalse(logFile.canonicalPath in paths)
            assertFalse(File(newestSuspendDir, "autosave_suspend.spt").canonicalPath in paths)
            assertFalse(File(newestSuspendDir, "autosave_suspend.rng").canonicalPath in paths)

            // Newest 2 save bundles (autosave_suspend @ 400k and Coop 1 @ 300k) are backed up completely
            for (extName in listOf("sav", "bas", "rpl")) {
                assertTrue(File(newestSuspendDir, "autosave_suspend.$extName").canonicalPath in paths)
                assertTrue(File(mpSaveDir, "Coop 1.$extName").canonicalPath in paths)
            }
            assertTrue(File(mpSaveDir, "Coop 1.sloc").canonicalPath in paths)

            // Older saves (Save 2 @ 200k, Save 1 @ 100k) are skipped in cloud backup...
            assertFalse(File(midSaveDir, "Save 2.sav").canonicalPath in paths)
            assertFalse(File(oldSaveDir, "Save 1.sav").canonicalPath in paths)
            // ...while remaining 100% intact on local storage!
            assertTrue(File(midSaveDir, "Save 2.sav").isFile)
            assertTrue(File(oldSaveDir, "Save 1.sav").isFile)

            // Device-to-device transfer (2 GB default quota) includes all 4 valid save bundles
            val d2dPlan = KaMBackupAgent.selectBackupPlan(
                sharedPrefsDir = sharedPrefs,
                externalFilesDir = ext,
                quotaBytes = 0L,
                isDeviceTransfer = true
            )
            assertEquals(4, d2dPlan.includedSaveBundles)
        } finally {
            root.deleteRecursively()
        }
    }
}
