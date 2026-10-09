package ph.edu.bsit.tcc.ojtdtr.auth

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import ph.edu.bsit.tcc.ojtdtr.proof.ProofImage
import ph.edu.bsit.tcc.ojtdtr.proof.cleanupProofCache

/** Synthetic images only: real Android decoder/EXIF, no camera, GPS or hosted backend. */
class ProofImageTest {
    private fun jpeg(file: File) {
        file.parentFile!!.mkdirs()
        val bitmap = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888)
        try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)) } }
        finally { bitmap.recycle() }
    }
    @Test fun realDecoderAcceptsJpegAndRejectsCorruptDeletedAndInvalidExif() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"image-test-${UUID.randomUUID()}")
        val file = File(root,"synthetic.jpg")
        try {
            jpeg(file); assertTrue(ProofImage.valid(file))
            for (orientation in 1..8) {
                ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION,orientation.toString()); saveAttributes() }
                val image = ProofImage.load(file)!!
                assertTrue(image.width > 0 && image.height > 0); image.recycle()
            }
            ExifInterface(file).apply { setAttribute(ExifInterface.TAG_ORIENTATION,"9"); saveAttributes() }
            assertFalse(ProofImage.valid(file))
            file.writeBytes(byteArrayOf(0,1,2)); assertFalse(ProofImage.valid(file))
            file.delete(); assertFalse(ProofImage.valid(file))
            jpeg(file); assertTrue(ProofImage.valid(file))
        } finally { root.deleteRecursively() }
    }
    @Test fun startupCleanupPreservesOtherPrivateCacheOnAndroid() {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"cleanup-test-${UUID.randomUUID()}")
        try {
            val old = File(root,"native-proof/abandoned/synthetic.jpg"); jpeg(old)
            val other = File(root,"unrelated.txt").apply { writeText("keep") }
            assertTrue(cleanupProofCache(root)); assertFalse(old.exists()); assertEquals("keep",other.readText())
            assertTrue(cleanupProofCache(root))
        } finally { root.deleteRecursively() }
    }
    @Test fun noFollowCleanupPreservesAndroidFileAndDirectorySymlinkTargets() {
        val cache = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,"link-test-${UUID.randomUUID()}").apply { mkdirs() }
        val other = File(cache,"unrelated").apply { mkdirs() }
        val sentinel = File(other,"keep.txt").apply { writeText("keep") }
        val session = File(cache,"native-proof/student-a").apply { mkdirs() }
        val b = File(cache,"native-proof/student-b/keep.jpg").apply { parentFile!!.mkdirs(); writeText("student-b") }
        try {
            java.nio.file.Files.createSymbolicLink(File(session,"file-link").toPath(),sentinel.toPath())
            java.nio.file.Files.createSymbolicLink(File(session,"directory-link").toPath(),other.toPath())
            val nested = File(session,"nested").apply { mkdirs() }
            java.nio.file.Files.createSymbolicLink(File(nested,"nested-link").toPath(),other.toPath())
            assertTrue(ph.edu.bsit.tcc.ojtdtr.proof.cleanupProofSession(cache,session))
            assertFalse(session.exists()); assertEquals("keep",sentinel.readText()); assertEquals("student-b",b.readText())
            assertFalse(ph.edu.bsit.tcc.ojtdtr.proof.cleanupProofSession(cache,other))
            assertTrue(cleanupProofCache(cache)); assertEquals("keep",sentinel.readText())
            java.nio.file.Files.createSymbolicLink(File(cache,"native-proof").toPath(),other.toPath())
            assertTrue(cleanupProofCache(cache)); assertEquals("keep",sentinel.readText())
            assertTrue(cleanupProofCache(cache))
        } finally { cleanupProofCache(cache); cache.deleteRecursively() }
    }

    @Test fun androidBoundedReaderHandsOffJpegAndRejectsSymlink() {
        val cache = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "reader-test-${UUID.randomUUID()}").apply { mkdirs() }
        val image = File(cache, "native-proof/session/synthetic.jpg")
        try {
            jpeg(image)
            val bytes = ph.edu.bsit.tcc.ojtdtr.proof.readSubmissionImage(cache, image)
            try { assertEquals(image.length(), bytes.size.toLong()); assertTrue(bytes.isNotEmpty()) }
            finally { bytes.fill(0) }
            val outside = File(cache, "unrelated.jpg"); jpeg(outside)
            val link = File(image.parentFile, "link.jpg")
            java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
            try { ph.edu.bsit.tcc.ojtdtr.proof.readSubmissionImage(cache, link); fail() }
            catch (_: java.io.IOException) { }
            assertTrue(ProofImage.valid(outside)); assertTrue(ph.edu.bsit.tcc.ojtdtr.proof.cleanupProofCache(cache))
            assertTrue(ProofImage.valid(outside))
        } finally { ph.edu.bsit.tcc.ojtdtr.proof.cleanupProofCache(cache); cache.deleteRecursively() }
    }

}
