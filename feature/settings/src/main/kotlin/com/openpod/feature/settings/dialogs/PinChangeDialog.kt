package com.openpod.feature.settings.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun PinChangeDialog(
    step: Int,
    oldPin: String,
    newPin: String,
    confirmPin: String,
    error: String?,
    onUpdateField: (field: Int, value: String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val title = when (step) {
        0 -> "Enter Current PIN"
        1 -> "Enter New PIN"
        2 -> "Confirm New PIN"
        else -> "Change PIN"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = when (step) { 0 -> oldPin; 1 -> newPin; else -> confirmPin },
                    onValueChange = { onUpdateField(step, it) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    placeholder = { Text("Enter PIN") },
                )
                if (error != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (step < 2) "Next" else "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
