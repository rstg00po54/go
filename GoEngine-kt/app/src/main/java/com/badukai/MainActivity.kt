package com.badukai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.badukai.game.GameViewModel
import com.badukai.ui.GameScreen

class MainActivity : ComponentActivity() {

    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Initialize the engine
        viewModel.initialize(applicationContext)
        
        setContent {
            val state by viewModel.state.collectAsState()
            
            GameScreen(
                state = state,
                onBoardTap = viewModel::onBoardTap,
                onPass = viewModel::pass,
                onResign = viewModel::resign,
                onUndo = viewModel::undo,
                onNewGame = viewModel::showNewGameDialog,
                onStartNewGame = viewModel::startNewGame,
                onDismissNewGame = viewModel::hideNewGameDialog,
                onShowModelSelector = viewModel::showModelSelector,
                onSelectModel = viewModel::selectModel,
                onDismissModelSelector = viewModel::hideModelSelector,
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            )
        }
    }

    override fun onStart() {
        super.onStart()
        // Start the engine when activity starts
        if (!viewModel.state.value.isEngineReady && !viewModel.state.value.isEngineStarting) {
            viewModel.startEngine()
        }
    }

    override fun onStop() {
        super.onStop()
        // Keep engine running in background for quick resume
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) {
            viewModel.stopEngine()
        }
    }
}
