package cz.audioreplay.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey val id: String,
    val name: String,
    val location: String,
    val publicStorage: Boolean,
    val createdMs: Long,
    val durationUs: Long,
    val sizeBytes: Long,
    val sampleRate: Int,
    val bitrate: Int,
)
@Dao interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY createdMs DESC") fun observe(): Flow<List<Recording>>
    @Query("SELECT * FROM recordings") suspend fun all(): List<Recording>
    @Query("SELECT * FROM recordings WHERE id = :id") suspend fun find(id: String): Recording?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(recording: Recording)
    @Update suspend fun update(recording: Recording)
    @Delete suspend fun delete(recording: Recording)
}
@Database(entities = [Recording::class], version = 1, exportSchema = false)
abstract class RecordingDatabase : RoomDatabase() { abstract fun recordings(): RecordingDao }
