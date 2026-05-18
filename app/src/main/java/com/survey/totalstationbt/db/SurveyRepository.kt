package com.survey.totalstationbt.db

import kotlinx.coroutines.flow.Flow

class SurveyRepository(private val surveyDao: SurveyDao) {

    // Projects
    val allProjects: Flow<List<ProjectEntity>> = surveyDao.getAllProjects()

    suspend fun insertProject(project: ProjectEntity): Long {
        return surveyDao.insertProject(project)
    }

    suspend fun getProjectById(projectId: Long): ProjectEntity? {
        return surveyDao.getProjectById(projectId)
    }

    suspend fun deleteProject(project: ProjectEntity) {
        surveyDao.deleteProject(project)
    }

    // Points
    fun getPointsForProject(projectId: Long): Flow<List<PointEntity>> {
        return surveyDao.getPointsForProject(projectId)
    }

    fun getLastPointForProject(projectId: Long): Flow<PointEntity?> {
        return surveyDao.getLastPointForProject(projectId)
    }

    suspend fun insertPoint(point: PointEntity): Long {
        return surveyDao.insertPoint(point)
    }

    suspend fun clearPointsForProject(projectId: Long) {
        surveyDao.clearPointsForProject(projectId)
    }
    
    suspend fun deletePoint(point: PointEntity) {
        surveyDao.deletePoint(point)
    }

    suspend fun updatePoint(point: PointEntity) {
        surveyDao.updatePoint(point)
    }

    suspend fun updatePointNote(id: Long, note: String) {
        surveyDao.updatePointNote(id, note)
    }
}
