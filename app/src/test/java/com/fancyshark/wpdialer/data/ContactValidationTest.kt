package com.fancyshark.wpdialer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactValidationTest {

    private val local = ContactAccount(null, null)

    private fun form(
        name: EditName = EditName(),
        phones: List<String> = emptyList(),
        emails: List<String> = emptyList(),
    ) = EditableContact(
        -1L,
        listOf(
            EditRawContact(
                -1L, local, null, name,
                phones.map { EditPhone(null, it, "mobile") },
                emails = emails.map { EditEmail(null, it, 1) },
            ),
        ),
    )

    @Test fun phoneRules() {
        listOf("", "+961 3 039 056", "03-039-056", "(555) 123 4567", "*100#", "1-800-FLOWERS", "112;1234", "555,,12")
            .forEach { assertTrue(it, ContactValidation.isValidPhone(it)) }
        listOf("abc", "hello@x.com", "12😀3", "+", "--").forEach { assertFalse(it, ContactValidation.isValidPhone(it)) }
    }

    @Test fun emailRules() {
        listOf("", "a@b.co", " name.last+tag@example.com ").forEach { assertTrue(it, ContactValidation.isValidEmail(it)) }
        listOf("abc", "a@b", "a b@c.com", "@x.com", "a@.").forEach { assertFalse(it, ContactValidation.isValidEmail(it)) }
    }

    @Test fun newContactNeedsNameOrNumber() {
        val errors = ContactValidation.validate(form(phones = listOf("")), requireNameOrNumber = true)
        assertEquals(
            listOf(FieldRef("name", 0, 0), FieldRef("phone", 0, 0)),
            errors.keys.toList(),
        )
        assertTrue(errors.values.all { it == FieldError.NEED_NAME_OR_NUMBER })
        assertTrue(ContactValidation.validate(form(name = EditName(given = "Maya")), true).isEmpty())
        assertTrue(ContactValidation.validate(form(phones = listOf("70111222")), true).isEmpty())
        // Edits don't re-check the name-or-number rule.
        assertTrue(ContactValidation.validate(form(), requireNameOrNumber = false).isEmpty())
    }

    @Test fun errorsComeInFormOrder() {
        val errors = ContactValidation.validate(
            form(name = EditName(given = "Maya"), phones = listOf("ok 123", "nope"), emails = listOf("bad", "a@b.co")),
            requireNameOrNumber = true,
        )
        assertEquals(listOf(FieldRef("phone", 0, 1), FieldRef("email", 0, 0)), errors.keys.toList())
        assertEquals(FieldError.BAD_PHONE, errors[FieldRef("phone", 0, 1)])
        assertEquals(FieldError.BAD_EMAIL, errors[FieldRef("email", 0, 0)])
    }

    @Test fun saveErrorClassification() {
        assertEquals(SaveError.NO_PERMISSION, classifySaveError(SecurityException("no")))
        assertEquals(SaveError.ACCOUNT_REJECTED, classifySaveError(IllegalArgumentException("account")))
        assertEquals(SaveError.ACCOUNT_REJECTED, classifySaveError(RuntimeException("wrap", IllegalArgumentException())))
        assertEquals(SaveError.UNKNOWN, classifySaveError(RuntimeException("?")))
        assertEquals(SaveError.UNKNOWN, classifySaveError(null))
    }
}
