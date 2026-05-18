package com.survey.totalstationbt.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.survey.totalstationbt.model.DataFormat

@Entity(
    tableName = "points",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["projectId"])]
)
data class PointEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val pointName: String,
    val easting: Double?,
    val northing: Double?,
    val elevation: Double?,
    val code: String = "",
    val horizontalAngle: Double?,
    val verticalAngle: Double?,
    val slopeDistance: Double?,
    val horizontalDistance: Double?,
    val verticalDistance: Double?,
    val rawData: String,
    val format: String, // Store enum as string
    val photoPath: String? = null,
    val note: String = "",         // 現場備忘錄
    val timestamp: Long = System.currentTimeMillis()
)
