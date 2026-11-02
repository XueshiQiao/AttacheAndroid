package org.attache.tmux

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttacheFileInboxTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun classifyFileName_identifiesApkImageVideoAndOther() {
        assertEquals(AttacheFileKind.APK, AttacheFileStorage.classifyFileName("app-oss-debug.APK"))
        assertEquals(AttacheFileKind.IMAGE, AttacheFileStorage.classifyFileName("screenshot.png"))
        assertEquals(AttacheFileKind.IMAGE, AttacheFileStorage.classifyFileName("photo.jpg"))
        assertEquals(AttacheFileKind.VIDEO, AttacheFileStorage.classifyFileName("demo.mp4"))
        assertEquals(AttacheFileKind.VIDEO, AttacheFileStorage.classifyFileName("clip.mov"))
        assertEquals(AttacheFileKind.OTHER, AttacheFileStorage.classifyFileName("archive.zip"))
    }

    @Test
    fun allocateTargetFile_returnsWritableFile() {
        val dir = tempFolder.newFolder("Attache")
        val target = AttacheFileStorage.allocateTargetFile(dir, "app-oss-debug.apk")
        assertEquals("app-oss-debug.apk", target.name)
    }
}
