package com.garan.counterpart.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.garan.counterpart.HrServiceGrpcKt
import com.garan.counterpart.TAG
import com.garan.counterpart.hrMessage
import io.grpc.CallOptions
import io.grpc.ManagedChannel
import io.grpc.android.AndroidChannelBuilder
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class GrpcHrSenderClient(val context: Context, private val scope: CoroutineScope) :
    HrSenderClient() {
    private val endpoint = "grpc-hr-test-vwlfikbzcq-ew.a.run.app"
    private val port = 443
    private val networkConnectTimeoutMs = 15000L

    private val connectivityManager by lazy { context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }

    private var channel: ManagedChannel? = null
    private var hrServiceStub: HrServiceGrpcKt.HrServiceCoroutineStub? = null
    private var connectedNetwork: Network? = null
    private lateinit var networkCallback: ConnectivityManager.NetworkCallback

    override fun connect(): Boolean {
        try {
            runBlocking {
                connectedNetwork = getConnectedNetwork()
            }
        } catch (ioException: IOException) {
            return false
        }

        val options = CallOptions.DEFAULT
        channel = AndroidChannelBuilder
            .forAddress(endpoint, port)
            .useTransportSecurity()
            .executor(Dispatchers.IO.asExecutor())
            .build()

        channel?.let {
            hrServiceStub = HrServiceGrpcKt.HrServiceCoroutineStub(it, options)
        }
        channel?.enterIdle()
        return true
    }

    override fun disconnect() {
        connectivityManager.bindProcessToNetwork(null)
        connectivityManager.unregisterNetworkCallback(networkCallback)
        channel?.shutdown()
    }

    override fun sendValue(value: Int) {
        scope.launch(Dispatchers.IO) {
            hrServiceStub?.sendHr(hrMessage { hrValue = value })
        }
    }

    private suspend fun getConnectedNetwork() = suspendCoroutineWithTimeout(
        networkConnectTimeoutMs
    ) {
        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Will be called both for first acquiring a network and then also if the network
                // changes.
                if (connectivityManager.bindProcessToNetwork(network)) {
                    if (!it.isCompleted) {
                        it.resume(network)
                    }
                } else {
                    connectivityManager.bindProcessToNetwork(null)
                    it.resumeWithException(IOException("Failed to get network"))
                }
            }
        }
        val request: NetworkRequest = NetworkRequest.Builder().run {
            addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            build()
        }
        Log.i(TAG, "Requesting network")
        connectivityManager.requestNetwork(request, networkCallback)
    }

    private suspend inline fun <T> suspendCoroutineWithTimeout(
        timeout: Long,
        crossinline block: (CancellableContinuation<T>) -> Unit
    ): T? = withTimeout(timeout) {
        suspendCancellableCoroutine(block = block)
    }
}