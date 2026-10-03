package com.example.scantron.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.scantron.data.Container

/**
 * Shown when a hardware scan arrives while no container is open and the scanned code is not
 * already a container tag.
 *
 * The scanned value is therefore an *item*; this dialog asks which container it belongs to.
 * Both paths are offered: pick one of the [containers] that already exist, or create a new
 * container. If the user keeps the scanned value as the new container id, the code becomes the
 * container itself and is not added to it as an item.
 */
@Composable
fun OpenOrCreateContainerDialog(
    scannedValue: String,
    containers: List<Container>,
    onOpenExisting: (String) -> Unit,
    onCreateNew: (Container) -> Unit,
    onDismiss: () -> Unit,
) {
    var containerId by remember(scannedValue) { mutableStateOf(scannedValue.trim().uppercase()) }
    var showDetailsDialog by remember { mutableStateOf(value = false) }

    val exactMatch = containers.firstOrNull { it.id.equals(containerId.trim(), ignoreCase = true) }
    val scannedBecomesContainer = containerId.trim().equals(scannedValue.trim(), ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .verticalScroll(rememberScrollState()),
        title = {
            Text(
                text = "Assign Scanned Code",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.QrCodeScanner,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column {
                            Text(
                                text = "Scanned code",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                            Text(
                                text = scannedValue.ifBlank { "(empty)" },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = containerId,
                    onValueChange = { containerId = it },
                    label = { Text("Put it in container") },
                    placeholder = { Text("e.g. BIN-01, BOX-102") },
                    leadingIcon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (containerId.isNotBlank()) onOpenExisting(containerId)
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                when {
                    exactMatch != null -> Text(
                        text = "Container \"${exactMatch.id}\" already exists.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    scannedBecomesContainer -> Text(
                        text = "This will create \"$containerId\" as a new container. " +
                            "The code becomes the container itself, not an item inside it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (containers.isNotEmpty()) {
                    Text(
                        text = "Existing Containers (${containers.size})",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(containers, key = { it.id }) { container ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenExisting(container.id) },
                                shape = MaterialTheme.shapes.small,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(10.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Inventory2,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Text(
                                        text = container.id,
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    if (container.name.isNotBlank()) {
                                        Text(
                                            text = " • ${container.name}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onOpenExisting(containerId) },
                enabled = containerId.isNotBlank(),
            ) {
                Text(
                    when {
                        exactMatch != null -> "Open Container"
                        scannedBecomesContainer -> "Create Container"
                        else -> "Open / Create"
                    }
                )
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                TextButton(
                    onClick = { showDetailsDialog = true },
                    enabled = containerId.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text("  Add Details")
                }
            }
        },
    )

    if (showDetailsDialog) {
        EditContainerDialog(
            initialContainerId = containerId.trim().uppercase(),
            container = null,
            onDismiss = { showDetailsDialog = false },
            onSave = { created ->
                showDetailsDialog = false
                onCreateNew(created)
            },
        )
    }
}
