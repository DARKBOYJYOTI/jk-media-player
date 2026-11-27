package com.jk.mediaplayer.ui.common

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith

/**
 * Shared GPU-composited Material 3 motion specs for both JK Music and JK Video.
 * Uses pure translation + alpha with Emphasized curves (no full-screen scale rasterization)
 * to stay locked at 120 Hz without frame drops or micro-jitter.
 */
object PageTransitions {

    private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)
    private val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

    /** Forward drill-in for full-screen overlays (Video Folder, Settings, Blacklist). */
    fun overlayDrillEnter(): EnterTransition =
        fadeIn(animationSpec = tween(durationMillis = 210, easing = EmphasizedDecelerate)) +
            slideInHorizontally(
                initialOffsetX = { (it * 0.10f).toInt() },
                animationSpec = tween(durationMillis = 280, easing = EmphasizedDecelerate)
            )

    /** Backward exit for full-screen overlays. */
    fun overlayDrillExit(): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = 160, easing = EmphasizedAccelerate)) +
            slideOutHorizontally(
                targetOffsetX = { (it * 0.09f).toInt() },
                animationSpec = tween(durationMillis = 200, easing = EmphasizedAccelerate)
            )

    /** Subtle upward glide for modal/search overlays. */
    fun searchOverlayEnter(): EnterTransition =
        fadeIn(animationSpec = tween(durationMillis = 190, easing = EmphasizedDecelerate)) +
            slideInVertically(
                initialOffsetY = { (it * 0.05f).toInt() },
                animationSpec = tween(durationMillis = 250, easing = EmphasizedDecelerate)
            )

    /** Brisk downward fade for closing search overlays. */
    fun searchOverlayExit(): ExitTransition =
        fadeOut(animationSpec = tween(durationMillis = 140, easing = EmphasizedAccelerate)) +
            slideOutVertically(
                targetOffsetY = { (it * 0.04f).toInt() },
                animationSpec = tween(durationMillis = 175, easing = EmphasizedAccelerate)
            )

    /** Shared-axis horizontal transition for drilling forward into a detail page. */
    fun drillForwardTransform(): ContentTransform =
        (fadeIn(tween(210, easing = EmphasizedDecelerate)) +
            slideInHorizontally(
                initialOffsetX = { (it * 0.10f).toInt() },
                animationSpec = tween(280, easing = EmphasizedDecelerate)
            )) togetherWith
            (fadeOut(tween(135, easing = EmphasizedAccelerate)) +
                slideOutHorizontally(
                    targetOffsetX = { -(it * 0.04f).toInt() },
                    animationSpec = tween(200, easing = EmphasizedAccelerate)
                )) using SizeTransform(clip = false)

    /** Shared-axis horizontal transition for navigating back from a detail page. */
    fun drillBackwardTransform(): ContentTransform =
        (fadeIn(tween(200, easing = EmphasizedDecelerate)) +
            slideInHorizontally(
                initialOffsetX = { -(it * 0.06f).toInt() },
                animationSpec = tween(250, easing = EmphasizedDecelerate)
            )) togetherWith
            (fadeOut(tween(155, easing = EmphasizedAccelerate)) +
                slideOutHorizontally(
                    targetOffsetX = { (it * 0.10f).toInt() },
                    animationSpec = tween(200, easing = EmphasizedAccelerate)
                )) using SizeTransform(clip = false)

    /** Directional shared-axis transition for bottom navigation tab switches. */
    fun tabSwitchTransform(forward: Boolean): ContentTransform {
        val dir = if (forward) 1 else -1
        return (fadeIn(tween(185, easing = EmphasizedDecelerate)) +
            slideInHorizontally(
                initialOffsetX = { (it * 0.05f * dir).toInt() },
                animationSpec = tween(230, easing = EmphasizedDecelerate)
            )) togetherWith
            (fadeOut(tween(115, easing = EmphasizedAccelerate)) +
                slideOutHorizontally(
                    targetOffsetX = { -(it * 0.03f * dir).toInt() },
                    animationSpec = tween(160, easing = EmphasizedAccelerate)
                )) using SizeTransform(clip = false)
    }
}
