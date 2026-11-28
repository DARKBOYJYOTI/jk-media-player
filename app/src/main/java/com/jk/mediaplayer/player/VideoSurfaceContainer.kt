package com.jk.mediaplayer.player

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.SurfaceView
import android.widget.FrameLayout

/**
 * Direct-vout video + subtitle SurfaceView host that replaces LibVLC's VideoHelper.
 *
 * Unlike VLCVideoLayout (whose internal VideoHelper overwrites LayoutParams with stale PiP
 * pixel sizes and setFixedSize() during PiP exit on RealmeUI/ColorOS and MIUI), this container
 * always computes the centered fitted bounds directly from its own live width/height and never
 * tears down the active SurfaceView during PiP transitions.
 */
class VideoSurfaceContainer(context: Context) : FrameLayout(context) {
    val videoSurface: SurfaceView = SurfaceView(context)
    val subtitleSurface: SurfaceView = SurfaceView(context).apply {
        setZOrderMediaOverlay(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
    }

    private var videoWidth: Int = 0
    private var videoHeight: Int = 0
    private var sarNum: Int = 1
    private var sarDen: Int = 1

    var onBoundsUpdated: ((fitW: Int, fitH: Int) -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        addView(
            videoSurface,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
        addView(
            subtitleSurface,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
    }

    fun setVideoDimensions(width: Int, height: Int, sarNum: Int = 1, sarDen: Int = 1) {
        if (width <= 0 || height <= 0) return
        val safeNum = if (sarNum > 0) sarNum else 1
        val safeDen = if (sarDen > 0) sarDen else 1
        if (this.videoWidth == width &&
            this.videoHeight == height &&
            this.sarNum == safeNum &&
            this.sarDen == safeDen
        ) {
            return
        }
        this.videoWidth = width
        this.videoHeight = height
        this.sarNum = safeNum
        this.sarDen = safeDen
        post { applyFittedLayout(this.width, this.height) }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) {
            applyFittedLayout(w, h)
        }
    }

    fun applyFittedLayout(containerW: Int = width, containerH: Int = height) {
        if (containerW <= 0 || containerH <= 0) return
        val fitW: Int
        val fitH: Int
        if (videoWidth > 0 && videoHeight > 0) {
            val videoAspect = (videoWidth.toDouble() * sarNum.toDouble()) /
                (videoHeight.toDouble() * sarDen.toDouble())
            val containerAspect = containerW.toDouble() / containerH.toDouble()
            if (containerAspect > videoAspect) {
                fitH = containerH
                fitW = (containerH * videoAspect).toInt().coerceIn(1, containerW)
            } else {
                fitW = containerW
                fitH = (containerW / videoAspect).toInt().coerceIn(1, containerH)
            }
        } else {
            fitW = containerW
            fitH = containerH
        }
        val lp = videoSurface.layoutParams as? LayoutParams
            ?: LayoutParams(fitW, fitH, Gravity.CENTER)
        if (lp.width != fitW || lp.height != fitH || lp.gravity != Gravity.CENTER) {
            lp.width = fitW
            lp.height = fitH
            lp.gravity = Gravity.CENTER
            videoSurface.layoutParams = lp
        }
        val subLp = subtitleSurface.layoutParams as? LayoutParams
            ?: LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        if (subLp.width != LayoutParams.MATCH_PARENT ||
            subLp.height != LayoutParams.MATCH_PARENT ||
            subLp.gravity != Gravity.CENTER
        ) {
            subLp.width = LayoutParams.MATCH_PARENT
            subLp.height = LayoutParams.MATCH_PARENT
            subLp.gravity = Gravity.CENTER
            subtitleSurface.layoutParams = subLp
        }
        onBoundsUpdated?.invoke(fitW, fitH)
    }
}
