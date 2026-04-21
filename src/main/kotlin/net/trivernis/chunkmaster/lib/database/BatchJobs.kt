package net.trivernis.chunkmaster.lib.database

import net.trivernis.chunkmaster.lib.batch.BatchJob
import net.trivernis.chunkmaster.lib.batch.BatchState
import net.trivernis.chunkmaster.lib.batch.BatchWorld
import java.util.concurrent.CompletableFuture

class BatchJobs(private val sqliteManager: SqliteManager) {

    fun addBatchJob(
        totalIterations: Int,
        archiveDir: String,
        worlds: List<BatchWorld>
    ): CompletableFuture<Int> {
        val completableFuture = CompletableFuture<Int>()
        val now = System.currentTimeMillis()
        sqliteManager.executeStatement(
            """
            INSERT INTO batch_jobs (total_iterations, current_iteration, state, archive_dir, created_at)
            VALUES (?, 1, 'PENDING', ?, ?)
            """.trimIndent(),
            hashMapOf(
                1 to totalIterations,
                2 to archiveDir,
                3 to now
            )
        ) {
            sqliteManager.executeStatement(
                "SELECT id FROM batch_jobs ORDER BY id DESC LIMIT 1",
                HashMap()
            ) { res ->
                res!!.next()
                val id = res.getInt("id")
                insertWorlds(id, worlds).thenAccept {
                    completableFuture.complete(id)
                }
            }
        }
        return completableFuture
    }

    private fun insertWorlds(batchId: Int, worlds: List<BatchWorld>): CompletableFuture<Void> {
        val completableFuture = CompletableFuture<Void>()
        if (worlds.isEmpty()) {
            completableFuture.complete(null)
            return completableFuture
        }
        var remaining = worlds.size
        for (world in worlds) {
            sqliteManager.executeStatement(
                """
                INSERT INTO batch_worlds (batch_id, world_name, radius, shape, position)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                hashMapOf(
                    1 to batchId,
                    2 to world.worldName,
                    3 to world.radius,
                    4 to world.shape,
                    5 to world.position
                )
            ) {
                remaining--
                if (remaining == 0) completableFuture.complete(null)
            }
        }
        return completableFuture
    }

    fun updateState(batchId: Int, state: BatchState): CompletableFuture<Void> {
        val completableFuture = CompletableFuture<Void>()
        sqliteManager.executeStatement(
            "UPDATE batch_jobs SET state = ? WHERE id = ?",
            hashMapOf(1 to state.name, 2 to batchId)
        ) {
            completableFuture.complete(null)
        }
        return completableFuture
    }

    fun updateIteration(batchId: Int, iteration: Int): CompletableFuture<Void> {
        val completableFuture = CompletableFuture<Void>()
        sqliteManager.executeStatement(
            "UPDATE batch_jobs SET current_iteration = ? WHERE id = ?",
            hashMapOf(1 to iteration, 2 to batchId)
        ) {
            completableFuture.complete(null)
        }
        return completableFuture
    }

    fun deleteBatchJob(batchId: Int): CompletableFuture<Void> {
        val completableFuture = CompletableFuture<Void>()
        sqliteManager.executeStatement(
            "DELETE FROM batch_worlds WHERE batch_id = ?",
            hashMapOf(1 to batchId)
        ) {
            sqliteManager.executeStatement(
                "DELETE FROM batch_jobs WHERE id = ?",
                hashMapOf(1 to batchId)
            ) {
                completableFuture.complete(null)
            }
        }
        return completableFuture
    }

    fun getAllBatchJobs(): CompletableFuture<List<BatchJob>> {
        val completableFuture = CompletableFuture<List<BatchJob>>()
        sqliteManager.executeStatement("SELECT * FROM batch_jobs ORDER BY id ASC", HashMap()) { jobsRes ->
            val jobsList = mutableListOf<Pair<Int, BatchJobRow>>()
            while (jobsRes!!.next()) {
                jobsList.add(
                    jobsRes.getInt("id") to BatchJobRow(
                        totalIterations = jobsRes.getInt("total_iterations"),
                        currentIteration = jobsRes.getInt("current_iteration"),
                        state = BatchState.fromString(jobsRes.getString("state")),
                        archiveDir = jobsRes.getString("archive_dir"),
                        createdAt = jobsRes.getLong("created_at")
                    )
                )
            }
            if (jobsList.isEmpty()) {
                completableFuture.complete(emptyList())
                return@executeStatement
            }
            sqliteManager.executeStatement(
                "SELECT * FROM batch_worlds ORDER BY batch_id ASC, position ASC",
                HashMap()
            ) { worldsRes ->
                val worldsByJob = HashMap<Int, MutableList<BatchWorld>>()
                while (worldsRes!!.next()) {
                    val batchId = worldsRes.getInt("batch_id")
                    worldsByJob.getOrPut(batchId) { mutableListOf() }.add(
                        BatchWorld(
                            worldName = worldsRes.getString("world_name"),
                            radius = worldsRes.getInt("radius"),
                            shape = worldsRes.getString("shape"),
                            position = worldsRes.getInt("position")
                        )
                    )
                }
                val result = jobsList.map { (id, row) ->
                    BatchJob(
                        id = id,
                        totalIterations = row.totalIterations,
                        currentIteration = row.currentIteration,
                        state = row.state,
                        archiveDir = row.archiveDir,
                        createdAt = row.createdAt,
                        worlds = worldsByJob[id] ?: emptyList()
                    )
                }
                completableFuture.complete(result)
            }
        }
        return completableFuture
    }

    private data class BatchJobRow(
        val totalIterations: Int,
        val currentIteration: Int,
        val state: BatchState,
        val archiveDir: String,
        val createdAt: Long
    )
}
