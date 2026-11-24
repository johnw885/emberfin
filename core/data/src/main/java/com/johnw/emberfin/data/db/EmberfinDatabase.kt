package com.johnw.emberfin.data.db

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
import com.johnw.emberfin.data.CachedRow
import com.johnw.emberfin.data.CachedScreen
import com.johnw.emberfin.data.RowCache
import com.johnw.emberfin.data.RowCodec
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
 * The audio/subtitle choice a profile last made in a series ("<profile key>/<series id>").
 * Stores what the tracks are (language, title, forced), not their indexes, which differ per episode.
 * A null column means "no preference" for that part.
 */
@Entity(tableName = "series_tracks")
data class SeriesTracksEntity(
    @PrimaryKey val key: String,
    val audioLanguage: String? = null,
    val audioTitle: String? = null,
    /** True once the user picked audio in this series (the language may still be unknown). */
    val audioSet: Boolean = false,
    /** null = no subtitle choice yet, true = subtitles off, false = [subtitleLanguage] etc. */
    val subtitleOff: Boolean? = null,
    val subtitleLanguage: String? = null,
    val subtitleForced: Boolean = false,
    val subtitleTitle: String? = null,
    val subtitleCodec: String? = null,
    val savedAt: Long = 0,
)

@Dao
interface SeriesTracksDao {
    @Query("SELECT * FROM series_tracks WHERE `key` = :key")
    suspend fun get(key: String): SeriesTracksEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SeriesTracksEntity)
}

/**
 * Emberfin's local database: version 1 held the row cache, version 2 (Session 5b) adds
 * per-series audio/subtitle choices. Version 1 had only the cache, so its upgrade can simply
 * rebuild; later versions need real migrations, since the track choices are the user's.
 */
@Database(entities = [ScreenCacheEntity::class, SeriesTracksEntity::class], version = 2, exportSchema = false)
abstract class EmberfinDatabase : RoomDatabase() {
    abstract fun screenCache(): ScreenCacheDao
    abstract fun seriesTracks(): SeriesTracksDao

    companion object {
        fun create(context: Context): EmberfinDatabase =
            Room.databaseBuilder(context.applicationContext, EmberfinDatabase::class.java, "emberfin.db")
                .fallbackToDestructiveMigrationFrom(dropAllTables = true, 1)
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
