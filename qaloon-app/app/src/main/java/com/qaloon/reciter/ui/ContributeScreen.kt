package com.qaloon.reciter.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qaloon.reciter.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContributeScreen(
    viewModel: ContributeViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val surahs by viewModel.surahs.collectAsStateWithLifecycle()
    val currentSurah by viewModel.currentSurah.collectAsStateWithLifecycle()
    val currentAyah by viewModel.currentAyah.collectAsStateWithLifecycle()
    val count by viewModel.contributionCount.collectAsStateWithLifecycle()

    // Auto-reset success state after showing it briefly
    LaunchedEffect(state) {
        if (state is ContributeViewModel.UploadState.Success) {
            kotlinx.coroutines.delay(1500)
            viewModel.resetState()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.contribute_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Text("←", fontSize = 20.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    titleContentColor = MaterialTheme.colorScheme.onTertiary,
                    navigationIconContentColor = MaterialTheme.colorScheme.onTertiary
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Instructions
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    stringResource(R.string.contribute_instructions),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            Spacer(Modifier.height(12.dp))

            // Contribution count badge
            if (count > 0) {
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        stringResource(R.string.contributions_count, count),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            // Surah / Ayah selector
            SurahAyahSelector(
                surahs = surahs,
                selectedSurah = currentSurah,
                selectedAyah = currentAyah,
                onSurahSelected = viewModel::selectSurah,
                onAyahSelected = viewModel::selectAyah
            )

            Spacer(Modifier.height(16.dp))

            // Ayah text to read
            val ayahText = viewModel.getCurrentText()
            if (ayahText.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = ayahText,
                        modifier = Modifier
                            .padding(16.dp)
                            .fillMaxWidth(),
                        style = MaterialTheme.typography.titleLarge.copy(
                            textDirection = TextDirection.Rtl,
                            lineHeight = 40.sp
                        ),
                        textAlign = TextAlign.Right,
                        fontSize = 22.sp
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Record + Upload button
            val isRecording = state is ContributeViewModel.UploadState.Recording
            val isUploading = state is ContributeViewModel.UploadState.Uploading
            val isSuccess = state is ContributeViewModel.UploadState.Success

            val buttonColor by animateColorAsState(
                targetValue = when {
                    isRecording -> Color(0xFFF44336)
                    isUploading -> Color(0xFFFF9800)
                    isSuccess -> Color(0xFF4CAF50)
                    else -> MaterialTheme.colorScheme.tertiary
                },
                label = "contributeButtonColor"
            )

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(buttonColor)
                        .clickable(enabled = !isUploading) {
                            if (isRecording) viewModel.stopRecording() else viewModel.startRecording()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            isRecording -> "⏹"
                            isUploading -> "↑"
                            isSuccess -> "✓"
                            else -> "🎤"
                        },
                        fontSize = 32.sp,
                        color = Color.White
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(
                        when {
                            isRecording -> R.string.tap_to_stop
                            isUploading -> R.string.uploading
                            isSuccess -> R.string.upload_success
                            else -> R.string.tap_to_contribute
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Error message
            if (state is ContributeViewModel.UploadState.Error) {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        (state as ContributeViewModel.UploadState.Error).message,
                        modifier = Modifier.padding(12.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
