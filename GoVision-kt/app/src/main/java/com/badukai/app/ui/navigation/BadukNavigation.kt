package com.badukai.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.badukai.app.ui.analysis.AnalysisScreen
import com.badukai.app.ui.analysis.AnalysisViewModel
import com.badukai.app.ui.games.GamesScreen
import com.badukai.app.ui.play.PlayScreen
import com.badukai.app.ui.settings.SettingsScreen
import androidx.lifecycle.viewmodel.compose.viewModel

/** 顶级目的地 */
private enum class Dest(val route: String, val label: String) {
    Play("play", "对弈"),
    Analysis("analysis", "分析"),
    Games("games", "棋谱"),
    Settings("settings", "设置")
}

@Composable
fun BadukNavHost() {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    val items = listOf(Dest.Play, Dest.Analysis, Dest.Games, Dest.Settings)

    Scaffold(
        bottomBar = {
            NavigationBar {
                items.forEach { dest ->
                    val selected = currentRoute?.startsWith(dest.route) == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) navController.navigate(dest.route) {
                                popUpTo(navController.graph.startDestinationId) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                when (dest) {
                                    Dest.Play -> Icons.Default.PlayArrow
                                    Dest.Analysis -> Icons.Default.BarChart
                                    Dest.Games -> Icons.Default.GridView
                                    Dest.Settings -> Icons.Default.Settings
                                },
                                contentDescription = dest.label
                            )
                        },
                        label = { Text(dest.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Dest.Play.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Dest.Play.route) {
                PlayScreen(
                    onOpenSettings = { navController.navigate(Dest.Settings.route) },
                    onOpenGames = { navController.navigate(Dest.Games.route) },
                )
            }
            composable(
                route = "${Dest.Analysis.route}?gameId={gameId}",
                arguments = listOf(navArgument("gameId") {
                    type = NavType.LongType; defaultValue = -1L
                })
            ) { entry ->
                val gameId = entry.arguments?.getLong("gameId") ?: -1L
                val vm: AnalysisViewModel = viewModel()
                LaunchedEffect(gameId) {
                    if (gameId > 0L) vm.loadFromGameId(gameId)
                }
                AnalysisScreen(onPickGame = { navController.navigate(Dest.Games.route) })
            }
            composable(Dest.Games.route) {
                GamesScreen(onOpenGame = { id ->
                    navController.navigate("${Dest.Analysis.route}?gameId=$id")
                })
            }
            composable(Dest.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
