package com.qaloon.reciter.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qaloon.reciter.R
import com.qaloon.reciter.RecitationDiffer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReciterScreen(viewModel: ReciterViewModel) {
    val modelState by viewModel.modelState.collectAsStateWithLifecycle()
    val recordingState by viewModel.recordingState.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    val surahs by viewModel.surahs.collectAsStateWithLifecycle()
    val selectedSurah by viewModel.selectedSurah.collectAsStateWithLifecycle()
    val selectedAyah by viewModel.selectedAyah.collectAsStateWithLifecycle()
    val autoDetect by viewModel.autoDetect.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.app_name),
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    titleContentColor = MaterialTheme.colorScheme.onPrimary
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
            // Model status
            when (val state = modelState) {
                is ReciterViewModel.ModelState.NotLoaded,
                is ReciterViewModel.ModelState.Loading -> {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.loading_model),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    return@Scaffold
                }
                is ReciterViewModel.ModelState.Error -> {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            state.message,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.loadModel() }) {
                        Text(stringResource(R.string.retry))
                    }
                    return@Scaffold
                }
                is ReciterViewModel.ModelState.Ready -> { /* Continue below */ }
            }

            // Auto-detect toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.auto_detect),
                    style = MaterialTheme.typography.bodyMedium
                )
                Switch(
                    checked = autoDetect,
                    onCheckedChange = viewModel::setAutoDetect
                )
            }

            Spacer(Modifier.height(8.dp))

            // Surah & Ayah selector (hidden when auto-detect is on)
            if (!autoDetect) {
                SurahAyahSelector(
                    surahs = surahs,
                    selectedSurah = selectedSurah,
                    selectedAyah = selectedAyah,
                    onSurahSelected = viewModel::selectSurah,
                    onAyahSelected = viewModel::selectAyah
                )
            }

            Spacer(Modifier.height(16.dp))

            // Reference text display
            val currentSurah = surahs.find { it.number == selectedSurah }
            val referenceText = if (autoDetect && result != null) {
                result?.referenceText
            } else {
                currentSurah?.ayahs?.find { it.number == selectedAyah }?.text
            }

            // Show detected ayah info when auto-detect found a match
            if (autoDetect && result?.detectedSurah != null) {
                val detectedSurahObj = surahs.find { it.number == result?.detectedSurah }
                Text(
                    "${detectedSurahObj?.name ?: result?.detectedSurah} — ${stringResource(R.string.ayah)} ${result?.detectedAyah}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
            }
            if (referenceText != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = referenceText,
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

            // Record button
            RecordButton(
                recordingState = recordingState,
                onStart = viewModel::startRecording,
                onStop = viewModel::stopRecording
            )

            Spacer(Modifier.height(24.dp))

            // Result display
            result?.let { res ->
                ResultCard(res)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SurahAyahSelector(
    surahs: List<com.qaloon.reciter.QuranTextProvider.Surah>,
    selectedSurah: Int,
    selectedAyah: Int,
    onSurahSelected: (Int) -> Unit,
    onAyahSelected: (Int) -> Unit
) {
    var surahExpanded by remember { mutableStateOf(false) }
    var ayahExpanded by remember { mutableStateOf(false) }

    val currentSurah = surahs.find { it.number == selectedSurah }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Surah dropdown
        ExposedDropdownMenuBox(
            expanded = surahExpanded,
            onExpandedChange = { surahExpanded = it },
            modifier = Modifier.weight(1f)
        ) {
            OutlinedTextField(
                value = currentSurah?.let { "${it.number}. ${it.name}" } ?: "$selectedSurah",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.surah)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = surahExpanded) },
                modifier = Modifier.menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = surahExpanded,
                onDismissRequest = { surahExpanded = false }
            ) {
                surahs.forEach { surah ->
                    DropdownMenuItem(
                        text = { Text("${surah.number}. ${surah.name}") },
                        onClick = {
                            onSurahSelected(surah.number)
                            surahExpanded = false
                        }
                    )
                }
            }
        }

        // Ayah dropdown
        ExposedDropdownMenuBox(
            expanded = ayahExpanded,
            onExpandedChange = { ayahExpanded = it },
            modifier = Modifier.weight(0.5f)
        ) {
            OutlinedTextField(
                value = "$selectedAyah",
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.ayah)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = ayahExpanded) },
                modifier = Modifier.menuAnchor()
            )
            ExposedDropdownMenu(
                expanded = ayahExpanded,
                onDismissRequest = { ayahExpanded = false }
            ) {
                currentSurah?.ayahs?.forEach { ayah ->
                    DropdownMenuItem(
                        text = { Text("${ayah.number}") },
                        onClick = {
                            onAyahSelected(ayah.number)
                            ayahExpanded = false
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun RecordButton(
    recordingState: ReciterViewModel.RecordingState,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val isRecording = recordingState is ReciterViewModel.RecordingState.Recording
    val isTranscribing = recordingState is ReciterViewModel.RecordingState.Transcribing

    val buttonColor by animateColorAsState(
        targetValue = when {
            isRecording -> Color(0xFFF44336) // red
            isTranscribing -> Color(0xFFFF9800) // orange
            else -> MaterialTheme.colorScheme.primary
        },
        label = "recordButtonColor"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(buttonColor)
                .clickable(enabled = !isTranscribing) {
                    if (isRecording) onStop() else onStart()
                },
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = when {
                    isRecording -> "⏹"
                    isTranscribing -> "..."
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
                    isTranscribing -> R.string.transcribing
                    else -> R.string.tap_to_record
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun ResultCard(result: ReciterViewModel.RecitationResult) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Accuracy badge
            val accuracyColor = when {
                result.accuracy >= 0.9f -> Color(0xFF4CAF50)
                result.accuracy >= 0.7f -> Color(0xFFFF9800)
                else -> Color(0xFFF44336)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.accuracy),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Surface(
                    color = accuracyColor,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Text(
                        "${(result.accuracy * 100).toInt()}%",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // Word-level diff
            if (result.diff.isNotEmpty()) {
                val annotated = buildAnnotatedString {
                    result.diff.forEach { word ->
                        val color = when (word.status) {
                            RecitationDiffer.WordStatus.CORRECT -> Color(0xFF4CAF50)
                            RecitationDiffer.WordStatus.WRONG -> Color(0xFFF44336)
                            RecitationDiffer.WordStatus.MISSING -> Color(0xFFFF9800)
                            RecitationDiffer.WordStatus.EXTRA -> Color(0xFF9E9E9E)
                        }
                        withStyle(SpanStyle(color = color, fontWeight = FontWeight.Medium)) {
                            append(word.text)
                        }
                        append(" ")
                    }
                }
                Text(
                    text = annotated,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        textDirection = TextDirection.Rtl,
                        lineHeight = 32.sp
                    ),
                    textAlign = TextAlign.Right,
                    fontSize = 20.sp,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(12.dp))

                // Legend
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LegendItem(Color(0xFF4CAF50), stringResource(R.string.correct))
                    LegendItem(Color(0xFFF44336), stringResource(R.string.wrong))
                    LegendItem(Color(0xFFFF9800), stringResource(R.string.missing))
                }
            }

            // Raw transcription
            if (result.transcription.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.your_recitation),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    result.transcription,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        textDirection = TextDirection.Rtl
                    ),
                    textAlign = TextAlign.Right,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
