import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ============================================================================
// demo 模块：独立液态玻璃效果验证 APK（不影响 app Xposed 模块）
// - applicationId: com.coloros16.liquidglass.demo
// - 复用 app 模块的 LiquidGlassShader.kt（复制到 demo 源码，见
//   src/main/kotlin/com/coloros16/liquidglass/demo/liquidglass/LiquidGlassShader.kt 头部来源标注）
// - 无任何第三方依赖：纯平台 View + AGSL RuntimeShader，隔离验证 shader 效果
// ============================================================================
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.coloros16.liquidglass.demo"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.coloros16.liquidglass.demo"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1-demo"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    // demo 无外部依赖（不引 LibXposed / AndroidX / Material），纯平台 API
}
