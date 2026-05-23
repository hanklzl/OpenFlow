package com.hank.flow.open.debug

import com.hank.flow.open.debug.DebugModelInstaller.InstallResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class DebugModelInstallerTest {

    private lateinit var tmp: File

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("of-installer-test").toFile()
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    private fun newSrc(name: String, size: Int = 1024, byte: Byte = 0x42): File {
        val f = File(tmp, name)
        f.writeBytes(ByteArray(size) { byte })
        return f
    }

    private fun newTarget(name: String): File = File(File(tmp, "models").apply { mkdirs() }, name)

    @Test
    fun installCopiesSrcToTargetAndDeletesSrcOnFreshInstall() {
        val src = newSrc("ggml-tiny-q5_1.bin", size = 1_000_000)
        val target = newTarget("ggml-tiny-q5_1.bin")
        val result = DebugModelInstaller.install(src, target, force = false)

        assertTrue(result is InstallResult.Done)
        assertEquals(1_000_000L, (result as InstallResult.Done).targetSize)
        assertTrue(target.exists())
        assertEquals(1_000_000L, target.length())
        assertFalse("src should be deleted on success", src.exists())
    }

    @Test
    fun installSkipsWhenTargetSameSizeAndForceFalse() {
        val src = newSrc("model.gguf", size = 4096)
        val target = newTarget("model.gguf")
        // 先预置 target 同 size
        target.writeBytes(ByteArray(4096) { 0x99.toByte() })

        val result = DebugModelInstaller.install(src, target, force = false)

        assertTrue(result is InstallResult.Skip)
        assertEquals(4096L, (result as InstallResult.Skip).targetSize)
        // target 内容未被覆盖（仍是 0x99 不是 src 的 0x42）
        assertEquals(0x99.toByte(), target.readBytes()[0])
        // src 不应被删（skip 不消耗 src）
        assertTrue(src.exists())
    }

    @Test
    fun installOverwritesWhenForceTrueEvenSameSize() {
        val src = newSrc("model.gguf", size = 4096, byte = 0x42)
        val target = newTarget("model.gguf")
        target.writeBytes(ByteArray(4096) { 0x99.toByte() })

        val result = DebugModelInstaller.install(src, target, force = true)

        assertTrue(result is InstallResult.Done)
        assertEquals(0x42.toByte(), target.readBytes()[0])  // 已被覆盖
        assertFalse(src.exists())
    }

    @Test
    fun installCopiesWhenTargetExistsButSizeDiffers() {
        val src = newSrc("model.gguf", size = 8192)
        val target = newTarget("model.gguf")
        target.writeBytes(ByteArray(4096))  // 不同 size

        val result = DebugModelInstaller.install(src, target, force = false)

        assertTrue(result is InstallResult.Done)
        assertEquals(8192L, target.length())
    }

    @Test
    fun installReturnsSrcMissingWhenSrcDoesNotExist() {
        val src = File(tmp, "nonexistent.bin")
        val target = newTarget("model.bin")

        val result = DebugModelInstaller.install(src, target, force = false)

        assertTrue(result is InstallResult.SrcMissing)
        assertEquals(src.absolutePath, (result as InstallResult.SrcMissing).srcPath)
        assertFalse(target.exists())
    }

    @Test
    fun installReturnsSrcMissingWhenSrcIsEmpty() {
        val src = newSrc("empty.bin", size = 0)
        val target = newTarget("model.bin")

        val result = DebugModelInstaller.install(src, target, force = false)

        assertTrue(result is InstallResult.SrcMissing)
        assertFalse(target.exists())
    }

    @Test
    fun installCreatesTargetParentDirsWhenMissing() {
        val src = newSrc("model.bin", size = 256)
        // 给 target 一个三层深、未创建的父目录
        val target = File(tmp, "nested/dir/structure/model.bin")
        assertFalse(target.parentFile.exists())

        val result = DebugModelInstaller.install(src, target, force = false)

        assertTrue(result is InstallResult.Done)
        assertTrue(target.exists())
        assertTrue(target.parentFile.exists())
    }

    @Test
    fun installReturnsCopyFailedWhenTargetIsADirectory() {
        val src = newSrc("model.bin", size = 256)
        val target = newTarget("a-directory")
        target.mkdirs()  // target 是已存在的目录，copyTo 会抛

        val result = DebugModelInstaller.install(src, target, force = true)

        assertTrue("expected CopyFailed but got $result", result is InstallResult.CopyFailed)
        assertNotNull((result as InstallResult.CopyFailed).cause)
    }
}
