package com.tunisianprayertimes.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tunisianprayertimes.Gouvernorat
import com.tunisianprayertimes.Locality
import com.tunisianprayertimes.R
import com.tunisianprayertimes.searchLocalities
import com.tunisianprayertimes.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun LocalityPickerSheet(
    catalog: List<Locality>,
    gouvernorats: List<Gouvernorat>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelect: (Locality) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val groups = remember(catalog, query, gouvernorats) {
        val matches = searchLocalities(catalog, query).groupBy { it.governorateId }
        val names = gouvernorats.associate { it.id to it.nomAr }
        (gouvernorats.map { it.id } + matches.keys.filter { it !in names }).mapNotNull { id ->
            matches[id]?.let { rows -> Triple(id, names[id] ?: rows.first().parentName, rows) }
        }
    }
    LaunchedEffect(query, catalog) {
        var selectedIndex = 0
        if (query.isBlank()) {
            for ((_, _, rows) in groups) {
                val index = rows.indexOfFirst { it.id == selectedId }
                if (index >= 0) {
                    // Leave one item above the selection so the sticky header cannot cover it.
                    selectedIndex += index
                    break
                }
                selectedIndex += rows.size + 1
            }
            if (selectedIndex >= groups.sumOf { it.third.size + 1 }) selectedIndex = 0
        }
        listState.scrollToItem(selectedIndex)
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
                            val selected = locality.id == selectedId
                            Row(
                                modifier = Modifier.fillMaxWidth().testTag("locality_row_${locality.id}")
                                    .clickable { onSelect(locality) }
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
                                    if (locality.parentName.isNotBlank() && locality.parentName != locality.name &&
                                        locality.parentName != governorName) {
                                        Text(locality.parentName, fontSize = 12.sp, color = TextMuted)
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
