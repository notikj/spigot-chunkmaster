package net.trivernis.chunkmaster.lib.generation

import io.papermc.lib.PaperLib
import net.trivernis.chunkmaster.Chunkmaster
import net.trivernis.chunkmaster.lib.shapes.Shape
import org.bukkit.World
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class DefaultGenerationTask(
    private val plugin: Chunkmaster,
    unloader: ChunkUnloader,
    world: World,
    startChunk: ChunkCoordinates,
    override val radius: Int = -1,
    shape: Shape,
    missingChunks: HashSet<ChunkCoordinates>,
    state: TaskState
) : GenerationTask(plugin, world, unloader, startChunk, shape, missingChunks, state) {

    private val maxPendingChunks = plugin.config.getInt("generation.max-pending-chunks")
    val pendingChunks = ArrayBlockingQueue<PendingChunkEntry>(maxPendingChunks)

    override var count = 0
    override var endReached: Boolean = false

    private val diagCompletedCount = java.util.concurrent.atomic.AtomicLong(0) // DIAG: remove after bug fix
    private val diagPutCount = java.util.concurrent.atomic.AtomicLong(0) // DIAG: remove after bug fix

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
     * Validates that all chunks have been generated or generates missing ones
     */
    override fun validate() {
        this.shape.reset()
        val missedChunks = HashSet<ChunkCoordinates>()

        while (!cancelRun && !borderReached()) {
            val chunkCoordinates = nextChunkCoordinates
            triggerDynmapRender(chunkCoordinates)
            if (!PaperLib.isChunkGenerated(world, chunkCoordinates.x, chunkCoordinates.z)) {
                missedChunks.add(chunkCoordinates)
            }
        }
        this.missingChunks.addAll(missedChunks)
    }

    /**
     * Generates chunks that are missing
     */
    override fun generateMissing() {
        val missing = this.missingChunks.toHashSet()
        this.count = 0
        var diagIter = 0 // DIAG: remove after bug fix

        while (missing.size > 0 && !cancelRun) {
            if (plugin.mspt < msptThreshold && !unloader.isFull) {
                val chunk = missing.first()
                missing.remove(chunk)
                this.requestGeneration(chunk)
                this.count++
            } else {
                Thread.sleep(50L)
            }
            // DIAG: remove after bug fix
            if (diagIter++ % 200 == 0) {
                plugin.logger.info("[DIAG] generateMissing iter=$diagIter missing=${missing.size} mspt=${plugin.mspt} unloaderFull=${unloader.isFull} unloaderPending=${unloader.pendingSize} pendingChunks=${pendingChunks.size}/$maxPendingChunks")
            }
        }
        if (!cancelRun) {
            plugin.logger.info("[DIAG] generateMissing exited, joinPending starting, pendingChunks=${pendingChunks.size}") // DIAG: remove after bug fix
            this.joinPending()
            plugin.logger.info("[DIAG] generateMissing joinPending done") // DIAG: remove after bug fix
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
        var diagIter = 0 // DIAG: remove after bug fix

        while (!cancelRun && !borderReached()) {
            if (plugin.mspt < msptThreshold && !unloader.isFull) {
                chunkCoordinates = nextChunkCoordinates
                requestGeneration(chunkCoordinates)

                lastChunkCoords = chunkCoordinates
                count = shape.count
            } else {
                Thread.sleep(50L)
            }
            // DIAG: remove after bug fix
            if (diagIter++ % 200 == 0) {
                plugin.logger.info("[DIAG] generateUntilBorder iter=$diagIter mspt=${plugin.mspt} unloaderFull=${unloader.isFull} unloaderPending=${unloader.pendingSize} pendingChunks=${pendingChunks.size}/$maxPendingChunks putCount=${diagPutCount.get()} completedCount=${diagCompletedCount.get()}")
            }
        }
        if (!cancelRun) {
            plugin.logger.info("[DIAG] generateUntilBorder exited, joinPending starting, pendingChunks=${pendingChunks.size}") // DIAG: remove after bug fix
            joinPending()
            plugin.logger.info("[DIAG] generateUntilBorder joinPending done") // DIAG: remove after bug fix
        }
    }

    private fun joinPending() {
        var diagJoinIter = 0 // DIAG: remove after bug fix
        while (!this.pendingChunks.isEmpty()) {
            // DIAG: remove after bug fix
            if (diagJoinIter++ % 20 == 0) {
                plugin.logger.info("[DIAG] joinPending waiting iter=$diagJoinIter pendingChunks=${pendingChunks.size} putCount=${diagPutCount.get()} completedCount=${diagCompletedCount.get()}")
            }
            Thread.sleep(msptThreshold)
        }
    }

    /**
     * Request the generation of a chunk
     */
    private fun requestGeneration(chunkCoordinates: ChunkCoordinates) {
        if (!PaperLib.isChunkGenerated(world, chunkCoordinates.x, chunkCoordinates.z) || PaperLib.isSpigot()) {
            val pendingChunkEntry = PendingChunkEntry(
                chunkCoordinates,
                PaperLib.getChunkAtAsync(world, chunkCoordinates.x, chunkCoordinates.z, true)
            )
            // DIAG: remove after bug fix
            val diagSizeBefore = pendingChunks.size
            val diagNearFull = diagSizeBefore >= maxPendingChunks - 50
            if (diagNearFull) {
                plugin.logger.info("[DIAG] put start size=$diagSizeBefore/$maxPendingChunks at ${chunkCoordinates.x},${chunkCoordinates.z}")
            }
            val diagPutStart = System.currentTimeMillis()
            // Bounded wait — if Paper callbacks stall, don't block the generation thread forever
            val accepted = this.pendingChunks.offer(pendingChunkEntry, 30, TimeUnit.SECONDS)
            // DIAG: remove after bug fix
            val diagPutMs = System.currentTimeMillis() - diagPutStart
            diagPutCount.incrementAndGet()
            if (diagPutMs > 500 || diagNearFull) {
                plugin.logger.info("[DIAG] put done took ${diagPutMs}ms accepted=$accepted size=${pendingChunks.size}/$maxPendingChunks")
            }
            if (!accepted) {
                plugin.logger.warning("Pending queue full for 30s at ${chunkCoordinates.x},${chunkCoordinates.z}; skipping chunk and cancelling its future. Re-run generation to fill any gaps.")
                pendingChunkEntry.chunk.cancel(false)
                missingChunks.add(chunkCoordinates)
                return
            }

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

            pendingChunkEntry.chunk.whenComplete { chunk, err ->
                if (timeoutTask != null) {
                    try { timeoutTask.cancel() } catch (_: Exception) {}
                }
                if (err == null && chunk != null) {
                    this.unloader.add(chunk)
                } else if (err != null && err !is java.util.concurrent.CancellationException) {
                    plugin.logger.warning("Chunk future failed at ${chunkCoordinates.x},${chunkCoordinates.z}: $err")
                }
                // Always remove from queue, even on error/cancel — keeps the queue from filling up forever
                this.pendingChunks.remove(pendingChunkEntry)
                // DIAG: remove after bug fix
                val completedN = diagCompletedCount.incrementAndGet()
                if (completedN % 200 == 0L) {
                    plugin.logger.info("[DIAG] whenComplete #$completedN fired at ${chunkCoordinates.x},${chunkCoordinates.z} pending=${pendingChunks.size} err=$err")
                }
            }
        }
    }

    /**
     * Cancels the generation task.
     * This unloads all chunks that were generated but not unloaded yet.
     */
    override fun cancel() {
        this.cancelRun = true
        // Snapshot first — clearing the queue while futures are still being cancelled would race
        val snapshot = this.pendingChunks.toList()
        snapshot.forEach { it.chunk.cancel(false) }
        // Force-empty the queue so any blocked put() / joinPending() loop exits immediately
        this.pendingChunks.clear()
        updateGenerationAreaMarker(true)
    }
}