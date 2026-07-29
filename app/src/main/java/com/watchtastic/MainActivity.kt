package com.watchtastic

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.ambient.AmbientLifecycleObserver
import com.watchtastic.di.AppGraph
import com.watchtastic.service.MeshService
import com.watchtastic.ui.LocalAppGraph
import com.watchtastic.ui.nav.Routes
import com.watchtastic.ui.nav.WatchtasticNav
import com.watchtastic.ui.theme.WatchtasticTheme

class MainActivity : ComponentActivity() {

    companion object {
        /** Deep-link a notification tap straight into its conversation. */
        const val EXTRA_ROUTE = "com.watchtastic.extra.ROUTE"
    }

    private var deepLinkRoute by mutableStateOf<String?>(null)
    private var isAmbient by mutableStateOf(false)

    /**
     * Wear puts the app into a low-power ambient state rather than closing it, so a
     * long-running mesh session stays visible on the watch face side of the screen-off
     * boundary. We dim rather than swap layouts: the same information stays glanceable,
     * and the OLED draws far less of it.
     */
    private val ambientObserver = AmbientLifecycleObserver(
        this,
        object : AmbientLifecycleObserver.AmbientLifecycleCallback {
            override fun onEnterAmbient(
                ambientDetails: AmbientLifecycleObserver.AmbientDetails,
            ) {
                isAmbient = true
            }

            override fun onExitAmbient() {
                isAmbient = false
            }
        },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        val graph = AppGraph.from(this)
        lifecycle.addObserver(ambientObserver)
        deepLinkRoute = intent?.getStringExtra(EXTRA_ROUTE)

        // Anchor the BLE link to a foreground service so the mesh keeps working with the
        // screen off, then pick the saved radio back up — but only once the wearer has
        // actually granted Bluetooth access. Before that, both calls are guaranteed to
        // fail, and on Android 14 the service start is a fatal SecurityException.
        if (MeshService.isPermitted(this)) {
            MeshService.start(this)
            graph.repository.resumeSavedRadio()
        }

        // Read once, here: which screen the app opens on is decided at launch and must
        // not shift under the wearer if the saved radio is cleared mid-session.
        val startDestination = if (graph.prefs.radioAddress.value == null) {
            Routes.CONNECT
        } else {
            Routes.HOME
        }

        setContent {
            CompositionLocalProvider(LocalAppGraph provides graph) {
                WatchtasticTheme {
                    val ambient = isAmbient
                    Box(
                        Modifier
                            .fillMaxSize()
                            .alpha(if (ambient) AMBIENT_ALPHA else 1f),
                    ) {
                        WatchtasticNav(
                            startDestination = startDestination,
                            deepLinkRoute = deepLinkRoute,
                            onDeepLinkHandled = { deepLinkRoute = null },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLinkRoute = intent.getStringExtra(EXTRA_ROUTE)
    }

    override fun onDestroy() {
        lifecycle.removeObserver(ambientObserver)
        super.onDestroy()
    }
}

private const val AMBIENT_ALPHA = 0.55f
