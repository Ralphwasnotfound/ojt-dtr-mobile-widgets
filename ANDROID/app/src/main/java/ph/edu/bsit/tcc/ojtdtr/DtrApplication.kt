package ph.edu.bsit.tcc.ojtdtr

import android.app.Application
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import ph.edu.bsit.tcc.ojtdtr.auth.AuthCoordinator
import ph.edu.bsit.tcc.ojtdtr.widget.DtrWidget
import ph.edu.bsit.tcc.ojtdtr.widget.WidgetPresentation
import ph.edu.bsit.tcc.ojtdtr.widget.WidgetPublication

class DtrApplication : Application() {
    internal var proofStorageReady = true
        private set
    override fun onCreate() {
        super.onCreate()
        // Cache is excluded from backup; discard orphan proof files after process restart.
        proofStorageReady = ph.edu.bsit.tcc.ojtdtr.proof.cleanupProofCache(cacheDir)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val publication = WidgetPublication<GlanceId>(
        current = { currentWidgetPresentation() },
        instances = { GlanceAppWidgetManager(this).getGlanceIds(DtrWidget::class.java) },
        update = { DtrWidget().update(this, it) })
    val widgetPresentation = publication.presentation
    private val coordinator = lazy {
        AuthCoordinator(this).also { auth ->
            scope.launch {
                publication.observe(combine(auth.state, auth.attendance.state) { _, _ -> Unit })
            }
        }
    }
    val authentication: AuthCoordinator get() = coordinator.value
    internal val authenticationInitialized get() = coordinator.isInitialized()
    private fun currentWidgetPresentation(): WidgetPresentation =
        if (coordinator.isInitialized()) coordinator.value.widgetPresentation() else WidgetPresentation.Unknown
    suspend fun reassessWidget() = withContext(Dispatchers.Main.immediate) { publication.reassess() }
}
