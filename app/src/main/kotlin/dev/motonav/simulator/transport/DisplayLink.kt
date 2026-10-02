package dev.motonav.simulator.transport

import android.content.Context
import dev.motonav.simulator.calls.CallAlerts

/**
 * The foreground BLE link to the handlebar display, owned by the process rather than by one
 * activity instance. Arriving from a Google Maps share, a recreated activity or a new window
 * reuses the live connection instead of opening a competing GATT client.
 */
object DisplayLink {
    private var transport: AndroidBleDisplayTransport? = null

    /** The display the rider last chose; the ride service connects to it. */
    var selectedDisplay: DisplayDevice? = null

    val isOpen: Boolean get() = transport != null

    private val callListener = CallAlerts.Listener { alert -> transport?.setCallAlert(alert) }

    fun acquire(context: Context): AndroidBleDisplayTransport =
        transport ?: AndroidBleDisplayTransport(context.applicationContext).also {
            transport = it
            CallAlerts.start(context)
            CallAlerts.addListener(callListener)
        }

    /** Closes the link, e.g. before the ride service takes over the display. */
    fun release() {
        CallAlerts.removeListener(callListener)
        transport?.close()
        transport = null
    }
}
