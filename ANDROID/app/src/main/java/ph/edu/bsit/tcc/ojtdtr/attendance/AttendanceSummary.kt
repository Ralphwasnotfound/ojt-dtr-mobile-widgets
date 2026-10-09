package ph.edu.bsit.tcc.ojtdtr.attendance

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.serialization.json.*

val Manila: ZoneId = ZoneId.of("Asia/Manila")
enum class AttendanceAction { TimeIn, TimeOut, None }
data class AttendanceSession(val id: String, val timeIn: Instant, val timeOut: Instant?, val ordinal: Int)
data class AttendanceSummary(val day: LocalDate, val sessions: List<AttendanceSession>,
    val openSessionId: String?, val openTimeIn: Instant?, val openOrdinal: Int?,
    val nextAction: AttendanceAction, val completedSeconds: BigDecimal,
    val todayCompletedSeconds: BigDecimal, val completedSessions: Long, val daysPresent: Long,
    val requiredHours: Int) {
    val remainingSeconds: BigDecimal get() = (requiredHours.toBigDecimal() * BigDecimal(3600) - completedSeconds).max(BigDecimal.ZERO)
    val overnight: Boolean get() = openTimeIn?.atZone(Manila)?.toLocalDate()?.isBefore(day) == true
    val displaySession: AttendanceSession? get() = if (openSessionId != null)
        AttendanceSession(openSessionId, openTimeIn!!, null, openOrdinal!!) else sessions.lastOrNull()
}

/** No defaults/coercion: missing fields and inconsistent rows never become zero attendance. */
internal object AttendanceContract {
    fun requiredHours(profile: String, userId: String): Int = try {
        val p = one(profile)
        if (p.text("id") != userId || p.text("role") != "student" || p.text("status") != "approved")
            throw AttendanceReadFailure(AttendanceProblem.AccessDenied)
        p.integer("required_hours").also { require(it in 1..Int.MAX_VALUE.toLong()) }.toInt()
    } catch (known: AttendanceReadFailure) { throw known }
    catch (_: Exception) { throw AttendanceReadFailure(AttendanceProblem.InvalidResponse) }

    fun parse(summary: String, profile: String, userId: String): AttendanceSummary = try {
        val required = requiredHours(profile, userId)
        val s = one(summary)
        val day = LocalDate.parse(s.text("manila_day"))
        val openId = s.nullableText("open_session_id")?.also(::uuid)
        val openIn = s.nullableText("open_time_in")?.let(Instant::parse)
        val ordinal = s.nullableInteger("open_session_ordinal")?.also { require(it in 1..2) }?.toInt()
        require((openId == null) == (openIn == null) && (openId == null) == (ordinal == null))
        require(openIn == null || !openIn.atZone(Manila).toLocalDate().isAfter(day))
        val starts = s.integer("starts_today").also { require(it in 0..2) }.toInt()
        val started = (s.required("started_today") as? JsonPrimitive)?.let {
            require(!it.isString); it.booleanOrNull ?: error("boolean")
        } ?: error("boolean")
        require(started == (starts > 0))
        val rows = s.required("today_sessions") as? JsonArray ?: error("array")
        val sessions = rows.mapIndexed { index, element ->
            val row = element as? JsonObject ?: error("row")
            require(row.text("student_uid") == userId)
            val id = row.text("id").also(::uuid)
            val timeIn = Instant.parse(row.text("time_in"))
            val timeOut = row.nullableText("time_out")?.let(Instant::parse)
            require(timeOut == null || !timeOut.isBefore(timeIn))
            require(LocalDate.parse(row.text("start_day")) == day && timeIn.atZone(Manila).toLocalDate() == day)
            require(row.integer("session_ordinal") == (index + 1).toLong())
            AttendanceSession(id, timeIn, timeOut, index + 1)
        }
        require(sessions.size == starts && sessions.map { it.id }.distinct().size == starts)
        require(sessions.zipWithNext().all { (a, b) -> a.timeOut != null && !b.timeIn.isBefore(a.timeOut) })
        val todayOpen = sessions.filter { it.timeOut == null }
        require(todayOpen.size <= 1)
        if (openIn?.atZone(Manila)?.toLocalDate() == day) {
            require(todayOpen.singleOrNull()?.let { it.id == openId && it.timeIn == openIn && it.ordinal == ordinal } == true)
        } else {
            require(todayOpen.isEmpty())
            if (openId != null) require(sessions.isEmpty())
        }
        val action = when (s.text("next_action")) {
            "time_in" -> AttendanceAction.TimeIn
            "time_out" -> AttendanceAction.TimeOut
            "none" -> AttendanceAction.None
            else -> error("action")
        }
        require(action == if (openId != null) AttendanceAction.TimeOut else if (starts < 2) AttendanceAction.TimeIn else AttendanceAction.None)
        val total = s.decimal("completed_seconds")
        val today = s.decimal("today_completed_seconds")
        val expectedToday = sessions.filter { it.timeOut != null }.fold(BigDecimal.ZERO) { sum, row ->
            val duration = Duration.between(row.timeIn, row.timeOut)
            sum + duration.seconds.toBigDecimal() + duration.nano.toBigDecimal().movePointLeft(9)
        }
        require(today.compareTo(expectedToday) == 0 && total >= today)
        val completed = s.integer("completed_sessions").also { require(it >= sessions.count { row -> row.timeOut != null }) }
        val days = s.integer("days_present").also { require(it >= 0) }
        require(completed >= 0 && (completed != 0L || total.compareTo(BigDecimal.ZERO) == 0))
        // SQL counts every row as closed or the single open row; days counts distinct start dates.
        // BigInteger prevents completed + 1 from overflowing for malformed extreme counts.
        val rowCount = completed.toBigInteger() + if (openId == null) java.math.BigInteger.ZERO else java.math.BigInteger.ONE
        require((days == 0L) == (rowCount.signum() == 0))
        require(days.toBigInteger() <= rowCount && starts.toBigInteger() <= rowCount)
        AttendanceSummary(day, sessions, openId, openIn, ordinal, action, total, today, completed, days, required)
    } catch (known: AttendanceReadFailure) { throw known }
    catch (_: Exception) { throw AttendanceReadFailure(AttendanceProblem.InvalidResponse) }

    private fun one(raw: String): JsonObject {
        val array = Json.parseToJsonElement(raw) as? JsonArray ?: error("array")
        return array.singleOrNull() as? JsonObject ?: error("single row")
    }
    private fun JsonObject.required(key: String): JsonElement = get(key) ?: error("missing")
    private fun JsonObject.text(key: String): String = (required(key) as? JsonPrimitive)?.let {
        require(it.isString); it.content
    } ?: error("text")
    private fun JsonObject.nullableText(key: String): String? = if (required(key) == JsonNull) null else text(key)
    private fun JsonObject.integer(key: String): Long = (required(key) as? JsonPrimitive)?.let {
        require(!it.isString); it.longOrNull ?: error("integer")
    } ?: error("integer")
    private fun JsonObject.nullableInteger(key: String): Long? = if (required(key) == JsonNull) null else integer(key)
    private fun JsonObject.decimal(key: String): BigDecimal = (required(key) as? JsonPrimitive)?.let {
        require(!it.isString)
        BigDecimal(it.content).also { value -> require(value >= BigDecimal.ZERO && value.precision() <= 30 && value.scale() in 0..9) }
    } ?: error("number")
    private fun uuid(value: String) { require(UUID.fromString(value).toString() == value.lowercase()) }
}
