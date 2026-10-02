package dev.motonav.simulator.transport

import dev.motonav.simulator.protocol.DisplayFrame

data class DisplayDevice(val address: String, val name: String)

sealed interface TransportState {
    data object Idle : TransportState
    data object Scanning : TransportState
    data class Connecting(val device: DisplayDevice) : TransportState
    data class Validating(val device: DisplayDevice) : TransportState
    data class Connected(val device: DisplayDevice, val mtu: Int) : TransportState
    data class Reconnecting(
        val device: DisplayDevice,
        val delaySeconds: Int,
        val reason: String,
    ) : TransportState
    data object PermissionRequired : TransportState
    data object BluetoothDisabled : TransportState
    data class Error(val message: String) : TransportState
}

interface DisplayTransport {
    interface Listener {
        fun onStateChanged(state: TransportState)
        fun onScanResults(devices: List<DisplayDevice>)
        fun onFrameSent(sequence: Int)
    }

    fun setListener(listener: Listener?)
    fun startScan()
    fun stopScan()
    fun connect(device: DisplayDevice)
    fun disconnect()
    fun submit(frame: DisplayFrame)
    fun close()
}
