package com.tunisianprayertimes.mosque.trace

import com.tunisianprayertimes.mosque.validMosqueCoordinates

/** Two minutes without a route sample leaves an unobserved interval on the map. */
internal const val TRACE_ROUTE_MAX_GAP_MS = 120_000L

internal val TraceEntry.isRouteSample: Boolean get() = kind == "route"

internal val TraceEntry.breaksRouteContinuity: Boolean get() = when (kind) {
    "route_provider", "recorder_error", "recording_interrupted", "recording_started" -> true
    else -> false
}

/** Index of a recent measured route position for context. An event's own geometry stays untouched. */
internal fun nearbyRouteIndices(entries: List<TraceEntry>): IntArray {
    val nearby = IntArray(entries.size) { -1 }
    var lastRoute = -1
    entries.forEachIndexed { index, entry ->
        when {
            entry.breaksRouteContinuity -> lastRoute = -1
            entry.isRouteSample -> lastRoute = if (entry.hasCoordinates()) index else -1
            !entry.hasCoordinates() && lastRoute >= 0 && entry.at - entries[lastRoute].at in 0L..15_000L ->
                nearby[index] = lastRoute
        }
    }
    return nearby
}

/** Shared by the recorder's Arabic timeline and its standalone replay. */
internal val TraceEntry.displayTitle: String get() = when (kind) {
    "walk_steps" -> when (val steps = stepDelta) {
        1L -> "خطوة مرصودة"
        2L -> "خطوتان مرصودتان"
        null -> "قراءة خطوات"
        else -> "عدد الخطوات في القراءة $steps"
    }
    "step_detector_started" -> "بدء رصد الخطوات الفردية"
    "step_counter_started" -> "تشغيل عدّاد الخطوات"
    "step_counter_ready" -> "قراءة مرجعية لعدّاد الخطوات"
    "step_sensor_unavailable" -> "تعذّر رصد الخطوات"
    "step_sensor_stopped" -> "نهاية رصد الخطوات"
    "route" -> "نقطة من المسار"
    "monitor_fix" -> "قراءة موقع لرصد المسجد"
    "recording_started" -> "بداية التسجيل"
    "recording_stopped" -> "نهاية التسجيل"
    "recording_interrupted" -> "انقطاع التسجيل"
    "test_arrived" -> "وصول سجّلته يدويًا"
    "test_left" -> "مغادرة سجّلتها يدويًا"
    "test_context" -> "حالة الإسكات عند بدء الاختبار"
    "prayer_schedule" -> "مواقيت الصلاة والإسكات"
    "prayer_alarm" -> "تنبيه فترة الإسكات"
    "device_audio" -> "حالة الصوت وعدم الإزعاج"
    "device_clock" -> "تغيّر وقت الهاتف أو منطقته الزمنية"
    "route_provider" -> "مصدر موقع المسار"
    "recorder_error" -> "تعذّر تسجيل المسار"
    "mosque_window" -> "فترة رصد المسجد والإسكات"
    "mosque_presence" -> "حالة التواجد في المسجد"
    "mosque_visit_ended" -> "نهاية التواجد المؤكّد"
    "mosque_sampling" -> "وتيرة رصد الموقع"
    "mosque_decision" -> "قرار الإسكات"
    "mosque_geofence" -> "عبور النطاق الجغرافي"
    "mosque_skipped" -> "تخطّي رصد المسجد"
    "mosque_observation_failed" -> "تعذّر رصد المسجد"
    "rule_requested" -> "طلب تفعيل قاعدة الإسكات"
    "rule_released" -> "طلب إيقاف قاعدة الإسكات"
    "rule_write_failed" -> "تعذّر تحديث قاعدة الإسكات"
    "rule_status" -> "حالة قاعدة الإسكات في أندرويد"
    "silence_requested" -> "طلب إسكات الهاتف"
    "sound_restore_requested" -> "طلب استعادة إعدادات الصوت"
    "sound_write_failed" -> "تعذّر ضبط الصوت"
    "silence_paused" -> "تعليق الإسكات التلقائي"
    else -> kind
}

internal fun TraceEntry.hasCoordinates(): Boolean {
    val lat = latitude ?: return false
    val lon = longitude ?: return false
    return validMosqueCoordinates(lat, lon)
}

internal fun TraceEntry.connectsRouteFrom(previous: TraceEntry): Boolean =
    isRouteSample && previous.isRouteSample && hasCoordinates() && previous.hasCoordinates() &&
        at - previous.at in 0L..TRACE_ROUTE_MAX_GAP_MS
