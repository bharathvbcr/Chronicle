package com.chronicle.app.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chronicle.app.ui.theme.MotionSpec
import com.chronicle.app.ui.theme.SemanticColors
import com.chronicle.app.ui.theme.isChronicleDark

@Composable
fun Modifier.shimmer(): Modifier {
    val dark = isChronicleDark()
    val transition = rememberInfiniteTransition(label = "shimmer")
    val shift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(MotionSpec.ShimmerMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_shift",
    )
    val base = SemanticColors.skeletonBase(dark)
    val shine = SemanticColors.skeletonShine(dark)
    val brush = Brush.linearGradient(
        colors = listOf(base, shine, base),
        start = Offset(shift * 400f - 200f, 0f),
        end = Offset(shift * 400f + 200f, 0f),
    )
    return this.background(brush)
}

@Composable
fun ShimmerListPlaceholder(
    rows: Int = 5,
    rowHeight: Dp = 56.dp,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(16.dp)) {
        repeat(rows) { i ->
            Box(
                modifier = Modifier
                    .fillMaxWidth(1f - (i % 3) * 0.08f)
                    .height(rowHeight)
                    .clip(RoundedCornerShape(12.dp))
                    .shimmer(),
            )
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}
