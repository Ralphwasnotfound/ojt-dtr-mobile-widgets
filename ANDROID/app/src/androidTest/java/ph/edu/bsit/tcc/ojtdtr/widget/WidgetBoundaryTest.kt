package ph.edu.bsit.tcc.ojtdtr.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import ph.edu.bsit.tcc.ojtdtr.DtrApplication
import ph.edu.bsit.tcc.ojtdtr.MainActivity

class WidgetBoundaryTest {
    @Test fun providerIsDiscoverableAndDoesNotSchedulePeriodicUpdates() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = AppWidgetManager.getInstance(context).installedProviders.firstOrNull {
            it.provider == ComponentName(context, DtrWidgetReceiver::class.java)
        }
        assertNotNull(provider); assertEquals(0, provider!!.updatePeriodMillis)
        assertEquals(android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN, provider.widgetCategory)
        assertNotEquals(0, provider.resizeMode)
        assertNull(DtrWidget().stateDefinition)
    }
    @Test fun tapUsesOnlyExplicitNativeActivityAndNoSensitiveExtras() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = widgetOpenIntent(context)
        assertEquals(ComponentName(context, MainActivity::class.java), intent.component)
        assertNull(intent.extras); assertNull(intent.data); assertEquals(android.content.Intent.ACTION_MAIN, intent.action)
    }
    @Test fun recreatedApplicationStartsWithNoSnapshotOrAuthentication() = runBlocking<Unit> {
        val recreated = DtrApplication()
        assertFalse(recreated.authenticationInitialized)
        recreated.reassessWidget()
        assertFalse(recreated.authenticationInitialized)
        assertEquals(WidgetPresentation.Unknown, recreated.widgetPresentation.value)
    }
}
