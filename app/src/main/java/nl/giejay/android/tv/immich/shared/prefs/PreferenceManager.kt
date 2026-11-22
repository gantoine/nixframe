package nl.giejay.android.tv.immich.shared.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import nl.giejay.android.tv.immich.security.ApiKeyCipher
import okhttp3.HttpUrl
import kotlin.reflect.KClass

object PreferenceManager {
    lateinit var sharedPreference: SharedPreferences
    private lateinit var liveSharedPreferences: LiveSharedPreferences

    // stores the typed values, not the internal PrefTypes (String, Int)
    private val liveContext: MutableMap<String, Any?> = mutableMapOf()

    fun <T : Any> subclasses(clazz: KClass<T>): List<KClass<out T>> {
        return clazz.sealedSubclasses.flatMap { subclasses(it) + it }
    }

    fun init(context: Context) {
        sharedPreference = PreferenceManager.getDefaultSharedPreferences(context)
        liveSharedPreferences = LiveSharedPreferences(sharedPreference)
        migrateApiKeyIfNeeded()
        subclasses(Pref::class).filter { it.objectInstance != null }.forEach { pref ->
            val prefInstance = pref.objectInstance!! as Pref<Any, *>
            liveSharedPreferences.subscribeTyped(prefInstance) { typeValue ->
                liveContext[prefInstance.key()] = typeValue
            }
        }
    }

    // one-time, before the subscription loop below reads it: re-save a legacy-plaintext or
    // previously-unencryptable API key now that ApiKeyCipher can (re)try encrypting it
    private fun migrateApiKeyIfNeeded() {
        // Nothing to migrate to below API 23 (no AndroidKeyStore AES): legacy plaintext is read as-is.
        if (!ApiKeyCipher.isSupported) return
        val raw = sharedPreference.getString(API_KEY.key(), "") ?: return
        if (raw.isNotEmpty() && !ApiKeyCipher.isEncrypted(raw)) {
            API_KEY.save(sharedPreference, ApiKeyCipher.decryptOrAdoptLegacy(raw))
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: Pref<T, *>): T {
        return liveContext[key.key()] as T ?: key.getValue(sharedPreference)
    }

    val hostName: String
        get() = get(HOST_NAME).replace("\\s".toRegex(), "").removeSuffix("/")

    fun <T, PREFTYPE> save(key: Pref<T, PREFTYPE>, value: T) {
        liveContext[key.key()] = value
        key.save(sharedPreference, value)
    }

    fun <T> subscribe(key: Pref<T, *>, onChange: (T) -> Unit) {
        liveSharedPreferences.subscribeTyped(key, onChange)
    }

    fun subscribeMultiple(keys: List<Pref<*, *>>, onChange: (Map<String, Any?>) -> Unit) {
        liveSharedPreferences.subscribeMultiple(keys, onChange)
    }

    fun isLoggedId(): Boolean {
        return isValid(get(HOST_NAME), get(API_KEY))
    }

    // Minutes without sensor movement before the display is turned off. 0 = always on.
    fun wakeLockMinutes(): Int {
        return get(WAKE_LOCK_MINUTES)
    }

    fun isValid(hostName: String?, apiKey: String?): Boolean {
        return hostName?.isNotBlank() == true && apiKey?.isNotBlank() == true && HttpUrl.parse(hostName) != null // OkHttp 3.12 (KitKat) API
    }

    fun removeApiSettings() {
        save(HOST_NAME, "")
        save(API_KEY, "")
    }

    fun getString(key: String, default: String): String {
        return sharedPreference.getString(key, default)!!
    }
}
