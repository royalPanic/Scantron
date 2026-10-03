package com.example.scantron

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.example.scantron.data.Container
import com.example.scantron.data.ExportImportManager
import com.example.scantron.scanner.HoneywellScanReceiver
import com.example.scantron.scanner.ScanBus
import com.example.scantron.scanner.ScanSessionViewModel
import com.example.scantron.ui.components.OpenOrCreateContainerDialog
import com.example.scantron.ui.detail.ContainerDetailScreen
import com.example.scantron.ui.detail.DetailViewModel
import com.example.scantron.ui.lookup.ContainerLookupScreen
import com.example.scantron.ui.lookup.LookupViewModel
import com.example.scantron.ui.lookup.TagLookupScreen
import com.example.scantron.ui.navigation.NavRoutes
import com.example.scantron.ui.navigation.UriEncoder
import com.example.scantron.ui.search.SearchScreen
import com.example.scantron.ui.search.SearchViewModel
import com.example.scantron.ui.theme.ScantronTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    // Created in onCreate and torn down in onDestroy. A fresh instance is used for each
    // registration so the receiver never outlives the activity.
    private val scanReceiver = HoneywellScanReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Listen for Honeywell Data Intent broadcasts from the CK65 Data Collection Service.
        // RECEIVER_EXPORTED is required because the broadcast originates in another process;
        // ContextCompat picks the right flag for the running API level.
        ContextCompat.registerReceiver(
            this,
            scanReceiver,
            HoneywellScanReceiver.intentFilter(),
            ContextCompat.RECEIVER_EXPORTED,
        )

        setContent {
            ScantronTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    ScantronApp()
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(scanReceiver) }
        super.onDestroy()
    }

    companion object {
        const val EXPORT_FILENAME = ExportImportManager.DEFAULT_FILENAME
        const val FILE_MIME_TYPE = ExportImportManager.MIME_TYPE
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScantronApp() {
    val navController = rememberNavController()
    val context = LocalContext.current.applicationContext as ScantronApplication
    val repository = context.repository

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // URI holders for pending export/import operations
    var exportUri: Uri? by rememberSaveable { mutableStateOf(null) }
    var importUri: Uri? by rememberSaveable { mutableStateOf(null) }

    val exportImportManager = remember { ExportImportManager(context, repository) }

    // Export file launcher - creates a new file for the export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(MainActivity.FILE_MIME_TYPE),
    ) { uri: Uri? ->
        exportUri = uri
    }

    // Import file launcher - opens existing file for import
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        importUri = uri
    }

    // Handle export when the user has chosen a destination
    LaunchedEffect(exportUri) {
        exportUri?.let { uri ->
            val result = withContext(Dispatchers.IO) {
                exportImportManager.exportToJson(uri)
            }
            Toast.makeText(
                context,
                if (result.success) {
                    "Exported ${result.containerCount} containers and ${result.itemCount} items"
                } else {
                    result.message
                },
                Toast.LENGTH_LONG,
            ).show()
            exportUri = null
        }
    }

    // Handle import when the user has chosen a file
    LaunchedEffect(importUri) {
        importUri?.let { uri ->
            val result = withContext(Dispatchers.IO) {
                exportImportManager.importFromJson(uri)
            }
            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            importUri = null
        }
    }

    val navigateToContainer: (String) -> Unit = { containerId ->
        navController.navigate(NavRoutes.ContainerDetail.createRoute(containerId))
    }

    // ---- Honeywell Data Intent scan plumbing -------------------------------------------

    val scanSessionViewModel: ScanSessionViewModel = viewModel(
        factory = ScanSessionViewModel.Factory(repository),
    )
    val containerPrompt by scanSessionViewModel.containerPrompt.collectAsState()
    val pendingScans by scanSessionViewModel.pendingScans.collectAsState()
    val allContainers by repository.allContainers.collectAsState(initial = emptyList())

    // The container detail screen is the single source of truth for "a container is open".
    val activeContainerId: String? = remember(navBackStackEntry, currentRoute) {
        if (currentRoute == NavRoutes.ContainerDetail.route) {
            navBackStackEntry?.arguments?.getString("containerId")?.let(UriEncoder::decode)
        } else {
            null
        }
    }

    LaunchedEffect(activeContainerId) {
        scanSessionViewModel.onActiveContainerChanged(activeContainerId)
    }

    // Single collector for the process-wide scan bus.
    LaunchedEffect(Unit) {
        ScanBus.events.collect { event ->
            scanSessionViewModel.onScan(event, navigateToContainer)
        }
    }

    val openContainerFromScan: (String) -> Unit = { containerId ->
        scanSessionViewModel.onContainerResolved(containerId, navigateToContainer)
    }
    val createContainerFromScan: (Container) -> Unit = { container ->
        scanSessionViewModel.onContainerCreated(container, navigateToContainer)
    }

    // Scanning a tag should land on the container, not leave the lookup screen behind it.
    val navigateToContainerReplacingTagLookup: (String) -> Unit = { containerId ->
        navController.navigate(NavRoutes.ContainerDetail.createRoute(containerId)) {
            popUpTo(NavRoutes.TagLookup.route) { inclusive = true }
        }
    }

    val popBackStack: () -> Unit = { navController.popBackStack() }

    // Hoisted so the nav graph can pass function references instead of trailing lambdas.
    val launchExport: () -> Unit = { exportLauncher.launch(MainActivity.EXPORT_FILENAME) }
    val launchImport: () -> Unit = { importLauncher.launch(arrayOf(MainActivity.FILE_MIME_TYPE)) }

    Scaffold(
        bottomBar = {
            if ((currentRoute == NavRoutes.Containers.route) || (currentRoute == NavRoutes.Search.route)) {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Inventory2, contentDescription = "Containers") },
                        label = { Text("Containers") },
                        selected = currentRoute == NavRoutes.Containers.route,
                        onClick = {
                            navController.navigate(NavRoutes.Containers.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Search, contentDescription = "Search Items") },
                        label = { Text("Search Items") },
                        selected = currentRoute == NavRoutes.Search.route,
                        onClick = {
                            navController.navigate(NavRoutes.Search.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoutes.Containers.route,
            modifier = Modifier
                .padding(innerPadding)
                .imePadding(),
        ) {
            composable(NavRoutes.Containers.route) {
                val lookupViewModel: LookupViewModel = viewModel(
                    factory = LookupViewModel.Factory(repository),
                )
                ContainerLookupScreen(
                    viewModel = lookupViewModel,
                    onNavigateToTagLookup = {
                        navController.navigate(NavRoutes.TagLookup.route)
                    },
                    onNavigateToContainer = navigateToContainer,
                    onExportClick = launchExport,
                    onImportClick = launchImport,
                )
            }

            composable(NavRoutes.TagLookup.route) {
                val lookupViewModel: LookupViewModel = viewModel(
                    factory = LookupViewModel.Factory(repository),
                )
                TagLookupScreen(
                    viewModel = lookupViewModel,
                    onNavigateToContainer = navigateToContainerReplacingTagLookup,
                    onNavigateBack = popBackStack,
                )
            }

            composable(NavRoutes.Search.route) {
                val searchViewModel: SearchViewModel = viewModel(
                    factory = SearchViewModel.Factory(repository),
                )
                SearchScreen(
                    viewModel = searchViewModel,
                    onNavigateToContainer = navigateToContainer,
                )
            }

            composable(
                route = NavRoutes.ContainerDetail.route,
                arguments = listOf(
                    navArgument("containerId") { type = NavType.StringType },
                ),
            ) { backStackEntry ->
                val encodedId = backStackEntry.arguments?.getString("containerId") ?: ""
                val containerId = UriEncoder.decode(encodedId)

                val detailViewModel: DetailViewModel = viewModel(
                    key = containerId,
                    factory = DetailViewModel.Factory(containerId, repository),
                )

                val containerPendingScans = pendingScans.filter { it.containerId == containerId }

                ContainerDetailScreen(
                    viewModel = detailViewModel,
                    onNavigateBack = popBackStack,
                    pendingScans = containerPendingScans,
                    onCommitPendingScans = {
                        scanSessionViewModel.commitPendingScans(containerId) { count ->
                            Toast.makeText(
                                context,
                                "Added $count unit(s) to $containerId",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onDiscardPendingScan = { scan ->
                        scanSessionViewModel.discardPendingScan(scan.id)
                    },
                    onDiscardAllPendingScans = {
                        scanSessionViewModel.discardAllPendingScans(containerId)
                    },
                )
            }
        }
    }

    // A scan arrived while no container was open - ask which container it belongs to.
    containerPrompt?.let { scanEvent ->
        OpenOrCreateContainerDialog(
            scannedValue = scanEvent.data,
            containers = allContainers,
            onOpenExisting = openContainerFromScan,
            onCreateNew = createContainerFromScan,
            onDismiss = { scanSessionViewModel.dismissContainerPrompt() },
        )
    }
}
