package com.tunisianprayertimes.tv.kiosk

import android.content.Context
import java.io.File

/**
 * Which boot of the box this is, for the clock guard's reset check: the kernel's boot id, which only a
 * real boot changes. A restart of the system alone (its server crashing) keeps the kernel, its clock
 * and the time since boot, and is no reset. Where apps cannot read that id (older systems), the
 * system's boot count, which such a restart also moves: it is then taken for a boot, and at worst the
 * time is asked for again.
 */
object BootId {
    private val KERNEL_BOOT_ID = File("/proc/sys/kernel/random/boot_id")

    fun current(context: Context): String? = of(runCatching { KERNEL_BOOT_ID.readText() }.getOrNull(), BootTiming.bootCount(context))

    /** The kernel's boot id ([kernel], null when unreadable), else the system's boot [count] (-1 when unknown). */
    internal fun of(kernel: String?, count: Int): String? =
        kernel?.trim()?.takeIf { it.isNotEmpty() } ?: count.takeIf { it >= 0 }?.let { "count $it" }
}
