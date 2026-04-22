# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & test

Use the Gradle wrapper. Always invoke from the repo root.

- **Build the plugin JAR**: `./gradlew shadowJar` — this is the only command that produces a usable plugin. `./gradlew build` produces a thin JAR that crashes the server with `NoClassDefFoundError` because PaperLib / Kotlin stdlib / bStats aren't included. Output: `build/libs/chunkmaster-<version>.jar`.
- **Run tests**: `./gradlew test`
- **Single test class**: `./gradlew test --tests "net.trivernis.chunkmaster.lib.shapes.CircleTest"`
- **Plugin version**: bumped via `PLUGIN_VERSION` in [gradle.properties](gradle.properties); injected into `plugin.yml` at `processResources` time.

Targets Kotlin 1.4.10, JVM 1.8, Paper API 1.14.4 at compile time; runtime target is **Paper/Purpur 1.16.5** (see "Thread-safety on 1.16.5" below). The `shadowJar` block in [build.gradle](build.gradle) **relocates** `io.papermc.lib`, `org.bstats`, `kotlin`, `org.intellij`, `org.jetbrains` into the `net.trivernis.chunkmaster.*` namespace — keep this in mind if adding new runtime dependencies (anything not relocated will collide with other plugins).

## Architecture

This is a Spigot/Paper plugin that pre-generates chunks around a world's center. The non-obvious part is the concurrency model.

### Four-context concurrency model

Generation coordinates across four execution contexts that must not deadlock:

1. **Generation worker thread** — one `Thread` per active task, owned by [RunningTaskEntry](src/main/kotlin/net/trivernis/chunkmaster/lib/generation/taskentry/RunningTaskEntry.kt). Runs the spiral walk in [DefaultGenerationTask](src/main/kotlin/net/trivernis/chunkmaster/lib/generation/DefaultGenerationTask.kt): pick next chunk → `working.acquire()` (a `Semaphore` sized by `generation.max-pending-chunks`) → call `PaperLib.getChunkAtAsync` → insert a `PendingChunkEntry` into a `ConcurrentHashMap<ChunkCoordinates, PendingChunkEntry>` for diagnostics/persistence.
2. **Paper async chunk callbacks** — the `whenComplete` handler on each chunk future fires on Paper's chunk-loading threads. It removes the entry from `pendingChunksMap` and calls `working.release()`, which unblocks the worker. **This is the only thing that unblocks the worker** — if a callback ever fails to fire, the worker stalls. Two safeguards: a 60s Bukkit-scheduled safety-net that cancels the future (forcing `whenComplete` through the cancellation path), and `whenComplete` itself (not `thenAccept`) so error/cancel paths still drain the permit. `joinPending()` uses `acquire(maxPendingChunks)` + `release(maxPendingChunks)` for event-driven "wait for all in-flight to finish" — no polling.
3. **Bukkit main thread** — runs `saveProgress()` every 600 ticks (~30s) and `saveActiveWorlds()` every 6000 ticks (~5 min), both synchronously. `saveProgress` snapshots task state on the main thread (`snapshotForSave` copies `lastChunkCoords`, `pendingChunks`, `missingChunks` into an immutable `TaskSaveSnapshot`) before dispatching the JDBC work to `dbExecutor`. `saveActiveWorlds()` must stay on main because `world.save()` fires `WorldSaveEvent` synchronously (see "Thread-safety on 1.16.5" below). `onDisable` also runs here — `stopAll()` must use bounded waits (see below), never `.join()` on Bukkit async chains, or shutdown hangs and the server has to be killed (which corrupts chunk state on disk).
4. **dbExecutor (single-thread)** — [GenerationManager](src/main/kotlin/net/trivernis/chunkmaster/lib/generation/GenerationManager.kt) owns an `Executors.newSingleThreadExecutor` that serializes SQLite writes off the main thread. The shared SQLite connection in [SqliteManager](src/main/kotlin/net/trivernis/chunkmaster/lib/database/SqliteManager.kt) is reached from both Bukkit main (quick foreground ops like `addTask`/`removeTask`) and the dbExecutor thread (periodic saves), so `SqliteManager.executeStatement` is `@Synchronized` on the manager. In `stopAll`, the final save is routed through `dbExecutor.submit(...).get(10, SECONDS)` so the dbExecutor serializes with any in-flight periodic save, but the wait is bounded.

There is **no ChunkUnloader anymore** — previously a periodic Bukkit task called `chunk.unload(true)` for every generated chunk. It's gone. Paper naturally unloads chunks that fall out of range as the spiral walks away, and `world.save()` is invoked every ~5 minutes (`saveActiveWorlds`) and again in `stopAll` as a durability safety net. Don't re-introduce manual unloading.

`missingChunks` is a `HashSet` shared between worker and main threads (worker mutates during validate/correct, main snapshots during `saveProgress`). Every read/write site uses `synchronized(this.missingChunks) { ... }` — keep that pattern when adding new access points.

### Task state machine

Each task's `run()` in [GenerationTask](src/main/kotlin/net/trivernis/chunkmaster/lib/generation/GenerationTask.kt) walks `GENERATING → VALIDATING → CORRECTING`. State is persisted in SQLite, so a task resumed after restart picks up in the right phase. `VALIDATING` re-walks the shape and checks each coord with `PaperLib.isChunkGenerated`, parallelised across a `Executors.newFixedThreadPool(generation.validation-threads)` (default 4, range 1–16) in batches of 256 — the shape walk stays sequential (it's stateful), only the region-file I/O is fanned out. Missed chunks go into `missingChunks`; `CORRECTING` then feeds them back through `requestGeneration`.

### Persistence

[SqliteManager](src/main/kotlin/net/trivernis/chunkmaster/lib/database/SqliteManager.kt) declares six tables (`generation_tasks`, `world_properties`, `pending_chunks`, `completed_generation_tasks`, `batch_jobs`, `batch_worlds`) and auto-migrates by diffing declared columns against `PRAGMA table_info` — new columns added to the `tables` list get `ALTER TABLE`'d on next startup. Don't bypass this; there are no separate migration files. The shared connection is opened lazily and closed when `activeTasks` hits zero (reference-counted).

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

The shutdown hook also bypasses `SqliteManager` entirely (Bukkit async machinery is gone) and goes straight through `java.sql.DriverManager` against the captured DB path.

### Thread-safety on Paper 1.16.5

`PaperLib.getChunkAtAsync` is safe to call from any thread — it's the designated async-chunk entry point and is what the worker thread uses. Most other `World` APIs are not.

- **`World.addPluginChunkTicket` / `removePluginChunkTicket` are NOT thread-safe.** They mutate `ChunkMapDistance`'s internal `Long2ObjectOpenHashMap` without synchronization. Calling them from a non-main thread races with `ChunkProviderServer.tick → purgeTickets` and NPEs inside fastutil's `FastEntryIterator`, crashing the world tick, then the server, then Bukkit's plugin-disable ticket cleanup (which hits the same corrupted map). If a future optimization ever needs plugin chunk tickets, schedule every add/remove via `plugin.server.scheduler.runTask` and mutate the tracking deque only from the main thread.
- **`World.save()` must be called on the main thread.** It fires `WorldSaveEvent` synchronously, and Bukkit rejects async callers with `IllegalStateException: WorldSaveEvent may only be triggered synchronously`. The periodic safety-net save in `GenerationManager.init()` therefore uses `runTaskTimer` (sync), not `runTaskTimerAsynchronously`. The same rule applies to anything else that fires a Bukkit `Event`.

## Project status

The upstream project (Trivernis/spigot-chunkmaster) is no longer actively developed; the README points users to Chunky as an alternative. This fork (`сменил репо` commit) exists for local fixes — don't expect upstream PRs to be merged.
