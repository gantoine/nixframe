package nl.giejay.android.tv.immich.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.leanback.app.RowsSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.navigation.fragment.findNavController
import nl.giejay.android.tv.immich.ImmichApplication
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.prefs.DebugPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.DisplayPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.HomeAssistantPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.HomeScreenChannelsPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.ScreensaverPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.ViewPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.GalleryPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.WeatherPrefScreen
import timber.log.Timber


class SettingsFragment : RowsSupportFragment() {
    private val mRowsAdapter: ArrayObjectAdapter

    init {
        val selector = ListRowPresenter()
        selector.setNumRows(1)
        mRowsAdapter = ArrayObjectAdapter(selector)
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            val card = item as SettingsCard
            card.onClick()
        }
        adapter = mRowsAdapter
    }

    override fun onAttach(activity: Activity) {
        super.onAttach(activity)
        loadData()
    }

    private fun loadData() {
        if (isAdded) {
            mRowsAdapter.add(
                createCardRow(
                    listOf(
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.server),
                            null,
                            "server",
                            "ic_settings_settings",
                            "ic_settings_settings"
                        ) {
                            findNavController().navigate(
                                HomeFragmentDirections.actionGlobalSignInFragment()
                            )
                        },
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.view_settings),
                            null,
                            "view_settings",
                            "icon_view",
                            "icon_view"
                        ) {
                            findNavController().navigate(
                                HomeFragmentDirections.actionGlobalToSettingsDialog(ViewPrefScreen.key)
                            )
                        },
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.screensaver),
                            null,
                            "screensaver",
                            "screensaver",
                            "ic_settings_settings"
                        ) {
                            findNavController().navigate(
                                HomeFragmentDirections.actionGlobalToSettingsDialog(ScreensaverPrefScreen.key)
                            )
                        },
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.display_brightness),
                            null,
                            "display_brightness",
                            "ic_display_brightness",
                            "ic_display_brightness"
                        ) {
                            findNavController().navigate(
                                HomeFragmentDirections.actionGlobalToSettingsDialog(DisplayPrefScreen.key)
                            )
                        },
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.weather),
                            null,
                            "weather",
                            "ic_weather_partly",
                            "ic_weather_partly"
                        ) {
                            findNavController().navigate(
                                HomeFragmentDirections.actionGlobalToSettingsDialog(WeatherPrefScreen.key)
                            )
                        },
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.home_assistant),
                            null,
                            "home_assistant",
                            "ic_home_assistant",
                            "ic_home_assistant"
                        ) {
                            findNavController().navigate(
                                HomeFragmentDirections.actionGlobalToSettingsDialog(HomeAssistantPrefScreen.key)
                            )
                        },
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.gallery),
                            null,
                            "gallery",
                            "ic_gallery",
                            "ic_gallery"
                        ) {
                            findNavController().navigate(
                                HomeFragmentDirections.actionGlobalToSettingsDialog(GalleryPrefScreen.key)
                            )
                        },
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.home_screen_channels),
                            null,
                            "home_screen_channels",
                            "ic_home_channels",
                            "ic_home_channels",
                            // Android TV home-screen channels need API 26 (O); hidden on KitKat frames.
                            visible = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ) {
                            findNavController().navigate(
                                HomeFragmentDirections.actionGlobalToSettingsDialog(HomeScreenChannelsPrefScreen.key)
                            )
                        },
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.debug),
                            null,
                            "debug",
                            "bug",
                            "bug"
                        ) {
                            findNavController().navigate(
                                HomeFragmentDirections.actionGlobalToSettingsDialog(DebugPrefScreen.key)
                            )
                        },
                        SettingsCard(
                            ImmichApplication.appContext!!.getString(R.string.android_settings),
                            null,
                            "android_settings",
                            "ic_settings_settings",
                            "ic_settings_settings",
                            visible = systemSettingsIntent().resolveActivity(requireContext().packageManager) != null
                        ) {
                            try {
                                startActivity(systemSettingsIntent())
                            } catch (e: ActivityNotFoundException) {
                                Timber.e(e, "No system settings app available")
                            }
                        }
                    )
                )
            )
            mainFragmentAdapter.fragmentHost?.notifyDataReady(
                mainFragmentAdapter
            )
        }
    }

    private fun systemSettingsIntent() = Intent(Settings.ACTION_SETTINGS)

    private fun createCardRow(cards: List<SettingsCard>): ListRow {
        val iconCardPresenter = SettingsIconPresenter(requireContext())
        val adapter = ArrayObjectAdapter(iconCardPresenter)
        adapter.addAll(0, cards.filter { it.visible })
        val headerItem = HeaderItem(ImmichApplication.appContext!!.getString(R.string.settings))
        return ListRow(headerItem, adapter)
    }
}
