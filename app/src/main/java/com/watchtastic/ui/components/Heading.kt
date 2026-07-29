package com.watchtastic.ui.components

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The direction the wearer is facing, in degrees true, 0..360.
 *
 * Reads the fused rotation-vector sensor rather than the raw magnetometer: it folds in
 * the gyroscope, so the value doesn't swim the way a bare compass does when an arm
 * swings. Registration is tied to composition, so the sensor is released the moment the
 * screen using it goes away — this runs on a watch battery.
 */
@Composable
fun rememberHeadingDegrees(): State<Float> {
    val context = LocalContext.current
    val heading = remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)
                val degrees = Math.toDegrees(orientation[0].toDouble()).toFloat()
                heading.floatValue = (degrees + 360f) % 360f
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (sensor != null) {
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        }
        onDispose { manager?.unregisterListener(listener) }
    }

    return heading
}
