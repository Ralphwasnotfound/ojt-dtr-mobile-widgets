package ph.edu.bsit.tcc.ojtdtr.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import ph.edu.bsit.tcc.ojtdtr.DtrApplication
import ph.edu.bsit.tcc.ojtdtr.MainActivity

fun widgetOpenIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
    .setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

class DtrWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact
    override val stateDefinition = null // Never put attendance or auth in Glance preferences.
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val app = context.applicationContext as DtrApplication
        app.reassessWidget() // Reads only an already initialized coordinator; never initializes auth or performs HTTP.
        provideContent {
            val view by app.widgetPresentation.collectAsState()
            Column(GlanceModifier.fillMaxSize().background(Color(0xFF720D27)).padding(16.dp)
                .clickable(actionStartActivity(widgetOpenIntent(context)))) {
                Text("BSIT TCC", style = TextStyle(color = ColorProvider(Color(0xFFE2B449)), fontSize = 14.sp))
                Text("OJT DTR", style = TextStyle(color = ColorProvider(Color.White), fontSize = 22.sp))
                Column {
                    Text("Completed: ${view.completed ?: "Unavailable"}", style = TextStyle(color = ColorProvider(Color.White)))
                    Text("Remaining: ${view.remaining ?: "Unavailable"}", style = TextStyle(color = ColorProvider(Color.White)))
                    Text("Status: ${view.status ?: "Unavailable"}", style = TextStyle(color = ColorProvider(Color.White)))
                    Text("Last refresh (Manila, device clock): ${view.checked ?: "Unavailable"}", style = TextStyle(color = ColorProvider(Color.White), fontSize = 11.sp))
                }
                Text(view.message, style = TextStyle(color = ColorProvider(Color(0xFFE2B449)), fontSize = 12.sp))
            }
        }
    }
}
class DtrWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DtrWidget()
}
