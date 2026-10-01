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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Delegation
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.Prayer
import com.tunisianprayertimes.mosque.DisplayTexts
import com.tunisianprayertimes.mosque.MosqueProfile
import com.tunisianprayertimes.mosque.MosqueSchedule
import com.tunisianprayertimes.tv.data.IqamahConfig
import com.tunisianprayertimes.tv.data.IqamahMode
import com.tunisianprayertimes.tv.data.PrefsManager
import com.tunisianprayertimes.tv.ui.TvStrings
import com.tunisianprayertimes.tv.ui.common.ChoiceGrid
import com.tunisianprayertimes.tv.ui.common.FocusableListItem
import com.tunisianprayertimes.tv.ui.common.KeyHints
import com.tunisianprayertimes.tv.ui.common.initialFocus
import com.tunisianprayertimes.tv.ui.common.rtl
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
 * The iqamah table through a recreation of the activity: eight numbers a prayer (every field of
 * [IqamahConfig], held and the Jumu'a dua as 1 or 0), in [PrefsManager.EDITABLE]'s order.
 */
internal val IqamahConfigsSaver: Saver<Map<Prayer, IqamahConfig>, Any> = listSaver(
    save = { configs ->
        PrefsManager.EDITABLE.flatMap { prayer ->
            configs.getValue(prayer).run {
                listOf(mode.ordinal, delayMinutes, fixedHour, fixedMinute, salahMinutes, if (held) 1 else 0, khutbaMinutes, if (adhanDua) 1 else 0)
            }
        }
    },
    restore = { values ->
        PrefsManager.EDITABLE.zip(values.chunked(CONFIG_VALUES)) { prayer, value ->
            prayer to IqamahConfig(IqamahMode.entries[value[0]], value[1], value[2], value[3], value[4], held = value[5] == 1, khutbaMinutes = value[6],
                adhanDua = value[7] == 1)
        }.toMap()
    },
)

private const val CONFIG_VALUES = 8

/**
 * Onboarding, four steps on the settings' palette:
 * 1. Select gouvernorat (under the welcome)
 * 2. Select delegation
 * 3. Configure iqamah and prayer duration per prayer
 * 4. Mosque name (optional) + confirm
 *
 * [onUsbSetup], when a plugged-in key holds a settings file that names the mosque's place, offers
 * to set the whole TV from it instead. What was entered survives a recreation of the activity (a
 * change of output mode, a low-memory box back from a system page).
 */
@Composable
fun SetupWizard(
    gouvernorats: List<Gouvernorat>,
    onUsbSetup: (() -> Unit)? = null,
    onComplete: (
        gouvernoratId: Int,
        delegation: Delegation,
        iqamahConfigs: Map<Prayer, IqamahConfig>,
        mosqueName: String
    ) -> Unit
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var gouvernoratId by rememberSaveable { mutableStateOf<Int?>(null) }
    var delegationId by rememberSaveable { mutableStateOf<Int?>(null) }
    val selectedGouvernorat = gouvernorats.find { it.id == gouvernoratId }
    val selectedDelegation = selectedGouvernorat?.delegations?.find { it.id == delegationId }
    // Iqamah and prayer duration per prayer start from the shared defaults.
    var iqamahConfigs by rememberSaveable(stateSaver = IqamahConfigsSaver) {
        mutableStateOf(PrefsManager.EDITABLE.associateWith { IqamahConfig.from(MosqueSchedule.DEFAULT.settings(it)) })
    }
    var mosqueName by rememberSaveable { mutableStateOf("") }
    // Where the times will be computed, on the steps after the choice: a delegation chosen by a slip shows at once.
    val place = selectedGouvernorat?.let { g -> selectedDelegation?.let { "${it.nomAr} — ${g.nomAr}" } }

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
                    gouvernoratId = g.id
                    step = 1
                },
                onUsbSetup = onUsbSetup,
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
                        delegationId = d.id
                        step = 2
                    },
                    // Back from the iqamah returns to the delegation chosen, not the top of the list.
                    focusFirst = selectedDelegation,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
            2 -> WizardStep(
                step = 2,
                title = TvStrings.SETUP_IQAMAH_TITLE,
                subtitle = TvStrings.SETUP_IQAMAH_SUBTITLE,
                aside = place,
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
 * Qur'an's silver medallion between fading rules, and the app's name in Kufic. [onUsbSetup] puts
 * «الإعداد من مفتاح USB» beside the title, one press up from the grid, which keeps the focus.
 */
@Composable
private fun WelcomeStep(
    gouvernorats: List<Gouvernorat>,
    selected: Gouvernorat?,
    onSelect: (Gouvernorat) -> Unit,
    onUsbSetup: (() -> Unit)?,
) {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(DisplayTexts.BASMALA.text, style = midadStyle(20.sp, color = Midad.Verse, family = Amiri, lineHeight = 1.4f))
        Text(TvStrings.APP_NAME, style = midadStyle(30.sp, FontWeight.SemiBold, family = Kufi, lineHeight = 1.25f))
        MedallionRule(width = 260.dp, modifier = Modifier.padding(vertical = 6.dp))
        Text(TvStrings.SETUP_WELCOME_SUB, style = midadStyle(15.sp, color = Midad.Muted))
        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(TvStrings.SETUP_SELECT_GOUVERNORAT, style = midadStyle(21.sp, FontWeight.SemiBold))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (onUsbSetup != null) {
                    FocusableListItem(text = TvStrings.USB_SETUP, onClick = onUsbSetup, modifier = Modifier.width(220.dp))
                }
                StepIndicator(0)
            }
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
    /** At the other end of the title: the place chosen, once it is. */
    aside: String? = null,
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(title, style = midadStyle(26.sp, FontWeight.SemiBold), modifier = Modifier.alignByBaseline())
                if (aside != null) Text(aside, style = midadStyle(17.sp, FontWeight.Medium).rtl(), modifier = Modifier.alignByBaseline())
            }
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
