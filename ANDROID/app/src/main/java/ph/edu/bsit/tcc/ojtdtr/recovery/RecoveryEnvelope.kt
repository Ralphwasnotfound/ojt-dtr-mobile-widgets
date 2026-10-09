package ph.edu.bsit.tcc.ojtdtr.recovery

import java.time.Instant
import kotlinx.serialization.json.*
import ph.edu.bsit.tcc.ojtdtr.proof.ProofContract

internal data class RecoveredReservation(val upload: String, val session: String)
internal data class RecoveryObservation(val reservation: RecoveredReservation?, val confirmed: Boolean,
    val lookup: String, val objectStatus: String, val receiptStatus: String)

/** Strict wire projection. No paths, bytes, credentials, coordinates or raw diagnostics. */
internal fun recoveryEnvelope(raw: String, expected: RecoveryRecord, objectChecked: Boolean): RecoveryObservation {
    require(raw.toByteArray(Charsets.UTF_8).size <= 64 * 1024)
    // This exact contract has objects at depth <= 2 and no arrays. Scan iteratively
    // BEFORE decoding: recursive regex repetition can overflow on a bounded string.
    require('\uFFFD' !in raw)
    val objects = ArrayDeque<MutableSet<String>>()
    var index = 0
    while (index < raw.length) {
        when (raw[index]) {
            '{' -> { require(objects.size < 2); objects.addLast(mutableSetOf()) }
            '}' -> { require(objects.isNotEmpty()); objects.removeLast() }
            '[', ']' -> throw IllegalArgumentException()
            '"' -> {
                val start = index++
                var closed = false
                while (index < raw.length) {
                    require(index - start <= 256)
                    when (raw[index]) {
                        '\\' -> { index += 2; continue }
                        '"' -> { closed = true; break }
                    }
                    index++
                }
                require(closed)
                var next = index + 1
                while (next < raw.length && raw[next] in " \t\r\n") next++
                if (next < raw.length && raw[next] == ':') {
                    require(objects.isNotEmpty())
                    val key = Json.parseToJsonElement(raw.substring(start, index + 1)).jsonPrimitive.content
                    require(objects.last().add(key))
                }
            }
        }
        index++
    }
    require(objects.isEmpty())
    val row = Json.parseToJsonElement(raw) as JsonObject
    fun JsonObject.text(key: String) = (getValue(key) as JsonPrimitive).also { require(it.isString) }.content
    fun uuid(value: String): String = ProofContract.uuid(value).also { require(value == value.lowercase()) }
    require(row.keys == setOf("contract_version", "observed_at", "student_uid", "request_id", "lookup_status",
        "reservation", "object_status", "receipt_status", "receipt"))
    val version = row.getValue("contract_version") as JsonPrimitive
    require(!version.isString && version.content == "1")
    Instant.parse(row.text("observed_at"))
    require(uuid(row.text("student_uid")) == expected.owner && uuid(row.text("request_id")) == expected.request)
    val lookup = row.text("lookup_status"); val objectStatus = row.text("object_status"); val receiptStatus = row.text("receipt_status")
    require(lookup in setOf("found", "not_found", "unknown"))
    require(objectStatus in setOf("not_checked", "missing", "present", "invalid", "unknown"))
    require(receiptStatus in setOf("missing", "confirmed", "unknown"))
    if (lookup != "found") {
        require(row.getValue("reservation") == JsonNull && row.getValue("receipt") == JsonNull && receiptStatus == "unknown")
        require(objectStatus == if (lookup == "not_found") "not_checked" else "unknown")
        return RecoveryObservation(null, false, lookup, objectStatus, receiptStatus)
    }
    require(if (objectChecked) objectStatus in setOf("missing", "present", "invalid", "unknown") else objectStatus == "not_checked")
    require(receiptStatus in setOf("missing", "confirmed"))
    val reservation = row.getValue("reservation") as JsonObject
    require(reservation.keys == setOf("upload_id", "attendance_session_id", "action_type", "state", "expires_at", "expired"))
    val upload = uuid(reservation.text("upload_id")); val session = uuid(reservation.text("attendance_session_id"))
    require(reservation.text("action_type") == expected.action)
    require(expected.upload == null || expected.upload == upload && expected.session == session)
    val state = reservation.text("state"); require(state in setOf("pending", "attached", "discarded"))
    val expiry = Instant.parse(reservation.text("expires_at"))
    val expired = reservation.getValue("expired") as JsonPrimitive
    require(!expired.isString && expired.content in setOf("true", "false"))
    require(expired.boolean == !expiry.isAfter(Instant.parse(row.text("observed_at"))))
    val confirmed = receiptStatus == "confirmed"
    if (confirmed) {
        require(state == "attached")
        val receipt = row.getValue("receipt") as JsonObject
        // Reuse the existing immutable receipt validator and its timestamp/identity joins.
        require(confirmedReceipt(JsonArray(listOf(receipt)).toString(),
            expected.copy(schema = 2, upload = upload, session = session, recovered = expected.phase == RecoveryPhase.PrepareIntent)))
        uuid(receipt.text("id")); uuid(receipt.text("student_uid")); uuid(receipt.text("upload_id")); uuid(receipt.text("attendance_session_id"))
    } else require(state != "attached" && row.getValue("receipt") == JsonNull)
    return RecoveryObservation(RecoveredReservation(upload, session), confirmed, lookup, objectStatus, receiptStatus)
}
