package com.tunisianprayertimes

internal fun formatArabicMinutes(value: Int): String {
    val count = value.coerceAtLeast(0)
    return when (count) {
        0 -> "0 دقيقة"
        1 -> "دقيقة واحدة"
        2 -> "دقيقتين"
        in 3..10 -> "$count دقائق"
        else -> "$count دقيقة"
    }
}