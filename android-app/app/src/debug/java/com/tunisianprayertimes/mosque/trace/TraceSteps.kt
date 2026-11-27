package com.tunisianprayertimes.mosque.trace

internal val TraceEntry.stepDelta: Long?
    get() = if (kind == "walk_steps") detail.toLongOrNull()?.takeIf { it > 0 } else null

/** Step detector events identify individual steps. A counter reading covers an interval. */
internal class TraceSteps(entries: List<TraceEntry>) {
    private val totals = LongArray(entries.size) { -1 }
    private val counterIntervals = mutableMapOf<Long, Long>()
    val sources = linkedSetOf<String>()
    var recordedCount: Long? = null
        private set
    var interrupted = false
        private set
    private var unavailableReason: String? = null

    init {
        var lastCounterReading: Long? = null
        var stopped = false
        for ((index, entry) in entries.withIndex()) {
            when (entry.kind) {
                "step_detector_started", "step_counter_started" -> {
                    if (stopped) interrupted = true
                    stopped = false
                    lastCounterReading = null
                    if (entry.kind == "step_detector_started") {
                        sources.add("detector")
                        if (recordedCount == null) recordedCount = 0
                    } else sources.add("counter")
                }
                "step_counter_ready" -> {
                    sources.add("counter")
                    if (lastCounterReading != null) interrupted = true
                    lastCounterReading = entry.at
                    if (recordedCount == null) recordedCount = 0
                }
                "step_sensor_unavailable", "step_sensor_stopped" -> {
                    if (entry.kind == "step_sensor_unavailable") {
                        unavailableReason = entry.detail
                        interrupted = true
                    }
                    lastCounterReading = null
                    stopped = true
                }
                "walk_steps" -> entry.stepDelta?.let { delta ->
                    lastCounterReading?.let { previous ->
                        counterIntervals[entry.id] = previous
                        lastCounterReading = entry.at
                    }
                    recordedCount = (recordedCount ?: 0L) + delta
                }
            }
            totals[index] = recordedCount ?: -1L
        }
    }

    fun recordedThrough(index: Int): Long? = totals[index].takeIf { it >= 0 }
    fun intervalStart(entry: TraceEntry): Long? = counterIntervals[entry.id]

    val summary: String get() {
        val count = recordedCount ?: return unavailableReason?.takeIf { it.isNotBlank() }
            ?: if ("counter" in sources) "لم تصل قراءة مرجعية من عدّاد الخطوات." else "لا تتوفّر بيانات خطوات لهذا المسار."
        val parts = mutableListOf("عدد الخطوات المرصودة $count")
        if ("detector" in sources) parts.add("مستشعر الخطوات الفردية")
        if ("counter" in sources) parts.add("عدّاد الخطوات")
        if (interrupted) parts.add("توجد فترة بلا قياس")
        return parts.joinToString(" · ")
    }
}
