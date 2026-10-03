package com.example.scantron.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.scantron.data.Container
import com.example.scantron.data.ContainerItem
import com.example.scantron.scanner.PendingScan
import com.example.scantron.ui.components.EditContainerDialog
import com.example.scantron.ui.components.EditItemDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerDetailScreen(
    viewModel: DetailViewModel,
    onNavigateBack: () -> Unit,
    pendingScans: List<PendingScan> = emptyList(),
    onCommitPendingScans: () -> Unit = {},
    onDiscardPendingScan: (PendingScan) -> Unit = {},
    onDiscardAllPendingScans: () -> Unit = {},
) {
    val container by viewModel.container.collectAsState()
    val items by viewModel.items.collectAsState()

    var editingItem by remember { mutableStateOf<ContainerItem?>(null) }
    var showAddItemDialog by remember { mutableStateOf(value = false) }
    var showEditContainerDialog by remember { mutableStateOf(value = false) }
    var showDeleteContainerDialog by remember { mutableStateOf(value = false) }
    var itemToDelete by remember { mutableStateOf<ContainerItem?>(null) }

    val dismissAddItemDialog: () -> Unit = { showAddItemDialog = false }
    val dismissEditItemDialog: () -> Unit = { editingItem = null }
    val dismissEditContainerDialog: () -> Unit = { showEditContainerDialog = false }
    val dismissDeleteContainerDialog: () -> Unit = { showDeleteContainerDialog = false }

    val addNewItem: (ContainerItem) -> Unit = { newItem ->
        // Goes through the merging add path so re-adding an item raises its quantity
        // instead of creating a second row for the same thing.
        viewModel.addItem(newItem)
        showAddItemDialog = false
    }
    val applyItemEdit: (ContainerItem) -> Unit = { updatedItem ->
        viewModel.saveItem(updatedItem)
        editingItem = null
    }
    val applyContainerEdit: (Container) -> Unit = { updatedContainer ->
        viewModel.saveContainer(updatedContainer)
        showEditContainerDialog = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Container ${viewModel.containerId}",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        container?.name?.takeIf { it.isNotBlank() }?.let { name ->
                            Text(
                                text = name,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showEditContainerDialog = true }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Container Info")
                    }
                    IconButton(onClick = { showDeleteContainerDialog = true }) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete Container",
                            tint = MaterialTheme.colorScheme.error,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAddItemDialog = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Add Item") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Container Header Info Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = container?.name?.ifBlank { "Unlabeled Container" } ?: "Container ${viewModel.containerId}",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Text(
                                text = "${items.size} items (${items.sumOf { it.quantity }} total)",
                                modifier = Modifier.padding(4.dp),
                            )
                        }
                    }

                    if (!container?.location.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Location: ${container?.location}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    if (!container?.notes.isNullOrBlank()) {
                        Text(
                            text = "Notes: ${container?.notes}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            Text(
                text = "Items Inside Container",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )

            // Hardware scans received for this container that are not yet persisted.
            PendingScansCard(
                pendingScans = pendingScans,
                onCommitAll = onCommitPendingScans,
                onDiscardAll = onDiscardAllPendingScans,
                onDiscardOne = onDiscardPendingScan,
            )

            // Items List
            if (items.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Inventory,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            "This container is empty",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Button(onClick = { showAddItemDialog = true }) {
                            Text("Add First Item")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        val updateQuantity: (Int) -> Unit = { newQty ->
                            viewModel.updateItemQuantity(item, newQty)
                        }
                        ItemCard(
                            item = item,
                            onEdit = { editingItem = item },
                            onDelete = { itemToDelete = item },
                            onQuantityChange = updateQuantity,
                        )
                    }
                }
            }
        }
    }

    // Dialogs
    if (showAddItemDialog) {
        EditItemDialog(
            item = null,
            containerId = viewModel.containerId,
            onDismiss = dismissAddItemDialog,
            onSave = addNewItem,
        )
    }

    editingItem?.let { item ->
        EditItemDialog(
            item = item,
            containerId = viewModel.containerId,
            onDismiss = dismissEditItemDialog,
            onSave = applyItemEdit,
        )
    }

    if (showEditContainerDialog) {
        EditContainerDialog(
            initialContainerId = viewModel.containerId,
            container = container,
            onDismiss = dismissEditContainerDialog,
            onSave = applyContainerEdit,
        )
    }

    if (showDeleteContainerDialog) {
        AlertDialog(
            onDismissRequest = dismissDeleteContainerDialog,
            title = { Text("Delete Container?") },
            text = { Text("Are you sure you want to delete container '${viewModel.containerId}' and all items inside it?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteContainer(onDeleted = onNavigateBack)
                        dismissDeleteContainerDialog()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteContainerDialog = false }) {
                    Text("Cancel")
                }
            },
        )
    }

    itemToDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete Item?") },
            text = { Text("Are you sure you want to remove '${item.name}' from this container?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteItem(item)
                        itemToDelete = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
fun ItemCard(
    item: ContainerItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onQuantityChange: (Int) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (item.category.isNotBlank()) {
                            SuggestionChip(
                                onClick = { },
                                label = { Text(item.category, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(24.dp),
                            )
                        }
                        if (item.barcode.isNotBlank()) {
                            AssistChip(
                                onClick = { },
                                label = { Text(item.barcode, style = MaterialTheme.typography.labelSmall) },
                                leadingIcon = { Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(12.dp)) },
                                modifier = Modifier.height(24.dp),
                            )
                        }
                    }
                }

                // Actions
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit Item", modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete Item",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }

            if (item.notes.isNotBlank()) {
                Text(
                    text = item.notes,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            // Quantity Control Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "In Stock:",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onQuantityChange(item.quantity - 1) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.Remove, contentDescription = "Decrease Quantity")
                    }

                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    ) {
                        Text(
                            text = item.quantity.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }

                    IconButton(
                        onClick = { onQuantityChange(item.quantity + 1) },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Increase Quantity")
                    }
                }
            }
        }
    }
}
