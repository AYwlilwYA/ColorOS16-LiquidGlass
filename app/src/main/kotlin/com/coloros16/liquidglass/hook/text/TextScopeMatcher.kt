package com.coloros16.liquidglass.hook.text

import android.graphics.RectF
import android.view.View
import com.coloros16.liquidglass.hook.BlurDrawHook
import com.coloros16.liquidglass.log.Lg
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** 文字链范围判定收口：状态栏排除 / 展开按钮数字 / 玻璃区域 / heads-up 窗口 / 近中性色。 */
object TextScopeMatcher {

    private const val TAG = "LiquidGlass"

    /** 类名 → 「是否状态栏区域宿主」判定缓存（类名判定恒定，避免父链逐层重复字符串搜索）。 */
    private val statusBarHostClassCache = ConcurrentHashMap<String, Boolean>()

    private var statusBarBottomPx = -1

    /** 判定屏幕坐标是否落在任一已登记玻璃区域（screenRegionMap，主线程读写）。
     *  状态栏区域（y < [statusBarBottom]）恒判"非玻璃"：screenRegionMap 中控件的 region 可能覆盖状态栏
     *  条带，状态栏文字（时钟/图标 label）被误判"玻璃区域内"会被文字变色误伤。 */
    fun isOverGlassRegion(screenX: Int, screenY: Int): Boolean {
        val sb = statusBarBottom()
        if (sb > 0 && screenY < sb) return false
        for (entry in BlurDrawHook.screenRegionMap.values) {
            val r = entry.region
            if (screenX >= r.left && screenX <= r.right && screenY >= r.top && screenY <= r.bottom) return true
        }
        return false
    }

    /** 状态栏区域底边（物理 px）：framework `status_bar_height` dimen（Resources.getSystem()，
     *  设备密度生效）。首次调用缓存到 [statusBarBottomPx]；解析失败返回 -1（不启用排除，回退现状）。 */
    private fun statusBarBottom(): Int {
        var v = statusBarBottomPx
        if (v >= 0) return v
        v = try {
            val res = android.content.res.Resources.getSystem()
            val id = res.getIdentifier("status_bar_height", "dimen", "android")
            if (id <= 0) -1 else res.getDimensionPixelSize(id)
        } catch (t: Throwable) {
            Lg.e(TAG, "text-contrast: resolve status_bar_height failed, status-bar strip exclusion disabled", t)
            -1
        }
        statusBarBottomPx = v
        if (v > 0) {
            Lg.i(TAG, "text-contrast: status_bar_height=$v px, status-bar strip excluded from glass-region (text never recolored)")
        }
        return v
    }

    /** 状态栏文字判定：沿祖先链向上（含自身），任一类名命中状态栏容器即返回 true。
     *  这些类名均为状态栏专属（不匹配 QS 容器/通知卡/别的布局的时钟），不会误伤玻璃区域内文字。 */
    fun isInStatusBarArea(textView: View): Boolean {
        var v: View? = textView
        while (v != null) {
            val name = v.javaClass.name
            val isHost = statusBarHostClassCache.getOrPut(name) {
                name.contains("PhoneStatusBarView") ||
                    name.contains("KeyguardStatusBarView") ||
                    name.contains("StatusBarWindowView") ||
                    name.contains("StatusBarContainer") ||
                    name.contains("StatusIconContainer")
            }
            if (isHost) return true
            val parent = v.parent
            v = if (parent is View) parent else null
        }
        return false
    }

    /** 判定 TextView 是否是折叠组头展开按钮数字（资源名匹配；无 id/非资源 id 抛异常返回 false）。
     *  该数字原色是灰色（中性色），保持系统原色，不参与文字变色。 */
    fun isExpandButtonNumber(tv: View): Boolean {
        if (tv.id <= 0) return false
        return try {
            tv.resources.getResourceEntryName(tv.id) == BlurDrawHook.EXPAND_BUTTON_NUMBER_RES_NAME
        } catch (t: Throwable) {
            false
        }
    }

    /** heads-up 窗口内文字判定：rootView=HeadsUpLayout/HeadsUpContainerWindow（type 2017）。
     *  heads-up 卡片背景即玻璃，窗口内文字恒视为玻璃区域文字——不受 screenRegionMap 登记时序/
     *  坐标/快照刷新影响。状态栏时钟 rootView=shade 根 → 不命中。 */
    fun isInHeadsUpWindow(textView: View): Boolean {
        return try {
            val root = textView.rootView
            root != null && BlurDrawHook.isHeadsUpRootView(root)
        } catch (t: Throwable) {
            false
        }
    }

    /** bgView 是否属于 heads-up 窗口（判定同 [isInHeadsUpWindow]）。 */
    fun isHeadsUpBgView(view: View): Boolean = isInHeadsUpWindow(view)

    /** heads-up 文字跟随整体颜色作用域：独立开关开启 && 在 heads-up 窗口内。
     *  开关关 → 恒 false → 守卫回退现状，保证与遮罩无关、可独立关闭。 */
    fun isHeadsUpTextFollowArea(textView: View): Boolean =
        BlurDrawHook.headsUpTextFollowEnabled && isInHeadsUpWindow(textView)

    /** 近中性文字色判定：alpha ≥ 0x80 且 RGB 通道差 ≤24（系统灰度）；
     *  饱和 app 自定义色不匹配 → 不覆盖，尊重 app。 */
    fun isNeutralTextColor(color: Int): Boolean {
        val a = (color ushr 24) and 0xFF
        if (a < 0x80) return false
        val r = (color ushr 16) and 0xFF
        val g = (color ushr 8) and 0xFF
        val b = color and 0xFF
        return abs(r - g) <= 24 && abs(g - b) <= 24 && abs(r - b) <= 24
    }
}
