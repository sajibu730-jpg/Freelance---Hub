package com.freelancehub.app.data

object Validation {
    fun email(value: String): Boolean = value.trim().matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
    fun password(value: String): Boolean = value.length >= 8 && value.any(Char::isLetter) && value.any(Char::isDigit)
    fun text(value: String, min: Int, max: Int): Boolean = value.trim().length in min..max
    fun httpsUrlList(value: String, maxItems: Int = 10): Boolean {
        val items = value.split(Regex("[,;\\n]")).map { it.trim() }.filter { it.isNotBlank() }
        return items.size <= maxItems && items.all(::httpsUrl)
    }
    fun apiPath(value: String): Boolean {
        val p = value.trim()
        return p.startsWith("/") && !p.contains("\\") && !p.split("/").any { it == ".." } &&
            !p.contains('\\n') && !p.contains('\\r') && p.length <= 2048
    }

    fun httpsUrl(value: String): Boolean = runCatching {
        val uri = android.net.Uri.parse(value.trim())
        uri.scheme.equals("https", true) &&
            !uri.host.isNullOrBlank() &&
            uri.userInfo.isNullOrBlank() &&
            uri.fragment.isNullOrBlank()
    }.getOrDefault(false)
}

fun BackendConfig.validate(): List<String> = buildList {
    if (!Validation.httpsUrl(baseUrl)) add("Backend base URL must use HTTPS and include a host")
    if (!Validation.text(projectId, 2, 120)) add("Project ID is invalid")
    if (environment !in setOf("development", "staging", "production")) add("Unsupported environment")
}
