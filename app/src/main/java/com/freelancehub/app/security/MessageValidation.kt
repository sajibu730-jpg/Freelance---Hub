package com.freelancehub.app.security

/** Common client-side message validation used before marketplace send actions. */
object MessageValidation {
    const val MAX_LENGTH = 5000

    fun normalize(input: String): String =
        input.replace("\\r\\n", "\\n").replace("\\r", "\\n").trim()

    fun validate(input: String): String? {
        val message = normalize(input)
        return when {
            message.isEmpty() -> "Message cannot be empty"
            message.length > MAX_LENGTH -> "Message exceeds the 5000-character limit"
            else -> null
        }
    }
}
