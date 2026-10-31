package com.fancyshark.wpdialer.data

import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactEditorTest {

    private val google = ContactAccount("me@gmail.com", "com.google")
    private val exchange = ContactAccount("me@work.com", "com.google.android.exchange")
    private val local = ContactAccount(null, null)

    private fun raw(
        id: Long,
        account: ContactAccount = google,
        phones: List<EditPhone> = emptyList(),
    ) = EditRawContact(id, account, 100 + id, EditName(given = "Alice", family = "Martin", display = "Alice Martin"), phones)

    private fun phone(id: Long?, number: String, default: Boolean = false) =
        EditPhone(id, number, "mobile", Phone.TYPE_MOBILE, null, default)

    // ---- dates ----

    @Test
    fun parsesDatesWithAndWithoutYear() {
        assertEquals(EventDate(1993, 3, 4), EventDates.parse("1993-03-04"))
        assertEquals(EventDate(1993, 3, 4), EventDates.parse("19930304"))
        assertEquals(EventDate(1993, 3, 4), EventDates.parse("1993-03-04T00:00:00.000Z"))
        assertEquals(EventDate(null, 3, 4), EventDates.parse("--03-04"))
        assertEquals(EventDate(null, 3, 4), EventDates.parse(" --0304 "))
        // Feb 29 is a valid yearless birthday.
        assertEquals(EventDate(null, 2, 29), EventDates.parse("--02-29"))
    }

    @Test
    fun rejectsImpossibleDates() {
        assertNull(EventDates.parse("1993-13-01"))
        assertNull(EventDates.parse("1993-02-30"))
        assertNull(EventDates.parse("--04-31"))
        assertNull(EventDates.parse("March 4"))
        assertNull(EventDates.parse(""))
    }

    @Test
    fun formatsYearlessAsAndroidDoes() {
        assertEquals("--03-04", EventDates.format(EventDate(null, 3, 4)))
        assertEquals("1993-03-04", EventDates.format(EventDate(1993, 3, 4)))
        assertEquals("0005-01-02", EventDates.format(EventDate(5, 1, 2)))
        listOf("--12-25", "2001-07-09").forEach {
            assertEquals(it, EventDates.format(EventDates.parse(it)!!))
        }
    }

    // ---- default number ----

    @Test
    fun defaultNumberIsUniqueAcrossAccounts() {
        val contact = EditableContact(
            1,
            listOf(
                raw(1, google, listOf(phone(11, "111", default = true), phone(12, "222"))),
                raw(2, exchange, listOf(phone(21, "333"))),
            ),
        )
        val moved = contact.withDefaultPhone(1, 0)
        assertEquals(listOf(false, false), moved.raws[0].phones.map { it.isDefault })
        assertEquals(listOf(true), moved.raws[1].phones.map { it.isDefault })
        assertEquals("333", moved.defaultPhone()?.number)

        // Long-pressing the default again clears it.
        val cleared = moved.withDefaultPhone(1, 0)
        assertNull(cleared.defaultPhone())

        // Out-of-range indices leave the contact untouched.
        assertEquals(contact, contact.withDefaultPhone(5, 0))
    }

    @Test
    fun defaultFlagsAreWrittenOnlyWhereTheyChange() {
        val before = raw(1, phones = listOf(phone(11, "111", default = true), phone(12, "222")))
        val after = before.copy(phones = listOf(phone(11, "111"), phone(12, "222", default = true)))
        val changes = ContactEditor.planChanges(before, after)
        assertEquals(
            listOf(
                RowChange.Update(
                    11,
                    mapOf(ContactsContract.Data.IS_PRIMARY to 0, ContactsContract.Data.IS_SUPER_PRIMARY to 0),
                ),
                RowChange.Update(
                    12,
                    mapOf(ContactsContract.Data.IS_PRIMARY to 1, ContactsContract.Data.IS_SUPER_PRIMARY to 1),
                ),
            ),
            changes,
        )
    }

    // ---- diff ----

    @Test
    fun unchangedFormPlansNothing() {
        val r = raw(1, phones = listOf(phone(11, "111")))
            .copy(addresses = listOf(EditAddress(31, "12 Rue Gouraud")), groupIds = setOf(7L))
        assertTrue(ContactEditor.planChanges(r, r).isEmpty())
    }

    @Test
    fun updatesCarryOnlyChangedColumns() {
        val before = raw(1, phones = listOf(phone(11, "111")))
            .copy(addresses = listOf(EditAddress(31, "12 Rue Gouraud", StructuredPostal.TYPE_HOME)))
        val after = before.copy(
            phones = listOf(phone(11, "999")),
            // Type change alone must not rewrite (and re-split) the address text.
            addresses = listOf(EditAddress(31, "12 Rue Gouraud", StructuredPostal.TYPE_WORK)),
        )
        assertEquals(
            listOf(
                RowChange.Update(11, mapOf(Phone.NUMBER to "999")),
                RowChange.Update(31, mapOf(StructuredPostal.TYPE to StructuredPostal.TYPE_WORK)),
            ),
            ContactEditor.planChanges(before, after),
        )
    }

    @Test
    fun insertsDeletesAndBlanks() {
        val before = raw(1, phones = listOf(phone(11, "111"), phone(12, "222")))
            .copy(noteDataId = 41, note = "old")
        val after = before.copy(
            // 11 blanked, 12 removed, a new one added.
            phones = listOf(phone(11, "  "), phone(null, "555")),
            note = "",
        )
        val changes = ContactEditor.planChanges(before, after)
        assertTrue(RowChange.Delete(11) in changes)
        assertTrue(RowChange.Delete(12) in changes)
        assertTrue(RowChange.Delete(41) in changes)
        val insert = changes.filterIsInstance<RowChange.Insert>().single()
        assertEquals(Phone.CONTENT_ITEM_TYPE, insert.mime)
        assertEquals("555", insert.values[Phone.NUMBER])
        assertEquals(Phone.TYPE_MOBILE, insert.values[Phone.TYPE])
        // A non-default new number carries no primary flags.
        assertFalse(insert.values.containsKey(ContactsContract.Data.IS_SUPER_PRIMARY))
    }

    @Test
    fun nameEditLetsTheProviderRejoinTheDisplayName() {
        val before = raw(1)
        val after = before.copy(name = before.name.copy(prefix = "Dr.", display = ""))
        val update = ContactEditor.planChanges(before, after).single() as RowChange.Update
        assertEquals(101L, update.dataId)
        assertEquals(
            mapOf(StructuredName.DISPLAY_NAME to null, StructuredName.PREFIX to "Dr."),
            update.values,
        )
        assertEquals("Dr. Alice Martin", after.name.composed())
    }

    @Test
    fun singleFieldsInsertWithTheirExtras() {
        val before = raw(1)
        val after = before.copy(nickname = "Ally", company = "Acme")
        val inserts = ContactEditor.planChanges(before, after).filterIsInstance<RowChange.Insert>()
        val nick = inserts.single { it.mime == Nickname.CONTENT_ITEM_TYPE }
        assertEquals(mapOf(Nickname.NAME to "Ally", Nickname.TYPE to Nickname.TYPE_DEFAULT), nick.values)
        assertTrue(inserts.any { it.mime == ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE })
    }

    @Test
    fun labelMembershipDiff() {
        val before = raw(1).copy(groupIds = setOf(1L, 2L))
        val after = before.copy(groupIds = setOf(2L, 3L), newGroups = listOf("Book club", "book club", " "))
        val changes = ContactEditor.planChanges(before, after)
        assertEquals(
            listOf(
                RowChange.Insert(GroupMembership.CONTENT_ITEM_TYPE, mapOf(GroupMembership.GROUP_ROW_ID to 3L)),
                RowChange.DeleteWhere(GroupMembership.CONTENT_ITEM_TYPE, GroupMembership.GROUP_ROW_ID, "1"),
                // Case-insensitive dedupe, blanks dropped.
                RowChange.JoinNewGroup("Book club"),
            ),
            changes,
        )
    }

    @Test
    fun newContactPlansOnlyInserts() {
        val draft = ContactEditor.blankRaw(local).copy(
            name = EditName(given = "Bob"),
            phones = listOf(phone(null, "123", default = true), phone(null, "")),
            note = "hi",
        )
        val changes = ContactEditor.planChanges(ContactEditor.blankRaw(local), draft)
        assertTrue(changes.all { it is RowChange.Insert })
        val mimes = changes.map { (it as RowChange.Insert).mime }
        assertEquals(listOf(StructuredName.CONTENT_ITEM_TYPE, Phone.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE), mimes)
        val p = changes[1] as RowChange.Insert
        assertEquals(1, p.values[ContactsContract.Data.IS_SUPER_PRIMARY])
        // Null display name is left out so the provider composes it.
        assertFalse((changes[0] as RowChange.Insert).values.containsKey(StructuredName.DISPLAY_NAME))
    }

    // ---- contact-wide edits, capabilities ----

    @Test
    fun contactWideEditsGoToHoldersElseTarget() {
        val a = raw(1).copy(nickname = "Ally", nicknameDataId = 5)
        val b = raw(2, exchange)
        val c = EditableContact(1, listOf(a, b))
        val renamed = c.withContactWide(1, { it.hasNickname }) { it.copy(nickname = "Al") }
        assertEquals(listOf("Al", ""), renamed.raws.map { it.nickname })

        val none = EditableContact(1, listOf(raw(1), raw(2, exchange)))
        val added = none.withContactWide(1, { it.hasNickname }) { it.copy(nickname = "Al") }
        assertEquals(listOf("", "Al"), added.raws.map { it.nickname })
    }

    @Test
    fun accountCapabilities() {
        assertEquals(FieldKind.entries.toSet(), ContactEditor.supportedKinds(local))
        assertEquals(FieldKind.entries.toSet(), ContactEditor.supportedKinds(google))
        val eas = ContactEditor.supportedKinds(ContactAccount("me", "com.android.exchange"))
        assertFalse(FieldKind.RELATION in eas)
        assertFalse(FieldKind.LABELS in eas)
        assertTrue(FieldKind.PHONE in eas)
        assertEquals(
            setOf(FieldKind.PHONE, FieldKind.EMAIL),
            ContactEditor.supportedKinds(ContactAccount("SIM1", "vnd.sec.contact.sim")),
        )
        // "sim" inside another word is not a SIM card.
        assertEquals(
            FieldKind.entries.toSet(),
            ContactEditor.supportedKinds(ContactAccount("x", "com.simplemobiletools.contacts")),
        )
    }

    @Test
    fun preferredTargetIsFirstSyncedAccount() {
        assertEquals(1, EditableContact(1, listOf(raw(1, local), raw(2, google))).preferredTarget())
        assertEquals(0, EditableContact(1, listOf(raw(1, local))).preferredTarget())
    }
}
