package com.tunisianprayertimes.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark

/**
 * The speaker shows the next action. The layout makes room for the 34 dp circle alone and the
 * 48 dp touch target overflows it, so the control never makes its row taller.
 */
@Composable
internal fun PrayerHeroSilenceControl(
    silenced: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val background by animateColorAsState(
        GreenPrimaryDark.copy(alpha = if (silenced) 0.96f else 0.84f),
        label = "heroSilenceBackground",
    )
    val border by animateColorAsState(
        GoldLight.copy(alpha = if (silenced) 0.75f else 0.4f),
        label = "heroSilenceBorder",
    )
    Box(modifier = modifier.size(34.dp), contentAlignment = Alignment.Center) {
        IconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.requiredSize(48.dp).testTag(TestTags.HERO_PHONE_SILENCE_BUTTON),
        ) {
            Box(
                modifier = Modifier.size(34.dp).clip(CircleShape)
                    .background(background).border(1.dp, border, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(if (silenced) R.drawable.ic_volume_on else R.drawable.ic_volume_off),
                    contentDescription = stringResource(if (silenced) R.string.hero_phone_unsilence_title else R.string.hero_phone_silence_title),
                    tint = GoldLight.copy(alpha = if (enabled) 1f else 0.45f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
