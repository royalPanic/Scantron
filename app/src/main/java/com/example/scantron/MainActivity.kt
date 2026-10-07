package com.example.scantron

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
import com.example.scantron.ui.components.ScantronNavItem
import com.example.scantron.ui.components.ScantronNavigationBar
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
import com.example.scantron.ui.sync.SyncScreen
import com.example.scantron.ui.sync.SyncViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The destinations shown in the bottom bar, in order. A plain top-level list rather than one built
 * inside the composable, so it is allocated once and its identity never depends on recomposition.
 */
private val ScantronNavItems = listOf(
    ScantronNavItem(NavRoutes.Containers.route, Icons.Default.Inventory2, "Containers"),
    ScantronNavItem(NavRoutes.Search.route, Icons.Default.Search, "Search Items"),
    ScantronNavItem(NavRoutes.Transfer.route, Icons.Default.SwapHoriz, "Sync"),
)

class MainActivity : ComponentActivity() {

    // Created in onCreate and torn down in onDestroy. A fresh instance is used for each
    // registration so the receiver never outlives the activity.
    private val scanReceiver = HoneywellScanReceiver()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemNavigationBar()

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
            // Supplied explicitly rather than left to the view tree.
            //
            // collectAsStateWithLifecycle resolves its Lifecycle through CompositionLocal, and
            // that local is only populated when the host decor view carries a ViewTreeLifecycleOwner.
            // Theme.Scantron derives from the *platform* Material theme rather than an AppCompat one,
            // and on this handheld that decor view does not get the tag - so the first screen that
            // calls collectAsStateWithLifecycle throws "CompositionLocal LocalLifecycleOwner not
            // present" and takes the whole process down. The Transfer screen was the only one using
            // it, which made the crash look like a transfer bug rather than a host bug.
            //
            // The activity is itself the LifecycleOwner, so naming it here is both correct and
            // independent of which theme the device supplies.
            CompositionLocalProvider(LocalLifecycleOwner provides this) {
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
    }

    /**
     * Hides the system navigation bar so the whole 480x800 panel belongs to the app, and brings it
     * back only as a transient overlay when the operator swipes up from the bottom edge.
     *
     * [WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE] is the "sticky" immersive
     * behaviour: the bar stays hidden, a swipe from the bottom reveals it semi-transparently over the
     * content, and it hides itself again once the gesture ends. Only the *navigation* bar is hidden -
     * the status bar is left alone.
     *
     * Hiding it also collapses the navigation-bar inset to zero, so the compact bottom bar drops from
     * 52dp + 48dp of reserved strip to just its 52dp, returning that strip to the content.
     *
     * This only has an effect under three-button navigation. Under gesture navigation the system owns
     * the gesture pill and an app cannot hide it, so there the call is a no-op rather than an error.
     */
    private fun hideSystemNavigationBar() {
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.navigationBars())
        }
    }

    /**
     * Re-applies the immersive navigation bar whenever the window regains focus.
     *
     * The platform brings the bars back on its own in several situations an app cannot intercept:
     * returning from another app, dismissing a system dialog, and the soft keyboard closing. Doing it
     * here is what stops the bar from coming back and then staying back - without this the hide is a
     * one-shot that the first keyboard dismissal undoes for the rest of the session.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemNavigationBar()
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
            if (
                currentRoute == NavRoutes.Containers.route ||
                currentRoute == NavRoutes.Search.route ||
                currentRoute == NavRoutes.Transfer.route
            ) {
                ScantronNavigationBar(
                    items = ScantronNavItems,
                    currentRoute = currentRoute,
                    onSelect = { route ->
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoutes.Containers.route,
            modifier = Modifier
                .padding(innerPadding)
                // The host Scaffold is now the single owner of the window insets. Consume the
                // padding it handed down so nothing inside the graph re-applies the status-bar or
                // navigation-bar insets; the screens wrap their compact top bars in a plain Column
                // that applies none, and this keeps it that way if a screen ever reaches for one.
                .consumeWindowInsets(innerPadding)
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

                        composable(NavRoutes.Transfer.route) {
                            val syncViewModel: SyncViewModel = viewModel(
                                factory = SyncViewModel.Factory(context, repository),
                            )
                            SyncScreen(viewModel = syncViewModel)
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
