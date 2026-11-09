package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.Divider
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextMuted
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class NightThirdSegment(
    val startTime: String,
    val endTime: String,
    val startMinuteOfDay: Int,
    val endMinuteOfDay: Int,
)

internal data class NightTimelineAlarm(
    val id: String,
    val wakeAlarmId: String,
    val title: String,
    val time: String,
    val minuteOfDay: Int,
    val isSubAlarm: Boolean,
)

internal data class NightTimelinePrayer(
    val prayer: Prayer,
    val title: String,
    val time: String,
    /** Counted from the selected day's midnight, so past 1440 for an Isha after midnight. */
    val minuteOfDay: Int,
)

internal data class NightTimesCardData(
    val sunriseTime: String,
    val sunriseMinuteOfDay: Int,
    val currentMinuteOfDay: Int,
    val showCurrentTime: Boolean,
    /** The night shown, from its Maghrib to its Fajr, in thirds. */
    val thirds: List<NightThirdSegment>,
    val timelinePrayers: List<NightTimelinePrayer>,
    val timelineAlarms: List<NightTimelineAlarm>,
    /** The day shown, written at the top of the dial on another day than today. */
    val selectedDateLabel: String,
    val sunAltitudeDeg: Double?,
)

@Composable
internal fun PrayerDayOverviewCard(
    data: NightTimesCardData,
    onEditWakeAlarm: (String) -> Unit,
) {
    val skyBlue = Color(0xFF92D8F3)
    val deepNight = Color(0xFF16345C)
    // The dial's texts follow the phone's full text size, like the rest of the page: at a large size its panel grows
    // taller to make room for them.
    var selectedAlarmCluster by remember(data.selectedDateLabel) { mutableStateOf<List<NightTimelineAlarm>?>(null) }

    selectedAlarmCluster?.let { alarms ->
        TimelineAlarmClusterSheet(
            alarms = alarms,
            onDismiss = { selectedAlarmCluster = null },
            onEditAlarm = { alarmId ->
                selectedAlarmCluster = null
                onEditWakeAlarm(alarmId)
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 430.dp)
                .fillMaxWidth()
                .testTag("prayer_day_overview"),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            border = BorderStroke(1.dp, Gold.copy(alpha = 0.18f)),
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    val dialTextSizes = rememberDialTextSizes()
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val panelWidth = maxWidth.value
                    // The layout ignores what changes every few seconds (the time, the sun's altitude), so it is
                    // worked out again only when the day, the place, the alarms or the text size change.
                    val layoutData = remember(data) {
                        data.copy(currentMinuteOfDay = 0, sunAltitudeDeg = data.sunAltitudeDeg?.let { 0.0 })
                    }
                    val layout = remember(layoutData, panelWidth, dialTextSizes) {
                        layoutDayDial(layoutData, panelWidth, dialTextSizes)
                    }
                    // The panel is as tall as the layout needs: taller at a large text size.
                    val panelHeight = layout.panelHeight
                    // TalkBack reads the dial, and the list under it, in the day's order: from Fajr round to the last
                    // third of the night.
                    val fajrMinute = data.timelinePrayers.firstOrNull { it.prayer == Prayer.FAJR }?.minuteOfDay ?: data.thirds[2].endMinuteOfDay
                    fun readOrder(minuteOfDay: Int): Float = minutesBetweenInDay(fajrMinute, minuteOfDay).toFloat()
                    Column(modifier = Modifier.fillMaxWidth().semantics { isTraversalGroup = true }) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(panelHeight.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(deepNight)
                            .border(BorderStroke(1.dp, Divider), RoundedCornerShape(22.dp)),
                    ) {
                    val circleCenterX = layout.centerX
                    val circleCenterY = layout.centerY
                    val circleRadius = layout.radius
                    val circleTop = circleCenterY - circleRadius
                    val circleBottom = circleCenterY + circleRadius
                    val sunriseAngleDegrees = layout.sunriseAngle
                    val daylightSweepDegrees = layout.daylightSweep
                    val twilightSweepDegrees = layout.twilightSweep
                    val fajrAngleDegrees = layout.fajrAngle
                    val sunriseX = layout.point(layout.sunriseAngle).x
                    val sunriseY = layout.point(layout.sunriseAngle).y
                    val sunsetX = layout.point(layout.sunsetAngle).x
                    val sunsetY = layout.point(layout.sunsetAngle).y
                    val sunriseSunX = layout.sunriseSun.x
                    val sunriseSunY = layout.sunriseSun.y
                    val sunsetSunX = layout.sunsetSun.x
                    val sunsetSunY = layout.sunsetSun.y
                    val sunsetPanelBorderX = layout.sunsetBorderEnd.x
                    val sunsetPanelBorderY = layout.sunsetBorderEnd.y
                    val secondThirdX = layout.point(layout.secondThirdAngle).x
                    val secondThirdY = layout.point(layout.secondThirdAngle).y
                    val finalThirdX = layout.point(layout.finalThirdAngle).x
                    val finalThirdY = layout.point(layout.finalThirdAngle).y
                    val fajrX = layout.point(layout.fajrAngle).x
                    val fajrY = layout.point(layout.fajrAngle).y
                    val timelinePrayerAngles = layout.prayerAngles
                    val timelineAlarmPlacements = layout.alarms
                    val (activeSegmentStartAngle, activeSegmentSweep) = layout.activeArc(data.currentMinuteOfDay)
                    val currentAngleDegrees = layout.angleOf(data.currentMinuteOfDay)
                    // Texts that moved away from their dot, with the colour of the sky they sit on.
                    val leaderLines = (listOf(layout.fajr, layout.secondThird, layout.finalThird) + layout.prayerLabels.map { it.second })
                        .mapNotNull { label ->
                            label.leaderFrom?.let { marker ->
                                leaderEnds(marker, label.rect)?.let { ends -> ends to layout.onDaySky(label.rect.center) }
                            }
                        }

                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val scaleX = size.width / panelWidth
                        val scaleY = size.height / panelHeight
                        val circleCenter = Offset(circleCenterX * scaleX, circleCenterY * scaleY)
                        val circleRadiusPx = circleRadius * scaleX
                        val circleTopPx = circleTop * scaleY
                        val circleBottomPx = circleBottom * scaleY
                        val circleLeftPx = (circleCenterX - circleRadius) * scaleX
                        val circleRightPx = (circleCenterX + circleRadius) * scaleX
                        val sunrisePoint = Offset(sunriseX * scaleX, sunriseY * scaleY)
                        val sunsetPoint = Offset(sunsetX * scaleX, sunsetY * scaleY)
                        val sunriseSunCenter = Offset(sunriseSunX * scaleX, sunriseSunY * scaleY)
                        val sunsetSunCenter = Offset(sunsetSunX * scaleX, sunsetSunY * scaleY)
                        val noonSunCenter = layout.noonSun?.let { Offset(it.x * scaleX, it.y * scaleY) }
                        val midnightMoonCenter = layout.moon?.let { Offset(it.x * scaleX, it.y * scaleY) }
                        val secondThirdPoint = Offset(secondThirdX * scaleX, secondThirdY * scaleY)
                        val finalThirdPoint = Offset(finalThirdX * scaleX, finalThirdY * scaleY)
                        val fajrPoint = Offset(fajrX * scaleX, fajrY * scaleY)
                        val nightApex = Offset(circleCenter.x, circleBottomPx - 8f * scaleY)
                        val nightHeightPx = circleBottomPx - circleCenter.y
                        val sunsetBoundaryEnd = Offset(sunsetPanelBorderX * scaleX, sunsetPanelBorderY * scaleY)
                        val fajrRayDx = fajrPoint.x - circleCenter.x
                        val fajrRayDy = fajrPoint.y - circleCenter.y
                        val fajrRayToLeft = if (fajrRayDx < 0f) (0f - fajrPoint.x) / fajrRayDx else Float.POSITIVE_INFINITY
                        val fajrRayToBottom = if (fajrRayDy > 0f) (size.height - fajrPoint.y) / fajrRayDy else Float.POSITIVE_INFINITY
                        val fajrRayScale = minOf(fajrRayToLeft, fajrRayToBottom).coerceAtLeast(0f)
                        val fajrBoundaryEnd = Offset(
                            x = fajrPoint.x + fajrRayDx * fajrRayScale,
                            y = fajrPoint.y + fajrRayDy * fajrRayScale,
                        )
                        val circleRect = Rect(circleLeftPx, circleTopPx, circleRightPx, circleBottomPx)
                        val circlePath = Path().apply {
                            addOval(circleRect)
                        }
                        val dayPath = Path().apply {
                            moveTo(circleCenter.x, circleCenter.y)
                            lineTo(sunrisePoint.x, sunrisePoint.y)
                            arcTo(circleRect, sunriseAngleDegrees, daylightSweepDegrees, false)
                            lineTo(circleCenter.x, circleCenter.y)
                            close()
                        }
                        val twilightPath = Path().apply {
                            moveTo(circleCenter.x, circleCenter.y)
                            lineTo(fajrPoint.x, fajrPoint.y)
                            arcTo(circleRect, fajrAngleDegrees, twilightSweepDegrees, false)
                            lineTo(circleCenter.x, circleCenter.y)
                            close()
                        }
                        // The sky outside the ring, day above the horizon and the sunset ray, night below: both
                        // outlines walk the panel's corners, so neither leaves a strip uncovered when the ray meets
                        // the top edge (short days) or the bottom edge (long days).
                        fun outline(points: List<Offset>) = Path().apply {
                            moveTo(points.first().x * scaleX, points.first().y * scaleY)
                            points.drop(1).forEach { lineTo(it.x * scaleX, it.y * scaleY) }
                            close()
                        }
                        val lowerNightField = outline(layout.nightSkyOutline)
                        val daylightField = outline(layout.daySkyOutline)
                        val twilightField = Path().apply {
                            moveTo(0f, sunrisePoint.y)
                            lineTo(sunrisePoint.x, sunrisePoint.y)
                            lineTo(fajrPoint.x, fajrPoint.y)
                            lineTo(fajrBoundaryEnd.x, fajrBoundaryEnd.y)
                            if (fajrBoundaryEnd.y >= size.height - 1f) {
                                lineTo(0f, size.height)
                            }
                            close()
                        }
                        fun drawHorizonSun(center: Offset, color: Color) {
                            for (ray in 0..8) {
                                val angle = PI + ray * PI / 8.0
                                drawLine(
                                    color = color.copy(alpha = 0.60f),
                                    start = Offset(
                                        center.x + 14f * scaleX * cos(angle).toFloat(),
                                        center.y + 14f * scaleY * sin(angle).toFloat(),
                                    ),
                                    end = Offset(
                                        center.x + 23f * scaleX * cos(angle).toFloat(),
                                        center.y + 23f * scaleY * sin(angle).toFloat(),
                                    ),
                                    strokeWidth = 1.7f * scaleY,
                                )
                            }
                            drawCircle(color.copy(alpha = 0.33f), radius = 18f * scaleX, center = center)
                            drawCircle(color, radius = 11f * scaleX, center = center)
                        }
                        fun drawCloud(anchorX: Float, anchorY: Float, cloudScale: Float, alpha: Float = 0.36f) {
                            val cloudScalePx = scaleX * cloudScale
                            val cloudColor = Color.White.copy(alpha = alpha)
                            val cloudPath = Path().apply {
                                moveTo(anchorX, anchorY + 28f * cloudScalePx)
                                cubicTo(
                                    anchorX + 5f * cloudScalePx,
                                    anchorY + 16f * cloudScalePx,
                                    anchorX + 15f * cloudScalePx,
                                    anchorY + 17f * cloudScalePx,
                                    anchorX + 21f * cloudScalePx,
                                    anchorY + 10f * cloudScalePx,
                                )
                                cubicTo(
                                    anchorX + 27f * cloudScalePx,
                                    anchorY - 4f * cloudScalePx,
                                    anchorX + 44f * cloudScalePx,
                                    anchorY - 2f * cloudScalePx,
                                    anchorX + 49f * cloudScalePx,
                                    anchorY + 12f * cloudScalePx,
                                )
                                cubicTo(
                                    anchorX + 60f * cloudScalePx,
                                    anchorY + 7f * cloudScalePx,
                                    anchorX + 75f * cloudScalePx,
                                    anchorY + 15f * cloudScalePx,
                                    anchorX + 76f * cloudScalePx,
                                    anchorY + 28f * cloudScalePx,
                                )
                                cubicTo(
                                    anchorX + 76f * cloudScalePx,
                                    anchorY + 40f * cloudScalePx,
                                    anchorX + 57f * cloudScalePx,
                                    anchorY + 43f * cloudScalePx,
                                    anchorX + 45f * cloudScalePx,
                                    anchorY + 39f * cloudScalePx,
                                )
                                cubicTo(
                                    anchorX + 31f * cloudScalePx,
                                    anchorY + 45f * cloudScalePx,
                                    anchorX + 10f * cloudScalePx,
                                    anchorY + 42f * cloudScalePx,
                                    anchorX,
                                    anchorY + 28f * cloudScalePx,
                                )
                                close()
                            }
                            val lowerWisp = Path().apply {
                                moveTo(anchorX + 8f * cloudScalePx, anchorY + 34f * cloudScalePx)
                                cubicTo(
                                    anchorX + 24f * cloudScalePx,
                                    anchorY + 41f * cloudScalePx,
                                    anchorX + 49f * cloudScalePx,
                                    anchorY + 42f * cloudScalePx,
                                    anchorX + 70f * cloudScalePx,
                                    anchorY + 31f * cloudScalePx,
                                )
                            }
                            drawPath(cloudPath, cloudColor)
                            drawPath(
                                path = cloudPath,
                                color = Color.White.copy(alpha = alpha * 0.50f),
                                style = Stroke(width = 1.4f * cloudScalePx),
                            )
                            drawPath(
                                path = lowerWisp,
                                color = Color.White.copy(alpha = alpha * 0.62f),
                                style = Stroke(width = 1.1f * cloudScalePx),
                            )
                        }
                        fun drawStar(center: Offset, radius: Float, alpha: Float = 0.70f) {
                            drawCircle(GoldLight.copy(alpha = alpha), radius = radius * 0.34f, center = center)
                            drawCircle(
                                Color.White.copy(alpha = alpha * 0.50f),
                                radius = radius * 0.13f,
                                center = Offset(center.x + radius * 0.15f, center.y - radius * 0.12f),
                            )
                        }
                        fun drawCrescent(center: Offset) {
                            val moonRadius = 8.5f * scaleX
                            val outerMoonPath = Path().apply {
                                addOval(
                                    Rect(
                                        left = center.x - moonRadius,
                                        top = center.y - moonRadius,
                                        right = center.x + moonRadius,
                                        bottom = center.y + moonRadius,
                                    ),
                                )
                            }
                            val innerMoonRadius = moonRadius * 0.96f
                            val innerMoonCenter = Offset(
                                x = center.x + moonRadius * 0.50f,
                                y = center.y,
                            )
                            val innerMoonPath = Path().apply {
                                addOval(
                                    Rect(
                                        left = innerMoonCenter.x - innerMoonRadius,
                                        top = innerMoonCenter.y - innerMoonRadius,
                                        right = innerMoonCenter.x + innerMoonRadius,
                                        bottom = innerMoonCenter.y + innerMoonRadius,
                                    ),
                                )
                            }
                            drawPath(Path.combine(PathOperation.Difference, outerMoonPath, innerMoonPath), GoldLight)
                        }
                        fun drawNightMosqueSilhouette(
                            centerX: Float,
                            baseY: Float,
                            silhouetteScale: Float,
                            alpha: Float,
                            color: Color = Color(0xFF061D32),
                        ) {
                            val silhouetteColor = color.copy(alpha = alpha)
                            val detailColor = Color(0xFF86AFC2).copy(alpha = alpha * 0.22f)
                            val xScale = scaleX * silhouetteScale
                            val yScale = scaleY * silhouetteScale

                            fun drawRectangle(leftOffset: Float, topOffset: Float, rightOffset: Float, bottomOffset: Float) {
                                val rectangle = Path().apply {
                                    moveTo(centerX + leftOffset * xScale, baseY + topOffset * yScale)
                                    lineTo(centerX + rightOffset * xScale, baseY + topOffset * yScale)
                                    lineTo(centerX + rightOffset * xScale, baseY + bottomOffset * yScale)
                                    lineTo(centerX + leftOffset * xScale, baseY + bottomOffset * yScale)
                                    close()
                                }
                                drawPath(rectangle, silhouetteColor)
                            }

                            fun drawArchedOpening(centerOffset: Float, bottomOffset: Float, width: Float, height: Float) {
                                val halfWidth = width * xScale / 2f
                                val openingCenterX = centerX + centerOffset * xScale
                                val openingBottomY = baseY + bottomOffset * yScale
                                val openingHeight = height * yScale
                                val openingPath = Path().apply {
                                    moveTo(openingCenterX - halfWidth, openingBottomY)
                                    lineTo(openingCenterX - halfWidth, openingBottomY - openingHeight * 0.56f)
                                    cubicTo(
                                        openingCenterX - halfWidth,
                                        openingBottomY - openingHeight * 0.88f,
                                        openingCenterX - halfWidth * 0.45f,
                                        openingBottomY - openingHeight,
                                        openingCenterX,
                                        openingBottomY - openingHeight,
                                    )
                                    cubicTo(
                                        openingCenterX + halfWidth * 0.45f,
                                        openingBottomY - openingHeight,
                                        openingCenterX + halfWidth,
                                        openingBottomY - openingHeight * 0.88f,
                                        openingCenterX + halfWidth,
                                        openingBottomY - openingHeight * 0.56f,
                                    )
                                    lineTo(openingCenterX + halfWidth, openingBottomY)
                                    close()
                                }
                                drawPath(openingPath, detailColor)
                            }

                            fun drawFinial(finialCenterX: Float, finialBaseY: Float, finialHeight: Float, finialRadius: Float) {
                                drawLine(
                                    color = silhouetteColor,
                                    start = Offset(finialCenterX, finialBaseY),
                                    end = Offset(finialCenterX, finialBaseY - finialHeight),
                                    strokeWidth = 1.1f * xScale,
                                )
                                drawCircle(
                                    color = silhouetteColor,
                                    radius = finialRadius,
                                    center = Offset(finialCenterX, finialBaseY - finialHeight * 0.58f),
                                )
                            }

                            fun drawDome(centerOffset: Float, baseOffset: Float, halfWidth: Float, height: Float, finialHeight: Float) {
                                val domeCenterX = centerX + centerOffset * xScale
                                val domeBaseY = baseY + baseOffset * yScale
                                val halfWidthPx = halfWidth * xScale
                                val heightPx = height * yScale
                                val domePath = Path().apply {
                                    moveTo(domeCenterX - halfWidthPx, domeBaseY)
                                    cubicTo(
                                        domeCenterX - halfWidthPx * 1.03f,
                                        domeBaseY - heightPx * 0.32f,
                                        domeCenterX - halfWidthPx * 0.64f,
                                        domeBaseY - heightPx * 0.64f,
                                        domeCenterX - halfWidthPx * 0.14f,
                                        domeBaseY - heightPx * 0.83f,
                                    )
                                    quadraticTo(
                                        domeCenterX,
                                        domeBaseY - heightPx,
                                        domeCenterX + halfWidthPx * 0.14f,
                                        domeBaseY - heightPx * 0.83f,
                                    )
                                    cubicTo(
                                        domeCenterX + halfWidthPx * 0.64f,
                                        domeBaseY - heightPx * 0.64f,
                                        domeCenterX + halfWidthPx * 1.03f,
                                        domeBaseY - heightPx * 0.32f,
                                        domeCenterX + halfWidthPx,
                                        domeBaseY,
                                    )
                                    close()
                                }
                                drawPath(domePath, silhouetteColor)
                                drawFinial(
                                    finialCenterX = domeCenterX,
                                    finialBaseY = domeBaseY - heightPx + 1.5f * yScale,
                                    finialHeight = finialHeight * yScale,
                                    finialRadius = 1.2f * xScale,
                                )
                            }

                            fun drawMinaret(centerOffset: Float, height: Float, width: Float, balconyLevels: List<Float>) {
                                val towerCenterX = centerX + centerOffset * xScale
                                val towerBaseY = baseY + 5f * yScale
                                val towerHeightPx = height * yScale
                                val towerWidthPx = width * xScale
                                val shaftTopY = towerBaseY - towerHeightPx
                                val shaftPath = Path().apply {
                                    moveTo(towerCenterX - towerWidthPx * 0.48f, towerBaseY)
                                    lineTo(towerCenterX - towerWidthPx * 0.36f, shaftTopY + towerWidthPx * 1.1f)
                                    lineTo(towerCenterX + towerWidthPx * 0.36f, shaftTopY + towerWidthPx * 1.1f)
                                    lineTo(towerCenterX + towerWidthPx * 0.48f, towerBaseY)
                                    close()
                                }
                                drawPath(shaftPath, silhouetteColor)
                                drawArchedOpening(centerOffset, -height * 0.28f, width * 0.38f, height * 0.10f)
                                drawArchedOpening(centerOffset, -height * 0.48f, width * 0.34f, height * 0.09f)
                                balconyLevels.forEach { level ->
                                    val balconyY = towerBaseY - towerHeightPx * level
                                    val balconyPath = Path().apply {
                                        moveTo(towerCenterX - towerWidthPx * 0.86f, balconyY - 1.4f * yScale)
                                        lineTo(towerCenterX + towerWidthPx * 0.86f, balconyY - 1.4f * yScale)
                                        lineTo(towerCenterX + towerWidthPx * 0.76f, balconyY + 2.4f * yScale)
                                        lineTo(towerCenterX - towerWidthPx * 0.76f, balconyY + 2.4f * yScale)
                                        close()
                                    }
                                    drawPath(balconyPath, silhouetteColor)
                                }
                                val capBaseY = shaftTopY + towerWidthPx * 1.1f
                                val capHeightPx = towerWidthPx * 2.2f
                                val capPath = Path().apply {
                                    moveTo(towerCenterX - towerWidthPx * 0.62f, capBaseY)
                                    cubicTo(
                                        towerCenterX - towerWidthPx * 0.62f,
                                        capBaseY - capHeightPx * 0.52f,
                                        towerCenterX - towerWidthPx * 0.22f,
                                        capBaseY - capHeightPx * 0.92f,
                                        towerCenterX,
                                        capBaseY - capHeightPx,
                                    )
                                    cubicTo(
                                        towerCenterX + towerWidthPx * 0.22f,
                                        capBaseY - capHeightPx * 0.92f,
                                        towerCenterX + towerWidthPx * 0.62f,
                                        capBaseY - capHeightPx * 0.52f,
                                        towerCenterX + towerWidthPx * 0.62f,
                                        capBaseY,
                                    )
                                    close()
                                }
                                drawPath(capPath, silhouetteColor)
                                drawFinial(
                                    finialCenterX = towerCenterX,
                                    finialBaseY = capBaseY - capHeightPx + 1f * yScale,
                                    finialHeight = 7f * yScale,
                                    finialRadius = 0.9f * xScale,
                                )
                            }

                            fun drawNeedle(centerOffset: Float, baseOffset: Float, height: Float) {
                                val needleCenterX = centerX + centerOffset * xScale
                                val needleBaseY = baseY + baseOffset * yScale
                                val needlePath = Path().apply {
                                    moveTo(needleCenterX - 2.4f * xScale, needleBaseY)
                                    lineTo(needleCenterX, needleBaseY - height * yScale)
                                    lineTo(needleCenterX + 2.4f * xScale, needleBaseY)
                                    close()
                                }
                                drawPath(needlePath, silhouetteColor)
                                drawFinial(needleCenterX, needleBaseY - height * yScale, 4.5f * yScale, 0.6f * xScale)
                            }

                            drawRectangle(-108f, 6f, 108f, 12f)
                            drawRectangle(-100f, -3f, 100f, 7f)
                            drawRectangle(-82f, -16f, -50f, 1f)
                            drawRectangle(-43f, -20f, 43f, 1f)
                            drawRectangle(50f, -16f, 82f, 1f)
                            drawRectangle(-99f, -25f, -87f, 1f)
                            drawRectangle(87f, -25f, 99f, 1f)

                            drawMinaret(-88f, 74f, 8f, listOf(0.35f, 0.66f))
                            drawMinaret(-63f, 58f, 6.5f, listOf(0.50f))
                            drawMinaret(63f, 58f, 6.5f, listOf(0.50f))
                            drawMinaret(88f, 74f, 8f, listOf(0.35f, 0.66f))

                            drawDome(-73f, -16f, 14f, 29f, 7f)
                            drawDome(-35f, -18f, 22f, 45f, 8f)
                            drawDome(0f, -19f, 31f, 62f, 10f)
                            drawDome(35f, -18f, 22f, 45f, 8f)
                            drawDome(73f, -16f, 14f, 29f, 7f)

                            drawNeedle(-18f, -21f, 16f)
                            drawNeedle(18f, -21f, 16f)
                            drawNeedle(-51f, -19f, 12f)
                            drawNeedle(51f, -19f, 12f)
                            listOf(-82f, -62f, -40f, -20f, 0f, 20f, 40f, 62f, 82f).forEach { offset ->
                                drawArchedOpening(offset, 6f, 5.6f, 9f)
                            }
                            listOf(-35f, 0f, 35f).forEach { offset ->
                                drawArchedOpening(offset, -3f, 7.2f, 12f)
                            }
                            drawLine(
                                color = detailColor,
                                start = Offset(centerX - 96f * xScale, baseY - 4f * yScale),
                                end = Offset(centerX + 96f * xScale, baseY - 4f * yScale),
                                strokeWidth = 0.9f * yScale,
                            )
                            drawLine(
                                color = detailColor,
                                start = Offset(centerX - 84f * xScale, baseY - 16f * yScale),
                                end = Offset(centerX + 84f * xScale, baseY - 16f * yScale),
                                strokeWidth = 0.8f * yScale,
                            )
                        }
                        drawRect(color = deepNight.copy(alpha = 0.96f), topLeft = Offset.Zero, size = size)
                        drawPath(
                            path = daylightField,
                            brush = Brush.verticalGradient(
                                colors = listOf(Color(0xFFCFF5FF), skyBlue, Color(0xFF88D5F1)),
                                startY = 0f,
                                endY = sunsetPoint.y + 18f * scaleY,
                            ),
                        )
                        drawHorizonSun(sunriseSunCenter, Color(0xFFFFB84D))
                        drawHorizonSun(sunsetSunCenter, Color(0xFFFF7A3D))
                        drawCloud(24f * scaleX, 54f * scaleY, 0.70f, alpha = 0.24f)
                        drawCloud(size.width - 120f * scaleX, 70f * scaleY, 0.64f, alpha = 0.20f)
                        drawPath(lowerNightField, Color(0xFF12375D))
                        drawNightMosqueSilhouette(
                            centerX = circleCenter.x - circleRadiusPx * 0.96f,
                            baseY = circleBottomPx + 82f * scaleY,
                            silhouetteScale = 0.78f,
                            alpha = 0.18f,
                        )
                        drawNightMosqueSilhouette(
                            centerX = circleCenter.x + circleRadiusPx * 0.96f,
                            baseY = circleBottomPx + 84f * scaleY,
                            silhouetteScale = 0.78f,
                            alpha = 0.17f,
                        )
                        drawNightMosqueSilhouette(
                            centerX = circleCenter.x,
                            baseY = size.height - 16f * scaleY,
                            silhouetteScale = 1.04f,
                            alpha = 0.12f,
                        )
                        listOf(
                            Offset(32f * scaleX, 210f * scaleY) to 1.5f,
                            Offset(82f * scaleX, 258f * scaleY) to 1.0f,
                            Offset(132f * scaleX, 350f * scaleY) to 1.3f,
                            Offset(54f * scaleX, 384f * scaleY) to 1.1f,
                            Offset(118f * scaleX, 416f * scaleY) to 1.2f,
                            Offset(190f * scaleX, 400f * scaleY) to 0.9f,
                            Offset(236f * scaleX, 378f * scaleY) to 1.0f,
                            Offset(size.width - 42f * scaleX, 238f * scaleY) to 1.4f,
                            Offset(size.width - 92f * scaleX, 318f * scaleY) to 1.1f,
                            Offset(size.width - 160f * scaleX, 376f * scaleY) to 1.2f,
                            Offset(size.width - 76f * scaleX, 398f * scaleY) to 0.9f,
                            Offset(size.width - 128f * scaleX, 424f * scaleY) to 1.1f,
                        ).forEach { (star, radius) -> drawStar(star, radius * scaleX, alpha = 0.55f) }
                        drawPath(
                            path = twilightField,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF12375D).copy(alpha = 0.0f),
                                    Color(0xFF3F86A8).copy(alpha = 0.48f),
                                    Color(0xFFBFEFFF).copy(alpha = 0.68f),
                                ),
                                start = fajrBoundaryEnd,
                                end = sunrisePoint,
                            ),
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.34f),
                            start = Offset(0f, sunrisePoint.y),
                            end = sunrisePoint,
                            strokeWidth = 1.6f * scaleY,
                        )
                        drawLine(
                            color = Color.White.copy(alpha = 0.34f),
                            start = sunsetPoint,
                            end = sunsetBoundaryEnd,
                            strokeWidth = 1.6f * scaleY,
                        )
                        clipPath(circlePath) {
                            drawRect(
                                brush = Brush.verticalGradient(listOf(Color(0xFF173F68), deepNight, Color(0xFF09223C))),
                                topLeft = Offset(circleLeftPx, circleTopPx),
                                size = Size(circleRadiusPx * 2f, circleRadiusPx * 2f),
                            )
                            val secondNightSector = Path().apply {
                                moveTo(circleRightPx, circleCenter.y)
                                lineTo(circleCenter.x, circleBottomPx)
                                lineTo(nightApex.x, nightApex.y)
                                close()
                            }
                            val thirdNightSector = Path().apply {
                                moveTo(circleCenter.x, circleBottomPx)
                                lineTo(sunrisePoint.x, sunrisePoint.y)
                                lineTo(nightApex.x, nightApex.y)
                                close()
                            }
                            drawPath(secondNightSector, Color(0xFF082A4A).copy(alpha = 0.22f))
                            drawPath(thirdNightSector, Color(0xFF0F5F83).copy(alpha = 0.18f))
                            drawPath(
                                path = twilightPath,
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFBFEFFF).copy(alpha = 0.66f),
                                        Color(0xFF3F86A8).copy(alpha = 0.44f),
                                        Color(0xFF12375D).copy(alpha = 0.0f),
                                    ),
                                    center = sunrisePoint,
                                    radius = circleRadiusPx * 0.82f,
                                ),
                            )
                            drawPath(dayPath, Brush.verticalGradient(listOf(Color(0xFFCFF5FF), skyBlue, Color(0xFF75C9EA))))
                            drawCloud(circleCenter.x - circleRadiusPx * 0.72f, circleTopPx + circleRadiusPx * 0.25f, 0.68f)
                            drawCloud(circleCenter.x + circleRadiusPx * 0.30f, circleTopPx + circleRadiusPx * 0.23f, 0.72f)

                            // The lines from the centre stop at the two big words instead of crossing them.
                            fun wordPx(word: Rect?) = word?.inflate(2f)?.let { Rect(it.left * scaleX, it.top * scaleY, it.right * scaleX, it.bottom * scaleY) } ?: Rect.Zero
                            val dayWordPx = wordPx(layout.dayWord)
                            val nightWordPx = wordPx(layout.nightWord)
                            clipRect(dayWordPx.left, dayWordPx.top, dayWordPx.right, dayWordPx.bottom, ClipOp.Difference) {
                                clipRect(nightWordPx.left, nightWordPx.top, nightWordPx.right, nightWordPx.bottom, ClipOp.Difference) {
                                    listOf(sunrisePoint, sunsetPoint).forEach { daylightBoundaryEnd ->
                                        drawLine(
                                            color = Color.White.copy(alpha = 0.74f),
                                            start = circleCenter,
                                            end = daylightBoundaryEnd,
                                            strokeWidth = 2.6f * scaleY,
                                        )
                                    }
                                    listOf(secondThirdPoint, finalThirdPoint).forEach { separatorEnd ->
                                        drawLine(Color(0xFF071D31).copy(alpha = 0.24f), start = circleCenter, end = separatorEnd, strokeWidth = 1.4f * scaleY)
                                        drawLine(GoldLight.copy(alpha = 0.20f), start = circleCenter, end = separatorEnd, strokeWidth = 0.75f * scaleY)
                                    }
                                }
                            }

                            listOf(
                                Offset(circleCenter.x - circleRadiusPx * 0.63f, circleCenter.y + nightHeightPx * 0.33f) to 2.2f,
                                Offset(circleCenter.x - circleRadiusPx * 0.53f, circleCenter.y + nightHeightPx * 0.48f) to 1.6f,
                                Offset(circleCenter.x - circleRadiusPx * 0.40f, circleCenter.y + nightHeightPx * 0.61f) to 1.4f,
                                Offset(circleCenter.x - circleRadiusPx * 0.26f, circleCenter.y + nightHeightPx * 0.44f) to 1.4f,
                                Offset(circleCenter.x + circleRadiusPx * 0.20f, circleCenter.y + nightHeightPx * 0.50f) to 1.3f,
                                Offset(circleCenter.x + circleRadiusPx * 0.42f, circleCenter.y + nightHeightPx * 0.34f) to 1.8f,
                                Offset(circleCenter.x + circleRadiusPx * 0.58f, circleCenter.y + nightHeightPx * 0.55f) to 1.5f,
                                Offset(circleCenter.x + circleRadiusPx * 0.70f, circleCenter.y + nightHeightPx * 0.42f) to 1.2f,
                            ).forEach { (star, radius) -> drawStar(star, radius * scaleX) }

                            val mosqueBaseY = circleCenter.y + nightHeightPx * 0.78f
                            drawNightMosqueSilhouette(
                                centerX = circleCenter.x,
                                baseY = mosqueBaseY,
                                silhouetteScale = 0.64f,
                                alpha = 0.62f,
                                color = Color(0xFF05192B),
                            )

                        }
                        midnightMoonCenter?.let { drawCrescent(it) }
                        noonSunCenter?.let { drawHorizonSun(it, Color(0xFFFFB84D)) }
                        drawCircle(
                            color = Color.White.copy(alpha = 0.76f),
                            radius = circleRadiusPx,
                            center = circleCenter,
                            style = Stroke(width = 3f * scaleX),
                        )
                        drawCircle(
                            color = Color(0xFF6F8491).copy(alpha = 0.52f),
                            radius = circleRadiusPx + 2f * scaleX,
                            center = circleCenter,
                            style = Stroke(width = 1.4f * scaleX),
                        )
                        if (data.showCurrentTime) {
                            drawArc(
                                color = Color(0xFFFFC247),
                                startAngle = activeSegmentStartAngle,
                                sweepAngle = activeSegmentSweep,
                                useCenter = false,
                                topLeft = Offset(circleLeftPx, circleTopPx),
                                size = Size(circleRadiusPx * 2f, circleRadiusPx * 2f),
                                style = Stroke(width = 3f * scaleX),
                            )
                        }
                        // The same lines the layout kept clear of every other text, dot and line.
                        timelineAlarmPlacements.forEach { placement ->
                            placement.leader?.let { (from, to) ->
                                val onDaySky = layout.onDaySky(Offset(placement.labelCenterX, placement.labelCenterY))
                                drawLine(
                                    color = if (onDaySky) {
                                        Color(0xFF173F68).copy(alpha = 0.45f)
                                    } else {
                                        Color(0xFFFFC247).copy(alpha = if (placement.nearNonAlarmMarker) 0.72f else 0.46f)
                                    },
                                    start = Offset(from.x * scaleX, from.y * scaleY),
                                    end = Offset(to.x * scaleX, to.y * scaleY),
                                    strokeWidth = 1.15f * scaleX,
                                )
                            }
                        }
                        leaderLines.forEach { (ends, onDaySky) ->
                            drawLine(
                                color = if (onDaySky) Color(0xFF22364F).copy(alpha = 0.45f) else Color.White.copy(alpha = 0.45f),
                                start = Offset(ends.first.x * scaleX, ends.first.y * scaleY),
                                end = Offset(ends.second.x * scaleX, ends.second.y * scaleY),
                                strokeWidth = 1.1f * scaleX,
                            )
                        }
                        fun drawTimelineMarker(center: Offset) {
                            drawCircle(Color.White.copy(alpha = 0.96f), radius = 5.2f * scaleX, center = center)
                            drawCircle(Color(0xFF173F68), radius = 2.4f * scaleX, center = center)
                        }
                        fun drawPrayerTimelineMarker(center: Offset) {
                            drawCircle(Color.White.copy(alpha = 0.96f), radius = 5.0f * scaleX, center = center)
                            drawCircle(GreenPrimaryDark.copy(alpha = 0.94f), radius = 3.0f * scaleX, center = center)
                            drawCircle(GoldLight.copy(alpha = 0.72f), radius = 1.05f * scaleX, center = center)
                        }
                        fun drawAlarmTimelineMarker(center: Offset, cluster: Boolean, sub: Boolean, nearNonAlarmMarker: Boolean) {
                            val amber = Color(0xFFFFC247).copy(alpha = if (sub && !cluster) 0.9f else 1f)
                            if (cluster) {
                                drawCircle(amber.copy(alpha = 0.52f), radius = 6.9f * scaleX, center = center, style = Stroke(width = 1.15f * scaleX))
                                drawCircle(Color.White.copy(alpha = 0.96f), radius = 5.4f * scaleX, center = center)
                                drawCircle(amber, radius = 3.0f * scaleX, center = center)
                                drawCircle(Color(0xFF061D32), radius = 1.1f * scaleX, center = center)
                            } else {
                                if (nearNonAlarmMarker) {
                                    drawCircle(amber.copy(alpha = 0.68f), radius = 6.9f * scaleX, center = center, style = Stroke(width = 1.1f * scaleX))
                                }
                                drawCircle(Color.White.copy(alpha = 0.96f), radius = 5.2f * scaleX, center = center)
                                drawCircle(amber, radius = 2.8f * scaleX, center = center)
                            }
                        }
                        // Alarm dots are drawn BEFORE non-alarm markers so prayer/event dots stay on top (req 2).
                        timelineAlarmPlacements.forEach { placement ->
                            drawAlarmTimelineMarker(
                                center = Offset(placement.iconCenterX * scaleX, placement.iconCenterY * scaleY),
                                cluster = placement.isCluster,
                                sub = placement.hasSubAlarm,
                                nearNonAlarmMarker = placement.nearNonAlarmMarker,
                            )
                        }
                        val timelineEventPoints = listOf(sunrisePoint, sunsetPoint, secondThirdPoint, finalThirdPoint, fajrPoint)
                        timelineEventPoints.forEach(::drawTimelineMarker)
                        timelinePrayerAngles.forEach { angleDegrees ->
                            val radians = angleDegrees * PI / 180.0
                            drawPrayerTimelineMarker(
                                Offset(
                                    x = (circleCenterX + circleRadius * cos(radians).toFloat()) * scaleX,
                                    y = (circleCenterY + circleRadius * sin(radians).toFloat()) * scaleY,
                                ),
                            )
                        }
                        if (data.showCurrentTime && data.sunAltitudeDeg != null) {
                            val radians = currentAngleDegrees * PI / 180.0
                            val sunCenter = Offset(
                                x = (circleCenterX + circleRadius * cos(radians).toFloat()) * scaleX,
                                y = (circleCenterY + circleRadius * sin(radians).toFloat()) * scaleY,
                            )
                            repeat(8) { ray ->
                                val rayRadians = ray * PI / 4.0
                                val unit = Offset(cos(rayRadians).toFloat(), sin(rayRadians).toFloat())
                                drawLine(
                                    color = Color(0xFFFFC247),
                                    start = sunCenter + unit * (6.5f * scaleX),
                                    end = sunCenter + unit * (8.5f * scaleX),
                                    strokeWidth = 1.3f * scaleX,
                                )
                            }
                            drawCircle(Color.White, radius = 5.2f * scaleX, center = sunCenter)
                            drawCircle(Color(0xFFFFC247), radius = 3.5f * scaleX, center = sunCenter)
                        }
                    }

                    val readByLabel = timelineAlarmPlacements.filter { it.showsLabel }.flatMap { it.labelAlarms }.map { it.id }.toSet()

                    timelineAlarmPlacements.forEach { placement ->
                        key(placement.id) {
                            val accessibilityDescription = timelineAlarmAccessibilityDescription(placement.alarms)
                            val actionLabel = timelineAlarmActionLabel(placement.alarms)
                            // Transparent hit target over the alarm dot (the dot itself is drawn in the
                            // Canvas below the non-alarm markers). Single → edit directly; cluster → sheet.
                            // A dot whose time is written next to it is read once, with that time.
                            // Its target is as large as it can be without reaching a neighbouring alarm dot's, so a
                            // tap between two dots never opens the wrong alarm.
                            val nearestDot = timelineAlarmPlacements.filter { it !== placement }
                                .minOfOrNull { other -> hypot(other.iconCenterX - placement.iconCenterX, other.iconCenterY - placement.iconCenterY) }
                            val touchSize = (nearestDot ?: 30f).coerceIn(12f, 30f)
                            val readByItsTime = placement.alarms.all { it.id in readByLabel }
                            Box(
                                modifier = Modifier
                                    .offset(x = (placement.iconCenterX - touchSize / 2f).dp, y = (placement.iconCenterY - touchSize / 2f).dp)
                                    .size(touchSize.dp)
                                    .testTag("prayer_original_alarm")
                                    // Cleared before the click: a dot TalkBack reads through its time is not a second,
                                    // unnamed button. A finger still opens it.
                                    .then(if (readByItsTime) Modifier.clearAndSetSemantics {} else Modifier)
                                    .clip(CircleShape)
                                    .clickable(role = Role.Button, onClickLabel = actionLabel) {
                                        if (placement.isCluster) {
                                            selectedAlarmCluster = placement.alarms
                                        } else {
                                            onEditWakeAlarm(placement.alarms.first().wakeAlarmId)
                                        }
                                    }
                                    .then(
                                        if (readByItsTime) {
                                            Modifier
                                        } else {
                                            Modifier.semantics {
                                                contentDescription = accessibilityDescription
                                                traversalIndex = readOrder(placement.alarms.first().minuteOfDay)
                                            }
                                        },
                                    ),
                            )
                        }
                    }

                    // The line at the top: the sun's altitude now, or, on another day, which day the dial shows.
                    val altitude = data.sunAltitudeDeg.takeIf { data.showCurrentTime }
                    if (altitude != null) {
                        // A real minus sign (U+2212), kept left of the digits by the isolate around it.
                        val formattedAngle = String.format(Locale.US, "%.1f°", if (abs(altitude) < 0.05) 0.0 else altitude).replace('-', '−')
                        val altitudeDescription = stringResource(R.string.prayer_day_overview_sun_angle_description, "⁦$formattedAngle⁩")
                        Text(
                            text = "${stringResource(R.string.prayer_day_overview_sun_angle)}  ⁦$formattedAngle⁩",
                            modifier = Modifier.offset(x = 12.dp, y = 14.dp).width((panelWidth - 24f).dp)
                                .testTag("prayer_day_sun_angle")
                                .semantics {
                                    contentDescription = altitudeDescription
                                    traversalIndex = -1f
                                },
                            color = Color(0xFF22364F),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            style = TextStyle(fontFamily = AppFontFamily, textDirection = TextDirection.Rtl).merge(SunAngleStyle),
                        )
                    } else {
                        Text(
                            text = data.selectedDateLabel,
                            modifier = Modifier.offset(x = 12.dp, y = 14.dp).width((panelWidth - 24f).dp)
                                .testTag("prayer_day_date")
                                .semantics { traversalIndex = -1f },
                            color = Color(0xFF22364F),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            style = TextStyle(fontFamily = AppFontFamily, textDirection = TextDirection.Rtl).merge(SunAngleStyle),
                        )
                    }

                    // Each text sits in the box the layout measured and placed for it; one spare dp on each side
                    // keeps a rounding step from cutting its last letter.
                    fun Modifier.placedAt(box: Rect): Modifier = offset(x = (box.left - 1f).dp, y = box.top.dp).width((box.width + 2f).dp)
                    // The big words and the wedge's name only name what the picture shows: TalkBack skips them.
                    layout.dayWord?.let { word ->
                        Text(
                            text = "النهار",
                            modifier = Modifier.placedAt(word).clearAndSetSemantics {},
                            color = Color(0xFF22364F),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            style = LocalTextStyle.current.merge(BigWordStyle),
                        )
                    }
                    layout.nightWord?.let { word ->
                        Text(
                            text = "الليل",
                            modifier = Modifier.placedAt(word).clearAndSetSemantics {},
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            style = LocalTextStyle.current.merge(BigWordStyle),
                        )
                    }
                    layout.twilight?.let { wedgeName ->
                        Text(
                            text = DIAL_FAJR_TIME,
                            modifier = Modifier.placedAt(wedgeName).clearAndSetSemantics {},
                            color = Color.White,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            // The wedge brightens towards sunrise: a dark halo keeps the white name readable on it.
                            style = LocalTextStyle.current.merge(TwilightStyle).merge(TextStyle(shadow = NightTextHalo)),
                        )
                    }
                    // Each text takes the colour that reads on the sky it sits on: navy on day, white on night.
                    @Composable
                    fun horizonTime(label: String, time: String, placed: DialLabel, minuteOfDay: Int) {
                        if (placed.inList) return
                        DayNightHorizonTime(
                            label = label,
                            time = time,
                            dark = layout.onDaySky(placed.rect.center),
                            modifier = Modifier.placedAt(placed.rect).semantics { traversalIndex = readOrder(minuteOfDay) },
                        )
                    }
                    horizonTime(DIAL_SUNRISE, data.sunriseTime, layout.sunrise, data.sunriseMinuteOfDay)
                    val maghrib = data.timelinePrayers.firstOrNull { it.prayer == Prayer.MAGHRIB }
                    horizonTime(DIAL_MAGHRIB, maghrib?.time ?: data.thirds[0].startTime, layout.maghrib, maghrib?.minuteOfDay ?: data.thirds[0].startMinuteOfDay)
                    val fajr = data.timelinePrayers.firstOrNull { it.prayer == Prayer.FAJR }
                    horizonTime(DIAL_FAJR, fajr?.time ?: data.thirds[2].endTime, layout.fajr, fajrMinute)
                    horizonTime(DIAL_FINAL_THIRD, data.thirds[2].startTime, layout.finalThird, data.thirds[2].startMinuteOfDay)
                    horizonTime(DIAL_SECOND_THIRD, data.thirds[1].startTime, layout.secondThird, data.thirds[1].startMinuteOfDay)
                    // A time stands for one dot, or, as a range, for a run of dots too close to show each time.
                    timelineAlarmPlacements.filter { it.showsLabel }.forEach { placement ->
                        key("label_${placement.id}") {
                            val accessibilityDescription = timelineAlarmAccessibilityDescription(placement.labelAlarms)
                            val actionLabel = timelineAlarmActionLabel(placement.labelAlarms)
                            val labelBox = Rect(placement.labelX, placement.labelY, placement.labelX + placement.labelWidth, placement.labelY + placement.labelHeight)
                            val labelModifier = Modifier
                                .placedAt(labelBox)
                                .then(
                                    if (placement.labelAlarms.size > 1) {
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(role = Role.Button, onClickLabel = actionLabel) { selectedAlarmCluster = placement.labelAlarms }
                                    } else {
                                        Modifier
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable(role = Role.Button, onClickLabel = actionLabel) { onEditWakeAlarm(placement.labelAlarms.first().wakeAlarmId) }
                                    },
                                )
                                .semantics {
                                    contentDescription = accessibilityDescription
                                    traversalIndex = readOrder(placement.labelAlarms.first().minuteOfDay)
                                }
                            DayNightHorizonAlarmTime(
                                time = placement.time,
                                onDaySky = layout.onDaySky(labelBox.center),
                                modifier = labelModifier,
                            )
                        }
                    }
                    layout.prayerLabels.filterNot { (_, label) -> label.inList }.forEach { (prayer, label) ->
                        key(prayer.prayer.name) {
                            DayNightHorizonPrayerTime(
                                label = prayer.title,
                                time = prayer.time,
                                dark = layout.onDaySky(label.rect.center),
                                modifier = Modifier.placedAt(label.rect).semantics { traversalIndex = readOrder(prayer.minuteOfDay) },
                            )
                        }
                    }
                    }
                    // The names the dial had no room for (the largest text sizes): listed under it, in the day's order.
                    val maghribPrayer = data.timelinePrayers.firstOrNull { it.prayer == Prayer.MAGHRIB }
                    val fajrPrayer = data.timelinePrayers.firstOrNull { it.prayer == Prayer.FAJR }
                    val unplaced = buildList {
                        if (layout.sunrise.inList) add(DialListEntry(DIAL_SUNRISE, data.sunriseTime, data.sunriseMinuteOfDay))
                        if (layout.maghrib.inList) {
                            add(DialListEntry(DIAL_MAGHRIB, maghribPrayer?.time ?: data.thirds[0].startTime, maghribPrayer?.minuteOfDay ?: data.thirds[0].startMinuteOfDay))
                        }
                        if (layout.fajr.inList) add(DialListEntry(DIAL_FAJR, fajrPrayer?.time ?: data.thirds[2].endTime, fajrMinute))
                        if (layout.secondThird.inList) add(DialListEntry(DIAL_SECOND_THIRD, data.thirds[1].startTime, data.thirds[1].startMinuteOfDay))
                        if (layout.finalThird.inList) add(DialListEntry(DIAL_FINAL_THIRD, data.thirds[2].startTime, data.thirds[2].startMinuteOfDay))
                        layout.prayerLabels.filter { (_, label) -> label.inList }.forEach { (prayer, _) ->
                            add(DialListEntry(prayer.title, prayer.time, prayer.minuteOfDay))
                        }
                    }.sortedBy { readOrder(it.minuteOfDay) }
                    if (unplaced.isNotEmpty()) {
                        DialUnplacedList(unplaced, readOrder = ::readOrder)
                    }
                    }
                }
            }
        }
    }
}
}

/** A soft dark halo round light text on the night sky, which brightens towards sunrise. */
private val NightTextHalo = Shadow(color = Color(0xFF061D32).copy(alpha = 0.9f), offset = Offset.Zero, blurRadius = 4f)

// The dial's text styles: the texts below are drawn with them and the layout measures them, so the two agree.
// Names were 8 and 9 sp, too small to read at a glance; the layout makes room for these sizes.
private val HorizonNameStyle = TextStyle(fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
private val HorizonTimeStyle = TextStyle(fontSize = 12.sp, lineHeight = 13.sp, fontWeight = FontWeight.Black)
private val PrayerNameStyle = TextStyle(fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.Bold)
private val PrayerTimeStyle = TextStyle(fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Black)
// Alarm times stay at 10 sp: a run of close alarms then still fits as one time per dot.
private val AlarmTimeStyle = TextStyle(fontSize = 10.sp, lineHeight = 11.sp, fontWeight = FontWeight.Black)
private val TwilightStyle = TextStyle(fontSize = 11.sp, lineHeight = 12.sp, fontWeight = FontWeight.Bold)
private val BigWordStyle = TextStyle(fontSize = 24.sp, lineHeight = 26.sp, fontWeight = FontWeight.Black)
private val SunAngleStyle = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)

/** The dial's text sizes (dp) at the phone's font and font size. */
@Composable
internal fun rememberDialTextSizes(): DialTextSizes {
    val textMeasurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val baseStyle = LocalTextStyle.current
    val sunAngleLabel = stringResource(R.string.prayer_day_overview_sun_angle)
    return remember(textMeasurer, density, baseStyle, sunAngleLabel) {
        fun line(text: String, style: TextStyle): DialSize {
            val size = textMeasurer.measure(text, style = style, softWrap = false, maxLines = 1, density = density).size
            return with(density) { DialSize(size.width.toDp().value, size.height.toDp().value) }
        }
        fun twoLines(top: DialSize, bottom: DialSize, gap: Float) =
            DialSize(maxOf(top.width, bottom.width), top.height + gap + bottom.height)
        val horizonName = baseStyle.merge(HorizonNameStyle)
        val horizonTime = baseStyle.merge(HorizonTimeStyle)
        val prayerName = baseStyle.merge(PrayerNameStyle)
        val prayerTime = baseStyle.merge(PrayerTimeStyle)
        val alarmTime = TextStyle(fontFamily = AppFontFamily).merge(AlarmTimeStyle)
        val bigWord = baseStyle.merge(BigWordStyle)
        DialTextSizes(
            horizon = { name, time -> twoLines(line(name, horizonName), line(time, horizonTime), 1f) },
            prayer = { name, time -> twoLines(line(name, prayerName), line(time, prayerTime), 0f) },
            alarmTime = { time -> line(time, alarmTime) },
            twilight = line(DIAL_FAJR_TIME, baseStyle.merge(TwilightStyle)),
            dayWord = line("النهار", bigWord),
            nightWord = line("الليل", bigWord),
            // Measured with its widest value, so it never grows past the space kept for it.
            sunAngle = line("$sunAngleLabel  ⁦−88.8°⁩", TextStyle(fontFamily = AppFontFamily).merge(SunAngleStyle)),
            dateLine = { date -> line(date, TextStyle(fontFamily = AppFontFamily).merge(SunAngleStyle)) },
        )
    }
}

@Composable
private fun timelineAlarmAccessibilityDescription(alarms: List<NightTimelineAlarm>): String {
    val mainAlarm = stringResource(R.string.prayer_day_overview_main_alarm)
    val extraAlarm = stringResource(R.string.prayer_day_overview_extra_alarm)
    val described = alarms.joinToString("؛ ") { alarm ->
        "${if (alarm.isSubAlarm) extraAlarm else mainAlarm}، ${alarm.title}، ${alarm.time}"
    }
    return if (alarms.size > 1) {
        "${stringResource(R.string.prayer_day_overview_alarm_count, alarms.size)}. $described"
    } else described
}

@Composable
private fun timelineAlarmActionLabel(alarms: List<NightTimelineAlarm>): String? =
    if (alarms.size > 1) null else stringResource(
        R.string.prayer_day_overview_edit_named_alarm,
        alarms.first().title,
        alarms.first().time,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimelineAlarmClusterSheet(
    alarms: List<NightTimelineAlarm>,
    onDismiss: () -> Unit,
    onEditAlarm: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sortedAlarms = remember(alarms) { alarms.sortedBy { alarm -> alarm.minuteOfDay } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = pluralStringResource(
                    R.plurals.prayer_day_alarm_cluster_title,
                    sortedAlarms.size,
                    sortedAlarms.size,
                ),
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.Bold,
                color = PrayerNameColor,
            )
            sortedAlarms.forEach { alarm ->
                TimelineAlarmClusterRow(
                    alarm = alarm,
                    onEditAlarm = onEditAlarm,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun TimelineAlarmClusterRow(
    alarm: NightTimelineAlarm,
    onEditAlarm: (String) -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable { onEditAlarm(alarm.wakeAlarmId) }
            .background(if (alarm.isSubAlarm) GoldLight.copy(alpha = 0.12f) else GreenPrimary.copy(alpha = 0.08f))
            .border(BorderStroke(1.dp, if (alarm.isSubAlarm) Gold.copy(alpha = 0.22f) else GreenPrimary.copy(alpha = 0.24f)), shape)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = alarm.time,
                fontSize = 18.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Black,
                color = PrayerNameColor,
                maxLines = 1,
            )
            Text(
                text = alarm.title,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                color = TextMuted,
                maxLines = 1,
            )
        }
        TextButton(onClick = { onEditAlarm(alarm.wakeAlarmId) }) {
            Text(text = "تعديل")
        }
    }
}

/** A name and time the dial had no free spot for. */
private data class DialListEntry(val label: String, val time: String, val minuteOfDay: Int)

private val DialListNameStyle = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Bold)
private val DialListTimeStyle = TextStyle(fontSize = 14.sp, lineHeight = 18.sp, fontWeight = FontWeight.Black)

/**
 * At the largest text sizes, the names and times the dial had no free spot for: under the dial, each name with its
 * time, in the day's order. TalkBack reads each at its place in the day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialUnplacedList(entries: List<DialListEntry>, readOrder: (Int) -> Float) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 2.dp)
                .testTag("prayer_day_unplaced"),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            entries.forEach { entry ->
                Row(
                    // Read as one: its name, then its time.
                    modifier = Modifier.semantics(mergeDescendants = true) { traversalIndex = readOrder(entry.minuteOfDay) },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = entry.label, color = Color(0xFF22364F), maxLines = 1, style = LocalTextStyle.current.merge(DialListNameStyle))
                    Text(text = entry.time, color = Color(0xFF22364F), maxLines = 1, style = LocalTextStyle.current.merge(DialListTimeStyle))
                }
            }
        }
    }
}

@Composable
private fun DayNightHorizonTime(
    label: String,
    time: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        // Read as one: its name, then its time.
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            text = label,
            color = if (dark) Color(0xFF22364F) else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = LocalTextStyle.current.merge(HorizonNameStyle).merge(TextStyle(shadow = if (dark) null else NightTextHalo)),
        )
        Text(
            text = time,
            color = if (dark) Color(0xFF22364F) else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = LocalTextStyle.current.merge(HorizonTimeStyle).merge(TextStyle(shadow = if (dark) null else NightTextHalo)),
        )
    }
}

@Composable
private fun DayNightHorizonAlarmTime(
    time: String,
    onDaySky: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Amber with a dark shadow reads on the night; on the day sky the time is navy, as the prayers there are.
        Text(
            text = time,
            color = if (onDaySky) Color(0xFF173F68) else Color(0xFFFFD479),
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = TextStyle(
                fontFamily = AppFontFamily,
                shadow = if (onDaySky) null else NightTextHalo,
            ).merge(AlarmTimeStyle),
        )
    }
}

@Composable
private fun DayNightHorizonPrayerTime(
    label: String,
    time: String,
    dark: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        // Read as one: its name, then its time.
        modifier = modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = label,
            color = if (dark) Color(0xFF173F68) else Color.White.copy(alpha = 0.92f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = LocalTextStyle.current.merge(PrayerNameStyle).merge(TextStyle(shadow = if (dark) null else NightTextHalo)),
        )
        Text(
            text = time,
            modifier = Modifier.offset(y = (-2).dp),
            color = if (dark) Color(0xFF173F68) else Color.White,
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = LocalTextStyle.current.merge(PrayerTimeStyle).merge(TextStyle(shadow = if (dark) null else NightTextHalo)),
        )
    }
}
