package com.example.scantron

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ScantronTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ScantronApp()
                }
            }
        }
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

    Scaffold(
        bottomBar = {
            // Only show bottom navigation on top-level screens
            if (currentRoute == NavRoutes.Containers.route || currentRoute == NavRoutes.Search.route) {
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
                        }
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
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = NavRoutes.Containers.route,
            modifier = Modifier
                .padding(innerPadding)
                .imePadding()
        ) {
            composable(NavRoutes.Containers.route) {
                val lookupViewModel: LookupViewModel = viewModel(
                    factory = LookupViewModel.Factory(repository)
                )
                ContainerLookupScreen(
                    viewModel = lookupViewModel,
                    onNavigateToTagLookup = {
                        navController.navigate(NavRoutes.TagLookup.route)
                    },
                    onNavigateToContainer = { containerId ->
                        navController.navigate(NavRoutes.ContainerDetail.createRoute(containerId))
                    }
                )
            }

            composable(NavRoutes.TagLookup.route) {
                val lookupViewModel: LookupViewModel = viewModel(
                    factory = LookupViewModel.Factory(repository)
                )
                TagLookupScreen(
                    viewModel = lookupViewModel,
                    onNavigateToContainer = { containerId ->
                        // Replace tag lookup screen in backstack with target container detail
                        navController.navigate(NavRoutes.ContainerDetail.createRoute(containerId)) {
                            popUpTo(NavRoutes.TagLookup.route) { inclusive = true }
                        }
                    },
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(NavRoutes.Search.route) {
                val searchViewModel: SearchViewModel = viewModel(
                    factory = SearchViewModel.Factory(repository)
                )
                SearchScreen(
                    viewModel = searchViewModel,
                    onNavigateToContainer = { containerId ->
                        navController.navigate(NavRoutes.ContainerDetail.createRoute(containerId))
                    }
                )
            }

            composable(
                route = NavRoutes.ContainerDetail.route,
                arguments = listOf(
                    navArgument("containerId") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val encodedId = backStackEntry.arguments?.getString("containerId") ?: ""
                val containerId = UriEncoder.decode(encodedId)

                val detailViewModel: DetailViewModel = viewModel(
                    key = containerId,
                    factory = DetailViewModel.Factory(containerId, repository)
                )

                ContainerDetailScreen(
                    viewModel = detailViewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
