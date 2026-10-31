package com.coloros16.liquidglass.hook.capture

import android.view.SurfaceControl

/** 抓屏排除层槽位（collectExcludes 列表驱动；解析器由宿主注册，自带 try-catch 兜底）。 */
data class ExcludeSpec(
    val id: String,
    /** 解析该槽的排除层（内部自带 try-catch 兜底，失败返回 null）。 */
    val resolve: () -> SurfaceControl?,
    /** 追加前查重（防重复层进表）。 */
    val dedupe: Boolean = false,
)
