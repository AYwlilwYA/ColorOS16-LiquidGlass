import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.coloros16.liquidglass"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.coloros16.liquidglass"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // 框架阶段先不开启 minify，避免 R8 误删 META-INF/xposed 清单文件；
            // 开启 minify 时需配合 proguard-rules.pro（见 LibXposed README 要求）。
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
    // LibXposed API：仅编译期可见（compileOnly），打包时排除，避免与框架注入冲突。
    compileOnly(libs.libxposed.api)

    // LibXposed Service：模块 UI 进程连接框架、读写框架配置存储。
    // 官方 aar(io.github.libxposed:service/interface:102.0.0) 声明 minCompileSdk=37，本工程 compileSdk=36
    // （AGP 8.13.2 上限）直接引 Maven aar 会校验失败；故用官方 aar 解压后把 aar-metadata 的
    // minCompileSdk 改为 36 重打包为本地 aar（app/libs/），按文件依赖引用。
    // 两 aar 均走 mergeExtDexDebug（带 global-synthetics consumer），可正常 desugar 其中 Java 17 record 类
    // （HotReloadResult）；XposedProvider 的 manifest 声明由 service aar 自动合并。
    implementation(files("libs/libxposed-interface-102.0.0.aar"))
    implementation(files("libs/libxposed-service-102.0.0.aar"))

    // 模块自身 UI（SettingsActivity，Material You 动态取色）
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
}
