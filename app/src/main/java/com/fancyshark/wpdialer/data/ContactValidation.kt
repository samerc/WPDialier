package com.fancyshark.wpdialer.data

/** What is wrong with one form field. */
enum class FieldError { NEED_NAME_OR_NUMBER, BAD_PHONE, BAD_EMAIL }

/**
 * A field in the contact form: [kind] is "name", "phone" or "email";
 * [raw] / [index] locate it (raw contact, then position in its list).
 * The name field has index 0 on raw 0.
 */
data class FieldRef(val kind: String, val raw: Int, val index: Int)

/**
 * Checks the contact form before saving, so problems show on the field
 * itself instead of as a failed save. Blank rows are fine: they're dropped.
 * Pure Kotlin — unit-tested in ContactValidationTest.
 */
object ContactValidation {

    // Digits plus what dialers and contact apps accept: separators, + * #,
    // post-dial pause/wait (, ; p w) and vanity letters (1-800-FLOWERS).
    private val PHONE_CHARS = Regex("""^[0-9A-Za-z+*#()\-./ ,; ]+$""")
    private val EMAIL = Regex("""^[^\s@]+@[^\s@]+\.[^\s@]+$""")

    fun isValidPhone(number: String): Boolean {
        val n = number.trim()
        return n.isEmpty() || (n.any { it.isDigit() } && PHONE_CHARS.matches(n))
    }

    fun isValidEmail(address: String): Boolean {
        val a = address.trim()
        return a.isEmpty() || EMAIL.matches(a)
    }

    /**
     * Errors in form order (name, phones, emails), so the first entry is
     * the one to scroll to. [requireNameOrNumber]: new contacts must have
     * a name or a number; edits of existing contacts don't re-check that.
     */
    fun validate(form: EditableContact, requireNameOrNumber: Boolean): LinkedHashMap<FieldRef, FieldError> {
        val out = LinkedHashMap<FieldRef, FieldError>()
        if (requireNameOrNumber) {
            val hasName = form.raws.any { !it.name.isBlank }
            val hasNumber = form.raws.any { r -> r.phones.any { p -> p.number.any { it.isDigit() } } }
            if (!hasName && !hasNumber) {
                out[FieldRef("name", 0, 0)] = FieldError.NEED_NAME_OR_NUMBER
                // Point at the first phone box too, so both options light up.
                form.raws.forEachIndexed { ri, r ->
                    if (r.phones.isNotEmpty() && out.size == 1) {
                        out[FieldRef("phone", ri, 0)] = FieldError.NEED_NAME_OR_NUMBER
                    }
                }
            }
        }
        form.raws.forEachIndexed { ri, r ->
            r.phones.forEachIndexed { pi, p ->
                if (!isValidPhone(p.number)) out[FieldRef("phone", ri, pi)] = FieldError.BAD_PHONE
            }
        }
        form.raws.forEachIndexed { ri, r ->
            r.emails.forEachIndexed { ei, e ->
                if (!isValidEmail(e.address)) out[FieldRef("email", ri, ei)] = FieldError.BAD_EMAIL
            }
        }
        return out
    }
}
