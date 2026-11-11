package io.github.mo3bdlaa.tvmouse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.mo3bdlaa.tvmouse.hid.HidManager
import io.github.mo3bdlaa.tvmouse.hid.MouseButton
import kotlin.math.hypot
import kotlin.math.min

private const val TAP_MAX_MS = 250L
private const val DOUBLE_TAP_MS = 300L
private const val LONG_PRESS_MS = 450L

@Composable
fun TouchpadScreen(settings: TouchpadSettings, modifier: Modifier = Modifier) {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val haptics = LocalHapticFeedback.current
    val currentSettings by rememberUpdatedState(settings)

    Column(modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(Unit) { touchpadGestures({ currentSettings }, haptics) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Move: 1 finger\nClick: tap   •   Right-click / Back: 2-finger tap\n" +
                    "Scroll: 2 fingers   •   Drag: long-press or double-tap and hold",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(24.dp),
            )
        }
        Row(Modifier.fillMaxWidth().height(72.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HoldButton("Left", MouseButton.LEFT, Modifier.weight(2f))
            HoldButton("Middle", MouseButton.MIDDLE, Modifier.weight(1f))
            HoldButton("Right", MouseButton.RIGHT, Modifier.weight(2f))
        }
    }
}

/** A mouse button that stays pressed while your finger is on it, so you can drag with another finger. */
@Composable
private fun HoldButton(label: String, button: Int, modifier: Modifier) {
    var pressed by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(if (pressed) colors.primary else colors.secondaryContainer)
            .pointerInput(button) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    pressed = true
                    HidManager.setButton(button, true)
                    waitForUpOrCancellation()
                    HidManager.setButton(button, false)
                    pressed = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (pressed) colors.onPrimary else colors.onSecondaryContainer)
    }
}

/** Turns finger movement into pointer movement and scroll steps, keeping fractional remainders. */
private class MotionAccumulator {
    private var restX = 0f
    private var restY = 0f
    private var scrollX = 0f
    private var scrollY = 0f

    fun move(dxDp: Float, dyDp: Float, s: TouchpadSettings) {
        val speed = hypot(dxDp, dyDp)
        val gain = s.sensitivity * (1f + s.acceleration * min(speed / 6f, 2.5f))
        restX += dxDp * gain
        restY += dyDp * gain
        val x = restX.toInt()
        val y = restY.toInt()
        restX -= x
        restY -= y
        if (x != 0 || y != 0) HidManager.moveMouse(x, y)
    }

    fun scroll(dxDp: Float, dyDp: Float, s: TouchpadSettings) {
        scrollX += dxDp
        scrollY += dyDp
        val stepsY = (scrollY / s.scrollStep).toInt()
        val stepsX = (scrollX / s.scrollStep).toInt()
        scrollY -= stepsY * s.scrollStep
        scrollX -= stepsX * s.scrollStep
        if (stepsX == 0 && stepsY == 0) return
        val direction = if (s.naturalScroll) 1 else -1
        // Wheel: positive scrolls up. Pan: positive scrolls right.
        HidManager.scroll(wheel = stepsY * direction, pan = -stepsX * direction)
    }

    fun resetScroll() {
        scrollX = 0f
        scrollY = 0f
    }
}

private suspend fun PointerInputScope.touchpadGestures(
    settings: () -> TouchpadSettings,
    haptics: HapticFeedback,
) {
    val motion = MotionAccumulator()
    var lastTapUp = 0L
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val downTime = down.uptimeMillis
        var lastTime = downTime
        var maxPointers = 1
        var travel = 0f
        var moved = false
        var dragging = false
        motion.resetScroll()

        if (downTime - lastTapUp < DOUBLE_TAP_MS) {
            // Double-tap and hold: drag with the left button held down.
            dragging = true
            lastTapUp = 0L
            HidManager.setButton(MouseButton.LEFT, true)
        }

        while (true) {
            val waitForLongPress = !moved && !dragging && maxPointers == 1
            val event = if (waitForLongPress) {
                val remaining = LONG_PRESS_MS - (lastTime - downTime)
                if (remaining <= 0) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }

            if (event == null) {
                // Finger held still: long-press starts a drag.
                dragging = true
                HidManager.setButton(MouseButton.LEFT, true)
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                continue
            }

            val pressedCount = event.changes.count { it.pressed }
            maxPointers = maxOf(maxPointers, pressedCount)
            event.changes.firstOrNull()?.let { lastTime = it.uptimeMillis }
            if (pressedCount == 0) break

            val moving = event.changes.filter { it.pressed && it.previousPressed }
            if (moving.isNotEmpty()) {
                var sumX = 0f
                var sumY = 0f
                moving.forEach {
                    sumX += it.position.x - it.previousPosition.x
                    sumY += it.position.y - it.previousPosition.y
                }
                val dx = sumX / moving.size
                val dy = sumY / moving.size
                travel += hypot(dx, dy)
                if (!moved && travel > viewConfiguration.touchSlop) moved = true
                if (moved) {
                    val s = settings()
                    if (pressedCount >= 2 && !dragging) {
                        motion.scroll(dx / density, dy / density, s)
                    } else {
                        motion.move(dx / density, dy / density, s)
                    }
                }
            }
            event.changes.forEach { it.consume() }
        }

        when {
            dragging -> HidManager.setButton(MouseButton.LEFT, false)
            !moved && lastTime - downTime < TAP_MAX_MS -> {
                if (maxPointers >= 2) {
                    HidManager.click(MouseButton.RIGHT)
                } else {
                    HidManager.click(MouseButton.LEFT)
                    lastTapUp = lastTime
                }
            }
        }
    }
}
