package nl.giejay.android.tv.immich.shared.prefs

import android.content.Context
import android.content.SharedPreferences
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager.sharedPreference

/** What has to happen for a changed setting to show, applied when the settings screen closes. */
enum class SettingsEffect {
    /** Nothing, or the setting applies itself (the display settings, the sleep timer). */
    NONE,
    /** Reload the slideshow. */
    SLIDESHOW,
    /** Reconnect to Home Assistant. */
    HOME_ASSISTANT
}

/** One section of the settings screen: its rows are [children]'s prefs, in order. */
sealed class PrefScreen(val title: String, val key: String, val children: List<PrefCategory>, val effect: SettingsEffect = SettingsEffect.NONE) {
    val prefs: List<Pref<*, *>> get() = children.flatMap { it.children }
}

data class PrefCategory(val title: String, val children: List<Pref<*, *>>)

/** A stored setting: [T] is its type in code, [PREFTYPE] the type it is stored as. */
sealed class Pref<T, PREFTYPE>(val defaultValue: T, val title: String, val summary: String) {
    abstract fun getValue(sharedPreferences: SharedPreferences): T

    open fun key() = javaClass.simpleName.lowercase()
    abstract fun save(sharedPreferences: SharedPreferences, value: T)
    open fun fromPrefValue(prefValue: PREFTYPE): T {
        // by default stored pref value and the type of the Pref is the same, but it can differ with for example ListPreferences<Int>
        // stored by String values
        @Suppress("UNCHECKED_CAST")
        return prefValue as T
    }

    @Suppress("UNCHECKED_CAST")
    open fun toPrefValue(value: T): PREFTYPE = value as PREFTYPE
}

/** What a settings-screen action can do besides its own work. */
interface ActionHost {
    val actionContext: Context
    /** Opens the Immich sign-in screen. */
    fun openSignIn()
}

/** A settings row that runs something instead of storing a value. */
open class ActionPref(
    val keyOverride: String? = null,
    title: String,
    summary: String,
    val run: (ActionHost) -> Unit,
    /** False hides the row (and a section left without rows). */
    val available: (Context) -> Boolean = { true }
) : Pref<String, String>("", title, summary) {
    override fun key(): String = keyOverride ?: super.key()
    override fun save(sharedPreferences: SharedPreferences, value: String) {}
    override fun getValue(sharedPreferences: SharedPreferences): String = ""
}

/** How a free-text setting is changed on the settings screen. */
enum class TextEditor {
    /** A text-entry dialog. */
    DIALOG,
    /** The sign-in screen (the Immich server and its key are only changed together, validated). */
    SIGN_IN,
    /** Shown, not changed. */
    NONE
}

/** How a number is written in a settings row. */
enum class NumberFormat {
    /** The option's title from the pref's titles array. */
    TITLE, SECONDS, MILLIS, MINUTES, PERCENT
}

sealed class EnumPref<T>(defaultValue: T, title: String, summary: String,
                         val titlesResourceId: Int, val valuesResourceId: Int) : Pref<T, String>
    (defaultValue, title, summary) where T : Enum<T> {
    override fun save(sharedPreferences: SharedPreferences, value: T) {
        sharedPreferences.edit().putString(key(), value.toString()).apply()
    }

    override fun toPrefValue(value: T): String {
        return value.toString()
    }

    override fun getValue(sharedPreferences: SharedPreferences): T {
        return fromPrefValue(sharedPreferences.getString(key(), toPrefValue(defaultValue))!!)
    }

    abstract override fun fromPrefValue(prefValue: String): T
}

sealed class EnumByTitlePref<T>(defaultValue: T, title: String, summary: String)  : Pref<T, String>(defaultValue, title, summary) where T : Enum<T>, T : EnumWithTitle {
    override fun save(sharedPreferences: SharedPreferences, value: T) {
        sharedPreferences.edit().putString(key(), value.toString()).apply()
    }

    override fun toPrefValue(value: T): String {
        return value.toString()
    }

    abstract override fun fromPrefValue(prefValue: String): T

    override fun getValue(sharedPreferences: SharedPreferences): T {
        return fromPrefValue(sharedPreferences.getString(key(), toPrefValue(defaultValue))!!)
    }

    abstract fun getEnumEntries(): Array<T>
}

sealed class BooleanPref(defaultValue: Boolean, title: String, summary: String) : Pref<Boolean, Boolean>(defaultValue,
    title,
    summary) {
    override fun save(sharedPreferences: SharedPreferences, value: Boolean) {
        sharedPreference.edit().putBoolean(key(), value).apply()
    }

    override fun getValue(sharedPreferences: SharedPreferences): Boolean {
        return sharedPreferences.getBoolean(key(), defaultValue)
    }
}

sealed class StringPref(defaultValue: String, title: String, summary: String) : Pref<String, String>(defaultValue,
    title,
    summary) {
    /** Never shown in clear on the settings screen (tokens, keys). */
    open val secret: Boolean = false
    /** The value used while this one is blank (a build-time default), or "" for none. */
    open val builtIn: String get() = ""
    /** Shown for a blank value when there is no [builtIn] one. */
    open val blankLabel: String = "Not set"
    open val editor: TextEditor = TextEditor.DIALOG

    override fun save(sharedPreferences: SharedPreferences, value: String) {
        sharedPreference.edit().putString(key(), value).apply()
    }

    override fun getValue(sharedPreferences: SharedPreferences): String {
        return sharedPreferences.getString(key(), defaultValue)!!
    }
}

sealed class StringSetPref(defaultValue: Set<String>, title: String, summary: String) : Pref<Set<String>, Set<String>>(defaultValue,
    title,
    summary) {
    override fun save(sharedPreferences: SharedPreferences, value: Set<String>) {
        sharedPreference.edit().putStringSet(key(), value).apply()
    }

    override fun getValue(sharedPreferences: SharedPreferences): Set<String> {
        return sharedPreferences.getStringSet(key(), defaultValue)!!
    }
}

/** A percentage, 0 to 100; picked from steps of [step] on the settings screen. */
sealed class IntSeekbarPref(defaultValue: Int, title: String, summary: String, val step: Int = 10) : Pref<Int, Int>(defaultValue, title, summary) {
    override fun save(sharedPreferences: SharedPreferences, value: Int) {
        sharedPreference.edit().putInt(key(), value).apply()
    }

    override fun getValue(sharedPreferences: SharedPreferences): Int {
        return sharedPreferences.getInt(key(), defaultValue)
    }
}

sealed class IntListPref(defaultValue: Int, title: String, summary: String,
                         val titlesResourceId: Int, val valuesResourceId: Int,
                         val format: NumberFormat = NumberFormat.TITLE) : Pref<Int, String>(defaultValue, title, summary) {
    override fun save(sharedPreferences: SharedPreferences, value: Int) {
        sharedPreference.edit().putString(key(), value.toString()).apply()
    }

    override fun toPrefValue(value: Int): String {
        return value.toString()
    }

    override fun fromPrefValue(prefValue: String): Int {
        return prefValue.toInt()
    }

    override fun getValue(sharedPreferences: SharedPreferences): Int {
        return sharedPreferences.getString(key(), defaultValue.toString())!!.toInt()
    }
}
