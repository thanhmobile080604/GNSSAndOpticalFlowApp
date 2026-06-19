package com.example.gnssandopticalflowapp.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.gnssandopticalflowapp.model.RouteSessionSummary

@Entity(tableName = "route_sessions")
data class RouteSessionEntity(
    @PrimaryKey val id: String,
    val startedAtMs: Long,
    val durationMs: Long,
    val destinationName: String,
    val startLat: Double,
    val startLon: Double,
    val destLat: Double,
    val destLon: Double,
    val routePointsJson: String,
    val gnssSegmentsJson: String,
    val opticalSegmentsJson: String,
    val weakPointsJson: String,
    val strongPointsJson: String,
    val outagePointCount: Int,
    val gnssPointCount: Int
)

@Dao
interface RouteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertSession(session: RouteSessionEntity)

    @Query("SELECT * FROM route_sessions WHERE id = :id")
    fun getSession(id: String): RouteSessionEntity?

    @Query(
        """
        SELECT id AS id,
               startedAtMs AS startedAtMs,
               durationMs AS durationMs,
               destinationName AS destinationName,
               outagePointCount AS outagePointCount,
               gnssPointCount AS gnssPointCount
        FROM route_sessions
        ORDER BY startedAtMs DESC
        """
    )
    fun getSummaries(): List<RouteSessionSummary>

    @Query("DELETE FROM route_sessions WHERE id = :id")
    fun deleteSession(id: String)
}
