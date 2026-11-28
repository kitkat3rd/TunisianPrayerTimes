package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.tunisianprayertimes.R
import com.tunisianprayertimes.ui.theme.GreenPrimaryDark

@Composable
internal fun AppInfoButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = GreenPrimaryDark) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(
            painter = painterResource(R.drawable.ic_adhkar_info),
            contentDescription = stringResource(R.string.info_about_topic, title),
            modifier = Modifier.size(20.dp),
            tint = tint,
        )
    }
}

@Composable
internal fun AppInfoButton(title: String, text: String, modifier: Modifier = Modifier) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    AppInfoButton(title = title, onClick = { open = true }, modifier = modifier)
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = GreenPrimaryDark,
            textContentColor = MaterialTheme.colorScheme.onSurface,
            title = { Text(title) },
            text = { Text(text, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(R.string.prayer_control_close)) }
            },
        )
    }
}
