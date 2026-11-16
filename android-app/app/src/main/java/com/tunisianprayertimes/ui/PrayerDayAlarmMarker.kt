package com.tunisianprayertimes.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform

/** Full reach of the alarm-clock drawing, including its bells, feet and grouped-alarm backing (dp). */
internal const val DIAL_ALARM_RADIUS = 9f

/** Filled face for a main alarm, outlined face for an extra, stacked faces for a group. */
internal fun DrawScope.drawDayDialAlarm(
    center: Offset,
    scale: Float,
    cluster: Boolean,
    extra: Boolean,
) {
    val amber = Color(0xFFFFC247)
    val ink = Color(0xFF173F68)
    val white = Color(0xFFFFFAF0)
    withTransform({
        translate(center.x, center.y)
        scale(scale, scale, pivot = Offset.Zero)
    }) {
        if (cluster) {
            val back = Offset(2f, -1.1f)
            drawCircle(white, radius = 5.6f, center = back)
            drawCircle(ink, radius = 4.7f, center = back)
            drawCircle(amber, radius = 4.1f, center = back, style = Stroke(1.1f))
        }
        fun outlinedLine(from: Offset, to: Offset, width: Float) {
            drawLine(white, from, to, strokeWidth = width + 1.7f, cap = StrokeCap.Round)
            drawLine(amber, from, to, strokeWidth = width, cap = StrokeCap.Round)
        }
        // The twin bells and feet make this a clock silhouette even at the dial's small size.
        outlinedLine(Offset(-5.2f, -3.6f), Offset(-3.2f, -5.1f), 2f)
        outlinedLine(Offset(3.2f, -5.1f), Offset(5.2f, -3.6f), 2f)
        outlinedLine(Offset(-2.9f, 4.3f), Offset(-4f, 6.1f), 1.1f)
        outlinedLine(Offset(2.9f, 4.3f), Offset(4f, 6.1f), 1.1f)
        val face = Offset(0f, 0.4f)
        val outlined = extra && !cluster
        drawCircle(white, radius = 5.6f, center = face)
        drawCircle(if (outlined) ink else amber, radius = 4.7f, center = face)
        if (outlined) drawCircle(amber, radius = 4.1f, center = face, style = Stroke(1.1f))
        val hands = if (outlined) amber else ink
        drawLine(hands, face + Offset(0f, -2.7f), face, strokeWidth = 1.3f, cap = StrokeCap.Round)
        drawLine(hands, face, face + Offset(2.1f, 1.1f), strokeWidth = 1.3f, cap = StrokeCap.Round)
    }
}
