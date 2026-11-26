package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark

@Composable
internal fun PrayerHeroSilenceControl(
    silenced: Boolean,
    actionLabel: String,
    onClick: () -> Unit,
    onChooseDuration: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val outline = BorderStroke(1.dp, Color.White.copy(alpha = 0.64f))
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.weight(1f, fill = false).heightIn(min = 48.dp).testTag(TestTags.HERO_PHONE_SILENCE_BUTTON),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GreenPrimaryDark, contentColor = Color.White),
            border = outline,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painterResource(if (silenced) R.drawable.ic_volume_on else R.drawable.ic_bell_off),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(actionLabel, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (!silenced) {
            OutlinedIconButton(
                onClick = onChooseDuration,
                enabled = enabled,
                modifier = Modifier.size(48.dp).testTag(TestTags.HERO_SILENCE_DURATION),
                border = outline,
            ) {
                Icon(
                    painterResource(R.drawable.ic_adhkar_clock),
                    contentDescription = stringResource(R.string.manual_silence_choose_end),
                    tint = Color.White,
                )
            }
        }
    }
}
