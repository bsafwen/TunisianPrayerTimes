package com.tunisianprayertimes.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens for the prayer-times silence screen. */
internal object PrayerSilencePalette {
    val Surface = Color(0xFFFFFFFF)
    val PrimaryText = Color(0xFF164E46)
    val SecondaryText = Color(0xFF4F6B65)
    val InteractiveTeal = Color(0xFF087F75)
    val GoldAccent = Color(0xFFA47B25)
    val SoftBorder = Color(0xFFE1EAE5)
    val InactiveTrack = Color(0xFFDCE5DF)
    val Tick = Color(0xFF7F9C90)
    val TickOnRange = Color(0xCCFFFFFF)
    val TintedStrip = Color(0xFFF0F5F2)
    val FieldPressed = Color(0xFFE3EDE8)
    val HandleFill = Color(0xFFFFFFFF)
    val Halo = Color(0x1F087F75)
    val OnAccent = Color(0xFFFFFFFF)
    const val DeemphasisAlpha = 0.42f
}

internal object PrayerSilenceDimens {
    val ContainerCorner = 22.dp
    val ContainerPadding = 16.dp
    val SectionHorizontalPadding = 16.dp
    val SectionVerticalPadding = 8.dp
    val SectionSpacing = 6.dp
    val SectionDetailsSpacing = 2.dp
    val FieldCorner = 14.dp
    val FieldMinHeight = 52.dp
    val FieldPaddingHorizontal = 14.dp
    val FieldPaddingVertical = 6.dp
    // Small permanent gap between the endpoint block and the track. The drag
    // tooltip is anchored by its bottom edge and borrows the block above instead
    // of reserving a permanent empty band.
    val SliderTrackTopClearance = 10.dp
    val SliderTrackHeight = 36.dp
    val SliderLabelsHeight = 16.dp
    val SliderHandleSize = 24.dp
    val SliderHandleTouchTarget = 48.dp
    val SliderTrackThickness = 8.dp
    val SliderTooltipPaddingHorizontal = 10.dp
    val SliderTooltipPaddingVertical = 2.dp
    val SliderMarkerHeight = 32.dp
    val MinTouchTarget = 48.dp
    val IconSize = 18.dp
}

internal object PrayerSilenceTypography {
    val PrayerName = 20.sp
    val PrayerTime = 19.sp
    val FieldLabel = 12.sp
    val FieldValue = 19.sp
    val Duration = 14.sp
    val ScaleLabel = 11.sp
    val AdhanLabel = 12.sp
    val Hint = 12.sp
    val Recurrence = 13.sp
    val ValueLabel = 12.sp
    val Section = 16.sp
}
