package com.tunisianprayertimes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.adhkar.DhikrCategory
import com.tunisianprayertimes.adhkar.DhikrEntry

/** Lets the user choose which collections (morning, evening, ...) show a dhikr. */
@Composable
internal fun DhikrCollectionsDialog(
    entry: DhikrEntry,
    isMember: (DhikrCategory) -> Boolean,
    onToggle: (DhikrCategory, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("مجموعات الذكر", color = AdhkarHeading, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(entry.title, color = AdhkarHeading, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                adhkarCategoryOrder.forEach { category ->
                    val checked = isMember(category)
                    Row(Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox,
                        onValueChange = { onToggle(category, it) }).heightIn(min = 48.dp)
                        .testTag("adhkar_collection_" + category.name),
                        verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked, onCheckedChange = null, modifier = Modifier.padding(horizontal = 12.dp))
                        DhikrIcon(categoryIcon(category), modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(collectionTitle(category), color = AdhkarHeading, fontSize = 14.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("تم") } })
}
