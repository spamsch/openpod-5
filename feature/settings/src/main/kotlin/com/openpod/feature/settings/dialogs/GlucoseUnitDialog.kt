package com.openpod.feature.settings.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openpod.model.glucose.GlucoseUnit

@Composable
fun GlucoseUnitDialog(
    currentUnit: GlucoseUnit,
    onSelect: (GlucoseUnit) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Glucose Units") },
        text = {
            Column {
                GlucoseUnit.entries.forEach { unit ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(unit) }
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(
                            selected = unit == currentUnit,
                            onClick = { onSelect(unit) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(unit.displayLabel)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
