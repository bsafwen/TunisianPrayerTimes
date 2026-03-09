package com.qaloon.reciter.ui

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.qaloon.reciter.R
import com.qaloon.reciter.ui.theme.QaloonTheme

class MainActivity : ComponentActivity() {

    private val reciterViewModel: ReciterViewModel by viewModels()
    private val contributeViewModel: ContributeViewModel by viewModels()

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            reciterViewModel.loadModel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request mic permission, then load model
        if (reciterViewModel.audioRecorder.hasPermission()) {
            reciterViewModel.loadModel()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            QaloonTheme {
                var currentTab by remember { mutableIntStateOf(0) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(
                                selected = currentTab == 0,
                                onClick = { currentTab = 0 },
                                icon = { Text("📖", style = MaterialTheme.typography.titleLarge) },
                                label = { Text(stringResource(R.string.tab_practice)) }
                            )
                            NavigationBarItem(
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 },
                                icon = { Text("🎁", style = MaterialTheme.typography.titleLarge) },
                                label = { Text(stringResource(R.string.tab_contribute)) }
                            )
                        }
                    }
                ) { padding ->
                    Surface(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        when (currentTab) {
                            0 -> ReciterScreen(viewModel = reciterViewModel)
                            1 -> ContributeScreen(
                                viewModel = contributeViewModel,
                                onBack = { currentTab = 0 }
                            )
                        }
                    }
                }
            }
        }
    }
}
