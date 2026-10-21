package com.kuguan.app.data.voice

/** Redacts current service credentials and common credential-bearing wire fields before export. */
internal object CredentialRedactor {
    private val authorization = Regex("""(?i)(authorization\s*[:=]\s*(?:bearer|basic)\s+)[^\s"',}]+""")
    private val jsonSecret = Regex("""(?i)("(?:api[_-]?key|access[_-]?token|authorization)"\s*:\s*")[^"]+""")

    fun redact(value: String, sensitiveValues: Set<String> = emptySet()): String {
        var safe = value
        sensitiveValues.asSequence().filter { it.isNotEmpty() }.sortedByDescending { it.length }
            .forEach { secret -> safe = safe.replace(secret, "[REDACTED]") }
        safe = authorization.replace(safe) { "${it.groupValues[1]}[REDACTED]" }
        safe = jsonSecret.replace(safe) { "${it.groupValues[1]}[REDACTED]" }
        return safe
    }
}
