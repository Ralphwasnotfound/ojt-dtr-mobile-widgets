package ph.edu.bsit.tcc.ojtdtr.widget

import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale
import ph.edu.bsit.tcc.ojtdtr.attendance.*
import ph.edu.bsit.tcc.ojtdtr.auth.AccountState

/** Minimal, in-memory rendering only. No identity, session, SDK, record or credential payload. */
data class WidgetPresentation(val message: String, val completed: String? = null,
    val remaining: String? = null, val status: String? = null, val checked: String? = null) {
    companion object {
        val Unknown = WidgetPresentation("Unavailable — open app to verify attendance")
        fun derive(account: AccountState, attendance: AttendanceState, currentBinding: Boolean): WidgetPresentation {
            if (account == AccountState.SignedOut) return WidgetPresentation("Unavailable — sign in to view attendance")
            if (account in listOf(AccountState.Pending, AccountState.Rejected, AccountState.AdminApproved))
                return WidgetPresentation("Unavailable — attendance unavailable")
            if (account != AccountState.StudentApproved || !currentBinding) return Unknown
            val pair = when (attendance) {
                is AttendanceState.Fresh -> attendance.summary to attendance.fetchedAt
                is AttendanceState.Stale -> attendance.summary to attendance.fetchedAt
                is AttendanceState.Refreshing -> attendance.summary to attendance.fetchedAt
                else -> return Unknown
            }
            val s = pair.first
            if (s.requiredHours <= 0 || s.completedSeconds < BigDecimal.ZERO || s.todayCompletedSeconds < BigDecimal.ZERO ||
                s.todayCompletedSeconds > s.completedSeconds || (s.openSessionId == null) != (s.openTimeIn == null)) return Unknown
            // RemoteViews may remain on a launcher after the process/timer stops. Never label them Fresh.
            return WidgetPresentation("Stale — open app to verify", hours(s.completedSeconds), hours(s.remainingSeconds),
                if (s.openSessionId != null) "Currently timed in" else "Not timed in",
                pair.second.atZone(Manila).format(DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH)))
        }
        private fun hours(seconds: BigDecimal): String {
            val minutes = seconds.divideToIntegralValue(BigDecimal(60)).toBigInteger()
            return "${minutes / java.math.BigInteger.valueOf(60)}h ${minutes % java.math.BigInteger.valueOf(60)}m"
        }
    }
}
