package com.johnw.reelfin.data.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import com.johnw.reelfin.data.CachedRow
import com.johnw.reelfin.data.CachedScreen
import com.johnw.reelfin.data.RowCache
import com.johnw.reelfin.data.RowCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemDto

/** One browse screen's last-seen content for one profile ("home/<server>/<user>"), as JSON. */
@Entity(tableName = "screen_cache")
data class ScreenCacheEntity(
    @PrimaryKey val key: String,
    val json: String,
    val savedAt: Long,
)

@Dao
interface ScreenCacheDao {
    @Query("SELECT * FROM screen_cache WHERE `key` = :key")
    suspend fun get(key: String): ScreenCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: ScreenCacheEntity)

    @Query("DELETE FROM screen_cache WHERE `key` LIKE :prefix || '%'")
    suspend fun deletePrefix(prefix: String)
}

/**
 * Reelfin's local database. Version 1 holds the row cache; Session 5b adds per-series
 * audio/subtitle choices. It's a cache, so a schema change may simply rebuild it.
 */
@Database(entities = [ScreenCacheEntity::class], version = 1, exportSchema = false)
abstract class ReelfinDatabase : RoomDatabase() {
    abstract fun screenCache(): ScreenCacheDao

    companion object {
        fun create(context: Context): ReelfinDatabase =
            Room.databaseBuilder(context.applicationContext, ReelfinDatabase::class.java, "reelfin.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}

/** [RowCache] on Room: Home is drawn from here first on a warm start, then refreshed. */
class RoomRowCache(private val dao: ScreenCacheDao) : RowCache {
    override suspend fun load(key: String): CachedScreen? = withContext(Dispatchers.IO) {
        dao.get(key)?.let { runCatching { RowCodec.decodeScreen(it.json) }.getOrNull() }
    }

    override suspend fun save(key: String, billboard: BaseItemDto?, rows: List<CachedRow>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.put(ScreenCacheEntity(key, RowCodec.encodeScreen(billboard, rows, now), now))
    }

    override suspend fun clear(prefix: String) = withContext(Dispatchers.IO) { dao.deletePrefix(prefix) }
}
