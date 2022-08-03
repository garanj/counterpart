package com.garan.counterpart.hrm

/**
 * Represents a heart rate sensor provider, to allow generalization across [SensorManager] and
 * [ExerciseClient].
 */
abstract class HeartRateSensor {
    var latestValue = 0
    var sendBlock: (Int) -> Unit = {}

    abstract fun start(onSendBlock: (Int) -> Unit)
    abstract fun stop()
}

