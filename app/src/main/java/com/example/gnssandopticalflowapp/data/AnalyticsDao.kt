package com.example.gnssandopticalflowapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.example.gnssandopticalflowapp.model.AnalyticsSessionSummary

@Entity(tableName = "analytics_sessions")
data class AnalyticsSessionEntity(
    @PrimaryKey val id: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val durationMs: Long,
    val kltSensitivity: Int,
    val farnebackSensitivity: Int,
    val movingMode: Boolean
)

@Entity(
    tableName = "analytics_samples",
    foreignKeys = [
        ForeignKey(
            entity = AnalyticsSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class AnalyticsSampleEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0L,
    val sessionId: String,
    val orderIndex: Int,
    val elapsedMs: Long,
    val frameIndex: Long,
    val kltFps: Double,
    val farnebackFps: Double,
    val kltProcessMs: Double,
    val farnebackProcessMs: Double,
    val kltFeatureCount: Int,
    val farnebackSampleCount: Int,
    val kltActiveVectorCount: Int,
    val farnebackActiveVectorCount: Int,
    val kltAvgDx: Double,
    val kltAvgDy: Double,
    val farnebackAvgDx: Double,
    val farnebackAvgDy: Double,
    val kltAvgMagnitude: Double,
    val farnebackAvgMagnitude: Double,
    val kltConfidence: Double,
    val farnebackConfidence: Double,
    val kltThreshold: Double,
    val farnebackThreshold: Double
)

@Dao
interface AnalyticsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSession(session: AnalyticsSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSamples(samples: List<AnalyticsSampleEntity>)

    @Query("DELETE FROM analytics_samples WHERE sessionId = :id")
    fun deleteSamples(id: String)

    @Transaction
    fun saveSession(session: AnalyticsSessionEntity, samples: List<AnalyticsSampleEntity>) {
        insertSession(session)
        deleteSamples(session.id)
        insertSamples(samples)
    }

    @Query("SELECT * FROM analytics_sessions WHERE id = :id")
    fun getSession(id: String): AnalyticsSessionEntity?

    @Query("SELECT * FROM analytics_samples WHERE sessionId = :id ORDER BY orderIndex ASC")
    fun getSamples(id: String): List<AnalyticsSampleEntity>

    @Query(
        """
        SELECT s.id AS id,
               s.startedAtMs AS startedAtMs,
               s.durationMs AS durationMs,
               COUNT(m.rowId) AS sampleCount,
               IFNULL(AVG(m.kltFps), 0.0) AS avgKltFps,
               IFNULL(AVG(m.farnebackFps), 0.0) AS avgFarnebackFps,
               IFNULL(AVG(m.kltConfidence), 0.0) AS avgKltConfidence,
               IFNULL(AVG(m.farnebackConfidence), 0.0) AS avgFarnebackConfidence
        FROM analytics_sessions s
        LEFT JOIN analytics_samples m ON m.sessionId = s.id
        GROUP BY s.id
        ORDER BY s.startedAtMs DESC
        """
    )
    fun getSummaries(): List<AnalyticsSessionSummary>

    @Query("DELETE FROM analytics_sessions WHERE id = :id")
    fun deleteSession(id: String)
}
