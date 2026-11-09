package org.kamremake.android

import android.view.KeyEvent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.lang.reflect.Modifier
import java.nio.file.Files

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
    }
}
