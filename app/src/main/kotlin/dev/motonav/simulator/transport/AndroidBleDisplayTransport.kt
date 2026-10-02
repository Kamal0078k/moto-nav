package dev.motonav.simulator.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import dev.motonav.simulator.calls.CallAlert
import dev.motonav.simulator.calls.CallPhase
import dev.motonav.simulator.protocol.DisplayCall
import dev.motonav.simulator.protocol.DisplayClock
import dev.motonav.simulator.navigation.NavigationState
import dev.motonav.simulator.protocol.DisplayFrame
import dev.motonav.simulator.protocol.FrameCodec
import java.util.ArrayDeque
import java.util.UUID

@SuppressLint("MissingPermission")
class AndroidBleDisplayTransport(context: Context) : DisplayTransport {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7d2ea28a-f7bd-485a-bd9d-92ad6ecfe93e")
        val FRAME_UUID: UUID = UUID.fromString("7d2ea28b-f7bd-485a-bd9d-92ad6ecfe93e")
        val CAPABILITY_UUID: UUID = UUID.fromString("7d2ea28c-f7bd-485a-bd9d-92ad6ecfe93e")
        private const val DESIRED_MTU = 185
        private const val SCAN_TIMEOUT_MILLIS = 12_000L
        private const val CONNECTION_TIMEOUT_MILLIS = 12_000L
        private const val WRITE_TIMEOUT_MILLIS = 4_000L
        private const val LOG_TAG = "MotoNavBle"
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter
    private var listener: DisplayTransport.Listener? = null
    private val devices = linkedMapOf<String, DisplayDevice>()
    private var scanning = false
    private var selectedDevice: DisplayDevice? = null
    private var gatt: BluetoothGatt? = null
    private var frameCharacteristic: BluetoothGattCharacteristic? = null
    private var capabilities: DisplayCapabilities? = null
    private var negotiatedMtu = 23
    private var ready = false
    private var userDisconnected = false
    private var reconnectAttempt = 0
    private var sessionGeneration = 0
    private var writeToken = 0
    private val fragments = ArrayDeque<ByteArray>()
    private var frameInFlight: DisplayFrame? = null
    private var latestFrame: DisplayFrame? = null
    private var nextSequence = 0
    private var forceNextFrame = false
    private val cadence = FrameCadence()
    private var closed = false

    private val heartbeat = object : Runnable {
        override fun run() {
            if (closed) return
            pumpLatestFrame()
            handler.postDelayed(this, 100)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = handleScanResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::handleScanResult)
        override fun onScanFailed(errorCode: Int) {
            handler.post {
                scanning = false
                publish(TransportState.Error("BLE scan failed ($errorCode). Tap Scan to retry."))
            }
        }
    }

    private val adapterStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_OFF -> {
                    stopScan()
                    clearConnection()
                    publish(TransportState.BluetoothDisabled)
                }
                BluetoothAdapter.STATE_ON -> if (!userDisconnected && selectedDevice != null) scheduleReconnect(immediate = true)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(callbackGatt: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (gatt !== callbackGatt) {
                    callbackGatt.close()
                    return@post
                }
                Log.d(LOG_TAG, "Connection state changed: status=$status state=$newState")
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            failAndReconnect("Connection failed (GATT $status)")
                            return@post
                        }
                        val device = selectedDevice ?: return@post
                        publish(TransportState.Validating(device))
                        if (!callbackGatt.discoverServices()) failAndReconnect("Service discovery could not start")
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        clearConnection(callbackGatt)
                        if (!userDisconnected && selectedDevice != null) {
                            val reason = if (status == BluetoothGatt.GATT_SUCCESS) {
                                "Display disconnected"
                            } else {
                                "Bluetooth link lost (GATT $status)"
                            }
                            scheduleReconnect(reason = reason)
                        } else {
                            publish(TransportState.Idle)
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(callbackGatt: BluetoothGatt, status: Int) {
            handler.post {
                if (gatt !== callbackGatt) return@post
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failAndReconnect("Service discovery failed (GATT $status)")
                    return@post
                }
                if (!selectCharacteristics(callbackGatt)) {
                    failPermanently(
                        "Display firmware is incompatible. Upload the current map sketch, then scan again.",
                    )
                    return@post
                }
                negotiatedMtu = 23
                if (!callbackGatt.requestMtu(DESIRED_MTU)) readCapabilities(callbackGatt)
            }
        }

        override fun onMtuChanged(callbackGatt: BluetoothGatt, mtu: Int, status: Int) {
            handler.post {
                if (gatt !== callbackGatt) return@post
                negotiatedMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu.coerceAtLeast(23) else 23
                readCapabilities(callbackGatt)
            }
        }

        @Deprecated("Used on Android 12 and below")
        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < 33) handleCapabilityRead(callbackGatt, characteristic, characteristic.value ?: byteArrayOf(), status)
        }

        override fun onCharacteristicRead(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = handleCapabilityRead(callbackGatt, characteristic, value, status)

        override fun onCharacteristicWrite(
            callbackGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            handler.post {
                if (gatt !== callbackGatt || characteristic.uuid != FRAME_UUID || frameInFlight == null) return@post
                writeToken++
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    failAndReconnect("Frame write failed ($status)")
                    return@post
                }
                if (fragments.isNotEmpty()) fragments.removeFirst()
                if (fragments.isEmpty()) {
                    val sent = requireNotNull(frameInFlight)
                    cadence.markSent(sent, SystemClock.elapsedRealtime())
                    frameInFlight = null
                    listener?.onFrameSent(sent.sequence)
                    pumpLatestFrame()
                } else {
                    writeNextFragment()
                }
            }
        }
    }

    init {
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) appContext.registerReceiver(adapterStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else appContext.registerReceiver(adapterStateReceiver, filter)
        handler.post(heartbeat)
    }

    override fun setListener(listener: DisplayTransport.Listener?) {
        this.listener = listener
        // A newly attached screen learns the live link state instead of assuming "disconnected".
        listener?.onStateChanged(lastState)
    }

    /** Detaches [listener] only if it is still the current one, so hand-offs cannot clobber each other. */
    fun removeListener(listener: DisplayTransport.Listener) {
        if (this.listener === listener) this.listener = null
    }

    override fun startScan() {
        if (closed) return
        if (!hasRuntimePermissions()) {
            publish(TransportState.PermissionRequired)
            return
        }
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null) {
            publish(TransportState.Error("This phone has no Bluetooth adapter"))
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            publish(TransportState.BluetoothDisabled)
            return
        }
        stopScan()
        devices.clear()
        listener?.onScanResults(emptyList())
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            bluetoothAdapter.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCallback)
                ?: error("BLE scanner is unavailable")
            scanning = true
            publish(TransportState.Scanning)
            handler.postDelayed({
                if (scanning) {
                    stopScan()
                    if (devices.isEmpty()) publish(TransportState.Error("MotoNav Display was not found. Check ESP32 advertising and retry."))
                }
            }, SCAN_TIMEOUT_MILLIS)
        } catch (_: SecurityException) {
            publish(TransportState.PermissionRequired)
        } catch (error: Exception) {
            publish(TransportState.Error(error.message ?: "BLE scan could not start"))
        }
    }

    override fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (_: SecurityException) { Unit }
    }

    override fun connect(device: DisplayDevice) {
        stopScan()
        if (!hasRuntimePermissions()) {
            publish(TransportState.PermissionRequired)
            return
        }
        if (adapter?.isEnabled != true) {
            publish(TransportState.BluetoothDisabled)
            return
        }
        userDisconnected = false
        selectedDevice = device
        reconnectAttempt = 0
        handler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        connectSelected()
    }

    /** Keeps a live or in-progress link to [device] instead of tearing it down to reconnect. */
    fun ensureConnected(device: DisplayDevice) {
        val linked = lastState.let {
            it is TransportState.Connected || it is TransportState.Connecting ||
                it is TransportState.Validating || it is TransportState.Reconnecting
        }
        if (linked && selectedDevice?.address == device.address) return
        connect(device)
    }

    override fun disconnect() {
        userDisconnected = true
        stopScan()
        selectedDevice = null
        handler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        clearConnection()
        publish(TransportState.Idle)
    }

    override fun submit(frame: DisplayFrame) {
        if (frame.state.isLiveLocationState() && capabilities?.supportsLiveLocationStates == false) {
            latestFrame = null
            publish(TransportState.Error("Incompatible display: upload the Plan 8 firmware for live GPS states"))
            return
        }
        latestFrame = frame.copy(sequence = 0)
        pumpLatestFrame()
    }

    override fun close() {
        if (closed) return
        closed = true
        disconnect()
        handler.removeCallbacksAndMessages(null)
        runCatching { appContext.unregisterReceiver(adapterStateReceiver) }
        listener = null
    }

    fun isBluetoothEnabled(): Boolean = try { adapter?.isEnabled == true } catch (_: SecurityException) { false }

    private fun hasRuntimePermissions(): Boolean {
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return permissions.all { appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun handleScanResult(result: ScanResult) {
        handler.post {
            if (!scanning) return@post
            val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: "MotoNav Display"
            val device = DisplayDevice(result.device.address, name)
            devices[device.address] = device
            listener?.onScanResults(devices.values.sortedBy { it.name })
        }
    }

    private fun connectSelected() {
        val selected = selectedDevice ?: return
        val bluetoothAdapter = adapter
        if (bluetoothAdapter?.isEnabled != true) {
            publish(TransportState.BluetoothDisabled)
            return
        }
        clearConnection()
        publish(TransportState.Connecting(selected))
        try {
            val remote = bluetoothAdapter.getRemoteDevice(selected.address)
            gatt = remote.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt == null) failAndReconnect("Connection could not start")
            else armConnectionTimeout()
        } catch (_: SecurityException) {
            publish(TransportState.PermissionRequired)
        } catch (error: Exception) {
            failAndReconnect(error.message ?: "Connection could not start")
        }
    }

    private fun selectCharacteristics(callbackGatt: BluetoothGatt): Boolean {
        val service: BluetoothGattService = callbackGatt.getService(SERVICE_UUID) ?: return false
        val frame = service.getCharacteristic(FRAME_UUID) ?: return false
        val capability = service.getCharacteristic(CAPABILITY_UUID) ?: return false
        if (frame.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0) return false
        if (capability.properties and BluetoothGattCharacteristic.PROPERTY_READ == 0) return false
        frameCharacteristic = frame
        return true
    }

    private fun readCapabilities(callbackGatt: BluetoothGatt) {
        val characteristic = callbackGatt.getService(SERVICE_UUID)?.getCharacteristic(CAPABILITY_UUID)
        if (characteristic == null || !callbackGatt.readCharacteristic(characteristic)) {
            failAndReconnect("Display capabilities could not be read")
        }
    }

    private fun handleCapabilityRead(
        callbackGatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
        status: Int,
    ) {
        handler.post {
            if (gatt !== callbackGatt || characteristic.uuid != CAPABILITY_UUID) return@post
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failAndReconnect("Display capability read failed ($status)")
                return@post
            }
            try {
                capabilities = DisplayCapabilities.decode(value).also { it.requireCompatible() }
            } catch (error: IllegalArgumentException) {
                val detail = error.message ?: "Display capabilities are incompatible"
                failPermanently("$detail. Upload the current map sketch, then scan again.")
                return@post
            }
            ready = true
            reconnectAttempt = 0
            cadence.reset()
            forceNextFrame = true
            val device = selectedDevice ?: return@post
            publish(TransportState.Connected(device, negotiatedMtu))
            pumpLatestFrame()
        }
    }

    private fun pumpLatestFrame() {
        if (!ready || frameInFlight != null) return
        val latest = latestFrame?.let(::adaptToDisplay)?.let(::withCallAlert)?.let(::withClock) ?: return
        if (latest.state.isLiveLocationState() && capabilities?.supportsLiveLocationStates != true) {
            publish(TransportState.Error("Incompatible display: upload the Plan 8 firmware for live GPS states"))
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (!cadence.shouldSend(latest, now, forceNextFrame)) return
        forceNextFrame = false
        val sequence = nextSequence
        nextSequence = (nextSequence + 1) and 0xffff
        val sequenced = latest.copy(sequence = sequence)
        val encoded = try { FrameCodec.encode(sequenced) } catch (error: IllegalArgumentException) {
            publish(TransportState.Error(error.message ?: "Frame could not be encoded"))
            return
        }
        val maximum = capabilities?.maximumFrameBytes ?: FrameCodec.MAX_FRAME_BYTES
        if (encoded.size > maximum) {
            publish(TransportState.Error("Frame has ${encoded.size} bytes; display accepts $maximum"))
            return
        }
        fragments.clear()
        val maximumGattWrite = minOf(
            (negotiatedMtu - 3).coerceAtLeast(20),
            FrameCodec.MAX_FRAME_BYTES,
        )
        fragments.addAll(FrameCodec.fragment(encoded, sequence, maximumGattWrite))
        frameInFlight = sequenced
        writeNextFragment()
    }

    /**
     * Firmware without text-screen support predates the Ready / Route-preview screens: it keeps its
     * own "linked" screen until a route is shown, and gets route previews as a plain map.
     */
    private fun adaptToDisplay(frame: DisplayFrame): DisplayFrame? {
        val compatible = if (capabilities?.supportsRemainingDuration == true) {
            frame
        } else {
            frame.copy(remainingDurationMinutes = null)
        }
        if (capabilities?.supportsTextScreens == true) return compatible
        return when (compatible.state) {
            NavigationState.READY -> null
            NavigationState.ROUTE_PREVIEW -> compatible.copy(
                state = NavigationState.NAVIGATING,
                primaryText = null,
                secondaryText = null,
            )
            else -> compatible.copy(primaryText = null, secondaryText = null)
        }
    }

    @Volatile private var callAlert: CallAlert? = null

    /** Rides along on every outgoing frame until the call ends; the ESP32 does the blinking. */
    fun setCallAlert(alert: CallAlert?) {
        handler.post {
            if (callAlert == alert) return@post
            callAlert = alert
            forceNextFrame = true
            pumpLatestFrame()
        }
    }

    private fun withCallAlert(frame: DisplayFrame): DisplayFrame {
        val alert = callAlert
        if (alert == null || capabilities?.supportsCallAlerts != true) return frame.copy(call = null)
        val status = when (alert.phase) {
            CallPhase.RINGING -> "calling..."
            CallPhase.MISSED -> "missed call"
            CallPhase.ACTIVE -> "on call"
        }
        return frame.copy(call = DisplayCall(alert.phase, alert.caller, status))
    }

    private fun withClock(frame: DisplayFrame): DisplayFrame {
        if (capabilities?.supportsClock != true) return frame.copy(clock = null)
        val now = java.time.LocalTime.now()
        return frame.copy(
            // The display always uses 24-hour time ("16:05"), whatever the phone's own setting.
            clock = DisplayClock(now.hour, now.minute, now.second, twentyFourHour = true),
        )
    }

    private fun writeNextFragment() {
        val callbackGatt = gatt ?: return
        val characteristic = frameCharacteristic ?: return
        val fragment = fragments.peekFirst() ?: return
        val started = if (Build.VERSION.SDK_INT >= 33) {
            callbackGatt.writeCharacteristic(
                characteristic,
                fragment,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = fragment
            @Suppress("DEPRECATION")
            callbackGatt.writeCharacteristic(characteristic)
        }
        if (!started) {
            failAndReconnect("Frame write could not start")
            return
        }
        val token = ++writeToken
        handler.postDelayed({
            if (token == writeToken && frameInFlight != null) failAndReconnect("Frame write timed out; reconnecting")
        }, WRITE_TIMEOUT_MILLIS)
    }

    private fun failAndReconnect(message: String) {
        Log.w(LOG_TAG, message)
        clearConnection()
        if (!userDisconnected && selectedDevice != null) scheduleReconnect(reason = message)
    }

    private fun failPermanently(message: String) {
        Log.e(LOG_TAG, message)
        clearConnection()
        publish(TransportState.Error(message))
    }

    private fun armConnectionTimeout() {
        val generation = sessionGeneration
        handler.postDelayed({
            if (generation == sessionGeneration && !ready && gatt != null) {
                failAndReconnect("Display connection timed out")
            }
        }, CONNECTION_TIMEOUT_MILLIS)
    }

    private fun scheduleReconnect(
        immediate: Boolean = false,
        reason: String = if (immediate) "Bluetooth restored" else "Connection lost",
    ) {
        val device = selectedDevice ?: return
        if (closed || userDisconnected) return
        val seconds = if (immediate) 0 else minOf(30, 1 shl reconnectAttempt.coerceAtMost(5))
        if (!immediate) reconnectAttempt++
        publish(TransportState.Reconnecting(device, seconds, reason))
        handler.removeCallbacksAndMessages(RECONNECT_TOKEN)
        handler.postAtTime({ connectSelected() }, RECONNECT_TOKEN, SystemClock.uptimeMillis() + seconds * 1_000L)
    }

    private fun clearConnection(specificGatt: BluetoothGatt? = null) {
        sessionGeneration++
        writeToken++
        ready = false
        frameCharacteristic = null
        capabilities = null
        negotiatedMtu = 23
        fragments.clear()
        frameInFlight = null
        cadence.reset()
        val old = specificGatt ?: gatt
        if (gatt === old) gatt = null
        runCatching { old?.disconnect() }
        runCatching { old?.close() }
    }

    private fun publish(state: TransportState) {
        lastState = state
        listener?.onStateChanged(state)
    }

    @Volatile private var lastState: TransportState = TransportState.Idle

    private fun NavigationState.isLiveLocationState(): Boolean = this == NavigationState.GPS_DEGRADED ||
        this == NavigationState.WAITING_PERMISSION || this == NavigationState.LOCATION_DISABLED

    private object RECONNECT_TOKEN
}
