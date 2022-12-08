package com.garan.counterpart.network

abstract class HrSenderClient {
    abstract fun connect(): Boolean

    abstract fun disconnect()

    abstract fun sendValue(value: Int)
}