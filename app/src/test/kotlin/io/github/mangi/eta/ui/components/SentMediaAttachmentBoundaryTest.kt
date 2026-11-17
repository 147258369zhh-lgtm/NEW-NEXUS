package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SentMediaAttachmentBoundaryTest {
    private val workspace = File("private/app/files/workspace")
    private val privateRoots = listOf(File("private/app"), File("storage/main/Android/data/app"))
    private val externalRoots = listOf(File("storage"))

    @Test
    fun privatePathsAreLimitedToWorkspaceAndTraversalCannotEscape() {
        assertTrue(allowed("private/app/files/workspace/movie.mp4"))
        assertFalse(allowed("private/app/shared_prefs/settings.xml"))
        assertFalse(allowed("private/app/files/workspace/../credentials.mp3"))
        assertFalse(allowed("private/app/files/workspace-copy/file.mp3"))
        assertFalse(allowed("private/app/files/workspace"))
    }

    @Test
    fun externalReferencesAreAllowedButOwnPrivateExternalDataIsExcluded() {
        assertTrue(allowed("storage/main/Download/music.mp3"))
        assertTrue(allowed("storage/sdcard/DCIM/video.mp4"))
        assertFalse(allowed("storage/main/Android/data/app/files/secrets.mp3"))
        assertFalse(allowed("storage-copy/movie.mp4"))
    }

    @Test
    fun readableSystemLocationsAreNotExternalSelections() {
        assertFalse(allowed("proc/version"))
        assertFalse(allowed("system/media/sound.ogg"))
        assertFalse(allowed("private/other-app/file.mp3"))
    }

    private fun allowed(path: String) = sentMediaPathAllowed(File(path), workspace, privateRoots, externalRoots)
}
