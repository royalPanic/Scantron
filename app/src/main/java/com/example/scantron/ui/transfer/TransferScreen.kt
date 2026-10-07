package com.example.scantron.ui.transfer

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.scantron.transfer.DeviceInfo
import com.example.scantron.transfer.Peer
import com.example.scantron.transfer.TransferState
import com.example.scantron.ui.components.ScantronTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manual address entry for the desktop hub, plus the two transfers.
 *
 * The address field is the whole feature in Phase 1, so it is deliberately the most prominent
 * control on the screen and is pre-filled with the last desktop that answered - see
 * [TransferHostStore] for why that has to survive process death.
 */
@Composable
fun TransferScreen(
    viewModel: TransferViewModel,
    onRequestDiscovery: () -> Unit = {},
) {
    val address by viewModel.address.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val localAddresses by viewModel.localAddresses.collectAsStateWithLifecycle()
    val pullPreview by viewModel.pullPreview.collectAsStateWithLifecycle()
        val peers by viewModel.peers.collectAsStateWithLifecycle()
        val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
        val discoveryUnavailable by viewModel.discoveryUnavailable.collectAsStateWithLifecycle()
        val context = LocalContext.current

        // Discovery needs a runtime location permission on API 28+, so the request is made lazily -
        // when the operator actually asks to search - rather than on screen entry. If it is refused
        // the address field above remains fully functional, which is the point.
        val locationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                viewModel.startDiscovery()
            } else {
                viewModel.onDiscoveryPermissionDenied()
            }
        }

    Column(modifier = Modifier.fillMaxSize()) {
        ScantronTopBar(
            title = {
                Text(
                    "Transfer to Desktop",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium,
                )
            },
        )
        // Scrollable because the content is taller than the usable height of a CK65 (480x800 at
        // 213dpi, minus the top bar and the bottom navigation bar leaves roughly 570px). In a
        // fixed Column the trailing children are measured with no height left and Compose drops
        // them out of the layout entirely, which silently hides the status line and the whole
        // Find desktops section - the two things an operator needs when a transfer goes wrong.
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = viewModel::onAddressChanged,
                label = { Text("Desktop address") },
                placeholder = { Text("192.168.1.50") },
                supportingText = {
                    Text("Read this off the desktop's Transfer panel. Port is always ${DEFAULT_PORT_TEXT}.")
                },
                // Numbers-only soft keyboard: an IP address is the only thing this accepts, and a
                // warehouse operator should not have to hunt for the colon key.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                singleLine = true,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedButton(
                onClick = viewModel::checkHealth,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Lan, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Check desktop")
            }

            Text(
                text = "This device",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${DeviceInfo.deviceName()} on ${DeviceInfo.currentWifiName(context) ?: "Wi-Fi (name unavailable)"} - " +
                    if (localAddresses.isEmpty()) {
                        "no network address yet"
                    } else {
                        localAddresses.joinToString()
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )

            HorizontalDivider()

                        Button(
                                        onClick = viewModel::sendToDesktop,
                                        enabled = !state.isBusy,
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Text("Send to desktop")
                                    }

            Button(
                onClick = viewModel::getFromDesktop,
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Get from desktop")
            }

            if (state.isBusy) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    text = (state as TransferState.Working).operation.label + "...",
                    style = MaterialTheme.typography.labelMedium,
                )
            }

            StatusLine(state)

            DiscoverySection(
                            peers = peers,
                            isSearching = isSearching,
                            guidance = discoveryUnavailable,
                            onFindDesktops = {
                                if (viewModel.canUseDiscovery()) {
                                    viewModel.startDiscovery()
                                } else {
                                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            },
                            onPeerSelected = viewModel::onPeerSelected,
                        )
                    }
    }

    /**
     * The mandatory confirmation. Pulling replaces everything on the device, so this dialog
     * states the counts and requires an explicit yes - an accidental tap must not destroy a day
     * of warehouse scanning. Dismissing is the safe default.
     */
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
                TextButton(onClick = viewModel::confirmPull) {
                    Text("Replace everything")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelPull) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun StatusLine(state: TransferState) {
    val (text, colour) = when (state) {
        is TransferState.Idle -> return
        is TransferState.Working ->
            state.operation.label to MaterialTheme.colorScheme.onSurfaceVariant
        is TransferState.Success ->
            state.message to MaterialTheme.colorScheme.primary
        is TransferState.Failed ->
            state.message to MaterialTheme.colorScheme.error
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
private fun DiscoverySection(
    peers: List<Peer>,
    isSearching: Boolean,
    guidance: String?,
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

        // Never disabled by a missing permission: the button is what triggers the prompt, and
        // keeping it live is what lets the feature degrade to "type the address" gracefully.
        OutlinedButton(
            onClick = onFindDesktops,
            enabled = !isSearching,
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
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(peers, key = { "${it.host}:${it.port}" }) { peer ->
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
                Icon(Icons.Default.Lan, contentDescription = "Use this desktop")
            }
        }
    }
}

/**
 * "just now" / "12s ago" / a clock time once it is old enough that the operator has stopped
 * watching. Exact timestamps are noise; what matters is whether the peer is answering right now.
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

private const val DEFAULT_PORT_TEXT = "8756"