package com.tunisianprayertimes.tv.ui

import com.tunisianprayertimes.tv.ui.common.canvasDensity
import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualCanvasTest {

    @Test
    fun everyTvGetsTheSameCanvas() {
        assertEquals(1280 / 960f, canvasDensity(1280, 720), 0.001f)
        assertEquals(2f, canvasDensity(1920, 1080), 0.001f)
        assertEquals(4f, canvasDensity(3840, 2160), 0.001f)
        // Not 16:9: the side that runs out first decides, so everything still fits.
        assertEquals(1280 / 960f, canvasDensity(1280, 800), 0.001f)
        assertEquals(720 / 540f, canvasDensity(1440, 720), 0.001f)
        // Before the first layout the size may be zero.
        assertEquals(1f, canvasDensity(0, 0), 0.001f)
    }
}
