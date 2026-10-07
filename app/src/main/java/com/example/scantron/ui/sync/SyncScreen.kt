package com.example.scantron.ui.sync

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scantron.sync.ItemConflict
import com.example.scantron.transfer.DeviceInfo
import com.example.scantron.transfer.Peer
import com.example.scantron.transfer.SyncStatus
import com.example.scantron.transfer.TransferState
import com.example.scantron.ui.components.ScantronTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Live sync with the desktop, with the manual file transfer folded away underneath it.
 *
 * The screen's job is to answer three questions in order, top to bottom: *who am I syncing with*
 * (address and pairing code), *what is happening right now* (the status block), and *is anything
 * waiting on me* (conflicts). Everything else - the push/pull buttons that used to be the whole
 * screen - lives in a collapsed section at the bottom, because it is now the fallback path rather
 * than the feature.
 */
@Composable
fun SyncScreen(
    viewModel: SyncViewModel,
    onRequestDiscovery: () -> Unit = {},
) {
    val address by viewModel.address.collectAsStateWithLifecycle()
    val pairCode by viewModel.pairCode.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val conflicts by viewModel.conflicts.collectAsStateWithLifecycle()
    val isPaired by viewModel.isPaired.collectAsStateWithLifecycle()
    val localAddresses by viewModel.localAddresses.collectAsStateWithLifecycle()
    val transferState by viewModel.state.collectAsStateWithLifecycle()
    val pullPreview by viewModel.pullPreview.collectAsStateWithLifecycle()
    val peers by viewModel.peers.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val discoveryUnavailable by viewModel.discoveryUnavailable.collectAsStateWithLifecycle()
    val isForeground by viewModel.isForeground.collectAsStateWithLifecycle()

    var showFileTransfer by remember { mutableStateOf(false) }
    var resolving by remember { mutableStateOf<ItemConflict?>(null) }
    val context = LocalContext.current

    // The session is tied to the screen's own foreground state rather than to a service. That is the
    // whole lifecycle contract: backgrounding pauses the socket, foregrounding resumes and resyncs.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> viewModel.onForeground()
                Lifecycle.Event.ON_STOP -> viewModel.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.any { it }) viewModel.startDiscovery() else viewModel.onDiscoveryPermissionDenied()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ScantronTopBar(
            title = {
                Text(
                    "Sync",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
        )

        // Scrollable because the content is taller than the usable height of a CK65 (480x800 at
        // 213dpi, minus the top bar and the bottom navigation bar leaves roughly 550px). In a fixed
        // Column the trailing children are measured with no height left and Compose drops them out of
        // the layout entirely - the exact defect that made pull-import unreachable on the transfer
        // screen, and the reason every added control has to live inside this scroll.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusBlock(status = status, isPaired = isPaired)

            if (conflicts.isNotEmpty()) {
                ConflictSection(
                    conflicts = conflicts,
                    onChoose = { conflict -> resolving = conflict },
                )
            }

            OutlinedTextField(
                value = address,
                onValueChange = viewModel::onAddressChanged,
                label = { Text("Desktop address") },
                placeholder = { Text("192.168.1.50") },
                supportingText = {
                    Text("Read this off the desktop's Sync panel. Port is always $DEFAULT_PORT_TEXT.")
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = pairCode,
                onValueChange = viewModel::onPairCodeChanged,
                label = { Text("Pairing code") },
                placeholder = { Text("123456") },
                supportingText = { Text("The $PAIR_CODE_LENGTH digits the desktop is showing.") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = viewModel::pair,
                enabled = !transferState.isBusy && isForeground,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Pair and sync")
            }

            if (isPaired) {
                OutlinedButton(
                    onClick = viewModel::unpair,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Unpair")
                }
            }

            Text(
                text = "This device",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${DeviceInfo.deviceName()} on " +
                    "${DeviceInfo.currentWifiName(context) ?: "Wi-Fi (name unavailable)"} - " +
                    if (localAddresses.isEmpty()) {
                        "no network address yet"
                    } else {
                        localAddresses.joinToString()
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            HorizontalDivider()

            DiscoverySection(
                peers = peers,
                isSearching = isSearching,
                guidance = discoveryUnavailable,
                enabled = !transferState.isBusy,
                onFindDesktops = {
                    if (viewModel.canUseDiscovery()) {
                        viewModel.startDiscovery()
                    } else {
                        locationPermissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    }
                },
                onPeerSelected = viewModel::pairWith,
            )

            HorizontalDivider()

            // Collapsed by default. The manual path still works exactly as it did - including its
            // destructive confirm - because it remains the only way to move a whole document when
            // live sync cannot be paired.
            TextButton(
                onClick = { showFileTransfer = !showFileTransfer },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (showFileTransfer) "Hide file transfer" else "More options (file transfer)")
            }

            AnimatedVisibility(visible = showFileTransfer) {
                FileTransferSection(
                    state = transferState,
                    isListening = viewModel.isListening.collectAsStateWithLifecycle().value,
                    listenProblem = viewModel.listenProblem.collectAsStateWithLifecycle().value,
                    onCheckHealth = viewModel::checkHealth,
                    onSend = viewModel::sendToDesktop,
                    onGet = viewModel::getFromDesktop,
                    onStartListening = viewModel::startListening,
                    onStopListening = viewModel::stopListening,
                )
            }

            TransferStatusLine(transferState)
        }
    }

    // The destructive confirm stays on the manual path only. Live sync applies deltas and never
    // clears the database, so showing this dialog from a sync would be a lie about what is about to
    // happen - and the operator might say yes.
    pullPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::cancelPull,
            title = { Text("Replace this device's inventory?") },
            text = {
                Text(
                    "The desktop has ${preview.containerCount} containers and " +
                        "${preview.itemCount} items.\n\n" +
                        "Getting them deletes everything currently stored on this device and " +
                        "replaces it with that. This cannot be undone.",
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmPull) { Text("Replace everything") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelPull) { Text("Cancel") }
            },
        )
    }

    resolving?.let { conflict ->
        ResolveDialog(
            conflict = conflict,
            onDismiss = { resolving = null },
            onChoose = { value ->
                viewModel.resolveConflict(conflict, value)
                resolving = null
            },
        )
    }
}

/**
 * The live-sync status block: the operator's answer to "is it working".
 *
 * Every state is spelled out in words rather than left to a colour or a spinner, because the two
 * states that matter most - "in sync as of 14:02" and "offline, retrying" - are precisely the ones
 * that look identical if you only show an icon.
 */
@Composable
internal fun StatusBlock(status: SyncStatus, isPaired: Boolean) {
    val (headline, detail, colour) = when (status) {
        is SyncStatus.Idle ->
            if (isPaired) {
                Triple(
                    "Paused",
                    "Open this screen again to sync. Your pairing is remembered.",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Triple(
                    "Not paired",
                    "Type the desktop's address and pairing code below.",
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

        is SyncStatus.Pairing -> Triple(
            "Pairing",
            "Talking to the desktop.",
            MaterialTheme.colorScheme.primary,
        )

        is SyncStatus.Syncing -> Triple(
            "Syncing",
            "Exchanging changes.",
            MaterialTheme.colorScheme.primary,
        )

        is SyncStatus.InSync -> Triple(
            "In sync",
            "Up to date as of ${clockTime(status.atMillis)}.",
            MaterialTheme.colorScheme.primary,
        )

        is SyncStatus.Offline -> Triple(
            "Offline - retrying",
            status.reason,
            MaterialTheme.colorScheme.error,
        )

        is SyncStatus.Conflicts -> Triple(
            "${status.count} conflict${if (status.count == 1) "" else "s"}",
            "Choose a value for each one below to finish syncing.",
            MaterialTheme.colorScheme.error,
        )
    }

    Surface(
        color = colour.copy(alpha = 0.10f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (status is SyncStatus.Pairing || status is SyncStatus.Syncing) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                }
                Text(
                    text = headline,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = colour,
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = colour,
            )
        }
    }
}

/**
 * Minimal conflict handling: a count and a choose-a-value per field.
 *
 * Deliberately not a merge editor. The two devices have already agreed on everything they can, so
 * what is left is one field where both sides typed something different - and the honest interaction
 * for that is "which of these two do you want", not a blank form inviting a third value nobody
 * proposed.
 */
@Composable
internal fun ConflictSection(
    conflicts: List<ItemConflict>,
    onChoose: (ItemConflict) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Needs your decision (${conflicts.size})",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )

        conflicts.forEach { conflict ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = describeConflict(conflict),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "This device: ${conflict.localValue ?: "(none)"}  |  " +
                            "Desktop: ${conflict.remoteValue ?: "(none)"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilledTonalButton(
                        onClick = { onChoose(conflict) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Choose a value")
                    }
                }
            }
        }
    }
}

@Composable
private fun ResolveDialog(
    conflict: ItemConflict,
    onDismiss: () -> Unit,
    onChoose: (String?) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which value should win?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(describeConflict(conflict), style = MaterialTheme.typography.bodyMedium)

                conflict.baseValue?.let {
                    Text(
                        "Agreed before: $it",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                TextButton(
                    onClick = { onChoose(conflict.localValue) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Keep this device's value (${conflict.localValue ?: "(none)"})")
                }

                TextButton(
                    onClick = { onChoose(conflict.remoteValue) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Take the desktop's value (${conflict.remoteValue ?: "(none)"})")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Names the row and field without showing a uuid.
 *
 * A uuid is not something an operator can act on, and the conflict already carries whatever the
 * merge knew about the row - so the name is shown when there is one and the tag is the fallback.
 */
private fun describeConflict(conflict: ItemConflict): String {
    val row = conflict.itemName.ifBlank { conflict.containerId }
    return "$row - ${conflict.field}"
}

/**
 * The manual file-transfer controls, unchanged in behaviour.
 */
@Composable
private fun FileTransferSection(
    state: TransferState,
    isListening: Boolean,
    listenProblem: String?,
    onCheckHealth: () -> Unit,
    onSend: () -> Unit,
    onGet: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            onClick = onCheckHealth,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  Check desktop")
        }

        Button(
            onClick = onSend,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Send to desktop")
        }

        Button(
            onClick = onGet,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Get from desktop")
        }

        FilledTonalButton(
            onClick = if (isListening) onStopListening else onStartListening,
            enabled = !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isListening) "Stop receiving from desktop" else "Receive a push from desktop")
        }

        listenProblem?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Text(
            text = "These move the whole document and one of them replaces everything on this " +
                "device. Live sync above is the normal way to keep both sides up to date.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun TransferStatusLine(state: TransferState) {
    val (text, colour) = when (state) {
        is TransferState.Idle -> return
        is TransferState.Working ->
            state.operation.label to MaterialTheme.colorScheme.onSurfaceVariant

        is TransferState.Success -> state.message to MaterialTheme.colorScheme.primary
        is TransferState.Failed -> state.message to MaterialTheme.colorScheme.error
    }

    Surface(
        color = colour.copy(alpha = 0.10f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = colour,
            modifier = Modifier.padding(8.dp),
        )
    }
}

@Composable
internal fun DiscoverySection(
    peers: List<Peer>,
    isSearching: Boolean,
    guidance: String?,
    enabled: Boolean,
    onFindDesktops: () -> Unit,
    onPeerSelected: (Peer) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (guidance != null) {
            Text(
                text = guidance,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        // Never disabled by a missing permission: the button is what triggers the prompt, and keeping
        // it live is what lets the feature degrade to "type the address" gracefully.
        OutlinedButton(
            onClick = onFindDesktops,
            enabled = !isSearching && enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("  Find desktops")
        }

        if (isSearching) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Looking on the local network...", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (peers.isEmpty() && !isSearching) {
            Text(
                text = "No desktops found yet. You can always type the address above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (peers.isNotEmpty()) {
            // A plain Column, not a LazyColumn: this sits inside the screen's own verticalScroll, and
            // a lazy list measured with unbounded height throws. A warehouse has a handful of
            // desktops, so there is nothing worth virtualising anyway.
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                peers.forEach { peer ->
                    PeerCard(peer = peer, onClick = { onPeerSelected(peer) })
                }
            }
        }
    }
}

@Composable
private fun PeerCard(peer: Peer, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = peer.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${peer.host}:${peer.port}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = "seen ${relativeLastSeen(peer.lastSeenMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
            IconButton(onClick = onClick) {
                Icon(Icons.Default.Sync, contentDescription = "Sync with this desktop")
            }
        }
    }
}

/**
 * "just now" / "12s ago" / a clock time once it is old enough that the operator has stopped watching.
 * Exact timestamps are noise; what matters is whether the peer is answering right now.
 */
private fun relativeLastSeen(lastSeenMillis: Long): String {
    val elapsedSeconds = (System.currentTimeMillis() - lastSeenMillis) / 1000
    return when {
        elapsedSeconds < 3 -> "just now"
        elapsedSeconds < 60 -> "${elapsedSeconds}s ago"
        elapsedSeconds < 3600 -> "${elapsedSeconds / 60}m ago"
        else -> SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(lastSeenMillis))
    }
}

private fun clockTime(millis: Long): String =
    SimpleDateFormat("HH:mm", Locale.US).format(Date(millis))

private const val DEFAULT_PORT_TEXT = "8756"

/** Kept in step with [SyncViewModel.PAIR_CODE_LENGTH] so the field and its hint cannot disagree. */
private const val PAIR_CODE_LENGTH = SyncViewModel.PAIR_CODE_LENGTH
