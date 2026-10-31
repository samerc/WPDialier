package com.fancyshark.wpdialer.data

/** History load-more math (pure; unit tested). */
object HistoryPaging {

    const val PAGE_SIZE = 300

    /** Upper bound so a fling through years of calls can't load unbounded rows. */
    const val MAX_LIMIT = PAGE_SIZE * 20

    /** Rows to start loading the next page before the end is reached. */
    const val PREFETCH = 15

    /** A full page came back, so the provider may hold more rows. */
    fun hasMore(loaded: Int, limit: Int): Boolean = loaded >= limit && limit < MAX_LIMIT

    fun nextLimit(limit: Int): Int = (limit + PAGE_SIZE).coerceAtMost(MAX_LIMIT)

    /** Whether the list scrolled within [PREFETCH] rows of its end. */
    fun nearEnd(lastVisibleIndex: Int, totalRows: Int): Boolean =
        totalRows > 0 && lastVisibleIndex >= totalRows - 1 - PREFETCH
}

/** Call-log matching for the search screen (pure; unit tested). */
object HistorySearch {

    /** By name (case-insensitive), or by number digits (3+ typed digits). */
    fun matches(item: HistoryItem, query: String): Boolean {
        val q = query.trim()
        if (q.isEmpty()) return false
        if (item.name?.contains(q, ignoreCase = true) == true) return true
        // Digits only when the query is number-like: "anna 2" must not match
        // every number containing a 2.
        if (q.any { it.isLetter() }) return false
        if (q.count { it.isDigit() } < 3) return false
        // Trunk zeros stripped on both sides, like every number comparison.
        val digits = Repo.numberDigits(q)
        if (digits.isEmpty()) return false
        return Repo.numberDigits(item.number).contains(digits)
    }

    /**
     * Newest matching call per line (national and E.164 spellings unify),
     * newest first — tapping a result opens that line's call details.
     */
    fun search(history: List<HistoryItem>, query: String, max: Int = 30): List<HistoryItem> {
        val out = mutableListOf<HistoryItem>()
        for (item in history) {
            if (out.size >= max) break
            if (item.number.isBlank() || !matches(item, query)) continue
            if (out.none { Repo.numbersMatch(it.number, item.number) }) out += item
        }
        return out
    }
}
