package com.chronicle.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Motion durations and easings mirroring tokens.css --motion-* variables.
 */
object MotionSpec {
    const val FastMs = 120
    const val BaseMs = 180
    const val SlowMs = 280
    const val EnterMs = 220
    const val ShimmerMs = 1400

    val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    val SpringEase = CubicBezierEasing(0.34f, 1.3f, 0.64f, 1f)

    fun <T> fast() = tween<T>(durationMillis = FastMs, easing = Ease)
    fun <T> base() = tween<T>(durationMillis = BaseMs, easing = Ease)
    fun <T> slow() = tween<T>(durationMillis = SlowMs, easing = Ease)
    fun <T> enter() = tween<T>(durationMillis = EnterMs, easing = EaseOut)

    /** Press / lift elevation used by interactive list rows. */
    val PressElevation: Dp = 4.dp
    val RestElevation: Dp = 0.dp

    const val SpringDamping = Spring.DampingRatioMediumBouncy
    const val SpringStiffness = Spring.StiffnessMediumLow
}
