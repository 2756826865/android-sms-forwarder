package org.fossify.messages.interfaces

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import org.fossify.messages.models.RecoveryRecordEntity

@Dao
interface RecoveryRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RecoveryRecordEntity): Long

    @Query("SELECT * FROM recovery_records ORDER BY scan_time DESC LIMIT :limit")
    suspend fun queryLatest(limit: Int = 50): List<RecoveryRecordEntity>

    @Query("SELECT COUNT(*) FROM recovery_records WHERE scan_time >= :startOfDay")
    suspend fun getCountSince(startOfDay: Long): Int

    @Query("DELETE FROM recovery_records WHERE created_at < :cutoff")
    suspend fun deleteExpired(cutoff: Long)
}
