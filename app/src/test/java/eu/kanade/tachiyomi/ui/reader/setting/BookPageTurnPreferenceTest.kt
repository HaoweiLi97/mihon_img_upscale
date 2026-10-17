package eu.kanade.tachiyomi.ui.reader.setting

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class BookPageTurnPreferenceTest {
    @Test
    fun `enabling book turns enables the master animation switch`() {
        val preferences = ReaderPreferences(InMemoryPreferenceStore())
        preferences.pageTransitions.set(false)
        preferences.setBookPageTransitions(true)
        assertTrue(preferences.pageTransitions.get())
        assertTrue(preferences.bookPageTransitions.get())
    }

    @Test
    fun `disabling book turns keeps ordinary page transitions available`() {
        val preferences = ReaderPreferences(InMemoryPreferenceStore())
        preferences.setBookPageTransitions(false)
        assertTrue(preferences.pageTransitions.get())
        assertFalse(preferences.bookPageTransitions.get())
    }
}
