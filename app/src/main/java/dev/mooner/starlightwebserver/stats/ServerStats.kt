package dev.mooner.starlightwebserver.stats

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Request counters and recent requests, kept in memory for the widget. */
class ServerStats(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    data class Record(
        val timeMillis: Long,
        val method: String,
        val path: String,
        val status: Int,
        val durationMillis: Long,
        val project: String?,
    ) {
        val isError: Boolean
            get() = status >= 500
    }

    class Snapshot(
        val total: Long,
        val errors: Long,
        val averageMillis: Long,
        /** Newest first. */
        val recent: List<Record>,
        /** Requests per minute, oldest first, ending with the current minute. */
        val perMinute: IntArray,
    )

    private var total = 0L
    private var errors = 0L
    private var durationSum = 0L
    private val recent = ArrayDeque<Record>()
    private val minuteCounts = IntArray(MINUTES)
    private val minuteKeys = LongArray(MINUTES) { -1 }

    private val mVersion = MutableStateFlow(0L)
    /** Changes whenever a request is recorded. */
    val version: StateFlow<Long> = mVersion.asStateFlow()

    @Synchronized
    fun record(record: Record) {
        total++
        if (record.isError)
            errors++
        durationSum += record.durationMillis
        recent.addFirst(record)
        while (recent.size > RECENT_SIZE)
            recent.removeLast()

        val minute = record.timeMillis / MINUTE_MILLIS
        val slot = (minute % MINUTES).toInt()
        if (minuteKeys[slot] != minute) {
            minuteKeys[slot] = minute
            minuteCounts[slot] = 0
        }
        minuteCounts[slot]++
        mVersion.value++
    }

    @Synchronized
    fun snapshot(): Snapshot {
        val now = clock() / MINUTE_MILLIS
        val perMinute = IntArray(MINUTES) { i ->
            val minute = now - (MINUTES - 1 - i)
            val slot = (minute % MINUTES).toInt()
            if (minuteKeys[slot] == minute) minuteCounts[slot] else 0
        }
        return Snapshot(
            total = total,
            errors = errors,
            averageMillis = if (total == 0L) 0 else durationSum / total,
            recent = recent.toList(),
            perMinute = perMinute,
        )
    }

    @Synchronized
    fun reset() {
        total = 0
        errors = 0
        durationSum = 0
        recent.clear()
        minuteCounts.fill(0)
        minuteKeys.fill(-1)
        mVersion.value++
    }

    companion object {
        const val MINUTES = 30
        const val RECENT_SIZE = 20
        private const val MINUTE_MILLIS = 60_000L
    }
}
