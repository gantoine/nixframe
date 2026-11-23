package nl.giejay.android.tv.immich

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.fragment.app.FragmentActivity
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import nl.giejay.android.tv.immich.ambient.AmbientFragment
import nl.giejay.android.tv.immich.sensors.ScreenOffAdmin
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
        requestScreenOffAdmin()
    }

    /**
     * Asks once per app start to activate [ScreenOffAdmin], so the remote's power button can turn
     * the display off at once. Android only shows this screen for startActivityForResult from an
     * activity (not from adb), and it needs one press of Activate on the frame. If it's declined,
     * the power button falls back to the 60 s screen-off timeout.
     */
    private fun requestScreenOffAdmin() {
        if (askedForScreenOffAdmin) return
        askedForScreenOffAdmin = true
        val admin = ComponentName(this, ScreenOffAdmin::class.java)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(admin)) return
        Timber.i("Asking to activate ScreenOffAdmin")
        try {
            startActivityForResult(
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, getString(R.string.screen_off_admin_explanation)),
                REQUEST_SCREEN_OFF_ADMIN
            )
        } catch (e: Exception) {
            Timber.w(e, "Couldn't open the device admin screen")
        }
    }

    /**
     * The ambient screen owns the remote: keys go to it before any view (the slideshow would
     * otherwise open its own controls on OK and Down), whichever view happens to have focus.
     * (Activity.dispatchKeyEvent is public platform API; androidx's ComponentActivity merely marks
     * its own override @RestrictTo, which is what lint's RestrictedApi trips over.)
     */
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // The remote's power button (F1 on the Nix remote). Consumed here so it never reaches the
        // slideshow, and acted on when released: turning the display off on the press would let
        // the release, a WAKE key, switch it straight back on.
        if (event.keyCode == KeyEvent.KEYCODE_POWER || event.keyCode == KeyEvent.KEYCODE_F1) {
            val sensor = ImmichApplication.activitySensor
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                sensor?.powerKeyDown()
            } else if (event.action == KeyEvent.ACTION_UP) {
                Timber.i("Power key pressed")
                sensor?.powerKeyUp()
            }
            return true
        }
        val ambient = navHost.childFragmentManager.fragments.firstOrNull { it is AmbientFragment && it.isResumed } as AmbientFragment?
        if (ambient?.handleKey(event) == true) return true
        return super.dispatchKeyEvent(event)
    }

    companion object {
        private const val REQUEST_SCREEN_OFF_ADMIN = 4711
        private var askedForScreenOffAdmin = false
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("Main activity got destroyed")
    }
}
