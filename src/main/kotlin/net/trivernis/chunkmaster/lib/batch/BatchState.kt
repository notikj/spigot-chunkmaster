package net.trivernis.chunkmaster.lib.batch

enum class BatchState {
    PENDING,
    GENERATING,
    ARCHIVE_PENDING,
    COMPLETED,
    FAILED;

    companion object {
        fun fromString(value: String): BatchState = try {
            valueOf(value)
        } catch (e: IllegalArgumentException) {
            FAILED
        }
    }
}
