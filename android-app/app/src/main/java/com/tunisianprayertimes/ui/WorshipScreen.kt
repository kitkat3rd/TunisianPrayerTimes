package com.tunisianprayertimes.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor

/** Whether the Qibla is open in «عباداتي»: closed at first, then as the reader last left it. */
private object WorshipQiblaPrefs {
    private const val FILE = "worship_tab"
    private const val KEY = "qibla_expanded"
    fun expanded(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setExpanded(context: Context, expanded: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, expanded).apply()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WorshipPage(placeRevision: Int) {
    val context = LocalContext.current
    var qiblaExpanded by remember { mutableStateOf(WorshipQiblaPrefs.expanded(context)) }
    // Opened by the reader's own tap, the Qibla may ask for the location, whatever opened the tab.
    var qiblaOpenedByTap by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.main_tab_worship), fontSize = 28.sp, lineHeight = 40.sp,
                    fontWeight = FontWeight.Bold, color = PrayerNameColor,
                    modifier = Modifier.padding(end = 12.dp).semantics { heading() },
                )
                QiblaToggle(qiblaExpanded) {
                    qiblaExpanded = !qiblaExpanded
                    if (qiblaExpanded) qiblaOpenedByTap = true
                    WorshipQiblaPrefs.setExpanded(context, qiblaExpanded)
                }
            }
            AnimatedVisibility(
                visible = qiblaExpanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
            ) {
                Column(Modifier.padding(top = 8.dp)) {
                    QiblaCard(placeRevision = placeRevision, askLocation = qiblaOpenedByTap)
                }
            }
        }
        VoluntaryPrayerTools(placeRevision = placeRevision)
        FastingCard()
        DeathRemembranceCard()
    }
}

@Composable
private fun QiblaToggle(expanded: Boolean, onToggle: () -> Unit) {
    val arrowTurn by animateFloatAsState(if (expanded) 180f else 0f, label = "qiblaFoldArrow")
    val shape = RoundedCornerShape(50)
    Row(
        modifier = Modifier
            .clip(shape)
            .background(GreenPrimary.copy(alpha = if (expanded) .14f else .07f))
            .clickable(role = Role.Button, onClickLabel = if (expanded) "طيّ القبلة" else "فتح القبلة", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "مفتوح" else "مطويّ" }
            .testTag("worship_section_toggle")
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_tab_qibla), null, Modifier.size(20.dp), tint = GreenPrimaryDark)
        Text("القبلة", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = GreenPrimaryDark,
            modifier = Modifier.semantics { heading() })
        Icon(painterResource(R.drawable.ic_expand_more), null, Modifier.size(16.dp).rotate(arrowTurn), tint = GreenPrimary)
    }
}
