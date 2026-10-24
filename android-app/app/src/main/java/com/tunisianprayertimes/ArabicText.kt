package com.tunisianprayertimes

internal fun formatArabicMinutes(value: Int, standalone: Boolean = false): String {
    val count = value.coerceAtLeast(0)
    return when (count) {
        0 -> "0 دقيقة"
        1 -> "دقيقة واحدة"
        2 -> if (standalone) "دقيقتان" else "دقيقتين"
        else -> if (count % 100 in 3..10) "$count دقائق" else "$count دقيقة"
    }
}