package com.survey.totalstationbt.db

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val date: Long = System.currentTimeMillis(),
    val description: String = "",
    val lastModified: Long = System.currentTimeMillis(),
    
    // CAD (DXF) Info
    val dxfPath: String? = null,
    val dxfDx: Double = 0.0,
    val dxfDy: Double = 0.0,
    val dxfScale: Double = 1.0,
    val dxfRotation: Double = 0.0
)
