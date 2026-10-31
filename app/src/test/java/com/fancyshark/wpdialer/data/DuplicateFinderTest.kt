package com.fancyshark.wpdialer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {

    private fun c(id: Long, name: String, phones: List<String> = emptyList(), emails: List<String> = emptyList()) =
        DupCandidate(id, "key$id", name, phones, emails)

    @Test
    fun normalizeStripsAccentsCaseAndPunctuation() {
        assertEquals("elodie anne d", DuplicateFinder.normalizeName("Élodie-Anne D."))
    }

    @Test
    fun similarNames() {
        assertTrue(DuplicateFinder.similarNames("Alice Martin", "alice martin"))
        assertTrue(DuplicateFinder.similarNames("Martin Alice", "Alice Martin"))
        assertTrue(DuplicateFinder.similarNames("Alice M", "Alice Martin"))
        assertTrue(DuplicateFinder.similarNames("A. Martin", "Alice Martin"))
        assertTrue(DuplicateFinder.similarNames("Jon Smith", "John Smith"))
        assertTrue(DuplicateFinder.similarNames("Hélène", "Helene"))
        assertFalse(DuplicateFinder.similarNames("Alice Martin", "Bob Stone"))
        assertFalse(DuplicateFinder.similarNames("Al", "Al"))
        assertFalse(DuplicateFinder.similarNames("Mom", "Tom"))
        assertFalse(DuplicateFinder.similarNames("Office 1", "Office 2"))
        assertFalse(DuplicateFinder.similarNames("+961 3 039 056", "+961 3 039 056"))
    }

    @Test
    fun levenshteinBounded() {
        assertEquals(1, DuplicateFinder.levenshtein("jon", "john"))
        assertEquals(3, DuplicateFinder.levenshtein("kitten", "sitting"))
        assertTrue(DuplicateFinder.levenshtein("abcdef", "uvwxyz", max = 2) > 2)
    }

    @Test
    fun matchPrefersNumberThenEmailThenName() {
        val a = c(1, "Alice Martin", listOf("03 039 056"), listOf("a@x.com"))
        val b = c(2, "Alice Martin", listOf("+961 3 039 056"), listOf("A@X.com"))
        assertEquals(DupReason.NUMBER, DuplicateFinder.match(a, b)?.reason)
        val e = c(3, "Ally", emptyList(), listOf("A@X.COM"))
        assertEquals(DupReason.EMAIL, DuplicateFinder.match(a, e)?.reason)
        val n = c(4, "Alice M")
        assertEquals(DupReason.NAME, DuplicateFinder.match(a, n)?.reason)
        assertNull(DuplicateFinder.match(a, c(5, "Zed")))
        assertNull(DuplicateFinder.match(a, a))
    }

    @Test
    fun groupsUnionTransitively() {
        val all = listOf(
            c(1, "Alice Martin", listOf("+961 3 039 056")),
            c(2, "A. Martin", listOf("03 039 056"), listOf("alice@x.com")),
            c(3, "Ally", emptyList(), listOf("ALICE@x.com")),
            c(4, "Bob Stone", listOf("+1 212 555 1234")),
            c(5, "Robert Stone", listOf("+1 646 555 1234")),
        )
        val groups = DuplicateFinder.findGroups(all)
        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L, 3L), groups[0].members.map { it.contactId })
        assertEquals(DupReason.NUMBER, groups[0].reason)
    }

    @Test
    fun dismissedGroupsAreHidden() {
        val all = listOf(c(1, "Alice Martin"), c(2, "Alice Martin"))
        val g = DuplicateFinder.findGroups(all).single()
        assertEquals(DuplicateFinder.signature(listOf("key2", "key1")), g.signature)
        assertTrue(DuplicateFinder.findGroups(all, setOf(g.signature)).isEmpty())
    }

    @Test
    fun dismissalsAreBackedUpAsAString() {
        assertTrue(SettingsBackup.accepts(AppPrefs.KEY_DUP_DISMISSED, "s", null))
        assertFalse(SettingsBackup.accepts(AppPrefs.KEY_DUP_DISMISSED, "b", "s"))
    }

    @Test
    fun suggestionsSortedByReason() {
        val target = c(1, "Alice Martin", listOf("+961 3 039 056"), listOf("a@x.com"))
        val s = DuplicateFinder.suggestionsFor(
            target,
            listOf(c(2, "Alice M"), c(3, "Other", listOf("03 039 056")), c(4, "Q", emptyList(), listOf("a@x.com"))),
        )
        assertEquals(listOf(3L, 4L, 2L), s.map { it.other.contactId })
    }

    @Test
    fun twoThousandContactsScanQuickly() {
        val first = listOf("Alice", "Bob", "Carla", "David", "Elena", "Farid", "Grace", "Hana", "Ivan", "Jad")
        val last = listOf("Martin", "Stone", "Haddad", "Khoury", "Smith", "Nguyen", "Garcia", "Rossi")
        val all = (0 until 2000).map { i ->
            c(
                i.toLong(),
                "${first[i % first.size]} ${last[(i / first.size) % last.size]} ${i / 80}",
                listOf("+1 555 %07d".format(i)),
            )
        }
        val start = System.nanoTime()
        DuplicateFinder.findGroups(all)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue("scan took ${ms}ms", ms < 5000)
    }
}
