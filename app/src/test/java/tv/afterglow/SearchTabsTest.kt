package tv.afterglow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTabsTest {
    @Test
    fun doesNotShowTabsWhenQueryIsBlank() {
        assertFalse(shouldShowSearchFilterTabs("", hasResults = true))
        assertFalse(shouldShowSearchFilterTabs("   ", hasResults = true))
        assertFalse(shouldShowSearchFilterTabs("", hasResults = false))
    }

    @Test
    fun doesNotShowTabsWhenNoResults() {
        assertFalse(shouldShowSearchFilterTabs("matrix", hasResults = false))
    }

    @Test
    fun showsTabsWhenUserTypedAndHasResults() {
        assertTrue(shouldShowSearchFilterTabs("matrix", hasResults = true))
    }
}
