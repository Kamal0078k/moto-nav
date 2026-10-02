package dev.motonav.simulator.display

import kotlin.random.Random

/** One drawable moment of the idle eyes: their x positions, and whether they are mid-blink. */
data class FacePose(val leftEyeX: Int = 40, val rightEyeX: Int = 80, val blinking: Boolean = false)

/**
 * Kotlin twin of the firmware's idle face (a port of ESP32_OLED_EYE.ino): blink every 2 s for
 * 120 ms, random moves between three eye positions, halving the distance each frame.
 */
class IdleFace(private val random: Random = Random.Default) {
    private var leftEyeX = 40
    private var rightEyeX = 80
    private var targetLeftEyeX = leftEyeX
    private var targetRightEyeX = rightEyeX
    private var blinking = false
    private var lastBlinkTime = 0L
    private var moveTime = 0L

    fun pose(now: Long): FacePose {
        if (now - lastBlinkTime > BLINK_DELAY_MS && !blinking) {
            blinking = true
            lastBlinkTime = now
        } else if (now - lastBlinkTime > BLINK_CLOSED_MS && blinking) {
            blinking = false
            lastBlinkTime = now
        }
        if (now - moveTime > random.nextLong(2_000, 5_000) && !blinking) {
            when (random.nextInt(3)) {
                1 -> { targetLeftEyeX = 30; targetRightEyeX = 60 }
                2 -> { targetLeftEyeX = 50; targetRightEyeX = 80 }
                else -> { targetLeftEyeX = 40; targetRightEyeX = 70 }
            }
            moveTime = now
        }
        leftEyeX += (targetLeftEyeX - leftEyeX) / MOVE_SPEED
        rightEyeX += (targetRightEyeX - rightEyeX) / MOVE_SPEED
        return FacePose(leftEyeX, rightEyeX, blinking)
    }

    companion object {
        const val FRAME_MS = 50L
        private const val BLINK_DELAY_MS = 2_000L
        private const val BLINK_CLOSED_MS = 120L
        private const val MOVE_SPEED = 2
    }
}
