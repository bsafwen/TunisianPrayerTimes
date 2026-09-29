package com.tunisianprayertimes.tv.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Delegation
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.data.PrefsManager
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.ChoiceGrid
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.common.KeyHints
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.theme.Amiri
import com.tunisianprayertimes.tv.ui.theme.Dots
import com.tunisianprayertimes.tv.ui.theme.Kufi
import com.tunisianprayertimes.tv.ui.theme.Medallion
import com.tunisianprayertimes.tv.ui.theme.MedallionRule
import com.tunisianprayertimes.tv.ui.theme.Midad
import com.tunisianprayertimes.tv.ui.theme.midadStyle

/** Location, delegation, iqamah, name. */
private const val STEPS = 4

/**
 * Onboarding, four steps on the settings' palette:
 * 1. Select gouvernorat (under the welcome)
 * 2. Select delegation
 * 3. Configure iqamah and prayer duration per prayer
 * 4. Mosque name (optional) + confirm
 */
@Composable
fun SetupWizard(
    gouvernorats: List<Gouvernorat>,
    onComplete: (
        gouvernoratId: Int,
        delegation: Delegation,
        iqamahConfigs: Map<Prayer, IqamahConfig>,
        mosqueName: String
    ) -> Unit
) {
    var step by remember { mutableIntStateOf(0) }
    var selectedGouvernorat by remember { mutableStateOf<Gouvernorat?>(null) }
    var selectedDelegation by remember { mutableStateOf<Delegation?>(null) }
    // Iqamah and prayer duration per prayer start from the shared defaults.
    var iqamahConfigs by remember {
        mutableStateOf(PrefsManager.EDITABLE.associateWith { IqamahConfig.from(MosqueSchedule.DEFAULT.settings(it)) })
    }
    var mosqueName by remember { mutableStateOf("") }

    // Back returns to the previous step, keeping what was entered; on the first step it stays.
    BackHandler(enabled = step > 0) { step -= 1 }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Midad.Ground)
            .padding(horizontal = 48.dp, vertical = 27.dp)
    ) {
        when (step) {
            0 -> WelcomeStep(
                gouvernorats = gouvernorats,
                selected = selectedGouvernorat,
                onSelect = { g ->
                    selectedGouvernorat = g
                    step = 1
                }
            )
            1 -> WizardStep(
                step = 1,
                title = "${TvStrings.SETUP_SELECT_DELEGATION} — ${selectedGouvernorat!!.nomAr}",
                onBack = { step = 0 },
            ) {
                val delegations = selectedGouvernorat!!.delegations
                ChoiceGrid(
                    choices = delegations,
                    label = { it.nomAr },
                    onChoose = { d ->
                        selectedDelegation = d
                        step = 2
                    },
                    // Back from the iqamah returns to the delegation chosen, not the top of the list.
                    focusFirst = selectedDelegation?.takeIf { it in delegations },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            2 -> WizardStep(
                step = 2,
                title = TvStrings.SETUP_IQAMAH_TITLE,
                subtitle = TvStrings.SETUP_IQAMAH_SUBTITLE,
                onBack = { step = 1 },
                onNext = { step = 3 },
            ) {
                IqamahTable(
                    configs = iqamahConfigs,
                    onChanged = { prayer, config -> iqamahConfigs = iqamahConfigs + (prayer to config) },
                    modifier = Modifier.weight(1f),
                )
            }
            3 -> WizardStep(
                step = 3,
                title = TvStrings.SETUP_MOSQUE_NAME,
                onBack = { step = 2 },
                nextText = TvStrings.CONFIRM,
                focusNext = true,
                onNext = {
                    onComplete(
                        selectedGouvernorat!!.id,
                        selectedDelegation!!,
                        iqamahConfigs,
                        mosqueName.trim().take(MosqueProfile.MAX_NAME_LENGTH)
                    )
                },
            ) {
                MosqueNameField(mosqueName, onNameChanged = { mosqueName = it })
                Text(
                    "${TvStrings.DELEGATION_LABEL}: ${selectedDelegation!!.nomAr} — ${selectedGouvernorat!!.nomAr}",
                    style = midadStyle(15.sp, color = Midad.Muted),
                )
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * The first step: a restrained welcome over the choice of gouvernorat. The ornament is the Blue
 * Qur'an's silver medallion between fading rules, and the app's name in Kufic.
 */
@Composable
private fun WelcomeStep(
    gouvernorats: List<Gouvernorat>,
    selected: Gouvernorat?,
    onSelect: (Gouvernorat) -> Unit
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(TvStrings.SETUP_WELCOME, style = midadStyle(20.sp, color = Midad.Verse, family = Amiri, lineHeight = 1.4f))
        Text(TvStrings.APP_NAME, style = midadStyle(30.sp, FontWeight.SemiBold, family = Kufi, lineHeight = 1.25f))
        MedallionRule(width = 260.dp, modifier = Modifier.padding(vertical = 6.dp))
        Text(TvStrings.SETUP_WELCOME_SUB, style = midadStyle(15.sp, color = Midad.Muted))
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(TvStrings.SETUP_SELECT_GOUVERNORAT, style = midadStyle(21.sp, FontWeight.SemiBold))
            StepIndicator(0)
        }
        ChoiceGrid(
            choices = gouvernorats,
            label = { it.nomAr },
            onChoose = onSelect,
            focusFirst = selected,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/**
 * The frame of the steps after the welcome: the app's name and where the admin is, the step's title,
 * the step, then Previous and Next (Next on the left, the way Arabic reads forward).
 */
@Composable
private fun WizardStep(
    step: Int,
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    onNext: (() -> Unit)? = null,
    nextText: String = TvStrings.NEXT,
    /** The step's own content does not take the focus: the Next key does. */
    focusNext: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Medallion(14.dp)
                Text(TvStrings.APP_NAME, style = midadStyle(17.sp, FontWeight.Medium, family = Kufi))
            }
            StepIndicator(step)
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = midadStyle(26.sp, FontWeight.SemiBold))
            if (subtitle != null) Text(subtitle, style = midadStyle(14.sp, color = Midad.Muted))
        }
        Column(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FocusableListItem(text = TvStrings.PREVIOUS, onClick = onBack, modifier = Modifier.width(160.dp))
            if (onNext != null) {
                FocusableListItem(text = nextText, onClick = onNext, modifier = Modifier.width(160.dp).initialFocus(focusNext))
            }
            Spacer(Modifier.weight(1f))
            KeyHints(listOf(TvStrings.HINT_BACK_TO_STEP))
        }
    }
}

/** «الخطوة 2 من 4» and four dots, lit from the right as the steps are done. */
@Composable
private fun StepIndicator(step: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(TvStrings.step(step + 1, STEPS), style = midadStyle(14.sp, color = Midad.Muted))
        Dots(count = STEPS, lit = { it <= step })
    }
}

/** The mosque's name; optional, and read from across the room once set. */
@Composable
private fun MosqueNameField(name: String, onNameChanged: (String) -> Unit) {
    OutlinedTextField(
        value = name,
        onValueChange = onNameChanged,
        placeholder = { Text(TvStrings.SETUP_MOSQUE_HINT, style = midadStyle(20.sp, color = Midad.Dim)) },
        colors = mosqueNameFieldColors(),
        textStyle = midadStyle(20.sp),
        shape = RoundedCornerShape(9.dp),
        modifier = Modifier.width(480.dp).padding(top = 6.dp),
        singleLine = true
    )
}

/** The name field on the ink ground; its focused edge is the gold of the focus ring. */
@Composable
internal fun mosqueNameFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Midad.Gold,
    unfocusedBorderColor = Midad.Keyline,
    focusedContainerColor = Midad.Surface,
    unfocusedContainerColor = Midad.Surface,
    focusedTextColor = Midad.Text,
    unfocusedTextColor = Midad.Text,
    cursorColor = Midad.Text,
)
