package au.com.chrismckechnie.hermesmobile

import java.net.URI

data class MobilePairingRequest(val baseUrl: String, val grant: String)

internal fun parseMobilePairingUri(raw: String): MobilePairingRequest? = runCatching {
    val uri = URI(raw.trim())
    if (uri.scheme != "hermes" || uri.host != "pair") return null
    val parameters = uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
        val pieces = part.split('=', limit = 2)
        if (pieces.size != 2) null else java.net.URLDecoder.decode(pieces[0], "UTF-8") to
            java.net.URLDecoder.decode(pieces[1], "UTF-8")
    }.toMap()
    val baseUrl = parameters["url"]?.trim()?.removeSuffix("/").orEmpty()
    val grant = parameters["grant"]?.trim().orEmpty()
    val host = URI(baseUrl)
    if (host.scheme !in setOf("https", "http") || host.host.isNullOrBlank() || grant.isBlank()) return null
    if (host.scheme == "http" && !isPrivateNetworkHost(host.host)) return null
    MobilePairingRequest(baseUrl.take(2_048), grant.take(256))
}.getOrNull()

private fun isPrivateNetworkHost(host: String): Boolean {
    val normalized = host.lowercase().removePrefix("[").removeSuffix("]")
    if (normalized == "localhost" || normalized == "::1" || normalized.startsWith("fc") || normalized.startsWith("fd")) return true
    val parts = normalized.split('.').mapNotNull(String::toIntOrNull)
    if (parts.size != 4 || parts.any { it !in 0..255 }) return false
    return parts[0] == 10 || parts[0] == 127 ||
        parts[0] == 192 && parts[1] == 168 ||
        parts[0] == 172 && parts[1] in 16..31 ||
        parts[0] == 100 && parts[1] in 64..127
}

data class MobilePairingResult(val token: String, val deviceId: String)
