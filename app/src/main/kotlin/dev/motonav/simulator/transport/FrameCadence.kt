package dev.motonav.simulator.transport

import dev.motonav.simulator.protocol.DisplayFrame

class FrameCadence(
    private val changedFrameIntervalMillis: Long = 400,
    private val heartbeatIntervalMillis: Long = 400,
) {
    private var lastFrame: DisplayFrame? = null
    private var lastSentAtMillis: Long? = null

    fun shouldSend(frame: DisplayFrame, nowMillis: Long, force: Boolean = false): Boolean {
        if (force || lastFrame == null || lastSentAtMillis == null) return true
        val elapsed = nowMillis - requireNotNull(lastSentAtMillis)
        val contentChanged = frame.copy(sequence = 0) != requireNotNull(lastFrame).copy(sequence = 0)
        val urgentChange = frame.state != lastFrame?.state || frame.maneuver != lastFrame?.maneuver
        return when {
            urgentChange -> true
            contentChanged -> elapsed >= changedFrameIntervalMillis
            else -> elapsed >= heartbeatIntervalMillis
        }
    }

    fun markSent(frame: DisplayFrame, nowMillis: Long) {
        lastFrame = frame
        lastSentAtMillis = nowMillis
    }

    fun reset() {
        lastFrame = null
        lastSentAtMillis = null
    }
}
