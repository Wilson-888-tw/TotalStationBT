package com.survey.totalstationbt.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CompassManager(context: Context) : SensorEventListener {

    data class State(
        val azimuth: Float = 0f,
        val accuracy: Int = SensorManager.SENSOR_STATUS_UNRELIABLE,
        val haOffset: Float = 0f,
        val haOffsetTimestamp: Long = 0L
    ) {
        val correctedAzimuth: Float get() = (azimuth + haOffset + 360f) % 360f
        val haOffsetAgeSeconds: Long get() =
            if (haOffsetTimestamp > 0L) (System.currentTimeMillis() - haOffsetTimestamp) / 1000L
            else -1L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    val isAvailable: Boolean get() = sensor != null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val rotMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)

    fun start() {
        sensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /**
     * Called after HA measurement is received.
     * stationAzimuthToRod: grid azimuth from total station to rod person (= HA if station is grid-oriented)
     */
    fun applyHaCalibration(stationAzimuthToRod: Float) {
        val rodFacingAzimuth = (stationAzimuthToRod + 180f) % 360f
        val offset = rodFacingAzimuth - _state.value.azimuth
        _state.value = _state.value.copy(
            haOffset = offset,
            haOffsetTimestamp = System.currentTimeMillis()
        )
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
        SensorManager.getOrientation(rotMatrix, orientationAngles)
        var az = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        if (az < 0f) az += 360f

        // Derive accuracy from heading accuracy value if available (more reliable than onAccuracyChanged on some devices)
        val derivedAccuracy = if (event.values.size > 4) {
            val headingAccRad = event.values[4]
            when {
                headingAccRad < 0.1f  -> SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                headingAccRad < 0.25f -> SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
                headingAccRad < 0.5f  -> SensorManager.SENSOR_STATUS_ACCURACY_LOW
                else                  -> SensorManager.SENSOR_STATUS_UNRELIABLE
            }
        } else {
            _state.value.accuracy
        }

        _state.value = _state.value.copy(azimuth = az, accuracy = derivedAccuracy)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        if (sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            _state.value = _state.value.copy(accuracy = accuracy)
        }
    }
}
