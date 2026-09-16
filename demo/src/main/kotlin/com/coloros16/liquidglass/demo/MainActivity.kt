package com.coloros16.liquidglass.demo

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager

/**
 * 液态玻璃 demo 入口：全屏显示 [LiquidGlassDemoView]。
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 隐藏系统栏（API 35+ edge-to-edge 强制，需显式隐藏以最大化可视区域）
        if (Build.VERSION.SDK_INT >= 35) {
            window.decorView.windowInsetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }

        setContentView(LiquidGlassDemoView(this))
    }
}
