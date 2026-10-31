package com.coloros16.liquidglass.config

/** hook 规格分组常量 + 中文白话标签（设置页/日志展示用）。 */
object SpecGroups {

    const val POSTEFFECT = "posteffect"
    const val NOTIF = "notif"
    const val CC = "cc"
    const val HEADSUP = "headsup"
    const val VOLUME = "volume"
    const val SEEDLING = "seedling"
    const val TEXT = "text"
    const val APP_PAGE = "appPage"

    /** group → 中文白话标签。 */
    val LABELS: Map<String, String> = mapOf(
        POSTEFFECT to "系统模糊管线",
        NOTIF to "通知卡渲染",
        CC to "控制中心",
        HEADSUP to "横幅通知",
        VOLUME to "音量条",
        SEEDLING to "流体云",
        TEXT to "文字颜色",
        APP_PAGE to "应用内页面",
    )
}
