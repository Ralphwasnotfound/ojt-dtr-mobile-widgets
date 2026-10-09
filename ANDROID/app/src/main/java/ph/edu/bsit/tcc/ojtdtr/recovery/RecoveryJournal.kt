package ph.edu.bsit.tcc.ojtdtr.recovery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import ph.edu.bsit.tcc.ojtdtr.proof.ProofContract

/** No proof path, image, coordinates, credentials or authorization is part of this schema. */
@Serializable
internal enum class RecoveryPhase { PrepareIntent, Prepared, UploadIntent, Uploaded, FinalizeIntent, Confirmed }
@Serializable
internal data class RecoveryRecord(val schema: Int = 1, val revision: Long = 1,
    val owner: String, val request: String, val action: String, val phase: RecoveryPhase,
    val upload: String? = null, val session: String? = null) {
    fun validate(): RecoveryRecord = apply {
        require(schema == 1 && revision in 1 until Long.MAX_VALUE)
        ProofContract.uuid(owner); ProofContract.uuid(request)
        require(action in setOf("time_in", "time_out"))
        require((upload == null) == (session == null))
        upload?.let(ProofContract::uuid); session?.let(ProofContract::uuid)
        require(if (phase == RecoveryPhase.PrepareIntent) upload == null else upload != null)
    }
}
internal class RecoveryStorageFailure : Exception()
internal interface JournalStorage {
    fun read(): ByteArray?
    /** Must replace atomically or leave the previous complete record intact. */
    fun replace(bytes: ByteArray)
    fun remove()
}

/** One application-owned instance. Every writer uses identity/revision CAS under this lock.
 * A global single unresolved slot deliberately blocks other accounts without revealing its owner.
 * Corruption/key loss is retained and blocks new attempts; never erase uncertain evidence. */
internal class RecoveryJournal(private val storage: JournalStorage) {
    private val lock = Any()
    private val json = Json { encodeDefaults = true }
    fun load(): RecoveryRecord? = synchronized(lock) { read() }
    private fun read(): RecoveryRecord? {
        val bytes = try { storage.read() } catch (_: Exception) { throw RecoveryStorageFailure() }
        return bytes?.let {
            try {
                require(it.size in 1..4096)
                json.decodeFromString<RecoveryRecord>(String(it, Charsets.UTF_8)).validate()
            } catch (_: Exception) { throw RecoveryStorageFailure() }
            finally { it.fill(0) }
        }
    }
    private fun write(record: RecoveryRecord) {
        val bytes = json.encodeToString(record.validate()).toByteArray(Charsets.UTF_8)
        try { storage.replace(bytes) } catch (_: Exception) { throw RecoveryStorageFailure() }
        finally { bytes.fill(0) }
    }
    fun begin(owner: String, request: String, action: String, current: () -> Boolean): RecoveryRecord = synchronized(lock) {
        if (!current() || read() != null) throw RecoveryStorageFailure()
        RecoveryRecord(owner = owner, request = request, action = action, phase = RecoveryPhase.PrepareIntent).also(::write)
    }
    fun advance(expected: RecoveryRecord, phase: RecoveryPhase, current: () -> Boolean,
        upload: String? = expected.upload, session: String? = expected.session): RecoveryRecord = synchronized(lock) {
        if (!current() || read() != expected) throw RecoveryStorageFailure()
        require(phase == RecoveryPhase.Confirmed || phase.ordinal == expected.phase.ordinal + 1)
        require(expected.phase != RecoveryPhase.Confirmed)
        if (expected.upload != null) require(upload == expected.upload && session == expected.session)
        expected.copy(revision = expected.revision + 1, phase = phase, upload = upload, session = session).also(::write)
    }

    /** Idempotent only after the caller validates an authoritative receipt for expected identity.
     * Another legitimate confirmer may already have advanced/removed this same attempt.
     * Never clear a newer request/account, even if a delayed old receipt is valid. */
    fun confirmAndClear(expected: RecoveryRecord, current: () -> Boolean) = synchronized(lock) {
        expected.validate()
        if (expected.upload == null || expected.session == null) throw RecoveryStorageFailure()
        if (!current()) throw RecoveryStorageFailure()
        val stored = read() ?: return@synchronized
        if (stored.owner != expected.owner || stored.request != expected.request || stored.action != expected.action ||
            stored.upload != expected.upload || stored.session != expected.session ||
            stored.revision < expected.revision) throw RecoveryStorageFailure()
        val confirmed = if (stored.phase == RecoveryPhase.Confirmed) stored else
            stored.copy(revision = stored.revision + 1, phase = RecoveryPhase.Confirmed).also(::write)
        clearConfirmed(confirmed, current)
    }
    fun clearConfirmed(expected: RecoveryRecord, current: () -> Boolean) = synchronized(lock) {
        if (!current() || expected.phase != RecoveryPhase.Confirmed || read() != expected) throw RecoveryStorageFailure()
        try { storage.remove() } catch (_: Exception) { throw RecoveryStorageFailure() }
    }
}
