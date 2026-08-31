package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.NavigationMenuPosition
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationMenuPositionTest {
    @Test
    fun `stored values restore side and top positions`() {
        assertEquals(
            NavigationMenuPosition.SIDE,
            parseNavigationMenuPosition(NavigationMenuPosition.SIDE.name)
        )
        assertEquals(
            NavigationMenuPosition.TOP,
            parseNavigationMenuPosition(NavigationMenuPosition.TOP.name)
        )
    }

    @Test
    fun `missing or invalid values safely preserve the existing side menu`() {
        assertEquals(NavigationMenuPosition.SIDE, parseNavigationMenuPosition(null))
        assertEquals(NavigationMenuPosition.SIDE, parseNavigationMenuPosition("UNKNOWN"))
    }
}
