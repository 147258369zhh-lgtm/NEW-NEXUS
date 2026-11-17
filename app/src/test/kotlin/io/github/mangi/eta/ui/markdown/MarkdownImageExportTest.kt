package io.github.mangi.eta.ui.markdown

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.graphics.Bitmap
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.File
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MarkdownImageExportTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private lateinit var provider: ImageExportTestProvider
    private lateinit var destination: File
    private val destinationUri get() = Uri.parse("content://$AUTHORITY/export")

    @Before fun setUp() {
        provider = ImageExportTestProvider().also {
            it.attachInfo(context, ProviderInfo().apply { authority = AUTHORITY; exported = true; grantUriPermissions = true })
        }
        ShadowContentResolver.registerProviderInternal(AUTHORITY, provider)
        destination = File(context.cacheDir, "image-export-${System.nanoTime()}.png").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        provider.file = destination
    }

    @Test fun writesTheExactValidatedImageSourceBytes() = runBlocking {
        val bytes = ByteArrayOutputStream().use { output ->
            Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).apply {
                eraseColor(android.graphics.Color.MAGENTA)
                compress(Bitmap.CompressFormat.PNG, 100, output)
                recycle()
            }
            output.toByteArray()
        }
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        MarkdownImageLoader.exportOriginal(context, "data:image/png;base64,$encoded", destinationUri)
        assertArrayEquals(bytes, destination.readBytes())
    }

    @Test fun rejectsNonImageAndDeletesThePrecreatedDestination() = runBlocking {
        val failure = runCatching {
            MarkdownImageLoader.exportOriginal(context, "data:image/png;base64,${Base64.encodeToString("not an image".toByteArray(), Base64.NO_WRAP)}", destinationUri)
        }.exceptionOrNull()
        assertTrue("non-image data must fail", failure != null)
        assertFalse("failed export must remove the selected destination", destination.exists())
    }

    companion object {
        private const val AUTHORITY = "nexus.markdown.image-export.test"
    }
}

private class ImageExportTestProvider : ContentProvider() {
    lateinit var file: File
    override fun onCreate() = true
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "image/png"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = if (::file.isInitialized && file.delete()) 1 else 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
}
