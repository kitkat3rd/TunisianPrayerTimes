package com.tunisianprayertimes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.LauncherIcon
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.TextDark

/** The colours of the app's logo, each drawn as the launcher shows it. A tap changes the launcher's icon at once. */
@Composable
internal fun LauncherIconChoice(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(LauncherIcon.current(context)) }
    Row(
        modifier = modifier.fillMaxWidth().selectableGroup().padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LauncherIcon.entries.forEach { icon ->
            val selected = icon == chosen
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .selectable(selected = selected, role = Role.RadioButton) {
                        LauncherIcon.choose(context, icon)
                        chosen = icon
                    }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                LauncherIconPicture(icon, selected)
                Text(
                    text = stringResource(icon.label),
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = TextDark,
                )
            }
        }
    }
}

@Composable
private fun LauncherIconPicture(icon: LauncherIcon, selected: Boolean) {
    val tile = RoundedCornerShape(15.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(68.dp)
            .border(2.dp, if (selected) GreenPrimary else Color.Transparent, RoundedCornerShape(20.dp))
            .padding(5.dp)
            .clip(tile)
            .background(colorResource(R.color.ic_launcher_background))
            .border(1.dp, PrayerSilencePalette.SoftBorder, tile),
    ) {
        // The launcher shows the middle 72 of the drawing's 108, so the drawing is 1.5 times the 58dp tile.
        Image(
            painter = painterResource(icon.foreground),
            contentDescription = null,
            modifier = Modifier.requiredSize(87.dp),
        )
    }
}
