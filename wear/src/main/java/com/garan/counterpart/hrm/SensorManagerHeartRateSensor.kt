package com.garan.counterpart.hrm

import android.content.Context
import android.hardware.Sensor
import android.hardware.Sensor.REPORTING_MODE_CONTINUOUS
import android.hardware.Sensor.REPORTING_MODE_ON_CHANGE
import android.hardware.Sensor.TYPE_HEART_RATE
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.garan.counterpart.TAG

class SensorManagerHeartRateSensor(private val context: Context) : HeartRateSensor() {
    private val SAMPLING_PERIOD_US = 1_000_000
    private val MAX_REPORTING_LATENCY_US = 5_000_000
    private val MIN_TRANSMISSION_INTERVAL_NANO = 4_750_000_000

    private val sensorManager by lazy { context.getSystemService(LifecycleService.SENSOR_SERVICE) as SensorManager }
    private val sensor by lazy { sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE) }
    private var listener: SensorEventListener? = null

    private val handler = Handler(Looper.getMainLooper())

    private var lastTransmittedValueTimestamp = 0L


    /**
     * HR sensors should be onChange sensors. We only want to transmit the HR to the phone when it
     * changes, and we also want to set a reporting latency for the sensor which is > than the
     * sampling latency. e.g. 1 second sampling, 5 second reporting. This allows the app to sleep a
     * little, and get woken up for new readings, then transmit.
     *
     * However, the Samsung GW sensor reports at a fixed 1s interval and does not batch data. This
     * behaviour is more consistent with REPORTING_MODE_CONTINUOUS, but it still identifies as
     * REPORTING_MODE_ON_CHANGE.
     *
     * To meet the behaviour above, we need to handle REPORTING_MODE_ON_CHANGE and
     * REPORTING_MODE_CONTINUOUS differently in the [onSensorChanged] callback. So
     * [correctedReportingMode] allows us to correctly choose the appropriate behaviour on
     * different devices.
     */
    private val Sensor.correctedReportingMode: Int
        get() = if (this.vendor.contains("samsung", ignoreCase = true)
                    && this.type == TYPE_HEART_RATE) {
            Sensor.REPORTING_MODE_CONTINUOUS
        } else {
            this.reportingMode
        }

    override fun start(onSendBlock: (Int) -> Unit) {
        sendBlock = onSendBlock

        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (sensor.correctedReportingMode == REPORTING_MODE_ON_CHANGE) {
                    sendBlock.invoke(event.values.last().toInt())
                } else if (sensor.correctedReportingMode == REPORTING_MODE_CONTINUOUS && event.values.isNotEmpty()) {
                    if (event.timestamp - lastTransmittedValueTimestamp > MIN_TRANSMISSION_INTERVAL_NANO) {
                        sendBlock.invoke(event.values.last().toInt())
                        lastTransmittedValueTimestamp = event.timestamp
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Log.i(TAG, "Accuracy changed: $accuracy")
            }
        }

        listener?.let {
            Log.i(TAG, "Enabling HR sensor")
            sensorManager.registerListener(listener, sensor, SAMPLING_PERIOD_US, MAX_REPORTING_LATENCY_US)
        }
    }

    override fun stop() {
        listener?.let {
            sensorManager.unregisterListener(listener)
        }
        listener = null
    }
}