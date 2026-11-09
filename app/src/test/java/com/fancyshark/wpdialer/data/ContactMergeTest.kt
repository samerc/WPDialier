package com.fancyshark.wpdialer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Merge planning: dedupe rules, conflicts, label mapping, notes. */
class ContactMergeTest {

    private var nextId = 1L

    private fun row(raw: Long, mime: String, vararg values: Pair<String, Any?>) =
        DataRow(nextId++, raw, mime, values.toMap())

    private fun phone(raw: Long, number: String, type: Int = 2) =
        row(raw, Mime.PHONE, "data1" to number, "data2" to type.toLong())

    private fun email(raw: Long, address: String) = row(raw, Mime.EMAIL, "data1" to address)
    private fun name(raw: Long, n: String) = row(raw, Mime.NAME, "data1" to n)
    private fun note(raw: Long, n: String) = row(raw, Mime.NOTE, "data1" to n)
    private fun org(raw: Long, company: String?, title: String? = null) =
        row(raw, Mime.ORGANIZATION, "data1" to company, "data4" to title)

    @Test
    fun phonesDedupeAcrossSpellingsPreferringE164() {
        val a = MergeInput(1, listOf(phone(1, "03 039 056"), phone(1, "+1 514 555 0142")), emptyList())
        val b = MergeInput(2, listOf(phone(2, "+961 3 039 056"), phone(2, "+961 1 444 210")), emptyList())
        val combined = ContactMerge.combine(listOf(a, b))
        val numbers = combined.phones.map { it.row.data1 }
        assertEquals(listOf("+961 3 039 056", "+1 514 555 0142", "+961 1 444 210"), numbers)
        assertEquals(2, combined.phones.first().parts)
        assertEquals(1, combined.phones[1].parts)
    }

    @Test
    fun differentLinesSharingTrailingDigitsStaySeparate() {
        val a = MergeInput(1, listOf(phone(1, "+1 212 555 1234")), emptyList())
        val b = MergeInput(2, listOf(phone(2, "+1 212 555 1235")), emptyList())
        assertEquals(2, ContactMerge.combine(listOf(a, b)).phones.size)
    }

    @Test
    fun firstPartWinsWhenNoInternationalForm() {
        val a = MergeInput(1, listOf(phone(1, "03 039 056")), emptyList())
        val b = MergeInput(2, listOf(phone(2, "3039056")), emptyList())
        val p = ContactMerge.combine(listOf(a, b)).phones.single()
        assertEquals(1L, p.row.rawId)
    }

    @Test
    fun emailsDedupeCaseInsensitively() {
        val a = MergeInput(1, listOf(email(1, "Alice.Home@Gmail.com")), emptyList())
        val b = MergeInput(2, listOf(email(2, "alice.home@gmail.com "), email(2, "a.martin@acme.com")), emptyList())
        val emails = ContactMerge.combine(listOf(a, b)).emails.map { it.data1 }
        assertEquals(listOf("Alice.Home@Gmail.com", "a.martin@acme.com"), emails)
    }

    @Test
    fun conflictsOnlyWhenValuesDiffer() {
        val same = ContactMerge.conflicts(
            listOf(
                MergeInput(1, listOf(name(1, "Alice Martin")), emptyList()),
                MergeInput(2, listOf(name(2, "alice  martin")), emptyList()),
            ),
        )
        assertFalse(same.any)

        val diff = ContactMerge.conflicts(
            listOf(
                MergeInput(1, listOf(name(1, "Alice Martin"), org(1, "Acme Corp", "Product lead")), emptyList()),
                MergeInput(2, listOf(name(2, "A. Martin"), org(2, "Acme")), emptyList()),
                MergeInput(3, listOf(org(3, "acme corp", "product lead")), emptyList()),
            ),
        )
        assertEquals(listOf("Alice Martin", "A. Martin"), diff.names.map { it.data1 })
        assertEquals(2, diff.orgs.size)
        assertTrue(diff.photos.isEmpty())
    }

    @Test
    fun photosConflictWhenTwoPartsHaveOne() {
        val c = ContactMerge.conflicts(
            listOf(
                MergeInput(1, listOf(row(1, Mime.PHOTO, "data15" to byteArrayOf(1))), emptyList()),
                MergeInput(2, listOf(row(2, Mime.PHOTO, "data15" to byteArrayOf(2))), emptyList()),
            ),
        )
        assertEquals(2, c.photos.size)
    }

    @Test
    fun choicesPickTheChosenPartsRows() {
        val inputs = listOf(
            MergeInput(1, listOf(name(1, "Alice Martin"), org(1, "Acme Corp")), emptyList()),
            MergeInput(2, listOf(name(2, "A. Martin"), org(2, "Acme")), emptyList()),
        )
        val defaults = ContactMerge.combine(inputs)
        assertEquals("Alice Martin", defaults.name?.data1)
        val chosen = ContactMerge.combine(inputs, MergeChoices(nameRaw = 2, orgRaw = 2))
        assertEquals("A. Martin", chosen.name?.data1)
        assertEquals("Acme", chosen.organization?.data1)
    }

    @Test
    fun everyDistinctOrganizationIsKeptChosenFirstAndPrimary() {
        val inputs = listOf(
            MergeInput(1, listOf(name(1, "Alice Martin"), org(1, "Acme Corp", "Lead"), org(1, "Night School")), emptyList()),
            MergeInput(2, listOf(name(2, "A. Martin"), org(2, "Globex"), org(2, "acme corp", "lead")), emptyList()),
        )
        val chosen = ContactMerge.combine(inputs, MergeChoices(orgRaw = 2))
        assertEquals(listOf("Globex", "Acme Corp", "Night School"), chosen.organizations.map { it.data1 })
        assertEquals(1, chosen.organizations[0].int(Cols.IS_PRIMARY))
        assertTrue(chosen.organizations.drop(1).all { it.int(Cols.IS_PRIMARY) == 0 })
        // One name row, but every organization row, in the written set.
        val rows = chosen.rows()
        assertEquals(1, rows.count { it.mimetype == Mime.NAME })
        assertEquals(3, rows.count { it.mimetype == Mime.ORGANIZATION })
        // Default: the first part's organization leads.
        assertEquals("Acme Corp", ContactMerge.combine(inputs).organization?.data1)
    }

    @Test
    fun singleOrganizationKeepsItsFlags() {
        val o = org(1, "Acme")
        val combined = ContactMerge.combine(listOf(MergeInput(1, listOf(o), emptyList())))
        assertTrue(combined.organizations.single() === o)
    }

    @Test
    fun extensionsAreDifferentNumbers() {
        val a = MergeInput(1, listOf(phone(1, "+1 212 555 1234"), phone(1, "+1 212 555 1234,22")), emptyList())
        val b = MergeInput(2, listOf(phone(2, "(212) 555-1234, 22"), phone(2, "+1 212 555 1234;33")), emptyList())
        val numbers = ContactMerge.combine(listOf(a, b)).phones.map { it.row.data1 }
        assertEquals(listOf("+1 212 555 1234", "+1 212 555 1234,22", "+1 212 555 1234;33"), numbers)
        assertEquals("p22", ContactMerge.postDial("+1 212 555 1234 P 22"))
        assertEquals("", ContactMerge.postDial("+1 212 555 1234"))
        assertTrue(ContactMerge.sameStoredNumber("03 039 056 p22", "+961 3 039 056P22"))
        assertFalse(ContactMerge.sameStoredNumber("+961 3 039 056", "+961 3 039 056w1"))
    }

    @Test
    fun emptyPhotoRowsAreNotPhotos() {
        val empty = row(1, Mime.PHOTO, "data15" to null, "data14" to null)
        val real = row(2, Mime.PHOTO, "data15" to byteArrayOf(7))
        val file = row(3, Mime.PHOTO, "data14" to 42L)
        assertFalse(ContactMerge.hasPhoto(empty))
        assertTrue(ContactMerge.hasPhoto(real))
        assertTrue(ContactMerge.hasPhoto(file))
        val inputs = listOf(
            MergeInput(1, listOf(empty), emptyList()),
            MergeInput(2, listOf(real), emptyList()),
        )
        // The kept part's empty row neither wins nor causes a conflict.
        assertEquals(2L, ContactMerge.combine(inputs).photo?.rawId)
        assertTrue(ContactMerge.conflicts(inputs).photos.isEmpty())
        // Even when the user's choice points at a part without an image.
        assertEquals(2L, ContactMerge.combine(inputs, MergeChoices(photoRaw = 1)).photo?.rawId)
    }

    @Test
    fun sendToVoicemailAndFavoriteSurviveFromAnyPart() {
        val flags = ContactMerge.combineFlags(
            listOf(
                PartFlags(starred = false, ringtone = null, sendToVoicemail = false),
                PartFlags(starred = true, ringtone = "content://tone/2", sendToVoicemail = true),
                PartFlags(starred = false, ringtone = "content://tone/3", sendToVoicemail = false),
            ),
        )
        assertTrue(flags.sendToVoicemail)
        assertTrue(flags.starred)
        assertEquals("content://tone/2", flags.ringtone)
        val kept = ContactMerge.combineFlags(
            listOf(
                PartFlags(starred = false, ringtone = "content://tone/1", sendToVoicemail = false),
                PartFlags(starred = false, ringtone = "content://tone/2", sendToVoicemail = false),
            ),
        )
        assertFalse(kept.sendToVoicemail)
        assertEquals("content://tone/1", kept.ringtone)
    }

    @Test
    fun uncopiedRowsCountOnlyDeletedPartsPrivateRows() {
        val inputs = listOf(
            MergeInput(1, listOf(name(1, "A"), row(1, "vnd.com.google.cursor.item/contact_user_defined_field")), emptyList()),
            MergeInput(
                2,
                listOf(
                    name(2, "B"),
                    row(2, "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"),
                    row(2, "vnd.com.google.cursor.item/contact_user_defined_field"),
                    row(2, Mime.GROUP, "data1" to 6L),
                ),
                emptyList(),
            ),
        )
        assertEquals(2, ContactMerge.uncopiedRows(inputs, keptRaw = 1))
        // No kept part: every part is deleted.
        assertEquals(3, ContactMerge.uncopiedRows(inputs, keptRaw = null))
    }

    @Test
    fun notesConcatenateWithBlankLineAndSkipRepeats() {
        assertEquals("one\n\ntwo", ContactMerge.combineNotes(listOf(" one ", null, "two", "one", "")))
        assertNull(ContactMerge.combineNotes(listOf(null, " ")))
        val combined = ContactMerge.combine(
            listOf(
                MergeInput(1, listOf(note(1, "Prefers WhatsApp")), emptyList()),
                MergeInput(2, listOf(note(2, "Allergic to peanuts")), emptyList()),
            ),
        )
        assertEquals("Prefers WhatsApp\n\nAllergic to peanuts", combined.note)
    }

    @Test
    fun labelsUnionAndMapByTitle() {
        val combined = ContactMerge.combine(
            listOf(
                MergeInput(1, emptyList(), listOf("family", "Book Club")),
                MergeInput(2, emptyList(), listOf("book club", "work")),
            ),
        )
        assertEquals(listOf("family", "Book Club", "work"), combined.labels)
        val plan = ContactMerge.mapLabels(combined.labels, listOf("Family", "book club "))
        assertEquals(mapOf("family" to "Family", "Book Club" to "book club "), plan.matched)
        assertEquals(listOf("work"), plan.create)
    }

    @Test
    fun websitesAndOthersDedupe() {
        val combined = ContactMerge.combine(
            listOf(
                MergeInput(
                    1,
                    listOf(
                        row(1, Mime.WEBSITE, "data1" to "https://www.alice.design/"),
                        row(1, Mime.NICKNAME, "data1" to "Ally"),
                    ),
                    emptyList(),
                ),
                MergeInput(
                    2,
                    listOf(
                        row(2, Mime.WEBSITE, "data1" to "alice.design"),
                        row(2, Mime.NICKNAME, "data1" to "ally"),
                        row(2, Mime.SIP, "data1" to "alice@sip.example"),
                    ),
                    emptyList(),
                ),
            ),
        )
        assertEquals(1, combined.websites.size)
        assertEquals(2, combined.others.size)
    }

    @Test
    fun combinedRowsCoverEveryCopyableKind() {
        // A merge deletes kept-part rows of copyable kinds that aren't in
        // rows(): every kind must be represented or data would be lost.
        val kinds = Mime.COPYABLE - Mime.NOTE
        val rows = kinds.map { row(1, it, "data1" to "x-$it", "data4" to "t") }
        val combined = ContactMerge.combine(listOf(MergeInput(1, rows, emptyList())))
        assertEquals(kinds, combined.rows().map { it.mimetype }.toSet())
    }
}
