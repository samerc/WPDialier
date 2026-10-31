package com.fancyshark.wpdialer.data

import android.content.Context
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.Locale

/*
 * Pure merge planning (what a merge keeps, dedupes and asks about) and
 * duplicate detection, plus the provider scan that feeds the latter. The
 * pure objects take plain data and are unit-tested.
 */

/** One writable part going into a merge: its rows and its label titles. */
data class MergeInput(val rawId: Long, val rows: List<DataRow>, val labels: List<String>)

/** The user's picks for conflicting single-valued fields (raw ids; null = default). */
data class MergeChoices(val nameRaw: Long? = null, val photoRaw: Long? = null, val orgRaw: Long? = null)

/** Single-valued fields that differ between the parts — one row per distinct value. */
data class MergeConflicts(val names: List<DataRow>, val photos: List<DataRow>, val orgs: List<DataRow>) {
    val any: Boolean get() = names.isNotEmpty() || photos.isNotEmpty() || orgs.isNotEmpty()
}

/** A combined phone and how many parts carried it. */
data class CombinedPhone(val row: DataRow, val parts: Int)

data class CombinedContact(
    val name: DataRow?,
    val organization: DataRow?,
    val photo: DataRow?,
    val phones: List<CombinedPhone>,
    val emails: List<DataRow>,
    val events: List<DataRow>,
    val addresses: List<DataRow>,
    val websites: List<DataRow>,
    /** Nicknames, IM, SIP, relations and any other copyable rows, deduped. */
    val others: List<DataRow>,
    val note: String?,
    val labels: List<String>,
) {
    /** Every combined data row except the note and photo (written separately). */
    fun rows(): List<DataRow> =
        listOfNotNull(name, organization) + phones.map { it.row } + emails + events +
            addresses + websites + others
}

/** Label mapping into a target account: wanted title -> existing title, and the ones to create. */
data class LabelPlan(val matched: Map<String, String>, val create: List<String>)

object ContactMerge {

    private fun norm(s: String?): String =
        s.orEmpty().trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")

    private fun orgKey(row: DataRow): String = norm(row.data1) + "|" + norm(row.text("data4"))

    private fun firstOf(input: MergeInput, mime: String, keep: (DataRow) -> Boolean = { true }) =
        input.rows.firstOrNull { it.mimetype == mime && keep(it) }

    private fun hasOrg(row: DataRow) = row.data1 != null || row.text("data4") != null

    /** Conflicts appear only when 2+ distinct values exist across the parts. */
    fun conflicts(inputs: List<MergeInput>): MergeConflicts {
        val names = inputs.mapNotNull { firstOf(it, Mime.NAME) { r -> r.data1 != null } }
            .distinctBy { norm(it.data1) }
        val photos = inputs.mapNotNull { firstOf(it, Mime.PHOTO) }
        val orgs = inputs.mapNotNull { firstOf(it, Mime.ORGANIZATION, ::hasOrg) }.distinctBy(::orgKey)
        return MergeConflicts(
            names = if (names.size > 1) names else emptyList(),
            photos = if (photos.size > 1) photos else emptyList(),
            orgs = if (orgs.size > 1) orgs else emptyList(),
        )
    }

    private fun pick(inputs: List<MergeInput>, rawId: Long?, mime: String, keep: (DataRow) -> Boolean): DataRow? =
        rawId?.let { id -> inputs.firstOrNull { it.rawId == id }?.let { firstOf(it, mime, keep) } }
            ?: inputs.firstNotNullOfOrNull { firstOf(it, mime, keep) }

    /** E.164-looking spellings ("+961 3 ...") win over national ones. */
    private fun isInternational(number: String?): Boolean = number?.trim()?.startsWith("+") == true

    /**
     * Phones clustered by [Repo.numbersMatch]; each cluster keeps one row,
     * preferring the international (E.164) spelling, else the first seen.
     */
    fun dedupePhones(rows: List<DataRow>): List<CombinedPhone> {
        val clusters = mutableListOf<MutableList<DataRow>>()
        rows.filter { it.mimetype == Mime.PHONE && it.data1 != null }.forEach { row ->
            val n = row.data1!!
            val cluster = clusters.firstOrNull { c -> c.any { Repo.numbersMatch(it.data1!!, n) } }
            if (cluster != null) cluster += row else clusters += mutableListOf(row)
        }
        return clusters.map { c ->
            val rep = c.firstOrNull { isInternational(it.data1) } ?: c.first()
            CombinedPhone(rep, c.map { it.rawId }.distinct().size)
        }
    }

    fun normalizeUrl(url: String?): String = norm(url)
        .removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

    private fun normalizeAddress(s: String?): String =
        norm(s).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()

    private val OTHER_MIMES = Mime.COPYABLE - setOf(
        Mime.NAME, Mime.ORGANIZATION, Mime.PHONE, Mime.EMAIL, Mime.EVENT,
        Mime.POSTAL, Mime.WEBSITE, Mime.NOTE,
    )

    /** Distinct non-blank notes, one after the other with a blank line between. */
    fun combineNotes(notes: List<String?>): String? =
        notes.mapNotNull { it?.trim()?.takeIf { n -> n.isNotEmpty() } }
            .distinct()
            .joinToString("\n\n")
            .takeIf { it.isNotEmpty() }

    /** Case-insensitive label union, first spelling kept. */
    fun combineLabels(labels: List<String>): List<String> =
        labels.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { it.lowercase(Locale.ROOT) }

    /** Matches wanted titles to existing ones case-insensitively; the rest are created. */
    fun mapLabels(wanted: List<String>, existing: List<String>): LabelPlan {
        val matched = LinkedHashMap<String, String>()
        val create = mutableListOf<String>()
        combineLabels(wanted).forEach { w ->
            val hit = existing.firstOrNull { it.trim().equals(w, ignoreCase = true) }
            if (hit != null) matched[w] = hit else create += w
        }
        return LabelPlan(matched, create)
    }

    /**
     * The merged contact. [inputs] order matters: the first part (the one
     * kept, when it exists) wins ties, so its own rows are reused rather
     * than re-inserted.
     */
    fun combine(inputs: List<MergeInput>, choices: MergeChoices = MergeChoices()): CombinedContact {
        val all = inputs.flatMap { it.rows }
        fun of(m: String) = all.filter { it.mimetype == m && it.data1 != null }
        return CombinedContact(
            name = pick(inputs, choices.nameRaw, Mime.NAME) { it.data1 != null },
            organization = pick(inputs, choices.orgRaw, Mime.ORGANIZATION, ::hasOrg),
            photo = pick(inputs, choices.photoRaw, Mime.PHOTO) { true },
            phones = dedupePhones(all),
            emails = of(Mime.EMAIL).distinctBy { norm(it.data1) },
            events = of(Mime.EVENT).distinctBy {
                Triple(it.int("data2") ?: 0, norm(it.data1), norm(it.text("data3")))
            },
            addresses = of(Mime.POSTAL).distinctBy { normalizeAddress(it.data1) },
            websites = of(Mime.WEBSITE).distinctBy { normalizeUrl(it.data1) },
            others = all.filter { it.mimetype in OTHER_MIMES && it.data1 != null }
                .distinctBy { Triple(it.mimetype, norm(it.data1), norm(it.text("data5"))) },
            note = combineNotes(of(Mime.NOTE).map { it.data1 }),
            labels = combineLabels(inputs.flatMap { it.labels }),
        )
    }
}

// ---- duplicate detection ---------------------------------------------------

/** One aggregate contact as duplicate detection sees it. */
data class DupCandidate(
    val contactId: Long,
    val lookupKey: String,
    val name: String,
    val phones: List<String>,
    val emails: List<String>,
    val accountTypes: List<String?> = emptyList(),
    val photoUri: String? = null,
)

enum class DupReason { NUMBER, EMAIL, NAME }

/** Why [other] looks like the same person; [detail] = the shared number/email. */
data class DupMatch(val other: DupCandidate, val reason: DupReason, val detail: String?)

data class DupGroup(val members: List<DupCandidate>, val reason: DupReason, val detail: String?) {
    val signature: String get() = DuplicateFinder.signature(members.map { it.lookupKey })
}

object DuplicateFinder {

    private val MARKS = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercase, accent-free, punctuation as spaces: "Élodie-Anne D." -> "elodie anne d". */
    fun normalizeName(s: String): String =
        java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
            .replace(MARKS, "")
            .lowercase(Locale.ROOT)
            .replace(NON_WORD, " ")
            .trim()

    /** Edit distance, giving up (returns max+1) once it exceeds [max]. */
    fun levenshtein(a: String, b: String, max: Int = Int.MAX_VALUE): Int {
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    private fun tokens(n: String) = n.split(' ').filter { it.isNotEmpty() }

    /** Same name modulo case/accents/order, an initial ("Alice M"), or a typo. */
    fun similarNames(a: String, b: String): Boolean =
        similarNormalized(normalizeName(a), normalizeName(b))

    /** [similarNames] over already-normalized names. */
    fun similarNormalized(na: String, nb: String): Boolean {
        if (na.length < 3 || nb.length < 3) return false
        // Names that are just numbers ("+961 3...") are not names.
        if (na.none { it.isLetter() } || nb.none { it.isLetter() }) return false
        if (na == nb) return true
        // "Office 1" vs "Office 2": only the numbers differ — different entries.
        if (na.filter { it.isDigit() } != nb.filter { it.isDigit() } &&
            na.filter { it.isLetter() } == nb.filter { it.isLetter() }
        ) {
            return false
        }
        val ta = tokens(na)
        val tb = tokens(nb)
        if (ta.sorted() == tb.sorted()) return true
        if (ta.size == tb.size && ta.size > 1) {
            var full = 0
            val ok = ta.indices.all { i ->
                val x = ta[i]
                val y = tb[i]
                when {
                    x == y -> { if (x.length >= 3) full++; true }
                    x.length == 1 && y.startsWith(x) -> true
                    y.length == 1 && x.startsWith(y) -> true
                    else -> false
                }
            }
            if (ok && full >= 1) return true
        }
        val shorter = minOf(na.length, nb.length)
        val allowed = when {
            shorter >= 10 -> 2
            shorter >= 5 -> 1
            else -> 0
        }
        return allowed > 0 && levenshtein(na, nb, allowed) <= allowed
    }

    /** Order-independent identity of a group, persisted for "not duplicates". */
    fun signature(lookupKeys: List<String>): String = lookupKeys.sorted().joinToString("\u0002")

    private fun sharedNumber(a: DupCandidate, b: DupCandidate): String? =
        a.phones.firstOrNull { p -> b.phones.any { Repo.numbersMatch(p, it) } }

    private fun sharedEmail(a: DupCandidate, b: DupCandidate): String? =
        a.emails.firstOrNull { e -> b.emails.any { it.equals(e, ignoreCase = true) } }

    /** Strongest reason [a] and [b] look like one person, or null. */
    fun match(a: DupCandidate, b: DupCandidate): DupMatch? =
        match(a, b, normalizeName(a.name), normalizeName(b.name))

    private fun match(a: DupCandidate, b: DupCandidate, na: String, nb: String): DupMatch? {
        if (a.contactId == b.contactId) return null
        sharedNumber(a, b)?.let { return DupMatch(b, DupReason.NUMBER, it) }
        sharedEmail(a, b)?.let { return DupMatch(b, DupReason.EMAIL, it) }
        if (similarNormalized(na, nb)) return DupMatch(b, DupReason.NAME, null)
        return null
    }

    /** Suggestions to link with [target], strongest reasons first. */
    fun suggestionsFor(target: DupCandidate, all: List<DupCandidate>): List<DupMatch> =
        all.mapNotNull { match(target, it) }.sortedBy { it.reason.ordinal }

    /**
     * All duplicate groups. Candidate pairs come from indexes — number key,
     * lowercased email, sorted-token name — plus edit-distance checks only
     * inside first-letter name buckets, so 2k contacts stay well under a
     * second. Pairs are unioned into groups; [dismissed] signatures drop.
     */
    fun findGroups(all: List<DupCandidate>, dismissed: Set<String> = emptySet()): List<DupGroup> {
        val n = all.size
        val names = Array(n) { normalizeName(all[it].name) }
        val parent = IntArray(n) { it }
        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != r) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }
        val edges = HashMap<Pair<Int, Int>, DupMatch>()
        fun consider(i: Int, j: Int) {
            if (i == j) return
            val key = if (i < j) i to j else j to i
            if (key in edges) return
            val m = match(all[key.first], all[key.second], names[key.first], names[key.second]) ?: return
            edges[key] = m
            parent[find(key.first)] = find(key.second)
        }
        fun pairsIn(bucket: Collection<Int>) {
            val list = bucket.distinct()
            for (x in list.indices) for (y in x + 1 until list.size) consider(list[x], list[y])
        }

        val byNumber = HashMap<String, MutableList<Int>>()
        val byEmail = HashMap<String, MutableList<Int>>()
        val byTokens = HashMap<String, MutableList<Int>>()
        val byLetter = HashMap<String, MutableList<Int>>()
        all.forEachIndexed { i, c ->
            c.phones.map { Repo.numberKey(it) }.filter { it.isNotEmpty() }.distinct()
                .forEach { byNumber.getOrPut(it) { mutableListOf() } += i }
            c.emails.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }.distinct()
                .forEach { byEmail.getOrPut(it) { mutableListOf() } += i }
            val nn = names[i]
            if (nn.length >= 3 && nn.any { it.isLetter() }) {
                byTokens.getOrPut(tokens(nn).sorted().joinToString(" ")) { mutableListOf() } += i
                byLetter.getOrPut(nn.take(1)) { mutableListOf() } += i
            }
        }
        // A crowded first-letter bucket is split by the first two letters.
        byLetter.entries.filter { it.value.size > 400 }.map { it.key }.forEach { k ->
            val crowd = byLetter.remove(k).orEmpty()
            crowd.forEach { i -> byLetter.getOrPut(names[i].take(2)) { mutableListOf() } += i }
        }
        // Huge buckets are noise (a shared switchboard number) — skip them.
        byNumber.values.filter { it.size in 2..25 }.forEach(::pairsIn)
        byEmail.values.filter { it.size in 2..25 }.forEach(::pairsIn)
        byTokens.values.filter { it.size >= 2 }.forEach(::pairsIn)
        byLetter.values.filter { it.size >= 2 }.forEach(::pairsIn)

        val members = HashMap<Int, MutableList<Int>>()
        edges.keys.forEach { (i, j) ->
            members.getOrPut(find(i)) { mutableListOf() }.apply { add(i); add(j) }
        }
        return members.values.mapNotNull { idx ->
            val ids = idx.distinct().sorted()
            val groupEdges = edges.filterKeys { it.first in ids }
            val best = groupEdges.values.minByOrNull { it.reason.ordinal } ?: return@mapNotNull null
            DupGroup(ids.map { all[it] }, best.reason, best.detail)
        }.filter { it.signature !in dismissed }
            .sortedWith(compareBy<DupGroup> { it.reason.ordinal }.thenBy { normalizeName(it.members.first().name) })
    }
}

/** Provider scan + the last result, for the Settings count. */
object Duplicates {

    private val _lastCount = MutableStateFlow<Int?>(null)

    /** Groups found by the most recent scan; null until one ran. */
    val lastCount: StateFlow<Int?> = _lastCount

    @Volatile
    private var lastScanAt = 0L

    /** Every aggregate contact with its numbers, emails and account types. */
    suspend fun loadCandidates(context: Context): List<DupCandidate> = withContext(Dispatchers.IO) {
        val heads = LinkedHashMap<Long, Triple<String, String, String?>>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                arrayOf(
                    ContactsContract.Contacts._ID,
                    ContactsContract.Contacts.LOOKUP_KEY,
                    ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                    ContactsContract.Contacts.PHOTO_THUMBNAIL_URI,
                ),
                null, null,
                "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} COLLATE NOCASE ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val key = c.getString(1) ?: continue
                    heads[c.getLong(0)] = Triple(key, c.getString(2) ?: "", c.getString(3))
                }
            }
        }
        val phones = HashMap<Long, MutableList<String>>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    val list = phones.getOrPut(c.getLong(0)) { mutableListOf() }
                    if (list.none { Repo.numbersMatch(it, n) }) list += n
                }
            }
        }
        val emails = HashMap<Long, MutableList<String>>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Email.ADDRESS,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val e = c.getString(1)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
                    val list = emails.getOrPut(c.getLong(0)) { mutableListOf() }
                    if (list.none { it.equals(e, ignoreCase = true) }) list += e
                }
            }
        }
        val types = HashMap<Long, MutableList<String?>>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.RawContacts.CONTENT_URI,
                arrayOf(ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_TYPE),
                "${ContactsContract.RawContacts.DELETED} = 0",
                null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.isNull(0)) continue
                    val list = types.getOrPut(c.getLong(0)) { mutableListOf() }
                    val t = c.getString(1)
                    if (t !in list) list += t
                }
            }
        }
        heads.map { (id, head) ->
            DupCandidate(
                contactId = id,
                lookupKey = head.first,
                name = head.second,
                phones = phones[id].orEmpty(),
                emails = emails[id].orEmpty(),
                accountTypes = types[id].orEmpty(),
                photoUri = head.third,
            )
        }
    }

    /** Full scan honoring the user's "not duplicates" dismissals. Off the main thread. */
    suspend fun scan(context: Context): List<DupGroup> {
        val candidates = loadCandidates(context)
        val dismissed = AppPrefs.dupDismissed.value
        val groups = withContext(Dispatchers.Default) { DuplicateFinder.findGroups(candidates, dismissed) }
        _lastCount.value = groups.size
        lastScanAt = android.os.SystemClock.elapsedRealtime()
        return groups
    }

    /** Settings' background count: rescans at most once a minute. */
    suspend fun refreshCount(context: Context) {
        val fresh = android.os.SystemClock.elapsedRealtime() - lastScanAt < 60_000L
        if (fresh && _lastCount.value != null) return
        runCatching { scan(context) }
    }

    /** A merge/link/dismissal changed the answer — next Settings visit rescans. */
    fun invalidate() {
        lastScanAt = 0L
    }
}
