package ph.edu.bsit.tcc.ojtdtr.proof

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.ensureActive

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

    private class Channel : java.nio.channels.SeekableByteChannel {
        var bytes: ByteArray? = null
        var closed = false
        var length = 3L
        var readAction: (java.nio.ByteBuffer) -> Int = { it.put(byteArrayOf(1, 2, 3)); 3 }
        var closeFailure = false
        override fun read(target: java.nio.ByteBuffer): Int { bytes = target.array(); return readAction(target) }
        override fun write(source: java.nio.ByteBuffer): Int = error("read only")
        override fun position() = 0L
        override fun position(value: Long): java.nio.channels.SeekableByteChannel = this
        override fun size() = length
        override fun truncate(size: Long): java.nio.channels.SeekableByteChannel = error("read only")
        override fun isOpen() = !closed
        override fun close() { closed = true; if (closeFailure) throw IOException("synthetic close") }
    }
    @Test fun failedPartialReadWipesAllocatedBuffer() {
        val channel = Channel().apply { readAction = { it.put(1.toByte()); throw IOException("synthetic read") } }
        try { readSubmissionBytes({ channel }); fail() } catch (_: IOException) { }
        assertTrue(channel.closed); assertTrue(channel.bytes!!.all { it == 0.toByte() })
    }
    @Test fun failedCloseWipesFullyReadBuffer() {
        val channel = Channel().apply { closeFailure = true }
        try { readSubmissionBytes({ channel }); fail() } catch (_: IOException) { }
        assertTrue(channel.closed); assertTrue(channel.bytes!!.all { it == 0.toByte() })
    }
    @Test fun failedReadAndCloseWipeAndPreserveReadException() {
        val channel = Channel().apply {
            closeFailure = true
            readAction = { it.put(1.toByte()); throw IOException("synthetic read") }
        }
        try { readSubmissionBytes({ channel }); fail() } catch (error: IOException) {
            assertEquals("synthetic read", error.message); assertEquals(1, error.suppressed.size)
        }
        assertTrue(channel.bytes!!.all { it == 0.toByte() })
    }
    @Test fun cancellationAfterReadBeforeHandoffWipesBuffer() {
        val job = kotlinx.coroutines.Job()
        val channel = Channel().apply { readAction = { it.put(byteArrayOf(1, 2, 3)); job.cancel(); 3 } }
        try { readSubmissionBytes({ channel }, { job.ensureActive() }); fail() }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(channel.closed); assertTrue(channel.bytes!!.all { it == 0.toByte() })
    }
    @Test fun cancellationDuringPartialReadWipesBuffer() {
        val channel = Channel().apply { readAction = { it.put(1.toByte()); throw kotlinx.coroutines.CancellationException() } }
        try { readSubmissionBytes({ channel }); fail() } catch (_: kotlinx.coroutines.CancellationException) { }
        assertTrue(channel.closed); assertTrue(channel.bytes!!.all { it == 0.toByte() })
    }
    @Test fun successfulReadHandsOffSameBufferOnlyAfterClose() {
        val channel = Channel()
        val result = readSubmissionBytes({ channel })
        assertTrue(channel.closed); assertSame(channel.bytes, result); assertArrayEquals(byteArrayOf(1, 2, 3), result)
        result.fill(0)
    }
    @Test fun eofAndOversizeFailClosed() {
        val eof = Channel().apply { readAction = { it.put(1.toByte()); -1 } }
        try { readSubmissionBytes({ eof }); fail() } catch (_: IOException) { }
        assertTrue(eof.bytes!!.all { it == 0.toByte() }); assertTrue(eof.closed)
        val oversized = Channel().apply { length = 5242881 }
        try { readSubmissionBytes({ oversized }); fail() } catch (_: IOException) { }
        assertNull(oversized.bytes); assertTrue(oversized.closed)
    }

}
