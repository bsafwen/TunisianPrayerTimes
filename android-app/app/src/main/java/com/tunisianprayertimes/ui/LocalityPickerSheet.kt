package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.Locality
import com.tunisianprayertimes.LocalityKindClass
import com.tunisianprayertimes.LocalityPickerCatalog
import com.tunisianprayertimes.LocalitySearchQuery
import com.tunisianprayertimes.R
import com.tunisianprayertimes.localityKindClass
import com.tunisianprayertimes.searchLocalities
import com.tunisianprayertimes.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LocalityPickerSheet(
    catalog: LocalityPickerCatalog,
    gouvernorats: List<Gouvernorat>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelect: (Locality) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialIndex = remember(catalog, selectedId) { catalog.selectionScrollIndex(selectedId) }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = initialIndex)
    val focusRequester = remember { FocusRequester() }
    val governorateNames = remember(gouvernorats) { gouvernorats.associate { it.id to it.nomAr } }
    val search = remember(query) { LocalitySearchQuery.parse(query) }
    // Opening renders the prepared groups; only a typed query filters rows.
    val groups = remember(catalog, search, governorateNames) {
        catalog.groups.mapNotNull { group ->
            val rows = searchLocalities(group.rows, search)
            if (rows.isEmpty()) null
            else Triple(group.governorateId, governorateNames[group.governorateId] ?: group.fallbackName, rows)
        }
    }
    LaunchedEffect(query, catalog) {
        listState.scrollToItem(if (query.isBlank()) catalog.selectionScrollIndex(selectedId) else 0)
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = Color.White,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding().imePadding()) {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, GoldLight),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)
                        .focusRequester(focusRequester).testTag("locality_search"),
                    textStyle = TextStyle(fontSize = 15.sp, color = TextDark, textAlign = TextAlign.Start),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(
                            stringResource(R.string.hint_search_delegation), fontSize = 15.sp, color = TextMuted,
                        )
                        inner()
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
            if (groups.isEmpty()) {
                Text(
                    stringResource(R.string.locality_no_results),
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    textAlign = TextAlign.Center, color = TextMuted, fontSize = 14.sp,
                )
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f).testTag("locality_list")) {
                    groups.forEach { (governorId, governorName, rows) ->
                        stickyHeader(key = "governorate:$governorId") {
                            Text(
                                governorName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = GreenPrimary,
                                modifier = Modifier.fillMaxWidth().background(BgCream)
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                                    .testTag("locality_governorate_$governorId"),
                            )
                        }
                        items(rows, key = { it.id }, contentType = { "locality" }) { locality ->
                            val selected = locality.representsSelection(selectedId)
                            val parent = locality.parentName.takeIf {
                                it.isNotBlank() && it != locality.name && it != governorName
                            }
                            val type = if (locality.id in catalog.typeContextIds) {
                                stringResource(localityKindLabel(locality.kind))
                            } else null
                            val subtitle = listOfNotNull(type, parent).joinToString(" · ")
                            Row(
                                modifier = Modifier.fillMaxWidth().testTag("locality_row_${locality.id}")
                                    .selectable(selected = selected, role = Role.RadioButton, onClick = { onSelect(locality) })
                                    .background(if (selected) GoldLight.copy(alpha = 0.2f) else Color.Transparent)
                                    .padding(horizontal = 28.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        locality.name, fontSize = 15.sp,
                                        color = if (selected) GreenPrimaryDark else TextDark,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    )
                                    if (subtitle.isNotBlank()) {
                                        Text(subtitle, fontSize = 12.sp, color = TextMuted)
                                    }
                                }
                                if (selected) Icon(
                                    painterResource(R.drawable.ic_check), contentDescription = null,
                                    tint = GreenPrimary, modifier = Modifier.size(18.dp),
                                )
                            }
                            HorizontalDivider(color = Divider, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                    }
                }
            }
        }
    }
}

private fun localityKindLabel(kind: String): Int = when (localityKindClass(kind)) {
    LocalityKindClass.DELEGATION -> R.string.locality_kind_delegation
    LocalityKindClass.SECTOR -> R.string.locality_kind_sector
    LocalityKindClass.MUNICIPALITY -> R.string.locality_kind_municipality
    LocalityKindClass.TOWN -> R.string.locality_kind_town
    LocalityKindClass.VILLAGE -> R.string.locality_kind_village
    LocalityKindClass.HAMLET -> R.string.locality_kind_hamlet
    LocalityKindClass.NEIGHBORHOOD -> R.string.locality_kind_neighborhood
    LocalityKindClass.RESIDENTIAL -> R.string.locality_kind_residential
    LocalityKindClass.AREA -> R.string.locality_kind_area
}
