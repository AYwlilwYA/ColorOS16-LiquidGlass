package com.coloros16.liquidglass.hook.text

import android.widget.TextView
import com.coloros16.liquidglass.hook.BlurDrawHook
import com.coloros16.liquidglass.hook.spec.EngineLog
import com.coloros16.liquidglass.log.Lg
import java.lang.reflect.Method

/**
 * 文字可读性颜色注入优先级链：所有可读性场景（文字强制白 / heads-up 跟随系统取色 / 文字反转取色）
 * 单一入口，杜绝各路径独立判定造成「一个白一个黑」。
 */
object TextColorPolicy {

    private const val TAG = "LiquidGlass"

    /** `QsColorUtil.getQsColorState()` 反射（挂载期由 BlurDrawHook 解析写入）。 */
    @Volatile
    internal var mQsColorUtilGetState: Method? = null

    private var lastLoggedQsColorState = Int.MIN_VALUE

    /** 给定 TextView + 原色 + 屏幕坐标，按优先级返回注入色；返回 null = 不注入（保持系统色）。
     *  优先级：文字强制白 > heads-up 跟随 > 文字反转取色。
     *  调用方负责前置排除（状态栏 / 展开按钮 / app 彩色 / 未 attach）与缓存 / 防自循环注入尾。 */
    fun resolveReadableTextColor(textView: TextView, originalColor: Int, screenX: Int, screenY: Int): Int? {
        val tvTag = textView.javaClass.name.substringAfterLast('.')
        // 1. 文字强制白：玻璃区域内 / heads-up 窗口内中性色文字恒白/灰
        if (BlurDrawHook.textForceWhiteEnabled) {
            if (TextScopeMatcher.isOverGlassRegion(screenX, screenY) || TextScopeMatcher.isInHeadsUpWindow(textView)) {
                val a = (originalColor ushr 24) and 0xFF
                val injected = (a shl 24) or (BlurDrawHook.maskTextColor and 0x00FFFFFF)
                EngineLog.log("TextColor", "decide rule=forceWhite tv=$tvTag x=$screenX y=$screenY color=0x${injected.toString(16)}")
                return injected
            }
        }
        // 2. heads-up 跟随系统取色：heads-up 窗口内中性色文字按系统黑白通道（深底白字 / 浅底黑字）
        if (TextScopeMatcher.isHeadsUpTextFollowArea(textView)) {
            val injected = systemQsColorInjected(originalColor)
            EngineLog.log("TextColor", "decide rule=headsupFollow tv=$tvTag x=$screenX y=$screenY color=0x${injected.toString(16)}")
            return injected
        }
        // 3. 文字反转取色：玻璃区域内中性色文字按系统黑白通道
        if (BlurDrawHook.textContrastEnabled && TextScopeMatcher.isOverGlassRegion(screenX, screenY)) {
            val injected = systemQsColorInjected(originalColor)
            EngineLog.log("TextColor", "decide rule=contrast tv=$tvTag x=$screenX y=$screenY color=0x${injected.toString(16)}")
            return injected
        }
        EngineLog.log("TextColor", "decide rule=none tv=$tvTag x=$screenX y=$screenY")
        return null
    }

    /** 读取系统黑白通道决定注入色：0 → 深色字（保留原 alpha）；1 → 白色字（保留原 alpha）；
     *  2（null/无数据）/ 反射失败 → 返回 [original]（保持原样，不注入）。 */
    private fun systemQsColorInjected(original: Int): Int {
        val a = (original ushr 24) and 0xFF
        val aPrefix = a shl 24
        return when (readQsColorState()) {
            0 -> aPrefix or 0x000000   // 亮背景 → 深色字
            1 -> aPrefix or 0xFFFFFF   // 暗背景 → 白色字
            else -> original           // 2（null/无数据）或反射失败 → 保持原样
        }
    }

    /** 反射读取 `QsColorUtil.getQsColorState()`。失败/未解析返回 -1。 */
    private fun readQsColorState(): Int {
        val m = mQsColorUtilGetState ?: return -1
        val v = try {
            (m.invoke(null) as? Number)?.toInt() ?: -1
        } catch (t: Throwable) {
            -1
        }
        // 系统黑白通道值跳变才打一次（0 亮背景→深色字 / 1 暗背景→白字 / 2 null→原样 / -1 反射失败）
        if (v != lastLoggedQsColorState) {
            lastLoggedQsColorState = v
            Lg.i(TAG, "text-contrast: QsColorUtil.getQsColorState()=$v (0 light→dark text / 1 dark→white text / 2 null / -1 reflect fail)")
        }
        return v
    }
}
