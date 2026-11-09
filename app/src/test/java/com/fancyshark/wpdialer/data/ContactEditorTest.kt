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
    fun yearlessLeapDayRoundTripsThroughThePicker() {
        // Opens on a leap seed year, so Feb 29 isn't rolled to Mar 1.
        assertEquals(EventDate(EventDates.PICKER_SEED_YEAR, 2, 29), EventDates.pickerStart("--02-29"))
        // OK without touching the year keeps it yearless.
        assertEquals("--02-29", EventDates.picked("--02-29", EventDates.PICKER_SEED_YEAR, 2, 29))
        assertEquals("--03-04", EventDates.picked("--02-29", EventDates.PICKER_SEED_YEAR, 3, 4))
        // An explicit year is stored.
        assertEquals("1992-02-29", EventDates.picked("--02-29", 1992, 2, 29))
        // Dated (or new) values keep the picked year, even the seed year.
        assertEquals("2000-05-06", EventDates.picked("2000-05-06", 2000, 5, 6))
        assertEquals("2000-05-06", EventDates.picked("", 2000, 5, 6))
        assertEquals(EventDate(1993, 3, 4), EventDates.pickerStart("1993-03-04"))
        assertEquals(EventDate(1993, 2, 28), EventDates.pickerStart("1993-02-29"))
        assertNull(EventDates.pickerStart(""))
    }

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
        // Every part travels with the null display, so the provider rejoins from all of them.
        assertEquals(
            mapOf(
                StructuredName.DISPLAY_NAME to null,
                StructuredName.PREFIX to "Dr.",
                StructuredName.GIVEN_NAME to "Alice",
                StructuredName.MIDDLE_NAME to null,
                StructuredName.FAMILY_NAME to "Martin",
                StructuredName.SUFFIX to null,
                StructuredName.PHONETIC_GIVEN_NAME to null,
                StructuredName.PHONETIC_FAMILY_NAME to null,
            ),
            update.values,
        )
        assertEquals("Dr. Alice Martin", after.name.composed())
    }

    @Test
    fun phoneticOnlyEditStillCarriesEveryStructuredPart() {
        val before = raw(1)
        val after = before.copy(name = before.name.copy(phoneticGiven = "a-ri-su", display = ""))
        val update = ContactEditor.planChanges(before, after).single() as RowChange.Update
        val structured = listOf(
            StructuredName.PREFIX, StructuredName.GIVEN_NAME, StructuredName.MIDDLE_NAME,
            StructuredName.FAMILY_NAME, StructuredName.SUFFIX,
        )
        structured.forEach { assertTrue(it, update.values.containsKey(it)) }
        assertEquals("Alice", update.values[StructuredName.GIVEN_NAME])
        assertEquals("Martin", update.values[StructuredName.FAMILY_NAME])
        assertEquals("a-ri-su", update.values[StructuredName.PHONETIC_GIVEN_NAME])
        assertTrue(update.values.containsKey(StructuredName.DISPLAY_NAME))
        assertNull(update.values[StructuredName.DISPLAY_NAME])
    }

    // ---- addresses ----

    private val gouraud = EditAddress(
        31, "12 Rue Gouraud, Beirut 1100, Lebanon", StructuredPostal.TYPE_HOME,
        parts = mapOf(
            StructuredPostal.STREET to "12 Rue Gouraud",
            StructuredPostal.CITY to "Beirut",
            StructuredPostal.POSTCODE to "1100",
            StructuredPostal.COUNTRY to "Lebanon",
        ),
    )

    @Test
    fun addressTypeChangeLeavesTextAndPartsAlone() {
        val before = raw(1).copy(addresses = listOf(gouraud))
        val after = before.copy(addresses = listOf(gouraud.copy(type = StructuredPostal.TYPE_WORK)))
        assertEquals(
            listOf(RowChange.Update(31, mapOf(StructuredPostal.TYPE to StructuredPostal.TYPE_WORK))),
            ContactEditor.planChanges(before, after),
        )
    }

    @Test
    fun addressTypoFixKeepsTheStructuredParts() {
        val before = raw(1).copy(addresses = listOf(gouraud))
        val fixed = "Apt 3, 12 Rue Gouraud, Beirut 1100, Lebanon"
        val after = before.copy(addresses = listOf(gouraud.copy(formatted = fixed)))
        val update = ContactEditor.planChanges(before, after).single() as RowChange.Update
        assertEquals(fixed, update.values[StructuredPostal.FORMATTED_ADDRESS])
        assertEquals("12 Rue Gouraud", update.values[StructuredPostal.STREET])
        assertEquals("Beirut", update.values[StructuredPostal.CITY])
        assertEquals("1100", update.values[StructuredPostal.POSTCODE])
        assertEquals("Lebanon", update.values[StructuredPostal.COUNTRY])
        // Parts the row never had are written as null, never left stale.
        assertTrue(update.values.containsKey(StructuredPostal.REGION))
        assertNull(update.values[StructuredPostal.REGION])
    }

    @Test
    fun rewrittenAddressClearsEveryStructuredPart() {
        val before = raw(1).copy(addresses = listOf(gouraud))
        val after = before.copy(addresses = listOf(gouraud.copy(formatted = "5 Main St, Tripoli")))
        val update = ContactEditor.planChanges(before, after).single() as RowChange.Update
        assertEquals("5 Main St, Tripoli", update.values[StructuredPostal.FORMATTED_ADDRESS])
        listOf(
            StructuredPostal.STREET, StructuredPostal.POBOX, StructuredPostal.NEIGHBORHOOD,
            StructuredPostal.CITY, StructuredPostal.REGION, StructuredPostal.POSTCODE,
            StructuredPostal.COUNTRY,
        ).forEach { col ->
            assertTrue(col, update.values.containsKey(col))
            assertNull(col, update.values[col])
        }
        assertFalse(update.values.containsKey(StructuredPostal.TYPE))
    }

    // ---- photo ----

    @Test
    fun newPhotoIsSuperPrimaryOnItsOwnRawContact() {
        val before = raw(1)
        val after = before.copy(newPhoto = byteArrayOf(1, 2, 3))
        val changes = ContactEditor.planChanges(before, after)
        val photo = ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE
        assertEquals(RowChange.DeleteWhere(photo), changes[0])
        val insert = changes[1] as RowChange.Insert
        assertEquals(photo, insert.mime)
        assertEquals(1, insert.values[ContactsContract.Data.IS_SUPER_PRIMARY])
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

    @Test
    fun preferredTargetPutsSimLast() {
        val sim = ContactAccount("SIM1", "vnd.sec.contact.sim")
        assertEquals(1, EditableContact(1, listOf(raw(1, sim), raw(2, google))).preferredTarget())
        assertEquals(1, EditableContact(1, listOf(raw(1, sim), raw(2, local))).preferredTarget())
        assertEquals(2, EditableContact(1, listOf(raw(1, sim), raw(2, local), raw(3, exchange))).preferredTarget())
        assertEquals(0, EditableContact(1, listOf(raw(1, sim))).preferredTarget())
    }

    @Test
    fun switchingToSimDropsWhatItCantStore() {
        val sim = ContactEditor.supportedKinds(ContactAccount("SIM1", "vnd.sec.contact.sim"))
        val draft = ContactEditor.blankRaw(google).copy(
            phones = listOf(phone(null, "123")),
            addresses = listOf(EditAddress(null, "12 Rue Gouraud")),
            events = listOf(EditEvent(null, "", ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)),
            note = "hi",
        )
        // A blank date row isn't input worth a confirm.
        assertEquals(listOf(FieldKind.ADDRESS, FieldKind.NOTE), draft.unsupportedFilled(sim))
        val kept = draft.keepingOnly(sim)
        assertEquals(listOf("123"), kept.phones.map { it.number })
        assertTrue(kept.addresses.isEmpty() && kept.events.isEmpty() && kept.note.isEmpty())
        assertTrue(draft.unsupportedFilled(FieldKind.entries.toSet()).isEmpty())
    }
}
