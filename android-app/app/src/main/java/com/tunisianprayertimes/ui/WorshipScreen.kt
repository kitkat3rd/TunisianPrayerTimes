package com.tunisianprayertimes.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
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
import com.tunisianprayertimes.ui.theme.Gold
import com.tunisianprayertimes.ui.theme.GoldLight
import com.tunisianprayertimes.ui.theme.GreenPrimary
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark
import com.tunisianprayertimes.ui.theme.PrayerNameColor
import com.tunisianprayertimes.ui.theme.TextMuted

/** Whether the Qibla is open in «عباداتي»: closed at first, then as the reader last left it. */
private object WorshipQiblaPrefs {
    private const val FILE = "worship_tab"
    private const val KEY = "qibla_expanded"
    fun expanded(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setExpanded(context: Context, expanded: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(KEY, expanded).apply()
    }
}

/**
 * The «عباداتي» tab: the Qibla, folded under its own card until opened, then worship beyond the five prayers:
 * voluntary prayer times, the sunnah fasts, then the remembrance of death. Open, the Qibla card is shown whole, as it
 * always was.
 */
@Composable
internal fun WorshipPage(placeRevision: Int) {
    val context = LocalContext.current
    // Opened by a reminder's notification: its sheet comes up, without the location dialog over it.
    val openedForFasting = remember { FastingRequests.sheet || FastingRequests.suhoor || RemembranceRequests.sheet }
    var qiblaExpanded by remember { mutableStateOf(WorshipQiblaPrefs.expanded(context)) }
    // Opened by the reader's own tap, the Qibla may ask for the location, whatever opened the tab.
    var qiblaOpenedByTap by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(
            text = "عباداتي",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = PrayerNameColor,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
        QiblaFoldCard(
            expanded = qiblaExpanded,
            onToggle = {
                qiblaExpanded = !qiblaExpanded
                if (qiblaExpanded) qiblaOpenedByTap = true
                WorshipQiblaPrefs.setExpanded(context, qiblaExpanded)
            },
        )
        // Closed, the card leaves the screen: no compass, no location request.
        AnimatedVisibility(
            visible = qiblaExpanded,
            enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut(),
        ) {
            QiblaCard(placeRevision = placeRevision, askLocation = qiblaOpenedByTap || !openedForFasting)
        }
        WorshipSectionHeader(stringResource(R.string.worship_voluntary_title), R.drawable.ic_prayer_dhuhr_sun)
        VoluntaryPrayerTools(placeRevision = placeRevision)
        WorshipSectionHeader("الصيام", R.drawable.ic_adhkar_moon)
        FastingCard()
        WorshipSectionHeader("ذكر الموت", R.drawable.ic_hourglass)
        DeathRemembranceCard()
    }
}

/**
 * The Qibla's own card, open or closed: a card like the others on the page, so it reads as something to tap, with
 * the Kaaba, what it holds, and an arrow in a round button that turns when it opens.
 */
@Composable
private fun QiblaFoldCard(expanded: Boolean, onToggle: () -> Unit) {
    val arrowTurn by animateFloatAsState(if (expanded) 180f else 0f, label = "qiblaFoldArrow")
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(Color.White)
            .border(BorderStroke(1.dp, Gold.copy(alpha = 0.25f)), shape)
            .clickable(role = Role.Button, onClickLabel = if (expanded) "طيّ القبلة" else "فتح القبلة", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "مفتوح" else "مطويّ" }
            .testTag("worship_section_toggle")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(GoldLight.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_tab_qibla), contentDescription = null, tint = GreenPrimaryDark,
                modifier = Modifier.size(24.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("القبلة", color = PrayerNameColor, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() })
            Text("البوصلة واتجاه الكعبة من مكانك", color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
        }
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(GreenPrimary.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_expand_more), contentDescription = null, tint = GreenPrimary,
                modifier = Modifier.size(24.dp).rotate(arrowTurn))
        }
    }
}

@Composable
private fun WorshipSectionHeader(title: String, icon: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = GreenPrimary, modifier = Modifier.size(20.dp))
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = PrayerNameColor,
            modifier = Modifier.semantics { heading() },
        )
    }
}
