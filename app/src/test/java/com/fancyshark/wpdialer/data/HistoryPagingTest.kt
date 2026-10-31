package com.fancyshark.wpdialer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryPagingTest {

    private val page = HistoryPaging.PAGE_SIZE

    @Test
    fun aFullPageMeansMayHaveMore() {
        assertTrue(HistoryPaging.hasMore(page, page))
        assertFalse(HistoryPaging.hasMore(page - 1, page))
        // Bumped but not loaded yet: no second bump while waiting.
        assertFalse(HistoryPaging.hasMore(page, HistoryPaging.nextLimit(page)))
    }

    @Test
    fun limitGrowsByPagesUpToTheCap() {
        assertEquals(2 * page, HistoryPaging.nextLimit(page))
        assertEquals(HistoryPaging.MAX_LIMIT, HistoryPaging.nextLimit(HistoryPaging.MAX_LIMIT))
        assertFalse(HistoryPaging.hasMore(HistoryPaging.MAX_LIMIT, HistoryPaging.MAX_LIMIT))
    }

    @Test
    fun nearEndTriggersWithinThePrefetchWindow() {
        assertFalse(HistoryPaging.nearEnd(-1, 0))
        assertFalse(HistoryPaging.nearEnd(10, 100))
        assertTrue(HistoryPaging.nearEnd(100 - 1 - HistoryPaging.PREFETCH, 100))
        assertTrue(HistoryPaging.nearEnd(5, 8))
    }

    private fun call(id: Long, number: String, name: String? = null) =
        HistoryItem(id = id, number = number, name = name, type = 1, date = 1000 - id)

    @Test
    fun searchMatchesNamesAndDigits() {
        val alice = call(1, "+961 3 039 056", "Alice Martin")
        assertTrue(HistorySearch.matches(alice, "alice"))
        assertTrue(HistorySearch.matches(alice, "MART"))
        // National spelling of the same digits (trunk zero stripped).
        assertTrue(HistorySearch.matches(alice, "03 039"))
        assertTrue(HistorySearch.matches(alice, "039056"))
        assertFalse(HistorySearch.matches(alice, "12"))
        assertFalse(HistorySearch.matches(alice, "bob"))
        assertFalse(HistorySearch.matches(alice, "  "))
        // Letters + digits is a name query, not a digit query.
        assertFalse(HistorySearch.matches(alice, "a 039"))
    }

    @Test
    fun searchKeepsTheNewestCallPerLine() {
        val history = listOf(
            call(1, "+961 3 039 056", "Alice"),
            call(2, "03 039 056", "Alice"),
            call(3, "+1 212 555 0390", null),
            call(4, "", null),
        )
        assertEquals(listOf(1L, 3L), HistorySearch.search(history, "039").map { it.id })
        assertEquals(listOf(1L), HistorySearch.search(history, "039", max = 1).map { it.id })
    }
}
