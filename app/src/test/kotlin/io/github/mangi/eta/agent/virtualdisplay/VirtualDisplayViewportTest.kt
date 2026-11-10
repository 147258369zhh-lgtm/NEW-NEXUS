package io.github.mangi.eta.agent.virtualdisplay

import org.junit.Assert.*
import org.junit.Test

class VirtualDisplayViewportTest {
    @Test fun fitCenterRejectsLetterboxingAndMapsContent() {
        val viewport = VirtualDisplayViewport.fit(400, 400, 200, 400)!!
        assertNull(viewport.toDisplay(99f, 200f))
        assertNull(viewport.toDisplay(300f, 200f))
        assertEquals(VirtualDisplayViewport.Point(100, 200), viewport.toDisplay(200f, 200f))
        assertEquals(VirtualDisplayViewport.Point(199, 399), viewport.toDisplay(900f, 900f, clamp = true))
    }
    @Test fun invalidSizesAndNonFiniteInputCannotProduceActions() {
        assertNull(VirtualDisplayViewport.fit(0, 400, 200, 400))
        assertNull(VirtualDisplayViewport.fit(400, 400, -1, 400))
        val viewport = VirtualDisplayViewport.fit(400, 400, 200, 400)!!
        assertNull(viewport.toDisplay(Float.NaN, 200f)); assertNull(viewport.toDisplay(200f, Float.POSITIVE_INFINITY))
    }
}
