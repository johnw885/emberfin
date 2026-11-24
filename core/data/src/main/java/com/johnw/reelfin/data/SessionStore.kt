package com.johnw.reelfin.data

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/** The spike's single signed-in session (kept only to migrate it into [AccountStore]). */
data class StoredSession(val serverUrl: String, val userId: String, val accessToken: String)

/**
 * Holds the per-install device id. Also reads (once) the spike's single saved session so it can
 * be migrated into [AccountStore]; it's cleared after migration.
 *
 * Plain SharedPreferences in [FILE], which the backup rules exclude.
 */
class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Stable per install, regenerated after a reinstall (the server then sees a new device). */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit { putString(KEY_DEVICE_ID, it) }
        }

    /** The spike's session, if one is still waiting to be migrated. */
    val legacySession: StoredSession?
        get() {
            val server = prefs.getString(KEY_SERVER, null) ?: return null
            val user = prefs.getString(KEY_USER, null) ?: return null
            val token = prefs.getString(KEY_TOKEN, null) ?: return null
            return StoredSession(server, user, token)
        }

    fun clearLegacySession() = prefs.edit {
        remove(KEY_USER)
        remove(KEY_TOKEN)
        remove(KEY_SERVER)
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
