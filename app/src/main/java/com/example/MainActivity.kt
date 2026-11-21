package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.library.LibraryScreen
import com.example.ui.library.LibraryViewModel
import com.example.ui.player.EmulatorViewModel
import com.example.ui.player.PlayerScreen
import com.example.ui.theme.PocketAdvanceTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContent {
            PocketAdvanceTheme(darkTheme = true) {
                val navController = rememberNavController()
                val libraryViewModel: LibraryViewModel = viewModel()

                NavHost(
                    navController = navController,
                    startDestination = ROUTE_LIBRARY
                ) {
                    composable(ROUTE_LIBRARY) {
                        LibraryScreen(
                            viewModel = libraryViewModel,
                            onOpenPlayer = { romId ->
                                navController.navigate("player/$romId")
                            }
                        )
                    }
                    composable(
                        route = ROUTE_PLAYER,
                        arguments = listOf(navArgument("romId") { type = NavType.LongType })
                    ) { backStackEntry ->
                        val romId = backStackEntry.arguments?.getLong("romId") ?: return@composable
                        val emulatorViewModel: EmulatorViewModel = viewModel()
                        PlayerScreen(
                            romId = romId,
                            viewModel = emulatorViewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    companion object {
        const val ROUTE_LIBRARY = "library"
        const val ROUTE_PLAYER = "player/{romId}"
    }
}
