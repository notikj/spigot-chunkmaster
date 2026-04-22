package net.trivernis.chunkmaster.lib.generation

import io.papermc.lib.PaperLib
import net.trivernis.chunkmaster.Chunkmaster
import net.trivernis.chunkmaster.lib.shapes.Shape
import org.bukkit.World
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

class DefaultGenerationTask(
    private val plugin: Chunkmaster,
    world: World,
    startChunk: ChunkCoordinates,
    override val radius: Int = -1,
    shape: Shape,
    missingChunks: HashSet<ChunkCoordinates>,
    state: TaskState
) : GenerationTask(plugin, world, startChunk, shape, missingChunks, state) {

    private val maxPendingChunks = plugin.config.getInt("generation.max-pending-chunks")
    private val working = Semaphore(maxPendingChunks)
    private val pendingChunksMap: MutableMap<ChunkCoordinates, PendingChunkEntry> = ConcurrentHashMap()

    /**
     * Read-only view of in-flight chunk requests. Exposed for persistence and diagnostics.
     */
    val pendingChunks: Collection<PendingChunkEntry>
        get() = pendingChunksMap.values

    override var count = 0
    override var endReached: Boolean = false

    init {
        updateGenerationAreaMarker()
        count = shape.count
    }

    /**
     * Runs the generation task. Every Iteration the next chunks will be generated if
     * they haven't been generated already
     * After a configured number of chunks chunks have been generated, they will all be unloaded and saved.
     */
    override fun generate() {
        generateMissing()
        seekGenerated()
        generateUntilBorder()
    }

    /**
     * Validates that all chunks have been generated or generates missing ones.
     * The shape walk stays sequential (it's stateful), but the per-chunk
     * isChunkGenerated check (region-file I/O) is fanned out to a small worker pool.
     */
    override fun validate() {
        this.shape.reset()
        val missedChunks = HashSet<ChunkCoordinates>()
        val threadCount = plugin.config.getInt("generation.validation-threads").coerceIn(1, 16)
        val executor = Executors.newFixedThreadPool(threadCount) { r ->
            Thread(r, "Chunkmaster-Validate-${world.name}").apply { isDaemon = true }
        }
        val batchSize = 256
        try {
            while (!cancelRun && !borderReached()) {
                val batch = ArrayList<ChunkCoordinates>(batchSize)
                while (batch.size < batchSize && !borderReached()) {
                    batch.add(nextChunkCoordinates)
                }
                if (batch.isEmpty()) break

                for (c in batch) triggerDynmapRender(c)

                val futures = batch.map { coords ->
                    executor.submit<ChunkCoordinates?> {
                        if (!PaperLib.isChunkGenerated(world, coords.x, coords.z)) coords else null
                    }
                }
                for (f in futures) {
                    if (cancelRun) break
                    try {
                        val res = f.get()
                        if (res != null) missedChunks.add(res)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
        } finally {
            executor.shutdownNow()
        }
        synchronized(this.missingChunks) {
            this.missingChunks.addAll(missedChunks)
        }
    }

    /**
     * Generates chunks that are missing
     */
    override fun generateMissing() {
        val missing = synchronized(this.missingChunks) { this.missingChunks.toHashSet() }
        this.count = 0

        while (missing.size > 0 && !cancelRun) {
            if (plugin.mspt < msptThreshold) {
                val chunk = missing.first()
                missing.remove(chunk)
                this.requestGeneration(chunk)
                this.count++
            } else {
                Thread.sleep(50L)
            }
        }
        if (!cancelRun) {
            this.joinPending()
        }
    }

    /**
     * Seeks until it encounters a chunk that hasn't been generated yet
     */
    private fun seekGenerated() {
        do {
            lastChunkCoords = nextChunkCoordinates
            count = shape.count
        } while (PaperLib.isChunkGenerated(world, lastChunkCoords.x, lastChunkCoords.z) && !borderReached())
    }

    /**
     * Generates the world until it encounters the worlds border
     */
    private fun generateUntilBorder() {
        var chunkCoordinates: ChunkCoordinates

        while (!cancelRun && !borderReached()) {
            if (plugin.mspt < msptThreshold) {
                chunkCoordinates = nextChunkCoordinates
                requestGeneration(chunkCoordinates)

                lastChunkCoords = chunkCoordinates
                count = shape.count
            } else {
                Thread.sleep(50L)
            }
        }
        if (!cancelRun) {
            joinPending()
        }
    }

    /**
     * Waits until every in-flight chunk request has completed.
     * Acquires all permits, then releases them — event-driven, no spin-sleep.
     */
    private fun joinPending() {
        try {
            working.acquire(maxPendingChunks)
            working.release(maxPendingChunks)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * Request the generation of a chunk.
     * Blocks the worker thread on the semaphore until an in-flight slot is free,
     * which is Paper-friendly backpressure (no busy-loop, no offer timeouts, no missingChunks churn).
     */
    private fun requestGeneration(chunkCoordinates: ChunkCoordinates) {
        if (PaperLib.isChunkGenerated(world, chunkCoordinates.x, chunkCoordinates.z) && !PaperLib.isSpigot()) {
            return
        }

        // Wait for an in-flight slot. Re-check cancelRun periodically so cancel() unblocks us.
        while (!cancelRun) {
            try {
                if (working.tryAcquire(1, TimeUnit.SECONDS)) break
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
        if (cancelRun) return

        val pendingChunkEntry = PendingChunkEntry(
            chunkCoordinates,
            PaperLib.getChunkAtAsync(world, chunkCoordinates.x, chunkCoordinates.z, true)
        )
        pendingChunksMap[chunkCoordinates] = pendingChunkEntry

        // Safety net — if Paper's future never completes, cancel it after 60s so whenComplete fires
        val timeoutTask = try {
            plugin.server.scheduler.runTaskLaterAsynchronously(plugin, Runnable {
                if (!pendingChunkEntry.chunk.isDone) {
                    plugin.logger.warning("Chunk future timed out after 60s at ${chunkCoordinates.x},${chunkCoordinates.z}; cancelling")
                    pendingChunkEntry.chunk.cancel(false)
                }
            }, 60L * 20L)
        } catch (e: IllegalStateException) {
            null
        }

        pendingChunkEntry.chunk.whenComplete { _, err ->
            try {
                if (timeoutTask != null) {
                    try { timeoutTask.cancel() } catch (_: Exception) {}
                }
                if (err != null && err !is CancellationException) {
                    plugin.logger.warning("Chunk future failed at ${chunkCoordinates.x},${chunkCoordinates.z}: $err")
                }
            } finally {
                pendingChunksMap.remove(chunkCoordinates)
                working.release()
            }
        }
    }

    /**
     * Cancels the generation task.
     * Cancelling each in-flight future causes its whenComplete to fire, which releases
     * the semaphore permit and removes the entry from pendingChunksMap. That naturally
     * unblocks both requestGeneration and joinPending.
     */
    override fun cancel() {
        this.cancelRun = true
        val snapshot = ArrayList(pendingChunksMap.values)
        snapshot.forEach { it.chunk.cancel(false) }
        updateGenerationAreaMarker(true)
    }
}
