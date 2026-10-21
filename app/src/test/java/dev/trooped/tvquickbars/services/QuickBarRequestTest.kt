package dev.trooped.tvquickbars.services

import dev.trooped.tvquickbars.data.QuickBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuickBarRequestTest {
    private val living = QuickBar(id = "living-id", name = "Living room")
    private val bedroom = QuickBar(id = "bedroom-id", name = "Bedroom")
    private val disabled = QuickBar(id = "disabled-id", name = "Disabled", isEnabled = false)
    private val bars = listOf(living, bedroom, disabled)

    @Test fun selectsByIdOrExactName() {
        assertEquals(living, QuickBarRequest.resolve(bars, "living-id", null))
        assertEquals(bedroom, QuickBarRequest.resolve(bars, null, "Bedroom"))
    }

    @Test fun rejectsMissingUnknownBlankOrConflictingSelectors() {
        assertNull(QuickBarRequest.resolve(bars, null, null))
        assertNull(QuickBarRequest.resolve(bars, "missing", null))
        assertNull(QuickBarRequest.resolve(bars, "", null))
        assertNull(QuickBarRequest.resolve(bars, null, " "))
        assertNull(QuickBarRequest.resolve(bars, "living-id", "Bedroom"))
        assertNull(QuickBarRequest.resolve(bars, null, "bedroom"))
    }

    @Test fun rejectsDisabledAndAmbiguousBars() {
        assertNull(QuickBarRequest.resolve(bars, "disabled-id", null))
        assertNull(QuickBarRequest.resolve(bars, null, "Disabled"))
        val duplicates = bars + living.copy(id = "another-id")
        assertNull(QuickBarRequest.resolve(duplicates, null, "Living room"))
        assertEquals(living, QuickBarRequest.resolve(duplicates, "living-id", null))
    }

    @Test fun idStillWorksAfterRename() {
        val renamed = living.copy(name = "Lounge")
        assertEquals(renamed, QuickBarRequest.resolve(listOf(renamed), "living-id", null))
        assertNull(QuickBarRequest.resolve(listOf(renamed), null, "Living room"))
    }
}
