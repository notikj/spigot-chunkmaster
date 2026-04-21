package net.trivernis.chunkmaster.lib.generation.taskentry

import net.trivernis.chunkmaster.lib.generation.GenerationTask

class RunningTaskEntry(
    override val id: Int,
    override val generationTask: GenerationTask
) : TaskEntry {

    private var lastProgress: Pair<Long, Double>? = null
    private var lastChunkCount: Pair<Long, Int>? = null
    private var thread = Thread(generationTask)

    val threadState: Thread.State // DIAG: remove after bug fix
        get() = thread.state

    /**
     * Returns the generation Speed
     */
    val generationSpeed: Pair<Double?, Double?>
        get() {
            var generationSpeed: Double? = null
            var chunkGenerationSpeed: Double? = null
            val progress =
                generationTask.shape.progress(if (generationTask.radius < 0) (generationTask.world.worldBorder.size / 32).toInt() else null)
            if (lastProgress != null) {
                val progressDiff = progress - lastProgress!!.second
                val timeDiff = (System.currentTimeMillis() - lastProgress!!.first).toDouble() / 1000
                generationSpeed = progressDiff / timeDiff
            }
            if (lastChunkCount != null) {
                val chunkDiff = generationTask.count - lastChunkCount!!.second
                val timeDiff = (System.currentTimeMillis() - lastChunkCount!!.first).toDouble() / 1000
                chunkGenerationSpeed = chunkDiff / timeDiff
            }
            lastProgress = Pair(System.currentTimeMillis(), progress)
            lastChunkCount = Pair(System.currentTimeMillis(), generationTask.count)
            return Pair(generationSpeed, chunkGenerationSpeed)
        }

    init {
        lastProgress = Pair(System.currentTimeMillis(), generationTask.shape.progress(null))
        lastChunkCount = Pair(System.currentTimeMillis(), generationTask.count)
    }

    fun start() {
        thread.start()
    }

    fun cancel(timeout: Long): Boolean {
        // DIAG: remove after bug fix
        val diagLogger = java.util.logging.Logger.getLogger("Chunkmaster")
        diagLogger.info("[DIAG] cancel task=$id thread.state=${thread.state} isAlive=${thread.isAlive} isRunning=${generationTask.isRunning}")
        if (generationTask.isRunning) {
            generationTask.cancel()
            thread.interrupt()
        }
        return try {
            val result = joinThread(timeout)
            diagLogger.info("[DIAG] cancel task=$id joinThread result=$result isAlive=${thread.isAlive} state=${thread.state}") // DIAG: remove after bug fix
            result
        } catch (e: InterruptedException) {
            diagLogger.info("[DIAG] cancel task=$id interrupted") // DIAG: remove after bug fix
            true
        }
    }

    private fun joinThread(timeout: Long): Boolean {
        var threadStopped = false

        for (i in 0..100) {
            if (!thread.isAlive || !generationTask.isRunning) {
                threadStopped = true
                break
            }
            Thread.sleep(timeout / 100)
        }
        return threadStopped
    }
}