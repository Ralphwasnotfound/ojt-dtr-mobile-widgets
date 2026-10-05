package ph.edu.bsit.tcc.ojtdtr.auth

import java.net.URI
import java.net.URLDecoder

object CallbackPolicy {
    const val URI_VALUE = "ph.edu.bsit.tcc.ojtdtr://auth/callback"
    const val TTL_MILLIS = 5 * 60 * 1000L
    sealed interface Result {
        class Code(val value: String) : Result // No generated toString containing the OAuth code.
        data object ProviderError : Result
        data object Invalid : Result
    }
    fun fresh(createdAt: Long, now: Long): Boolean = now >= createdAt && now - createdAt < TTL_MILLIS

    fun parse(raw: String): Result { return try {
        val uri = URI(raw)
        if (raw.length > 8192 || uri.scheme != "ph.edu.bsit.tcc.ojtdtr" || uri.host != "auth" ||
            uri.rawPath != "/callback" || uri.port != -1 || uri.rawUserInfo != null ||
            uri.rawFragment != null || uri.isOpaque) return Result.Invalid
        val query = uri.rawQuery ?: return Result.Invalid
        val pairs = query.split('&').map {
            val parts = it.split('=', limit = 2)
            if (parts.size != 2) return Result.Invalid
            URLDecoder.decode(parts[0], "UTF-8") to URLDecoder.decode(parts[1], "UTF-8")
        }
        if (pairs.map { it.first }.distinct().size != pairs.size) return Result.Invalid
        val params = pairs.toMap()
        when {
            params.keys == setOf("code") && params.getValue("code").matches(Regex("[A-Za-z0-9_-]{8,2048}")) ->
                Result.Code(params.getValue("code"))
            "error" in params && params.getValue("error").isNotBlank() &&
                params.keys.all { it in setOf("error", "error_code", "error_description") } -> Result.ProviderError
            else -> Result.Invalid
        }
    } catch (_: Exception) { Result.Invalid }
    }
}
