package com.johnw.reelfin.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer

private val Context.accountsStore: DataStore<Preferences> by preferencesDataStore(name = AccountStore.FILE)

/**
 * The profiles saved on this device (server, user, token) and which one was used last.
 * Lives in its own DataStore file, which the backup rules exclude so tokens never leave the device.
 */
class AccountStore(context: Context) {
    private val store = context.applicationContext.accountsStore
    private val listSerializer = ListSerializer(Account.serializer())

    val accounts: Flow<List<Account>> = store.data.map { decode(it[KEY_ACCOUNTS]) }
    val lastServerUrl: Flow<String?> = store.data.map { it[KEY_LAST_SERVER] }

    suspend fun snapshot(): Pair<List<Account>, String?> = store.data.first().let { decode(it[KEY_ACCOUNTS]) to it[KEY_ACTIVE] }

    /** Adds or replaces (same server + user) an account and makes it the active one. */
    suspend fun upsertAndActivate(account: Account) {
        store.edit { p ->
            val list = decode(p[KEY_ACCOUNTS]).filterNot { it.key == account.key } + account
            p[KEY_ACCOUNTS] = encode(list)
            p[KEY_ACTIVE] = account.key
            p[KEY_LAST_SERVER] = account.serverUrl
        }
    }

    suspend fun setActive(key: String, touched: Long = System.currentTimeMillis()) {
        store.edit { p ->
            p[KEY_ACTIVE] = key
            p[KEY_ACCOUNTS] = encode(decode(p[KEY_ACCOUNTS]).map { if (it.key == key) it.copy(lastUsed = touched) else it })
        }
    }

    suspend fun remove(key: String) {
        store.edit { p ->
            p[KEY_ACCOUNTS] = encode(decode(p[KEY_ACCOUNTS]).filterNot { it.key == key })
            if (p[KEY_ACTIVE] == key) p.remove(KEY_ACTIVE)
        }
    }

    suspend fun setLastServer(url: String) {
        store.edit { it[KEY_LAST_SERVER] = url }
    }

    private fun decode(text: String?): List<Account> =
        text?.let { runCatching { RowCodec.json.decodeFromString(listSerializer, it) }.getOrNull() } ?: emptyList()

    private fun encode(list: List<Account>): String = RowCodec.json.encodeToString(listSerializer, list)

    companion object {
        /** Must match the exclusions in app/src/main/res/xml/backup_rules.xml and data_extraction_rules.xml. */
        const val FILE = "reelfin_accounts"
        private val KEY_ACCOUNTS = stringPreferencesKey("accounts")
        private val KEY_ACTIVE = stringPreferencesKey("active")
        private val KEY_LAST_SERVER = stringPreferencesKey("last_server")
    }
}
