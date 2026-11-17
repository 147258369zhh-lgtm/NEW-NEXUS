package io.github.mangi.eta.agent.cloud

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Android's rename atomically replaces an existing file. Windows File.renameTo does not, and the
 * SDK AtomicFile only logs that failure. Keep its real start/fail/read logic; adapt only rename.
 */
@Implements(AtomicFile::class)
class CloudAtomicFileRenameShadow {
    companion object {
        @JvmStatic
        @Implementation
        fun rename(source: File, destination: File) {
            Files.move(source.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
