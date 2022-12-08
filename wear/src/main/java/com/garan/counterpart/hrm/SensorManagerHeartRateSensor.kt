package com.garan.counterpart.hrm

import android.content.Context
import android.hardware.Sensor
import android.hardware.Sensor.TYPE_HEART_RATE
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import com.garan.counterpart.TAG

class SensorManagerHeartRateSensor(private val context: Context) : HeartRateSensor() {
    private val SAMPLING_PERIOD_US = 1_000_000
    private val MAX_REPORTING_LATENCY_US = 5_000_000
    private val MIN_TRANSMISSION_INTERVAL_NANO = 4_750_000_000
    private val DEBOUNCE_INTERVAL_NANO = 1_000_000_000

    private val sensorManager by lazy { context.getSystemService(LifecycleService.SENSOR_SERVICE) as SensorManager }
    private val sensor by lazy { sensorManager.getDefaultSensor(TYPE_HEART_RATE) }
    private var listener: SensorEventListener? = null

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
     * It also reports a correctedFifoReservedEventCount of 600, which suggests it can batch data,
     * but it does not batch.
     *
     * To meet the behaviour above, we need to handle batched and non-batched data differently in
     * [onSensorChanged] callback. So [correctedFifoReservedEventCount] allows us to correctly choose the
     * appropriate behaviour on different devices.
     */
    private val Sensor.correctedFifoReservedEventCount: Int
        get() = if (this.vendor.contains("samsung", ignoreCase = true)
            && this.type == TYPE_HEART_RATE
        ) {
            0
        } else {
            this.fifoMaxEventCount
        }

    private fun maybeSendValue(value: Int, timestamp: Long) {
        if (sensor.correctedFifoReservedEventCount > 0) {
            // Batching supported by sensor, so we should be receiving data at a max of
            // every 5 seconds, but sometimes sooner. If the reading is really soon for some reason
            // filter this out.
            if (timestamp - lastTransmittedValueTimestamp > DEBOUNCE_INTERVAL_NANO) {
                sendBlock.invoke(value)
                Log.i(TAG, "Time: ${System.currentTimeMillis()} value: $value")
                lastTransmittedValueTimestamp = timestamp
            }
        } else if (sensor.correctedFifoReservedEventCount == 0) {
            // Batching not supported by sensor
            if (timestamp - lastTransmittedValueTimestamp > MIN_TRANSMISSION_INTERVAL_NANO) {
                sendBlock.invoke(value)
                lastTransmittedValueTimestamp = timestamp
            }
        }
    }

    override fun start(onSendBlock: (Int) -> Unit) {
        sendBlock = onSendBlock

        listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                event.accuracy
                if (event.values.isNotEmpty()) {
                    maybeSendValue(event.values.last().toInt(), event.timestamp)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                Log.i(TAG, "Accuracy changed: $accuracy")
            }
        }

        listener?.let {
            Log.i(TAG, "Enabling HR sensor")
            sensorManager.registerListener(
                listener,
                sensor,
                SAMPLING_PERIOD_US,
                MAX_REPORTING_LATENCY_US
            )
        }
    }

    override fun stop() {
        listener?.let {
            sensorManager.unregisterListener(listener)
        }
        listener = null
    }
}