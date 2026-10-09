package ph.edu.bsit.tcc.ojtdtr.proof

import java.io.File
import java.io.IOException

/** Expected file/decoder failures only; programming errors must remain visible. */
internal fun <T> readProofImage(file: File, decode: (File) -> T?): T? = try {
    if (!file.isFile || !file.canRead() || file.length() == 0L) null else decode(file)
} catch (_: IOException) { null }
catch (_: SecurityException) { null }
catch (_: IllegalArgumentException) { null } // Invalid decoder/EXIF input.

/** Trusted cache comes from Context.cacheDir; only exact native-proof descendants are owned. */
private fun ownedPath(cache: File, target: File, session: Boolean): java.nio.file.Path? {
    try {
        val base = cache.toPath().toAbsolutePath().normalize()
        val raw = target.toPath().toAbsolutePath()
        if (raw.any { it.toString() == ".." }) return null
        val path = raw.normalize()
        val root = base.resolve("native-proof")
        if (session) {
            if (path.parent != root || path.fileName.toString().isBlank()) return null
            if (java.nio.file.Files.isSymbolicLink(root)) return null
        } else if (path != root) return null
        return path
    } catch (_: SecurityException) { return null }
    catch (_: java.nio.file.InvalidPathException) { return null }
}

private fun attributes(stream: java.nio.file.SecureDirectoryStream<java.nio.file.Path>, name: java.nio.file.Path) =
    stream.getFileAttributeView(name, java.nio.file.attribute.BasicFileAttributeView::class.java,
        java.nio.file.LinkOption.NOFOLLOW_LINKS).readAttributes()

/** Relative to an open descriptor; NOFOLLOW rejects a directory swapped for a symlink. */
private fun removeEntry(stream: java.nio.file.SecureDirectoryStream<java.nio.file.Path>, name: java.nio.file.Path) {
    try {
        if (attributes(stream, name).isDirectory) {
            stream.newDirectoryStream(name, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { child ->
                for (entry in child) removeEntry(child, entry.fileName)
            }
            stream.deleteDirectory(name)
        } else stream.deleteFile(name)
    } catch (_: java.nio.file.NoSuchFileException) { /* Missing is already clean. */ }
}

/** Android providers without secure directory streams: NOFOLLOW tree visitor, no FOLLOW_LINKS. */
private fun removeTree(path: java.nio.file.Path) {
    java.nio.file.Files.walkFileTree(path, object : java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
        override fun preVisitDirectory(dir: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            if (java.nio.file.Files.isSymbolicLink(dir)) throw IOException()
            return java.nio.file.FileVisitResult.CONTINUE
        }
        override fun visitFile(file: java.nio.file.Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            java.nio.file.Files.deleteIfExists(file)
            return java.nio.file.FileVisitResult.CONTINUE
        }
        override fun visitFileFailed(file: java.nio.file.Path, error: IOException): java.nio.file.FileVisitResult {
            if (error is java.nio.file.NoSuchFileException) return java.nio.file.FileVisitResult.CONTINUE
            throw error
        }
        override fun postVisitDirectory(dir: java.nio.file.Path, error: IOException?): java.nio.file.FileVisitResult {
            if (error != null) throw error
            java.nio.file.Files.deleteIfExists(dir)
            return java.nio.file.FileVisitResult.CONTINUE
        }
    })
}

private fun cleanupOwned(cache: File, target: File, session: Boolean): Boolean {
    val path = ownedPath(cache, target, session) ?: return false
    return try {
        java.nio.file.Files.newDirectoryStream(cache.toPath()).use { stream ->
            if (stream is java.nio.file.SecureDirectoryStream<java.nio.file.Path>) {
                if (session) stream.newDirectoryStream(java.nio.file.Paths.get("native-proof"),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS).use { root -> removeEntry(root, path.fileName) }
                else removeEntry(stream, path.fileName)
            } else removeTree(path)
        }
        true
    } catch (_: java.nio.file.NoSuchFileException) { true }
    catch (_: IOException) { false }
    catch (_: SecurityException) { false }
}

internal fun cleanupProofCache(cache: File): Boolean = cleanupOwned(cache, File(cache, "native-proof"), false)
internal fun cleanupProofSession(cache: File, directory: File): Boolean = cleanupOwned(cache, directory, true)

internal fun proofDirectoryOwned(cache: File, directory: File): Boolean = try {
    ownedPath(cache, directory, true)?.let { !java.nio.file.Files.isSymbolicLink(it) } == true
} catch (_: SecurityException) { false }

internal fun deleteProofImage(cache: File, file: File): Boolean {
    val directory = file.parentFile ?: return false
    if (!proofDirectoryOwned(cache, directory)) return false
    return try {
        val raw = file.toPath().toAbsolutePath()
        if (raw.any { it.toString() == ".." } || raw.normalize().parent != directory.toPath().toAbsolutePath().normalize()) return false
        java.nio.file.Files.newDirectoryStream(cache.toPath()).use { stream ->
            if (stream is java.nio.file.SecureDirectoryStream<java.nio.file.Path>) {
                stream.newDirectoryStream(java.nio.file.Paths.get("native-proof"), java.nio.file.LinkOption.NOFOLLOW_LINKS).use { root ->
                    root.newDirectoryStream(directory.toPath().fileName, java.nio.file.LinkOption.NOFOLLOW_LINKS).use { child ->
                        if (attributes(child, raw.fileName).isDirectory) return false
                        child.deleteFile(raw.fileName)
                    }
                }
            } else {
                if (java.nio.file.Files.isDirectory(raw, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false
                java.nio.file.Files.deleteIfExists(raw)
            }
        }
        true
    } catch (_: java.nio.file.NoSuchFileException) { true }
    catch (_: IOException) { false }
    catch (_: SecurityException) { false }
    catch (_: java.nio.file.InvalidPathException) { false }
}
