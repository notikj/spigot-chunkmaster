# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test

Use the Gradle wrapper. Always invoke from the repo root.

- **Build the plugin JAR**: `./gradlew shadowJar` — this is the only command that produces a usable plugin. `./gradlew build` produces a thin JAR that crashes the server with `NoClassDefFoundError` because PaperLib / Kotlin stdlib / bStats aren't included. Output: `build/libs/chunkmaster-<version>.jar`.
- **Run tests**: `./gradlew test`
- **Single test class**: `./gradlew test --tests "net.trivernis.chunkmaster.lib.shapes.CircleTest"`
- **Plugin version**: bumped via `PLUGIN_VERSION` in [gradle.properties](gradle.properties); injected into `plugin.yml` at `processResources` time.

Targets Kotlin 1.4.10, JVM 1.8, Paper API 1.14.4. The `shadowJar` block in [build.gradle](build.gradle) **relocates** `io.papermc.lib`, `org.bstats`, `kotlin`, `org.intellij`, `org.jetbrains` into the `net.trivernis.chunkmaster.*` namespace — keep this in mind if adding new runtime dependencies (anything not relocated will collide with other plugins).

## Architecture

This is a Spigot/Paper plugin that pre-generates chunks around a world's center. The non-obvious part is the concurrency model.

### Three-thread model (the thing that's easy to get wrong)

Generation runs across three independent execution contexts that must coordinate without deadlocking:

1. **Generation worker thread** — one `Thread` per active task, owned by [RunningTaskEntry](src/main/kotlin/net/trivernis/chunkmaster/lib/generation/taskentry/RunningTaskEntry.kt). Runs the loop in [DefaultGenerationTask](src/main/kotlin/net/trivernis/chunkmaster/lib/generation/DefaultGenerationTask.kt): pick next chunk → call `PaperLib.getChunkAtAsync` → put the resulting `CompletableFuture` into `pendingChunks` (an `ArrayBlockingQueue`).
2. **Paper async chunk callbacks** — fire on Paper's chunk-loading threads when `getChunkAtAsync` completes. The `whenComplete` handler removes the entry from `pendingChunks` and hands the chunk off to the unloader. **Critical**: `pendingChunks` will deadlock the worker if these callbacks ever stop firing (the queue fills, `offer` blocks). The current code guards this with three layers — bounded `offer(..., 30, SECONDS)`, a 60s safety-net `cancel()` scheduled via Bukkit, and `whenComplete` (not `thenAccept`) so cancellation/error paths still drain the queue.
3. **Bukkit main thread** — runs the periodic [ChunkUnloader](src/main/kotlin/net/trivernis/chunkmaster/lib/generation/ChunkUnloader.kt) (calls `chunk.unload(true)`) and the progress reporter. Also where `onDisable` runs — `stopAll()` must use bounded waits, never `.join()` on Bukkit async chains, or shutdown hangs forever and the server has to be killed (which corrupts chunk state on disk).

### Task state machine

Each task in [GenerationTask.run()](src/main/kotlin/net/trivernis/chunkmaster/lib/generation/GenerationTask.kt) walks `GENERATING → VALIDATING → CORRECTING`. The state is persisted in SQLite, so a task resumed after restart picks up at the right phase. `VALIDATING` re-walks the shape and adds any ungenerated chunks to `missingChunks`; `CORRECTING` then re-requests them.

### Persistence

[SqliteManager](src/main/kotlin/net/trivernis/chunkmaster/lib/database/SqliteManager.kt) auto-migrates four tables (`generation_tasks`, `pending_chunks`, `world_properties`, `completed_generation_tasks`) by diffing declared columns against `PRAGMA table_info`. New columns can be added to the `tables` list and they'll be `ALTER TABLE`'d on next startup. Don't bypass this — there are no separate migration files.

### Shapes

`generate <radius> <square|circle>` dispatches to a [Shape](src/main/kotlin/net/trivernis/chunkmaster/lib/shapes/Shape.kt) implementation that yields chunk coordinates in spiral order. Shapes are stateful (`shape.count`, `shape.next()`, `shape.reset()`) — the validate phase calls `reset()` before re-walking.

### Commands

`/chunkmaster` (aliases `/chm`, `/chunkm`, `/cmaster`) is dispatched by [CommandChunkmaster](src/main/kotlin/net/trivernis/chunkmaster/commands/CommandChunkmaster.kt) to per-subcommand classes (`Cmd*`) that all extend [Subcommand](src/main/kotlin/net/trivernis/chunkmaster/lib/Subcommand.kt). Argument parsing uses [ArgParser](src/main/kotlin/net/trivernis/chunkmaster/lib/ArgParser.kt).

### Localization

User-visible strings go through [LanguageManager](src/main/kotlin/net/trivernis/chunkmaster/lib/LanguageManager.kt). Default keys live in `src/main/resources/i18n/DEFAULT.i18n.properties`; per-language overrides in sibling `<lang>.i18n.properties` files. Server admins can drop additional translations into `plugins/Chunkmaster/i18n/`.

### Optional Dynmap integration

If the Dynmap plugin is present and `dynmap: true` in config, the plugin draws a marker showing the area being generated and triggers tile re-rendering for completed chunks. The integration is a soft dependency — wrapped in null-checks throughout.

### Batch jobs (`/chm batch ...`)

[BatchManager](src/main/kotlin/net/trivernis/chunkmaster/lib/batch/BatchManager.kt) drives multi-iteration runs that pre-generate one or more worlds, archive them, wipe them, and repeat. The flow:

1. `startBatch` validates worlds and writes a `batch_jobs` row (state = `GENERATING`). Iteration runs through normal `GenerationManager.addTask` calls per world; when each task hits its end it triggers the next world.
2. After the last world of an iteration finishes, `finishIteration` writes `pending_archive.json` (the marker file) into `plugins/Chunkmaster/`, sets state to `ARCHIVE_PENDING`, and calls `Bukkit.shutdown()` 60 ticks later. Worlds **must** be unloaded by the server before the archive runs — that's why we shut down rather than archiving in-process.
3. A JVM shutdown hook registered in [Chunkmaster.onEnable](src/main/kotlin/net/trivernis/chunkmaster/Chunkmaster.kt) calls `BatchManager.finalizeArchiveOnShutdown()`. That hook reads the marker, runs `tar --use-compress-program=zstd` ([BatchArchiveHook](src/main/kotlin/net/trivernis/chunkmaster/lib/batch/BatchArchiveHook.kt)), `deleteRecursively`s the world folders, and updates the DB row directly via JDBC — bumping `current_iteration` and resetting state to `GENERATING`, or marking `COMPLETED` on the last iteration.
4. On next startup, `BatchManager.resume()` picks up `GENERATING` jobs and starts the next iteration. Jobs found in `ARCHIVE_PENDING` at startup are flagged `FAILED` (means the hook didn't complete).

### JVM shutdown hook constraints (`finalizeArchiveOnShutdown`)

The shutdown hook runs **after** Bukkit has fully stopped, which voids most of the runtime you'd normally rely on. Three traps to remember:

- **Bukkit's plugin JAR is closed before the hook runs.** Any class not already loaded into the JVM throws `IllegalStateException: zip file closed` — including anonymous lambda classes inside our own methods. `BatchManager.preloadAllPluginClasses()` mitigates by `Class.forName`-ing every entry in the plugin JAR during `init()` while the JAR is still open. Do not introduce shutdown-hook code paths that assume new classes can be loaded.
- **Bukkit's logger is dead** (Log4j2's own shutdown hook runs first and tears it down silently). Diagnostics from the hook must go through `BatchManager.diagLog`, which writes to `plugins/Chunkmaster/shutdown_hook.log` via `FileWriter` and falls back to `System.out`. Never use `plugin.logger` from inside `finalizeArchiveOnShutdown`.
- **`plugin.server.*` may NPE**, since the Bukkit Server is torn down by hook time. `BatchManager.init()` captures `dataFolder`, `worldContainer`, and the SQLite DB path into final fields (`capturedDataFolder`, etc.) — the hook uses only those. Don't reach back into `plugin.server` from the hook.

### Persistence (batch tables)

[BatchJobs](src/main/kotlin/net/trivernis/chunkmaster/lib/database/BatchJobs.kt) owns two extra tables (`batch_jobs`, `batch_worlds`) created by the same auto-migration mechanism in `SqliteManager`. The shutdown hook bypasses `SqliteManager` entirely (Bukkit async machinery is gone) and goes straight through `java.sql.DriverManager` against the captured DB path.

## Project status

The upstream project (Trivernis/spigot-chunkmaster) is no longer actively developed; the README points users to Chunky as an alternative. This fork (`сменил репо` commit) exists for local fixes — don't expect upstream PRs to be merged.
