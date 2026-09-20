package com.example.janggiai.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.janggiai.game.Board
import com.example.janggiai.ui.about.AboutScreen
import com.example.janggiai.ui.analysis.AnalysisScreen
import com.example.janggiai.ui.game.GameMode
import com.example.janggiai.ui.game.GameScreen
import com.example.janggiai.ui.home.HomeScreen
import com.example.janggiai.ui.records.RecordsScreen
import com.example.janggiai.ui.settings.BenchmarkScreen
import com.example.janggiai.ui.settings.SettingsScreen

object Routes {
    const val HOME = "home"
    const val GAME = "game?mode={mode}&side={side}&configure={configure}"
    const val ANALYSIS = "analysis?record={record}"
    const val RECORDS = "records"
    const val SETTINGS = "settings"
    const val BENCHMARK = "benchmark"
    const val ABOUT = "about"

    fun game(mode: GameMode = GameMode.HUMAN_VS_AI, side: Int = Board.CHO, configure: Boolean = false) = "game?mode=${mode.id}&side=$side&configure=$configure"
    fun analysis(recordId: String? = null) = if (recordId == null) "analysis?record=" else "analysis?record=$recordId"
}

@Composable
fun JanggiNavHost() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.HOME) {
        composable(Routes.HOME) {
            HomeScreen(
                onPlay = { nav.navigate(Routes.game(configure = true)) },
                onAnalyze = { nav.navigate(Routes.analysis()) },
                onRecords = { nav.navigate(Routes.RECORDS) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
                onAbout = { nav.navigate(Routes.ABOUT) },
            )
        }
        composable(
            Routes.GAME,
            arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = GameMode.HUMAN_VS_AI.id },
                               navArgument("side") { type = NavType.IntType; defaultValue = Board.CHO },
                               navArgument("configure") { type = NavType.BoolType; defaultValue = false }),
        ) { entry ->
            GameScreen(mode = GameMode.fromId(entry.arguments?.getString("mode")), humanSide = entry.arguments?.getInt("side") ?: Board.CHO,
                configure = entry.arguments?.getBoolean("configure") ?: false, onBack = { nav.popBackStack() })
        }
        composable(Routes.ANALYSIS, arguments = listOf(navArgument("record") { type = NavType.StringType; defaultValue = "" })) { entry ->
            AnalysisScreen(recordId = entry.arguments?.getString("record")?.takeIf { it.isNotEmpty() }, onBack = { nav.popBackStack() })
        }
        composable(Routes.RECORDS) { RecordsScreen(onBack = { nav.popBackStack() }, onOpen = { id -> nav.navigate(Routes.analysis(id)) }) }
        composable(Routes.SETTINGS) { SettingsScreen(onBack = { nav.popBackStack() }, onBenchmark = { nav.navigate(Routes.BENCHMARK) }, onAbout = { nav.navigate(Routes.ABOUT) }) }
        composable(Routes.BENCHMARK) { BenchmarkScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.ABOUT) { AboutScreen(onBack = { nav.popBackStack() }) }
    }
}
