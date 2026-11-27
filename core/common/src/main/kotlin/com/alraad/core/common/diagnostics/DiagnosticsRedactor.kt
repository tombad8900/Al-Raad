package com.alraad.core.common.diagnostics

import com.alraad.core.common.contracts.DiagEntry
import java.net.URLDecoder

/**
 * Removes credentials from diagnostic payloads without changing the requests
 * made by the browser or player. Safe URLs and non-sensitive headers remain.
 */
object DiagnosticsRedactor {
    private const val REDACTED = "[REDACTED]"

    private val sensitiveNames =
        setOf(
            "auth",
            "authorization",
            "proxyauthorization",
            "cookie",
            "setcookie",
            "token",
            "accesstoken",
            "refreshtoken",
            "idtoken",
            "session",
            "sessionid",
            "phpsessid",
            "jsessionid",
            "jwt",
            "apikey",
            "xapikey",
            "key",
            "clientsecret",
            "privatekey",
            "secret",
            "password",
            "passwd",
            "signature",
            "sig",
            "credential",
            "credentials",
            "code",
        )

    private val cookieHeaderAssignment =
        Regex("""(?im)(["']?(?:set-cookie|cookie)["']?\s*[:=]\s*)[^\r\n]*""")

    private val headerAssignment =
        Regex(
            """(?i)(["']?[A-Za-z0-9_-]*(?:proxy-authorization|authorization|x-api-key|api-key|access-token|refresh-token|id-token|token|session(?:-id)?|jwt|client-secret|secret|password|passwd|signature|credential|private-key)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|(?:Bearer|Basic)\s+[^ \t,;]+|[^\s,;]+)""",
        )

    private val queryAssignment = Regex("""([?&;])([^=&#;\s]+)=([^&#;\s]*)""")

    private val encodedQueryAssignment =
        Regex("""(?i)(%3f|%26)((?:[^%&=\s]|%[0-9a-f]{2})+)(%3d)((?:[^%&#\s]|%[0-9a-f]{2})*)""")

    fun redact(entry: DiagEntry): DiagEntry =
        entry.copy(
            message = redactText(entry.message),
            details =
                entry.details.mapValues { (key, value) ->
                    if (isSensitiveName(key)) REDACTED else redactText(value)
                },
        )

    fun redactText(value: String): String {
        var safe =
            cookieHeaderAssignment.replace(value) { match ->
                match.groupValues[1] + REDACTED
            }
        safe =
            headerAssignment.replace(safe) { match ->
                match.groupValues[1] + REDACTED
            }
        safe =
            queryAssignment.replace(safe) { match ->
                if (isSensitiveName(match.groupValues[2])) {
                    match.groupValues[1] + match.groupValues[2] + "=" + REDACTED
                } else {
                    match.value
                }
            }
        safe =
            encodedQueryAssignment.replace(safe) { match ->
                if (isSensitiveName(match.groupValues[2])) {
                    match.groupValues[1] + match.groupValues[2] + match.groupValues[3] + "%5BREDACTED%5D"
                } else {
                    match.value
                }
            }
        return safe
    }

    private fun isSensitiveName(rawName: String): Boolean {
        val decoded =
            runCatching {
                URLDecoder.decode(rawName.replace("+", "%2B"), "UTF-8")
            }.getOrDefault(rawName)
        val normalized = decoded.lowercase().filter(Char::isLetterOrDigit)
        return normalized in sensitiveNames ||
            normalized.endsWith("token") ||
            normalized.endsWith("secret") ||
            normalized.endsWith("password") ||
            normalized.endsWith("signature") ||
            normalized.endsWith("credential") ||
            normalized.endsWith("apikey") ||
            normalized.endsWith("privatekey")
    }
}
