package mihon.core.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ForkMigrationVersionsTest {
    @Test
    fun `existing fork upgrades start at imported upstream baseline`() {
        assertEquals(16, ForkMigrationVersions.startVersion(39, 0, 40, 38))
        assertEquals(10, ForkMigrationVersions.startVersion(10, 0, 40, 38))
    }

    @Test
    fun `fresh install keeps upstream initial migration behavior`() {
        assertEquals(0, ForkMigrationVersions.startVersion(0, 0, 40, 38))
    }

    @Test
    fun `completed migrations stay completed across launches`() {
        assertEquals(38, ForkMigrationVersions.startVersion(40, 38, 40, 38))
    }

    @Test
    fun `fork only upgrade runs setup jobs without replaying upstream settings changes`() {
        assertEquals(37, ForkMigrationVersions.startVersion(40, 38, 41, 38))
    }

    @Test
    fun `future upstream upgrade runs even when fork version number is higher`() {
        assertEquals(38, ForkMigrationVersions.startVersion(64, 38, 65, 42))
    }
}
