package ph.edu.bsit.tcc.ojtdtr.recovery

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Inert, non-exported instrumentation fixture. No auth, journal, network or mutation entry point.
 * The test retains its real recovery coordinator independently while this observer is recreated. */
class RecoveryReviewActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "Recovery lifecycle verification" })
    }
}
