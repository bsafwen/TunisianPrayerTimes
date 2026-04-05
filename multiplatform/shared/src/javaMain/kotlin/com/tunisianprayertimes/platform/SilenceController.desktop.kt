package com.tunisianprayertimes.platform

/**
 * Desktop silence controller.
 * Uses OS-specific commands to mute/unmute the system volume.
 */
actual object SilenceController {
    private var silentState = false

    actual fun isSilent(): Boolean = silentState

    actual fun enableSilence() {
        silentState = true
        setSystemMute(true)
    }

    actual fun disableSilence() {
        silentState = false
        setSystemMute(false)
    }

    actual fun hasPermission(): Boolean = true // desktop always has permission

    private val osName: String = System.getProperty("os.name", "").lowercase()

    private fun setSystemMute(mute: Boolean) {
        try {
            when {
                osName.contains("win") -> {
                    // PowerShell: use [Audio]::Mute / nircmd, or SendKeys for volume mute toggle
                    val script = if (mute) {
                        "\$obj = New-Object -ComObject WScript.Shell; \$obj.SendKeys([char]0xAD)"
                    } else {
                        // Unmute: send mute toggle key (if already muted, this unmutes)
                        "\$obj = New-Object -ComObject WScript.Shell; \$obj.SendKeys([char]0xAD)"
                    }
                    Runtime.getRuntime().exec(arrayOf("powershell", "-Command", script))
                }
                osName.contains("mac") -> {
                    val vol = if (mute) "0" else "50"
                    Runtime.getRuntime().exec(arrayOf("osascript", "-e", "set volume output volume $vol"))
                }
                else -> {
                    // Linux
                    val cmd = if (mute) "amixer set Master mute" else "amixer set Master unmute"
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                }
            }
        } catch (e: Exception) {
            System.err.println("Failed to set system mute: ${e.message}")
        }
    }
}
