package ph.edu.bsit.tcc.ojtdtr

import android.os.Bundle
import android.content.Intent
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
