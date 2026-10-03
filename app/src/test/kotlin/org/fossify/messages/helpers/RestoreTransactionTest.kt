package org.fossify.messages.helpers

import org.junit.Assert.*
import org.junit.Test

class RestoreTransactionTest {
    @Test fun rejectedImportRollsBackAndNeverCommits() {
        val events = mutableListOf<String>()
        assertFalse(RestoreTransaction.run({ events.add("apply"); false }, { events.add("commit") }, { events.add("rollback"); true }))
        assertEquals(listOf("apply", "rollback"), events)
    }
    @Test fun commitFailureRestoresOldConfiguration() {
        var value = "old"
        assertFalse(RestoreTransaction.run({ value = "new"; true }, { error("disk") }, { value = "old"; true }))
        assertEquals("old", value)
    }
    @Test fun encryptionOrPreparationFailureNeverCommits() {
        var committed = false
        assertFalse(RestoreTransaction.run({ error("key unavailable") }, { committed = true }, { true }))
        assertFalse(committed)
    }
    @Test fun successfulCommitDoesNotRollBack() {
        var rolledBack = false
        assertTrue(RestoreTransaction.run({ true }, {}, { rolledBack = true; true }))
        assertFalse(rolledBack)
    }
    @Test fun rollbackFailureCannotReportSuccess() {
        assertFalse(RestoreTransaction.run({ false }, { fail("Unexpected commit") }, { false }))
    }
}
