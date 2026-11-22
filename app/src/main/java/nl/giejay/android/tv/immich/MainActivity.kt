package nl.giejay.android.tv.immich

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import nl.giejay.android.tv.immich.ambient.AmbientFragment
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import timber.log.Timber

/**
 * Hosts the ambient screen (or sign-in, before there is a server) and the settings.
 */
class MainActivity : FragmentActivity() {
    private lateinit var navController: NavController
    private lateinit var navHost: NavHostFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.i("Booting main activity")
        setContentView(R.layout.activity_main)

        navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHost.navController
        val graph = navController.navInflater.inflate(R.navigation.nav_graph)
        if (PreferenceManager.isLoggedId()) {
            Timber.i("Start page is the ambient screen")
            graph.setStartDestination(R.id.ambientFragment)
        } else {
            Timber.i("Start page is authentication")
            graph.setStartDestination(R.id.authFragment)
        }
        navController.graph = graph
    }

    /**
     * The ambient screen owns the remote: keys go to it before any view (the slideshow would
     * otherwise open its own controls on OK and Down), whichever view happens to have focus.
     * (Activity.dispatchKeyEvent is public platform API; androidx's ComponentActivity merely marks
     * its own override @RestrictTo, which is what lint's RestrictedApi trips over.)
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_POWER || event.keyCode == KeyEvent.KEYCODE_F1)) {
            Timber.i("Power key pressed")
            ImmichApplication.activitySensor?.powerButtonPressed()
        }
        val ambient = navHost.childFragmentManager.fragments.firstOrNull { it is AmbientFragment && it.isResumed } as AmbientFragment?
        if (ambient?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("Main activity got destroyed")
    }
}
