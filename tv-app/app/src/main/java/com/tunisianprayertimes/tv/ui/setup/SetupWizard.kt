package com.tunisianprayertimes.tv.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Delegation
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.data.IqamahMode
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.theme.Gold
import com.tunisianprayertimes.tv.ui.theme.CardBorder
import com.tunisianprayertimes.tv.ui.theme.SurfaceElevated
import com.tunisianprayertimes.tv.ui.theme.TealDark
import com.tunisianprayertimes.tv.ui.theme.TealPrimary

/**
 * Setup wizard — 4 step flow:
 * 1. Select gouvernorat
 * 2. Select delegation
 * 3. Configure iqamah per prayer
 * 4. Mosque name (optional) + confirm
 */
@Composable
fun SetupWizard(
    gouvernorats: List<Gouvernorat>,
    onComplete: (
        gouvernoratId: Int,
        delegation: Delegation,
        iqamahConfigs: Map<Prayer, IqamahConfig>,
        jomoaaConfig: IqamahConfig,
        mosqueName: String
    ) -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    var selectedGouvernorat by remember { mutableStateOf<Gouvernorat?>(null) }
    var selectedDelegation by remember { mutableStateOf<Delegation?>(null) }
    var iqamahConfigs by remember {
        mutableStateOf(
            mapOf(
                Prayer.FAJR to IqamahConfig(delayMinutes = 15),
                Prayer.DHUHR to IqamahConfig(delayMinutes = 10),
                Prayer.ASR to IqamahConfig(delayMinutes = 10),
                Prayer.MAGHRIB to IqamahConfig(delayMinutes = 5),
                Prayer.ISHA to IqamahConfig(delayMinutes = 10)
            )
        )
    }
    var jomoaaConfig by remember { mutableStateOf(IqamahConfig(delayMinutes = 15)) }
    var mosqueName by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(48.dp)
    ) {
        when (step) {
            0 -> GouvernoratStep(
                gouvernorats = gouvernorats,
                onSelect = { g ->
                    selectedGouvernorat = g
                    step = 1
                }
            )
            1 -> DelegationStep(
                gouvernorat = selectedGouvernorat!!,
                onSelect = { d ->
                    selectedDelegation = d
                    step = 2
                },
                onBack = { step = 0 }
            )
            2 -> IqamahStep(
                configs = iqamahConfigs,
                jomoaaConfig = jomoaaConfig,
                onConfigsChanged = { iqamahConfigs = it },
                onJomoaaChanged = { jomoaaConfig = it },
                onNext = { step = 3 },
                onBack = { step = 1 }
            )
            3 -> MosqueNameStep(
                mosqueName = mosqueName,
                delegationName = selectedDelegation!!.nomAr,
                onNameChanged = { mosqueName = it },
                onConfirm = {
                    onComplete(
                        selectedGouvernorat!!.id,
                        selectedDelegation!!,
                        iqamahConfigs,
                        jomoaaConfig,
                        mosqueName
                    )
                },
                onBack = { step = 2 }
            )
        }
    }
}

@Composable
private fun GouvernoratStep(
    gouvernorats: List<Gouvernorat>,
    onSelect: (Gouvernorat) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = TvStrings.SETUP_WELCOME,
            style = MaterialTheme.typography.headlineLarge,
            color = Gold,
            fontSize = 36.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = TvStrings.SETUP_SELECT_GOUVERNORAT,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 28.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(gouvernorats) { gouvernorat ->
                FocusableListItem(
                    text = gouvernorat.nomAr,
                    onClick = { onSelect(gouvernorat) }
                )
            }
        }
    }
}

@Composable
private fun DelegationStep(
    gouvernorat: Gouvernorat,
    onSelect: (Delegation) -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.key == Key.Back && event.type == KeyEventType.KeyUp) {
                    onBack(); true
                } else false
            },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${TvStrings.SETUP_SELECT_DELEGATION} — ${gouvernorat.nomAr}",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 28.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        val listState = rememberLazyListState()
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(gouvernorat.delegations) { delegation ->
                FocusableListItem(
                    text = delegation.nomAr,
                    onClick = { onSelect(delegation) }
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        NavigationButtons(onBack = onBack)
    }
}

@Composable
private fun IqamahStep(
    configs: Map<Prayer, IqamahConfig>,
    jomoaaConfig: IqamahConfig,
    onConfigsChanged: (Map<Prayer, IqamahConfig>) -> Unit,
    onJomoaaChanged: (IqamahConfig) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = TvStrings.SETUP_IQAMAH_TITLE,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 28.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = TvStrings.SETUP_IQAMAH_SUBTITLE,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        val prayers = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA)

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth(0.6f)
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(prayers) { prayer ->
                IqamahRow(
                    prayerName = TvStrings.prayerName(prayer),
                    config = configs[prayer] ?: IqamahConfig(),
                    onConfigChanged = { newConfig ->
                        onConfigsChanged(configs + (prayer to newConfig))
                    }
                )
            }
            item {
                IqamahRow(
                    prayerName = TvStrings.FRIDAY_IQAMAH,
                    config = jomoaaConfig,
                    onConfigChanged = onJomoaaChanged
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        NavigationButtons(onBack = onBack, onNext = onNext)
    }
}

@Composable
private fun IqamahRow(
    prayerName: String,
    config: IqamahConfig,
    onConfigChanged: (IqamahConfig) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = prayerName,
            style = MaterialTheme.typography.titleLarge,
            color = Gold,
            fontSize = 22.sp,
            modifier = Modifier.width(120.dp)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Decrease button
            FocusableButton(
                text = "−",
                onClick = {
                    if (config.delayMinutes > 1) {
                        onConfigChanged(config.copy(delayMinutes = config.delayMinutes - 1))
                    }
                }
            )

            Text(
                text = "${config.delayMinutes} ${TvStrings.MINUTES_SUFFIX}",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 24.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(80.dp)
            )

            // Increase button
            FocusableButton(
                text = "+",
                onClick = {
                    if (config.delayMinutes < 60) {
                        onConfigChanged(config.copy(delayMinutes = config.delayMinutes + 1))
                    }
                }
            )
        }
    }
}

@Composable
private fun MosqueNameStep(
    mosqueName: String,
    delegationName: String,
    onNameChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = TvStrings.SETUP_MOSQUE_NAME,
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 28.sp,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        OutlinedTextField(
            value = mosqueName,
            onValueChange = onNameChanged,
            placeholder = {
                Text(
                    TvStrings.SETUP_MOSQUE_HINT,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Gold,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                cursorColor = Gold
            ),
            textStyle = LocalTextStyle.current.copy(fontSize = 24.sp, textAlign = TextAlign.Center),
            modifier = Modifier.fillMaxWidth(0.5f),
            singleLine = true
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = delegationName,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 18.sp
        )

        Spacer(Modifier.height(48.dp))

        NavigationButtons(
            onBack = onBack,
            nextText = TvStrings.CONFIRM,
            onNext = onConfirm
        )
    }
}

// --- Reusable TV-focused components ---

@Composable
fun FocusableListItem(
    text: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isFocused) TealPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                RoundedCornerShape(14.dp)
            )
            .border(
                width = 1.dp,
                color = if (isFocused) Gold.copy(alpha = 0.7f) else CardBorder,
                shape = RoundedCornerShape(14.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.key == Key.Enter && event.type == KeyEventType.KeyUp ||
                    event.key == Key.DirectionCenter && event.type == KeyEventType.KeyUp
                ) {
                    onClick(); true
                } else false
            }
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleLarge,
            color = if (isFocused) Color.White else MaterialTheme.colorScheme.onSurface,
            fontSize = 22.sp
        )
    }
}

@Composable
fun FocusableButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(48.dp)
            .background(
                if (isFocused) Gold else SurfaceElevated,
                RoundedCornerShape(10.dp)
            )
            .border(
                width = 1.dp,
                color = if (isFocused) Gold else CardBorder,
                shape = RoundedCornerShape(10.dp)
            )
            .onFocusChanged { isFocused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.key == Key.Enter && event.type == KeyEventType.KeyUp ||
                    event.key == Key.DirectionCenter && event.type == KeyEventType.KeyUp
                ) {
                    onClick(); true
                } else false
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 24.sp,
            color = Color.White
        )
    }
}

@Composable
private fun NavigationButtons(
    onBack: (() -> Unit)? = null,
    nextText: String = TvStrings.NEXT,
    onNext: (() -> Unit)? = null
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) {
            var isFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .background(
                        if (isFocused) SurfaceElevated
                        else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        RoundedCornerShape(14.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isFocused) Gold.copy(alpha = 0.5f) else CardBorder,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .onFocusChanged { isFocused = it.isFocused }
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyUp ||
                            event.key == Key.DirectionCenter && event.type == KeyEventType.KeyUp
                        ) {
                            onBack(); true
                        } else false
                    }
                    .padding(horizontal = 32.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = TvStrings.PREVIOUS,
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        if (onNext != null) {
            var isFocused by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .background(
                        if (isFocused) Gold else TealPrimary,
                        RoundedCornerShape(14.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isFocused) Gold else TealPrimary.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .onFocusChanged { isFocused = it.isFocused }
                    .focusable()
                    .onKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyUp ||
                            event.key == Key.DirectionCenter && event.type == KeyEventType.KeyUp
                        ) {
                            onNext(); true
                        } else false
                    }
                    .padding(horizontal = 32.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = nextText,
                    fontSize = 20.sp,
                    color = Color.White
                )
            }
        }
    }
}
