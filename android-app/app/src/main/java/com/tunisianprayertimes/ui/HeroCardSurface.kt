package com.tunisianprayertimes.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import kotlin.math.roundToInt

internal val HeroCardShape = RoundedCornerShape(18.dp)
internal val HeroIconRingIconTint = Color(0xFFFFD479)

/**
 * The skyline card behind the Prayer and Alarms tab heroes and the alarm editor's hero, so the
 * three stay one family. [overlay] tints the artwork (e.g. to keep extra lines readable, or to
 * show a silenced/paused state) without touching the layout on top.
 */
@Composable
internal fun HeroCardSurface(
    modifier: Modifier = Modifier,
    overlay: Brush? = null,
    artworkColorFilter: ColorFilter? = null,
    crescentCenter: DpOffset? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val crop = remember(crescentCenter, density.density) {
        crescentCenter?.let { with(density) { CrescentCrop(it.x.toPx(), it.y.toPx()) } }
    }
    Box(
        modifier.fillMaxWidth().clip(HeroCardShape)
            .background(Brush.horizontalGradient(listOf(GreenPrimary, GreenPrimaryDark)))
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.24f)), HeroCardShape),
    ) {
        Image(
            painter = painterResource(R.drawable.prayer_hero_background),
            contentDescription = null,
            contentScale = crop ?: ContentScale.Crop,
            alignment = crop ?: AbsoluteAlignment.CenterLeft,
            colorFilter = artworkColorFilter,
            modifier = Modifier.matchParentSize().testTag("hero_skyline"),
        )
        if (overlay != null) {
            Box(Modifier.matchParentSize().background(overlay))
        }
        content()
    }
}

// The crescent in prayer_hero_background is centered at (10.7%, 20.7%) from the upper left.
// Cover the card on all four sides of that point so the skyline keeps its proportions as text wraps.
private class CrescentCrop(private val x: Float, private val y: Float) : ContentScale, Alignment {
    private val moonX = 0.107f
    private val moonY = 0.207f

    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val scale = maxOf(
            x / (srcSize.width * moonX),
            (dstSize.width - x) / (srcSize.width * (1f - moonX)),
            y / (srcSize.height * moonY),
            (dstSize.height - y) / (srcSize.height * (1f - moonY)),
        )
        return ScaleFactor(scale, scale)
    }

    override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection): IntOffset =
        IntOffset((x - size.width * moonX).roundToInt(), (y - size.height * moonY).roundToInt())
}

/** The gold ring that holds a hero's icon (alarm clock on the Alarms tab, pencil in the editor). */
@Composable
internal fun HeroIconRing(
    @DrawableRes iconRes: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(Gold.copy(alpha = 0.22f))
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.55f)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = HeroIconRingIconTint,
            modifier = Modifier.size(22.dp),
        )
    }
}

@DrawableRes
internal fun prayerHeroIconRes(prayer: Prayer): Int = when (prayer) {
    Prayer.FAJR -> R.drawable.ic_prayer_fajr_twilight
    Prayer.DHUHR -> R.drawable.ic_prayer_dhuhr_sun
    Prayer.ASR -> R.drawable.ic_prayer_asr_afternoon
    Prayer.MAGHRIB -> R.drawable.ic_prayer_maghrib_sunset
    Prayer.ISHA -> R.drawable.ic_prayer_isha_night
    Prayer.JOMOAA, Prayer.AID_FITR, Prayer.AID_ADHA -> R.drawable.ic_adhkar_mosque
}
