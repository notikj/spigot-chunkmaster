package net.trivernis.chunkmaster.lib.batch

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Performs the actual archive step. Lives in BatchArchiveHook (companion-style helper) so it can be
 * called both from the shutdown hook (no Bukkit available) and tested in isolation.
 *
 * Calls system `tar --use-compress-program=zstd` — host must have both binaries available.
 */
object BatchArchiveHook {

    /**
     * Creates the archive at `archivePath` containing each world folder under `worldContainer`.
     * Uses an atomic .tmp -> rename strategy so a crash mid-write never leaves a corrupted final file.
     *
     * Returns true on success, false on any failure. Diagnostics go through the supplied `log` lambda
     * (which the caller routes to a file, not the Bukkit logger — that one is dead during shutdown hooks).
     */
    fun archive(log: (String) -> Unit, archivePath: String, worlds: List<String>, worldContainer: File): Boolean {
        val finalFile = File(archivePath)
        val tmpFile = File("${archivePath}.tmp")
        finalFile.parentFile?.mkdirs()
        if (tmpFile.exists()) tmpFile.delete()

        val cmd = mutableListOf(
            "tar",
            "--use-compress-program=zstd",
            "-cf", tmpFile.absolutePath
        )
        cmd.addAll(worlds)

        log("running: ${cmd.joinToString(" ")} (cwd=${worldContainer.absolutePath})")
        val pb = ProcessBuilder(cmd)
            .directory(worldContainer)
            .redirectErrorStream(true)
        return try {
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit != 0) {
                log("tar exit=$exit, output:\n$output")
                if (tmpFile.exists()) tmpFile.delete()
                return false
            }
            Files.move(tmpFile.toPath(), finalFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            log("archive created: ${finalFile.absolutePath} (${finalFile.length()} bytes)")
            true
        } catch (e: Exception) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            log("archive exception: ${e.javaClass.name}: ${e.message}\n$sw")
            if (tmpFile.exists()) tmpFile.delete()
            false
        }
    }
}
