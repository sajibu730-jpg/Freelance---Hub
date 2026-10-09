package com.freelancehub.app.data

/** Pure business rules for deciding whether a freelancer may submit proposals. */
object ProfileReadiness {
    fun isReady(profile: UserProfile): Boolean =
        profile.role == UserRole.FREELANCER &&
            profile.name.trim().length >= 2 &&
            email(profile.email) &&
            profile.capabilities.isNotBlank() &&
            profile.skills.isNotBlank() &&
            profile.skillLevels.isNotBlank()

    fun gaps(profile: UserProfile): List<String> {
        if (profile.role != UserRole.FREELANCER) return emptyList()
        val gaps = mutableListOf<String>()
        if (profile.name.trim().length < 2) gaps += "Add your full name"
        if (!email(profile.email)) gaps += "Add a valid email"
        if (profile.capabilities.isBlank()) gaps += "Describe what work you can do"
        if (profile.skills.isBlank()) gaps += "Add your skills"
        if (profile.skillLevels.isBlank()) gaps += "Add skill levels"
        return gaps
    }

    private fun email(value: String): Boolean =
        value.trim().matches(Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$"))
}
