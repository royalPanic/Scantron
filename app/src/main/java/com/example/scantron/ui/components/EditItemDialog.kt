package com.example.scantron.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.example.scantron.data.ContainerItem

@Composable
fun EditItemDialog(
    item: ContainerItem?,
    containerId: String,
    onDismiss: () -> Unit,
    onSave: (ContainerItem) -> Unit,
) {
    var name by remember { mutableStateOf(item?.name ?: "") }
    var barcode by remember { mutableStateOf(item?.barcode ?: "") }
    var quantityText by remember { mutableStateOf(item?.quantity?.toString() ?: "1") }
    var category by remember { mutableStateOf(item?.category ?: "") }
    var notes by remember { mutableStateOf(item?.notes ?: "") }
    var nameError by remember { mutableStateOf(value = false) }

    val currentQty = quantityText.toIntOrNull() ?: 1
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
                text = if (item == null) "Add Item to $containerId" else "Edit Item",
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
                    value = name,
                    onValueChange = {
                        name = it
                        if (it.isNotBlank()) nameError = false
                    },
                    label = { Text("Item Name *") },
                    placeholder = { Text("e.g. Cordless Drill 18V") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = null) },
                    isError = nameError,
                    supportingText = { if (nameError) Text("Item name cannot be empty") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = barcode,
                    onValueChange = { barcode = it },
                    label = { Text("Barcode / SKU (Optional)") },
                    placeholder = { Text("Scan or type barcode...") },
                    leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Characters,
                        autoCorrect = false,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Quantity selector
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Quantity",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilledIconButton(
                                onClick = {
                                    if (currentQty > 1) {
                                        quantityText = (currentQty - 1).toString()
                                    }
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                ),
                                modifier = Modifier.size(38.dp),
                            ) {
                                Icon(Icons.Default.Remove, contentDescription = "Decrease")
                            }

                            OutlinedTextField(
                                value = quantityText,
                                onValueChange = { quantityText = it.filter { char -> char.isDigit() } },
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                    imeAction = ImeAction.Next,
                                ),
                                modifier = Modifier.width(72.dp),
                                singleLine = true,
                            )

                            FilledIconButton(
                                onClick = {
                                    quantityText = (currentQty + 1).toString()
                                },
                                colors = IconButtonDefaults.filledIconButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.size(38.dp),
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Increase")
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = category,
                    onValueChange = { category = it },
                    label = { Text("Category") },
                    placeholder = { Text("e.g. Tools, Electronics, Books") },
                    leadingIcon = { Icon(Icons.Default.Category, contentDescription = null) },
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
                    placeholder = { Text("Add extra details or serial numbers...") },
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
                            if (name.isNotBlank()) {
                                val finalQty = quantityText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                                onSave(
                                    ContainerItem(
                                        id = item?.id ?: 0L,
                                        containerId = containerId,
                                        name = name.trim(),
                                        barcode = barcode.trim(),
                                        quantity = finalQty,
                                        category = category.trim(),
                                        notes = notes.trim(),
                                                                        // Carried over from the row being edited: dropping it
                                                                        // would strip the item's identity and break the next
                                                                        // export/import match against a desktop edit.
                                                                        uuid = item?.uuid.orEmpty(),
                                                                        updatedAt = System.currentTimeMillis(),
                                                                    ),
                                                                )
                            } else {
                                nameError = true
                            }
                        },
                    ),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        nameError = true
                    } else {
                        val finalQty = quantityText.toIntOrNull()?.coerceAtLeast(1) ?: 1
                        val updatedItem = ContainerItem(
                            id = item?.id ?: 0L,
                            containerId = containerId,
                            name = name.trim(),
                            barcode = barcode.trim(),
                            quantity = finalQty,
                            category = category.trim(),
                            notes = notes.trim(),
                                                    uuid = item?.uuid.orEmpty(),
                                                    updatedAt = System.currentTimeMillis(),
                                                )
                        onSave(updatedItem)
                    }
                },
            ) {
                Text(if (item == null) "Add Item" else "Save Changes")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
