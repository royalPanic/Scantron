package com.example.scantron.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.scantron.data.Container

@Composable
fun EditContainerDialog(
    initialContainerId: String = "",
    container: Container?,
    onDismiss: () -> Unit,
    onSave: (Container) -> Unit,
) {
    var containerId by remember { mutableStateOf(container?.id ?: initialContainerId) }
    var name by remember { mutableStateOf(container?.name ?: "") }
    var location by remember { mutableStateOf(container?.location ?: "") }
    var notes by remember { mutableStateOf(container?.notes ?: "") }
    var idError by remember { mutableStateOf(value = false) }

    val isNew = container == null
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
        ),
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .imePadding(),
        title = {
            Text(
                text = if (isNew) "New Container" else "Edit Container Info",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    .padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                OutlinedTextField(
                    value = containerId,
                    onValueChange = {
                        containerId = it
                        if (it.isNotBlank()) idError = false
                    },
                    label = { Text("Container Tag / ID *") },
                    placeholder = { Text("e.g. BIN-01, BOX-102") },
                    leadingIcon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                    enabled = isNew, // Primary Key ID cannot be edited once created
                    isError = idError,
                    supportingText = { if (idError) Text("Container ID cannot be empty") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Friendly Name") },
                    placeholder = { Text("e.g. Heavy Duty Blue Bin") },
                    leadingIcon = { Icon(Icons.Default.Title, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = location,
                    onValueChange = { location = it },
                    label = { Text("Location") },
                    placeholder = { Text("e.g. Garage Shelf A, Top") },
                    leadingIcon = { Icon(Icons.Default.LocationOn, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes / Description") },
                    placeholder = { Text("Add any extra location details...") },
                    leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (containerId.isNotBlank()) {
                                onSave(
                                    Container(
                                        id = containerId.trim().uppercase(),
                                        name = name.trim(),
                                        location = location.trim(),
                                        notes = notes.trim(),
                                        updatedAt = System.currentTimeMillis(),
                                    ),
                                )
                            } else {
                                idError = true
                            }
                        },
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (containerId.isBlank()) {
                        idError = true
                    } else {
                        val updated = Container(
                            id = containerId.trim().uppercase(),
                            name = name.trim(),
                            location = location.trim(),
                            notes = notes.trim(),
                            updatedAt = System.currentTimeMillis(),
                        )
                        onSave(updated)
                    }
                },
            ) {
                Text("Save Container")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
