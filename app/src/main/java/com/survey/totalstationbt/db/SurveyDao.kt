package com.survey.totalstationbt.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SurveyDao {

    // Projects
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProject(project: ProjectEntity): Long

    @Query("SELECT * FROM projects ORDER BY lastModified DESC")
    fun getAllProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun getProjectById(projectId: Long): ProjectEntity?

    @Delete
    suspend fun deleteProject(project: ProjectEntity): Int

    // Points
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoint(point: PointEntity): Long

    @Query("SELECT * FROM points WHERE projectId = :projectId ORDER BY timestamp ASC")
    fun getPointsForProject(projectId: Long): Flow<List<PointEntity>>

    @Query("SELECT * FROM points WHERE projectId = :projectId ORDER BY timestamp DESC LIMIT 1")
    fun getLastPointForProject(projectId: Long): Flow<PointEntity?>

    @Query("DELETE FROM points WHERE projectId = :projectId")
    suspend fun clearPointsForProject(projectId: Long): Int
    
    @Delete
    suspend fun deletePoint(point: PointEntity): Int

    @Update
    suspend fun updatePoint(point: PointEntity): Int

    @Query("UPDATE points SET note = :note WHERE id = :pointId")
    suspend fun updatePointNote(pointId: Long, note: String): Int

    @Query("UPDATE points SET code = :code WHERE id = :pointId")
    suspend fun updatePointCode(pointId: Long, code: String): Int
}
