package net.trivernis.chunkmaster.lib.batch

import net.trivernis.chunkmaster.Chunkmaster
import org.bukkit.Bukkit
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CompletableFuture

class BatchManager(private val plugin: Chunkmaster) {

    private val batchJobsTable = plugin.sqliteManager.batchJobs
    private val activeBatches = mutableListOf<BatchJob>()

    // Captured at init() to avoid touching Bukkit from the JVM shutdown hook (where plugin.server may NPE).
    private lateinit var capturedDataFolder: File
    private lateinit var capturedWorldContainer: File
    private lateinit var capturedDbPath: String

    val markerFile: File
        get() = File(plugin.dataFolder, "pending_archive.json")

    val archiveRoot: File
        get() = File(plugin.dataFolder, "archives")

    private val shutdownDiagFile: File
        get() = File(capturedDataFolder, "shutdown_hook.log")

    fun init() {
        archiveRoot.mkdirs()
        capturedDataFolder = plugin.dataFolder
        capturedWorldContainer = plugin.server.worldContainer
        capturedDbPath = "${plugin.dataFolder.absolutePath}/${plugin.config.getString("database.filename")}"
        preloadAllPluginClasses()
        batchJobsTable.getAllBatchJobs().thenAccept { jobs ->
            activeBatches.addAll(jobs)
            plugin.logger.info("BatchManager: loaded ${jobs.size} batch job(s) from database")
        }
    }

    /**
     * Force-loads every class in the plugin JAR. Bukkit closes the plugin JAR
     * before JVM shutdown hooks run, so any class not already in memory throws
     * "zip file closed" when the hook tries to use it. This includes anonymous
     * lambda classes inside our own methods (e.g. the lambda passed to .map { }
     * inside parseMarker compiles to BatchManager$parseMarker$$inlined$...class).
     *
     * Enumerating JAR entries and Class.forName-ing each is the simplest robust
     * fix — covers all current and future lambdas/method-references/extensions
     * without us having to keep an exhaustive list.
     */
    private fun preloadAllPluginClasses() {
        val codeSource = plugin::class.java.protectionDomain?.codeSource
        val jarUrl = codeSource?.location
        if (jarUrl == null) {
            plugin.logger.warning("BatchManager: cannot determine plugin JAR location, shutdown hook may fail")
            return
        }
        val jarPath = try {
            File(jarUrl.toURI())
        } catch (e: Exception) {
            plugin.logger.warning("BatchManager: bad plugin JAR URL '$jarUrl': ${e.message}")
            return
        }
        if (!jarPath.exists() || !jarPath.isFile) {
            // Running from build dir (e.g. tests) — no JAR to preload.
            return
        }
        val loader = plugin::class.java.classLoader
        var loaded = 0
        var failed = 0
        try {
            java.util.jar.JarFile(jarPath).use { jf ->
                val entries = jf.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name
                    if (!name.endsWith(".class")) continue
                    if (name == "module-info.class") continue
                    if (name.startsWith("META-INF/")) continue
                    val className = name.substring(0, name.length - 6).replace('/', '.')
                    try {
                        // initialize=false: load bytecode only, skip static init.
                        // Static init still fires later on first use, but only touches
                        // already-loaded classes by then, so the JAR can be closed.
                        Class.forName(className, false, loader)
                        loaded++
                    } catch (_: Throwable) {
                        failed++
                    }
                }
            }
            plugin.logger.info("BatchManager: preloaded $loaded plugin classes (failed: $failed) for shutdown-hook resilience")
        } catch (e: Exception) {
            plugin.logger.warning("BatchManager: preload failed: ${e.message}")
        }
    }

    /**
     * Resumes batch jobs after a server restart. Should be called once after worlds are loaded.
     */
    fun resume() {
        for (job in activeBatches.toList()) {
            when (job.state) {
                BatchState.ARCHIVE_PENDING -> {
                    plugin.logger.warning(
                        "Batch ${job.id}: was in ARCHIVE_PENDING on startup (likely interrupted shutdown). Marking FAILED."
                    )
                    markFailed(job)
                }
                BatchState.GENERATING, BatchState.PENDING -> {
                    plugin.logger.info("Batch ${job.id}: resuming iteration ${job.currentIteration}/${job.totalIterations}")
                    startIteration(job)
                }
                BatchState.COMPLETED, BatchState.FAILED -> {
                    // terminal, leave alone
                }
            }
        }
        // If a marker file is left on disk but no matching ARCHIVE_PENDING batch exists, drop it.
        if (markerFile.exists() && activeBatches.none { it.state == BatchState.ARCHIVE_PENDING }) {
            plugin.logger.warning("Stray pending_archive.json found with no matching batch — deleting")
            markerFile.delete()
        }
    }

    /**
     * Returns true if a batch is currently active (PENDING/GENERATING/ARCHIVE_PENDING).
     */
    fun hasActiveBatch(): Boolean = activeBatches.any {
        it.state == BatchState.PENDING || it.state == BatchState.GENERATING || it.state == BatchState.ARCHIVE_PENDING
    }

    /**
     * Creates a new batch and starts iteration 1. Validates that all worlds exist on the server.
     * Returns failure (completed exceptionally) if validation fails or another batch is active.
     */
    fun startBatch(totalIterations: Int, worldDefs: List<BatchWorld>): CompletableFuture<Int> {
        val future = CompletableFuture<Int>()
        if (hasActiveBatch()) {
            future.completeExceptionally(IllegalStateException("Another batch is already active"))
            return future
        }
        if (totalIterations < 1) {
            future.completeExceptionally(IllegalArgumentException("Iterations must be >= 1"))
            return future
        }
        if (worldDefs.isEmpty()) {
            future.completeExceptionally(IllegalArgumentException("Worlds list must not be empty"))
            return future
        }
        for (w in worldDefs) {
            if (plugin.server.getWorld(w.worldName) == null) {
                future.completeExceptionally(IllegalArgumentException("World not found: ${w.worldName}"))
                return future
            }
        }
        batchJobsTable.addBatchJob(totalIterations, archiveRoot.absolutePath, worldDefs).thenAccept { id ->
            val job = BatchJob(
                id = id,
                totalIterations = totalIterations,
                currentIteration = 1,
                state = BatchState.GENERATING,
                archiveDir = archiveRoot.absolutePath,
                createdAt = System.currentTimeMillis(),
                worlds = worldDefs
            )
            activeBatches.add(job)
            batchJobsTable.updateState(id, BatchState.GENERATING)
            plugin.logger.info("Batch $id: started, $totalIterations iteration(s), worlds=${worldDefs.map { it.worldName }}")
            // Hop back to main thread before doing Bukkit work
            plugin.server.scheduler.runTask(plugin, Runnable { startIteration(job) })
            future.complete(id)
        }
        return future
    }

    /**
     * Cancels an active batch and removes its DB record. Does NOT clean up archives or world folders.
     */
    fun cancelBatch(batchId: Int): Boolean {
        val job = activeBatches.find { it.id == batchId } ?: return false
        plugin.logger.info("Batch $batchId: cancelling")
        for (w in job.worlds) {
            val task = plugin.generationManager.allTasks.find { it.generationTask.world.name == w.worldName }
            if (task != null) plugin.generationManager.removeTask(task.id)
        }
        markFailed(job)
        if (markerFile.exists()) markerFile.delete()
        return true
    }

    fun listBatches(): List<BatchJob> = activeBatches.toList()

    private fun markFailed(job: BatchJob) {
        job.state = BatchState.FAILED
        batchJobsTable.updateState(job.id, BatchState.FAILED)
    }

    private fun startIteration(job: BatchJob) {
        plugin.logger.info("Batch ${job.id}: starting iteration ${job.currentIteration}/${job.totalIterations}")
        val queue = job.worlds.toMutableList()
        generateNextWorld(job, queue)
    }

    private fun generateNextWorld(job: BatchJob, queue: MutableList<BatchWorld>) {
        if (queue.isEmpty()) {
            finishIteration(job)
            return
        }
        val w = queue.removeAt(0)
        val world = plugin.server.getWorld(w.worldName)
        if (world == null) {
            plugin.logger.severe("Batch ${job.id}: world '${w.worldName}' not loaded, marking FAILED")
            markFailed(job)
            return
        }
        // Skip if a task for this world is already running (resume case).
        val existing = plugin.generationManager.allTasks.find { it.generationTask.world == world }
        val taskId = existing?.id ?: plugin.generationManager.addTask(
            world,
            if (w.radius > 0) w.radius / 16 else -1,
            w.shape
        )
        val taskEntry = plugin.generationManager.tasks.find { it.id == taskId }
            ?: plugin.generationManager.pausedTasks.find { it.id == taskId }
        if (taskEntry == null) {
            plugin.logger.severe("Batch ${job.id}: failed to attach to task $taskId for world '${w.worldName}'")
            markFailed(job)
            return
        }
        plugin.logger.info("Batch ${job.id}: generating world '${w.worldName}' (task #$taskId)")
        taskEntry.generationTask.onEndReached {
            plugin.server.scheduler.runTaskLater(plugin, Runnable {
                generateNextWorld(job, queue)
            }, 20)
        }
    }

    private fun finishIteration(job: BatchJob) {
        val iterationDir = File(archiveRoot, "batch_${job.id}")
        iterationDir.mkdirs()
        val archivePath = File(iterationDir, "iter_${job.currentIteration}.tar.zst").absolutePath
        val worldNames = job.worlds.map { it.worldName }

        writeMarkerFile(job, archivePath, worldNames)

        job.state = BatchState.ARCHIVE_PENDING
        batchJobsTable.updateState(job.id, BatchState.ARCHIVE_PENDING)
        plugin.logger.info(
            "Batch ${job.id}: iteration ${job.currentIteration}/${job.totalIterations} finished. " +
                    "Marker written to ${markerFile.absolutePath}. Triggering shutdown."
        )
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            Bukkit.shutdown()
        }, 60L)
    }

    /**
     * Writes a diagnostic line to plugins/Chunkmaster/shutdown_hook.log AND to System.out.
     * The Bukkit logger is unreliable inside JVM shutdown hooks (Log4j may have already shut down),
     * so we bypass it entirely and write to a file we control.
     */
    private fun diagLog(msg: String) {
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(Date())
        val line = "[$ts] $msg"
        try {
            FileWriter(shutdownDiagFile, true).use { it.write("$line\n") }
        } catch (_: Exception) { /* best-effort */ }
        try {
            System.out.println("[Chunkmaster shutdown hook] $msg")
        } catch (_: Exception) { /* stdout may be closed */ }
    }

    /**
     * Called by the JVM shutdown hook AFTER Bukkit has fully shut down.
     * If the marker file exists and the iteration successfully archived, advances the batch:
     *   - increments current_iteration
     *   - if last iteration: marks COMPLETED
     *   - else: marks GENERATING (so the next startup resumes the next iteration)
     *
     * NOTE: This runs OUTSIDE Bukkit. Must NOT touch any Bukkit APIs (plugin.server is unreliable here).
     * Uses captured fields (capturedWorldContainer, capturedDbPath) and writes diagnostics to a file.
     */
    fun finalizeArchiveOnShutdown() {
        diagLog("hook entered")
        val marker = markerFile
        if (!marker.exists()) {
            diagLog("no marker file at ${marker.absolutePath} — nothing to do")
            return
        }
        diagLog("marker file present at ${marker.absolutePath}")
        try {
            val parsed = parseMarker(marker.readText())
            val batchId = parsed["batchId"]!!.toInt()
            val iteration = parsed["iteration"]!!.toInt()
            val totalIterations = parsed["totalIterations"]!!.toInt()
            val archivePath = parsed["archivePath"]!!
            val worlds = parsed["worlds"]!!.split("|").filter { it.isNotEmpty() }

            diagLog("archiving batch=$batchId iter=$iteration/$totalIterations worlds=$worlds archivePath=$archivePath")
            diagLog("worldContainer=${capturedWorldContainer.absolutePath}")
            val ok = BatchArchiveHook.archive(::diagLog, archivePath, worlds, capturedWorldContainer)
            if (!ok) {
                diagLog("ARCHIVE FAILED for batch=$batchId iter=$iteration — marking FAILED in DB")
                directDbUpdateState(batchId, BatchState.FAILED)
                marker.delete()
                return
            }
            diagLog("archive ok — deleting world folders")
            for (worldName in worlds) {
                val worldDir = File(capturedWorldContainer, worldName)
                if (worldDir.exists()) {
                    val deleted = worldDir.deleteRecursively()
                    diagLog("delete $worldDir -> $deleted")
                    if (!deleted) {
                        diagLog("WARNING: failed to fully delete $worldDir")
                    }
                } else {
                    diagLog("world dir $worldDir does not exist, skipping")
                }
            }
            val nextIter = iteration + 1
            if (nextIter > totalIterations) {
                directDbUpdateState(batchId, BatchState.COMPLETED)
                diagLog("batch $batchId fully completed")
            } else {
                directDbUpdateIteration(batchId, nextIter)
                directDbUpdateState(batchId, BatchState.GENERATING)
                diagLog("batch $batchId advanced to iter $nextIter; restart will resume")
            }
            marker.delete()
            diagLog("marker deleted, hook done")
        } catch (e: Exception) {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            diagLog("EXCEPTION in shutdown hook: ${e.javaClass.name}: ${e.message}\n$sw")
        }
    }

    private fun writeMarkerFile(job: BatchJob, archivePath: String, worlds: List<String>) {
        val esc = { s: String -> s.replace("\\", "\\\\").replace("\"", "\\\"") }
        val ws = worlds.joinToString(",") { "\"${esc(it)}\"" }
        val text = """{"batchId":${job.id},"iteration":${job.currentIteration},"totalIterations":${job.totalIterations},"archivePath":"${esc(archivePath)}","worlds":[$ws]}"""
        markerFile.writeText(text)
    }

    /**
     * Minimal hand-rolled JSON parser tuned to the marker format we write — we control both ends,
     * so a real JSON lib would be over-engineered. Returns the world list as a single pipe-joined
     * string in the "worlds" key (avoids returning a heterogeneous Map<String, Any>).
     */
    private fun parseMarker(json: String): Map<String, String> {
        val out = HashMap<String, String>()
        // numeric fields
        Regex("\"batchId\":(\\d+)").find(json)?.let { out["batchId"] = it.groupValues[1] }
        Regex("\"iteration\":(\\d+)").find(json)?.let { out["iteration"] = it.groupValues[1] }
        Regex("\"totalIterations\":(\\d+)").find(json)?.let { out["totalIterations"] = it.groupValues[1] }
        // string field
        Regex("\"archivePath\":\"((?:\\\\.|[^\"\\\\])*)\"").find(json)?.let {
            out["archivePath"] = it.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"")
        }
        // worlds array
        Regex("\"worlds\":\\[([^]]*)]").find(json)?.let { match ->
            val arr = match.groupValues[1]
            val items = Regex("\"((?:\\\\.|[^\"\\\\])*)\"").findAll(arr)
                .map { it.groupValues[1].replace("\\\\", "\\").replace("\\\"", "\"") }
                .toList()
            out["worlds"] = items.joinToString("|")
        }
        return out
    }

    /**
     * Synchronous DB update used from the shutdown hook (Bukkit async machinery is gone by then).
     * Uses captured DB path so we don't touch plugin.config (which might NPE in the hook).
     */
    private fun directDbUpdateState(batchId: Int, state: BatchState) {
        java.sql.DriverManager.getConnection("jdbc:sqlite:$capturedDbPath").use { conn ->
            conn.prepareStatement("UPDATE batch_jobs SET state = ? WHERE id = ?").use { ps ->
                ps.setString(1, state.name)
                ps.setInt(2, batchId)
                ps.executeUpdate()
            }
        }
    }

    private fun directDbUpdateIteration(batchId: Int, iteration: Int) {
        java.sql.DriverManager.getConnection("jdbc:sqlite:$capturedDbPath").use { conn ->
            conn.prepareStatement("UPDATE batch_jobs SET current_iteration = ? WHERE id = ?").use { ps ->
                ps.setInt(1, iteration)
                ps.setInt(2, batchId)
                ps.executeUpdate()
            }
        }
    }
}
