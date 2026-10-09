package ph.edu.bsit.tcc.ojtdtr

import android.os.Bundle
import android.content.Intent
import android.content.ActivityNotFoundException
import android.widget.Toast
import androidx.core.net.toUri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import ph.edu.bsit.tcc.ojtdtr.navigation.CompanionNavigation
import ph.edu.bsit.tcc.ojtdtr.ui.theme.OjtDtrTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback(intent)
        enableEdgeToEdge()
        setContent {
            OjtDtrTheme { CompanionNavigation(
                authentication = (application as DtrApplication).authentication,
                onGoogle = { url -> CustomTabsIntent.Builder().build().launchUrl(this, url.toUri()) },
                onWeb = { url ->
                    try { startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)) }
                    catch (_: ActivityNotFoundException) {
                        Toast.makeText(this, R.string.browser_unavailable, Toast.LENGTH_LONG).show()
                    }
                },
            ) }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback(intent)
    }

    private fun handleCallback(inbound: Intent) {
        if (inbound.action == Intent.ACTION_VIEW) {
            val callback = inbound.dataString
            inbound.data = null // Do not retain OAuth codes in activity intents/saved state.
            if (callback != null) (application as DtrApplication).authentication.callback(callback)
        }
    }
}
