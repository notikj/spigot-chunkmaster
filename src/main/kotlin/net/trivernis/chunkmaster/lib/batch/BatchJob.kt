package net.trivernis.chunkmaster.lib.batch

data class BatchJob(
    val id: Int,
    val totalIterations: Int,
    var currentIteration: Int,
    var state: BatchState,
    val archiveDir: String,
    val createdAt: Long,
    val worlds: List<BatchWorld>
)
