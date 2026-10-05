package ph.edu.bsit.tcc.ojtdtr.auth

import java.net.URI
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ClientConfiguration private constructor(val url: String, val clientKey: String) {
    // Never use a data class: its generated toString would disclose configuration.
    companion object {
        fun parse(url: String, key: String): ClientConfiguration? = try {
            val uri = URI(url)
            val validUrl = uri.scheme == "https" && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.port == -1 && uri.rawQuery == null &&
                uri.rawFragment == null && (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
            val validKey = when {
                key.startsWith("sb_publishable_") -> key.matches(Regex("sb_publishable_[A-Za-z0-9_-]{16,}"))
                key.split('.').size == 3 -> {
                    val payload = String(Base64.getUrlDecoder().decode(key.split('.')[1]), Charsets.UTF_8)
                    Json.parseToJsonElement(payload).jsonObject["role"]?.jsonPrimitive?.content == "anon"
                }
                else -> false
            }
            if (validUrl && validKey && key.none { it.isWhitespace() }) ClientConfiguration(url.trimEnd('/'), key) else null
        } catch (_: Exception) { null }
    }
}
