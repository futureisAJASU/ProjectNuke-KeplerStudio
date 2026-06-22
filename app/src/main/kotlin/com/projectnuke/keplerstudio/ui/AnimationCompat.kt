package com.projectnuke.keplerstudio.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

@Suppress("UNUSED_PARAMETER")
fun slideInVertically(
    animationSpec: TweenSpec<Int>,
    initialOffsetY: (fullHeight: Int) -> Int
): EnterTransition = androidx.compose.animation.slideInVertically(
    animationSpec = tween<androidx.compose.ui.unit.IntOffset>(
        durationMillis = 320,
        easing = FastOutSlowInEasing
    ),
    initialOffsetY = initialOffsetY
)

@Suppress("UNUSED_PARAMETER")
fun slideOutVertically(
    animationSpec: TweenSpec<Int>,
    targetOffsetY: (fullHeight: Int) -> Int
): ExitTransition = androidx.compose.animation.slideOutVertically(
    animationSpec = tween<androidx.compose.ui.unit.IntOffset>(
        durationMillis = 320,
        easing = FastOutSlowInEasing
    ),
    targetOffsetY = targetOffsetY
)
