package net.trivernis.chunkmaster.lib.generation

import net.trivernis.chunkmaster.Chunkmaster
import org.bukkit.Chunk
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock

class ChunkUnloader(private val plugin: Chunkmaster) : Runnable {
    private val maxLoadedChunks = plugin.config.getInt("generation.max-loaded-chunks")
    private val lock = ReentrantReadWriteLock()
    private var unloadingQueue = Vector<Chunk>(maxLoadedChunks)
    val isFull: Boolean
        get() {
            return pendingSize == maxLoadedChunks
        }

    val pendingSize: Int
        get() {
            lock.readLock().lock()
            val size = unloadingQueue.size
            lock.readLock().unlock()
            return size
        }

    /**
     * Unloads all chunks in the unloading queue with each run
     */
    override fun run() {
        val diagStart = System.currentTimeMillis() // DIAG: remove after bug fix
        lock.writeLock().lock()
        val diagQueueSize: Int // DIAG: remove after bug fix
        try {
            val chunkToUnload = unloadingQueue.toHashSet()
            diagQueueSize = chunkToUnload.size // DIAG: remove after bug fix
            // DIAG: remove after bug fix
            if (diagQueueSize >= maxLoadedChunks - 50) {
                plugin.logger.info("[DIAG] unloader.run start size=$diagQueueSize/$maxLoadedChunks")
            }

            for (chunk in chunkToUnload) {
                try {
                    chunk.unload(true)
                } catch (e: Exception) {
                    plugin.logger.severe(e.toString())
                }
            }
            unloadingQueue.clear()
        } finally {
            lock.writeLock().unlock()
        }
        // DIAG: remove after bug fix
        val diagMs = System.currentTimeMillis() - diagStart
        if (diagMs > 200 && diagQueueSize > 0) {
            plugin.logger.info("[DIAG] unloader.run done size=$diagQueueSize took ${diagMs}ms")
        }
    }

    /**
     * Adds a chunk to unload to the queue
     */
    fun add(chunk: Chunk) {
        lock.writeLock().lockInterruptibly()
        try {
            unloadingQueue.add(chunk)
        } finally {
            lock.writeLock().unlock()
        }
    }
}