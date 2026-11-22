package com.ukpro.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ settings (DataStore)

enum class OutputMode { BOTH, PC_ONLY, PHONE_ONLY }
enum class ConnectionMode { AUTO, WIFI, BLUETOOTH }

data class AppSettings(
    val androidToWindows: Boolean = true,
    val clipboardSync: Boolean = true,
    val clipboardImages: Boolean = true,
    val clipboardFiles: Boolean = true,
    val paused: Boolean = false,
    val historyEnabled: Boolean = true,
    val historyMaxItems: Int = 200,
    val keyboardHeight: Float = 1.0f,     // 0.5 … 2.0 (key height scale)
    val keyboardOffsetDp: Int = 0,        // lift above the bottom edge, 0 … 480 dp
    val keyboardWidth: Float = 1.0f,      // 0.5 … 1.0 of the screen width
    val keyboardAlign: String = "center", // left | center | right (one-handed use)
    val darkTheme: Boolean = true,
    val keySound: Boolean = false,
    val vibration: Boolean = true,
    /** Current keyboard language id (see keyboard.Languages) and the languages the 🌐 key cycles through. */
    val language: String = "en",
    val languages: Set<String> = setOf("en", "bn"),
    val outputMode: OutputMode = OutputMode.BOTH,
    val connectionMode: ConnectionMode = ConnectionMode.AUTO,
    val activeHubId: String? = null,
    val showNavPanel: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Settings with a synchronous snapshot (the IME reads it on every key) backed by DataStore. */
class SettingsRepo(private val context: Context, private val scope: CoroutineScope) {
    private object K {
        val a2w = booleanPreferencesKey("a2w"); val clip = booleanPreferencesKey("clip")
        val clipImg = booleanPreferencesKey("clip_img"); val clipFiles = booleanPreferencesKey("clip_files")
        val paused = booleanPreferencesKey("paused"); val history = booleanPreferencesKey("history")
        val historyMax = intPreferencesKey("history_max"); val height = floatPreferencesKey("kb_height")
        val dark = booleanPreferencesKey("dark"); val sound = booleanPreferencesKey("sound")
        val vib = booleanPreferencesKey("vibration"); val lang = stringPreferencesKey("language")
        val langs = stringSetPreferencesKey("languages")
        val output = stringPreferencesKey("output"); val conn = stringPreferencesKey("conn")
        val hub = stringPreferencesKey("active_hub"); val nav = booleanPreferencesKey("nav_panel")
        val offset = intPreferencesKey("kb_offset"); val width = floatPreferencesKey("kb_width"); val align = stringPreferencesKey("kb_align")
    }

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state
    val current: AppSettings get() = _state.value

    init {
        scope.launch {
            context.dataStore.data.collect { p ->
                _state.value = AppSettings(
                    androidToWindows = p[K.a2w] ?: true, clipboardSync = p[K.clip] ?: true,
                    clipboardImages = p[K.clipImg] ?: true, clipboardFiles = p[K.clipFiles] ?: true,
                    paused = p[K.paused] ?: false, historyEnabled = p[K.history] ?: true,
                    historyMaxItems = p[K.historyMax] ?: 200, keyboardHeight = p[K.height] ?: 1.0f,
                    darkTheme = p[K.dark] ?: true, keySound = p[K.sound] ?: false, vibration = p[K.vib] ?: true,
                    language = p[K.lang] ?: "en", languages = p[K.langs] ?: setOf("en", "bn"),
                    outputMode = p[K.output]?.let { runCatching { OutputMode.valueOf(it) }.getOrNull() } ?: OutputMode.BOTH,
                    connectionMode = p[K.conn]?.let { runCatching { ConnectionMode.valueOf(it) }.getOrNull() } ?: ConnectionMode.AUTO,
                    activeHubId = p[K.hub], showNavPanel = p[K.nav] ?: false,
                    keyboardOffsetDp = p[K.offset] ?: 0, keyboardWidth = p[K.width] ?: 1.0f, keyboardAlign = p[K.align] ?: "center",
                )
            }
        }
    }

    fun update(change: (AppSettings) -> AppSettings) {
        val n = change(_state.value)
        _state.value = n // immediate for the IME; persisted below
        scope.launch {
            context.dataStore.edit { p ->
                p[K.a2w] = n.androidToWindows; p[K.clip] = n.clipboardSync; p[K.clipImg] = n.clipboardImages
                p[K.clipFiles] = n.clipboardFiles; p[K.paused] = n.paused; p[K.history] = n.historyEnabled
                p[K.historyMax] = n.historyMaxItems; p[K.height] = n.keyboardHeight; p[K.dark] = n.darkTheme
                p[K.sound] = n.keySound; p[K.vib] = n.vibration; p[K.lang] = n.language; p[K.langs] = n.languages
                p[K.offset] = n.keyboardOffsetDp; p[K.width] = n.keyboardWidth; p[K.align] = n.keyboardAlign
                p[K.output] = n.outputMode.name; p[K.conn] = n.connectionMode.name; p[K.nav] = n.showNavPanel
                if (n.activeHubId != null) p[K.hub] = n.activeHubId else p.remove(K.hub)
            }
        }
    }
}

// ------------------------------------------------------------------ Room

@Entity(tableName = "clip_history")
data class ClipEntity(
    @PrimaryKey val clipId: String,
    val kind: String,           // text | image | files
    val preview: String,
    val text: String?,          // full text for kind=text
    val uris: String?,          // newline-separated content URIs / file paths for image/files
    val origin: String,
    val originName: String,
    val hash: String,
    val ts: Long,
    val pinned: Boolean = false,
)

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val transferId: String,
    val name: String,
    val size: Long,
    val incoming: Boolean,
    val state: String,
    val location: String?,
    val peerName: String,
    val ts: Long,
)

@Dao
interface ClipDao {
    @Query("SELECT * FROM clip_history ORDER BY pinned DESC, ts DESC")
    fun all(): Flow<List<ClipEntity>>

    @Query("SELECT * FROM clip_history WHERE clipId = :id")
    suspend fun get(id: String): ClipEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: ClipEntity)

    @Query("DELETE FROM clip_history WHERE hash = :hash AND pinned = 0 AND clipId != :keep")
    suspend fun dedupe(hash: String, keep: String)

    @Query("UPDATE clip_history SET pinned = :pinned WHERE clipId = :id")
    suspend fun pin(id: String, pinned: Boolean)

    @Query("DELETE FROM clip_history WHERE clipId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM clip_history WHERE pinned = 0")
    suspend fun clearUnpinned()

    @Query("DELETE FROM clip_history WHERE pinned = 0 AND clipId NOT IN (SELECT clipId FROM clip_history ORDER BY ts DESC LIMIT :keep)")
    suspend fun prune(keep: Int)
}

@Dao
interface TransferDao {
    @Query("SELECT * FROM transfers ORDER BY ts DESC LIMIT 500")
    fun all(): Flow<List<TransferEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(e: TransferEntity)

    @Query("DELETE FROM transfers")
    suspend fun clear()
}

@Database(entities = [ClipEntity::class, TransferEntity::class], version = 1, exportSchema = true)
abstract class UkDatabase : RoomDatabase() {
    abstract fun clips(): ClipDao
    abstract fun transfers(): TransferDao

    companion object {
        fun create(context: Context): UkDatabase =
            Room.databaseBuilder(context, UkDatabase::class.java, "ukpro.db").fallbackToDestructiveMigration().build()
    }
}
