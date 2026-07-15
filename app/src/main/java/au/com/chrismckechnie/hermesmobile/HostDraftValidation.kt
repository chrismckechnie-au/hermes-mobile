package au.com.chrismckechnie.hermesmobile

/** Field-scoped validation used by the host editor before credentials leave the sheet. */
data class HostDraftErrors(
    val name: String? = null,
    val baseUrl: String? = null,
    val apiKey: String? = null,
) {
    val isValid: Boolean get() = name == null && baseUrl == null && apiKey == null
}

internal fun validateHostDraft(
    existing: HostProfile?,
    name: String,
    baseUrl: String,
    apiKey: String,
    allowInsecureHttp: Boolean,
): HostDraftErrors {
    val nameError = "Give this host a name.".takeIf { name.isBlank() }
    val normalizedUrl = runCatching { normalizeHermesBaseUrl(baseUrl) }
    val urlError = normalizedUrl.exceptionOrNull()?.message
        ?: "HTTP is unencrypted. Enable private-network HTTP only for a trusted LAN or VPN."
            .takeIf { normalizedUrl.getOrNull()?.startsWith("http://", ignoreCase = true) == true && !allowInsecureHttp }
    val keyError = "Hermes API key is required.".takeIf { apiKey.isBlank() && existing == null }
    return HostDraftErrors(name = nameError, baseUrl = urlError, apiKey = keyError)
}
