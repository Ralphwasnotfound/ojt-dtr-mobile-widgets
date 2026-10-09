package ph.edu.bsit.tcc.ojtdtr.proof

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProofFilesTest {
    @Test fun expectedImageFailuresBecomeNullButProgrammingFailuresEscape() {
        val root = Files.createTempDirectory("proof-read").toFile()
        val file = File(root, "image.jpg").apply { writeBytes(byteArrayOf(1)) }
        try {
            assertNull(readProofImage<String>(file) { null }) // Undecodable content.
            for (failure in listOf(IOException(), SecurityException(), IllegalArgumentException()))
                assertNull(readProofImage<String>(file) { throw failure }) // I/O / unreadable / EXIF.
            try { readProofImage<String>(file) { throw IllegalStateException("synthetic programming error") }; fail() }
            catch (_: IllegalStateException) { }
            file.delete()
            assertNull(readProofImage<String>(file) { error("deleted file must not be decoded") })
            val unreadable = object : File(root, "fake-unreadable") {
                override fun isFile() = true
                override fun canRead() = false
            }
            assertNull(readProofImage<String>(unreadable) { error("unreadable file must not be decoded") })
        } finally { root.deleteRecursively() }
    }
    @Test fun startupRemovesOnlyAbandonedProofFilesAndIsIdempotent() {
        val cache = Files.createTempDirectory("proof-startup").toFile()
        try {
            val proof = File(cache, "native-proof/old-session/image.jpg").apply { parentFile!!.mkdirs(); writeText("synthetic") }
            val other = File(cache, "other-private/image.jpg").apply { parentFile!!.mkdirs(); writeText("keep") }
            assertTrue(cleanupProofCache(cache)); assertFalse(proof.exists()); assertEquals("keep", other.readText())
            assertTrue(cleanupProofCache(cache)); assertEquals("keep", other.readText())
        } finally { cache.deleteRecursively() }
    }
    @Test fun startupAndSessionSymlinkSentinelsSurvive() {
        val cache = Files.createTempDirectory("proof-links").toFile()
        val other = Files.createTempDirectory("proof-unrelated").toFile()
        try {
            val sentinel = File(other,"keep.txt").apply { writeText("keep") }
            val session = File(cache,"native-proof/student-a").apply { mkdirs() }
            val b = File(cache,"native-proof/student-b/keep.jpg").apply { parentFile!!.mkdirs(); writeText("student-b") }
            val normal = File(session,"image.jpg").apply { writeText("synthetic") }
            Files.createSymbolicLink(File(session,"file-link").toPath(),sentinel.toPath())
            assertTrue(deleteProofImage(cache,File(session,"file-link")))
            assertEquals("keep",sentinel.readText())
            Files.createSymbolicLink(File(session,"file-link").toPath(),sentinel.toPath())
            Files.createSymbolicLink(File(session,"directory-link").toPath(),other.toPath())
            val nested = File(session,"nested").apply { mkdirs() }
            Files.createSymbolicLink(File(nested,"nested-link").toPath(),other.toPath())
            assertTrue(cleanupProofSession(cache,session)); assertFalse(normal.exists())
            assertEquals("keep",sentinel.readText()); assertEquals("student-b",b.readText())
            assertTrue(cleanupProofSession(cache,session))
            Files.createSymbolicLink(File(cache,"native-proof/root-link").toPath(),other.toPath())
            assertTrue(cleanupProofCache(cache)); assertEquals("keep",sentinel.readText())
            assertFalse(File(cache,"native-proof").exists()); assertTrue(cleanupProofCache(cache))
            // Even the namespace root itself can be a link: startup unlinks, never descends.
            Files.createSymbolicLink(File(cache,"native-proof").toPath(),other.toPath())
            assertTrue(cleanupProofCache(cache)); assertEquals("keep",sentinel.readText())
        } finally { cleanupProofCache(cache); cache.deleteRecursively(); other.deleteRecursively() }
    }
    @Test fun invalidAndAncestorLinkPathsFailClosedWithoutDeletingTargets() {
        val cache = Files.createTempDirectory("proof-boundary").toFile()
        val other = Files.createTempDirectory("proof-outside").toFile()
        try {
            val sentinel = File(other,"keep.txt").apply { writeText("keep") }
            assertFalse(cleanupProofSession(cache,other))
            assertFalse(cleanupProofSession(cache,File(cache,"native-proof/../other")))
            assertFalse(cleanupProofSession(cache,File(cache,"native-proof")))
            assertFalse(deleteProofImage(cache,sentinel))
            assertTrue(cleanupProofSession(cache,File(cache,"native-proof/missing")))
            assertTrue(deleteProofImage(cache,File(cache,"native-proof/missing/missing.jpg")))
            val pretender = File(cache,"native-proof-extra/session/keep.txt").apply { parentFile!!.mkdirs(); writeText("keep") }
            assertFalse(cleanupProofSession(cache,pretender.parentFile!!)); assertEquals("keep",pretender.readText())
            val root = File(cache,"native-proof")
            Files.createSymbolicLink(root.toPath(),other.toPath())
            assertFalse(cleanupProofSession(cache,File(root,"keep.txt")))
            assertFalse(deleteProofImage(cache,File(root,"session/keep.txt")))
            assertEquals("keep",sentinel.readText())
            assertTrue(cleanupProofCache(cache)); assertEquals("keep",sentinel.readText())
        } finally { cleanupProofCache(cache); cache.deleteRecursively(); other.deleteRecursively() }
    }

}
