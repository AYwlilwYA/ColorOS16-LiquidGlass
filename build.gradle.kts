// 根构建脚本：仅声明插件版本，不直接应用。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
}
