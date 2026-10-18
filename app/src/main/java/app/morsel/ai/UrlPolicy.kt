package app.morsel.ai

import java.net.URI

/**
 * Which service addresses the app will send an API key and photos to.
 *
 * https is always fine. Plain http sends everything unencrypted, so it is only allowed to
 * machines on your own network (a local AI server on your PC, for example), never to the
 * internet. The built-in engines all use fixed https addresses.
 */
object UrlPolicy {

    /** Null when [url] is acceptable, otherwise a message for the user. */
    fun problem(url: String): String? {
        val uri = runCatching { URI(url.trim()) }.getOrNull()
        val host = uri?.host?.lowercase()
        return when {
            url.isBlank() -> "Enter the service address"
            uri == null || host.isNullOrBlank() -> "That address doesn't look right. Example: https://openrouter.ai/api/v1"
            uri.scheme.equals("https", ignoreCase = true) -> null
            uri.scheme.equals("http", ignoreCase = true) && isLocal(host) -> null
            uri.scheme.equals("http", ignoreCase = true) ->
                "Use https:// for internet services. Plain http:// is only allowed for servers on your own network."
            else -> "The address must start with https://"
        }
    }

    fun isLocal(host: String): Boolean {
        val h = host.trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h == "::1") return true
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || h.count { it == '.' } != 3) return false
        val (a, b) = parts
        // Private ranges, loopback and link-local. (The emulator reaches the PC at 10.0.2.2.)
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254)
    }
}
