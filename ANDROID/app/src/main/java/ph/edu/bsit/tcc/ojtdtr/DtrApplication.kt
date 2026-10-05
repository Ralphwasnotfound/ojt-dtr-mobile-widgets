package ph.edu.bsit.tcc.ojtdtr

import android.app.Application
import ph.edu.bsit.tcc.ojtdtr.auth.AuthCoordinator

class DtrApplication : Application() {
    lateinit var authentication: AuthCoordinator
        private set
    override fun onCreate() {
        super.onCreate()
        authentication = AuthCoordinator(this)
    }
}
