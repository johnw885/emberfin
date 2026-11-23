package com.johnw.reelfin.data

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/** A signed-in account on one server. */
data class StoredSession(val serverUrl: String, val userId: String, val accessToken: String)

/**
 * Keeps the per-install device id and the signed-in session.
 *
 * Spike storage: plain SharedPreferences in [FILE], which the manifest's backup rules exclude
 * so tokens never leave the device. A later session moves this to DataStore with multi-account support.
 */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Stable per install, regenerated after a reinstall (the server then sees a new device). */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }

    var lastServerUrl: String?
        get() = prefs.getString(KEY_SERVER, null)
        set(value) = prefs.edit { putString(KEY_SERVER, value) }

    var session: StoredSession?
        get() {
            val server = prefs.getString(KEY_SERVER, null) ?: return null
            val user = prefs.getString(KEY_USER, null) ?: return null
            val token = prefs.getString(KEY_TOKEN, null) ?: return null
            return StoredSession(server, user, token)
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(KEY_USER)
                remove(KEY_TOKEN)
            } else {
                putString(KEY_SERVER, value.serverUrl)
                putString(KEY_USER, value.userId)
                putString(KEY_TOKEN, value.accessToken)
            }
        }

    companion object {
        /** Must match the exclusions in app/src/main/res/xml/backup_rules.xml and data_extraction_rules.xml. */
        const val FILE = "reelfin_auth"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SERVER = "server_url"
        private const val KEY_USER = "user_id"
        private const val KEY_TOKEN = "access_token"
    }
}
