package ph.edu.bsit.tcc.ojtdtr.ui.screen

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import java.math.BigDecimal
import java.time.Instant
import java.time.format.DateTimeFormatter
import ph.edu.bsit.tcc.ojtdtr.attendance.*

private fun timestamp(value: Instant?): String = value?.atZone(Manila)?.format(
    DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", java.util.Locale.ENGLISH)) ?: "—"
private fun hours(seconds: BigDecimal): String {
    val minutes = seconds.divideToIntegralValue(BigDecimal(60)).toBigInteger()
    return "${minutes / java.math.BigInteger.valueOf(60)}h ${minutes % java.math.BigInteger.valueOf(60)}m"
}

@Composable
fun AttendanceSection(state: AttendanceState, onRefresh: () -> Unit) {
    val summary = when (state) {
        is AttendanceState.Fresh -> state.summary
        is AttendanceState.Stale -> state.summary
        else -> null
    }
    when (state) {
        AttendanceState.AccountChanged -> Text("Attendance requires a current approved student account.")
        AttendanceState.Loading -> Text("Loading confirmed attendance…")
        AttendanceState.AccessDenied -> Text("Attendance access denied. Refresh your account status or sign in again.")
        is AttendanceState.Unavailable -> Text(when (state.problem) {
            AttendanceProblem.ContractUnavailable -> "Attendance service is unavailable in this environment."
            AttendanceProblem.InvalidResponse -> "Attendance response could not be verified."
            else -> "Attendance could not be loaded. Check your connection and refresh."
        })
        is AttendanceState.Stale -> Text("Stale data — offline, refresh failed, or the Philippine day has changed. Last confirmed: ${timestamp(state.fetchedAt)}")
        is AttendanceState.Fresh -> Text("Confirmed: ${timestamp(state.fetchedAt)}")
    }
    if (summary != null) {
        Text("Philippine attendance day: ${summary.day}")
        Text("Status: ${if (summary.openSessionId != null) "IN" else "OUT"}")
        if (summary.overnight) Text("Open session carried over from a previous Philippine day.")
        Text("Time In: ${timestamp(summary.displaySession?.timeIn)}")
        Text("Time Out: ${timestamp(summary.displaySession?.timeOut)}")
        Text("Today's completed hours: ${hours(summary.todayCompletedSeconds)}")
        Text("Total completed hours: ${hours(summary.completedSeconds)}")
        Text("Required hours: ${summary.requiredHours}")
        Text("Remaining hours: ${hours(summary.remainingSeconds)}")
        Text("Open sessions are excluded from completed hours.")
    }
    Button(onClick = onRefresh, enabled = state != AttendanceState.Loading) { Text("Refresh attendance") }
}
