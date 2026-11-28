package com.tunisianprayertimes.access

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.mosque.MosqueBatteryGuidanceNotice
import com.tunisianprayertimes.mosque.MosqueInterruptionNotice
import com.tunisianprayertimes.mosque.rememberMosqueBatteryGuidance
import com.tunisianprayertimes.mosque.rememberMosqueInterruption
import com.tunisianprayertimes.ui.theme.BgCream

@Composable
internal fun AppAccessLayout(
    issues: List<FeatureAccessIssue>,
    mosqueEnabled: Boolean,
    content: @Composable BoxScope.() -> Unit,
) {
    val interruption = rememberMosqueInterruption()
    val showBatteryGuidance = rememberMosqueBatteryGuidance(mosqueEnabled)
    val hasNotice = issues.isNotEmpty() || interruption != null || showBatteryGuidance
    Column(
        Modifier.fillMaxSize().background(BgCream)
            .then(if (hasNotice) Modifier.statusBarsPadding() else Modifier),
    ) {
        if (hasNotice) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = LocalConfiguration.current.screenHeightDp.dp * 0.45f)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FeatureAccessNotice(issues)
                if (interruption != null) MosqueInterruptionNotice(interruption)
                else if (showBatteryGuidance) MosqueBatteryGuidanceNotice()
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), content = content)
    }
}
