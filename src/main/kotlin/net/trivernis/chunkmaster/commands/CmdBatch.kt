package net.trivernis.chunkmaster.commands

import net.trivernis.chunkmaster.Chunkmaster
import net.trivernis.chunkmaster.lib.Subcommand
import net.trivernis.chunkmaster.lib.batch.BatchWorld
import org.bukkit.command.Command
import org.bukkit.command.CommandSender

class CmdBatch(private val chunkmaster: Chunkmaster) : Subcommand {
    override val name = "batch"

    private val subcommands = listOf("start", "list", "cancel")
    private val shapes = listOf("square", "circle")

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: List<String>
    ): MutableList<String> {
        if (args.size == 1) {
            return subcommands.filter { it.startsWith(args[0]) }.toMutableList()
        }
        return mutableListOf()
    }

    override fun execute(sender: CommandSender, args: List<String>): Boolean {
        if (args.isEmpty()) {
            sender.sendMessage(chunkmaster.langManager.getLocalized("BATCH_USAGE"))
            return false
        }
        return when (args[0]) {
            "start" -> handleStart(sender, args.drop(1))
            "list" -> handleList(sender)
            "cancel" -> handleCancel(sender, args.drop(1))
            else -> {
                sender.sendMessage(chunkmaster.langManager.getLocalized("BATCH_USAGE"))
                false
            }
        }
    }

    private fun handleStart(sender: CommandSender, args: List<String>): Boolean {
        if (args.size < 2) {
            sender.sendMessage(chunkmaster.langManager.getLocalized("BATCH_USAGE"))
            return false
        }
        val iterations = args[0].toIntOrNull()
        if (iterations == null || iterations < 1) {
            sender.sendMessage(chunkmaster.langManager.getLocalized("BATCH_INVALID_ITERATIONS", args[0]))
            return false
        }
        val worldDefs = mutableListOf<BatchWorld>()
        for ((idx, raw) in args.drop(1).withIndex()) {
            val parts = raw.split(":")
            if (parts.size < 2 || parts.size > 3) {
                sender.sendMessage(chunkmaster.langManager.getLocalized("BATCH_INVALID_WORLD_SPEC", raw))
                return false
            }
            val name = parts[0]
            val radius = parts[1].toIntOrNull()
            if (radius == null || radius <= 0) {
                sender.sendMessage(chunkmaster.langManager.getLocalized("BATCH_INVALID_WORLD_SPEC", raw))
                return false
            }
            val shape = parts.getOrNull(2) ?: "square"
            if (shape !in shapes) {
                sender.sendMessage(chunkmaster.langManager.getLocalized("BATCH_INVALID_WORLD_SPEC", raw))
                return false
            }
            worldDefs.add(BatchWorld(name, radius, shape, idx))
        }
        chunkmaster.batchManager.startBatch(iterations, worldDefs).whenComplete { id, err ->
            if (err != null) {
                sender.sendMessage(
                    chunkmaster.langManager.getLocalized("BATCH_START_FAILED", err.message ?: "unknown error")
                )
            } else {
                sender.sendMessage(
                    chunkmaster.langManager.getLocalized(
                        "BATCH_STARTED",
                        id,
                        iterations,
                        worldDefs.joinToString(", ") { "${it.worldName}:${it.radius}" }
                    )
                )
            }
        }
        return true
    }

    private fun handleList(sender: CommandSender): Boolean {
        val batches = chunkmaster.batchManager.listBatches()
        if (batches.isEmpty()) {
            sender.sendMessage(chunkmaster.langManager.getLocalized("BATCH_LIST_EMPTY"))
            return true
        }
        sender.sendMessage(chunkmaster.langManager.getLocalized("BATCH_LIST_HEADER"))
        for (b in batches) {
            sender.sendMessage(
                chunkmaster.langManager.getLocalized(
                    "BATCH_LIST_ENTRY",
                    b.id,
                    b.state.name,
                    b.currentIteration,
                    b.totalIterations,
                    b.worlds.joinToString(",") { it.worldName }
                )
            )
        }
        return true
    }

    private fun handleCancel(sender: CommandSender, args: List<String>): Boolean {
        val id = args.firstOrNull()?.toIntOrNull()
        if (id == null) {
            sender.sendMessage(chunkmaster.langManager.getLocalized("BATCH_USAGE"))
            return false
        }
        val ok = chunkmaster.batchManager.cancelBatch(id)
        sender.sendMessage(
            if (ok) chunkmaster.langManager.getLocalized("BATCH_CANCELLED", id)
            else chunkmaster.langManager.getLocalized("BATCH_NOT_FOUND", id)
        )
        return ok
    }
}
