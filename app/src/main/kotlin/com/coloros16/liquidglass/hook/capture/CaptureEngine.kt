package com.coloros16.liquidglass.hook.capture

import com.coloros16.liquidglass.hook.spec.EngineLog

/**
 * 抓屏触发编排收口：七个触发入口的唯一调用面。
 * 策略门（省电/静态模式）与核心体留在宿主实现；install 时 attach 注册，
 * 文件内外调用点一律经本对象分发（收口模式同 RestoreEngine）。
 */
object CaptureEngine {

    private var onBackground: ((Boolean) -> Unit)? = null
    private var onMainThread: (() -> Unit)? = null
    private var onFirstFrame: ((() -> Boolean, String, Boolean, Boolean, Boolean) -> Unit)? = null
    private var onHeadsUpContinuous: (() -> Unit)? = null
    private var onPanelContinuous: (() -> Unit)? = null
    private var onCardContinuous: (() -> Unit)? = null
    private var onBannerContinuous: ((Boolean) -> Unit)? = null

    fun attach(
        background: (contentChange: Boolean) -> Unit,
        mainThread: () -> Unit,
        firstFrame: (
            burstStop: () -> Boolean,
            logTag: String,
            pauseDuringPanelExpansion: Boolean,
            mainThreadSyncFirstFrame: Boolean,
            bypassMainSyncDedup: Boolean,
        ) -> Unit,
        headsUpContinuous: () -> Unit,
        panelContinuous: () -> Unit,
        cardContinuous: () -> Unit,
        bannerContinuous: (force: Boolean) -> Unit,
    ) {
        onBackground = background
        onMainThread = mainThread
        onFirstFrame = firstFrame
        onHeadsUpContinuous = headsUpContinuous
        onPanelContinuous = panelContinuous
        onCardContinuous = cardContinuous
        onBannerContinuous = bannerContinuous
    }

    fun triggerBackgroundCapture(contentChange: Boolean = true) {
        EngineLog.log("CaptureEngine", "triggerBackground contentChange=$contentChange")
        onBackground?.invoke(contentChange)
    }

    fun triggerBackgroundCaptureOnMainThread() {
        EngineLog.log("CaptureEngine", "triggerBackgroundMainThread")
        onMainThread?.invoke()
    }

    fun triggerFirstFrameCaptureWithBurst(
        burstStop: () -> Boolean,
        logTag: String,
        pauseDuringPanelExpansion: Boolean,
        mainThreadSyncFirstFrame: Boolean = false,
        bypassMainSyncDedup: Boolean = false,
    ) {
        EngineLog.log("CaptureEngine", "triggerFirstFrame tag=$logTag pauseOnExpand=$pauseDuringPanelExpansion sync=$mainThreadSyncFirstFrame bypassDedup=$bypassMainSyncDedup")
        onFirstFrame?.invoke(burstStop, logTag, pauseDuringPanelExpansion, mainThreadSyncFirstFrame, bypassMainSyncDedup)
    }

    fun startHeadsUpContinuousCapture() {
        EngineLog.log("CaptureEngine", "startContinuous headsup")
        onHeadsUpContinuous?.invoke()
    }

    fun startPanelContinuousCapture() {
        EngineLog.log("CaptureEngine", "startContinuous panel")
        onPanelContinuous?.invoke()
    }

    fun startCardBackgroundContinuousCapture() {
        EngineLog.log("CaptureEngine", "startContinuous card")
        onCardContinuous?.invoke()
    }

    fun startSimpleBannerContinuousCapture(force: Boolean = false) {
        EngineLog.log("CaptureEngine", "startContinuous banner force=$force")
        onBannerContinuous?.invoke(force)
    }
}
