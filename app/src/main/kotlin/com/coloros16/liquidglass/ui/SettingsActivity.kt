package com.coloros16.liquidglass.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.coloros16.liquidglass.App
import com.coloros16.liquidglass.R
import com.coloros16.liquidglass.config.Prefs
import com.coloros16.liquidglass.update.UpdateChecker
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.libxposed.service.XposedService
import kotlin.math.roundToInt

/**
 * 模块配置界面（Material You 动态取色，二级菜单）。
 *
 * 二级菜单结构：
 * - 一级 = 大类（总开关 / 液态玻璃材质 / 背景与抓屏 / 文字与可读性 / 遮罩 / 流体云 /
 *   锁屏时钟 / 追踪器 / 日志与诊断 / 场景过滤），列表页展示。
 * - 二级 = 各类内具体配置（开关 / 滑杆 / 下拉），点进大类后切换子视图。
 *   全部配置清单与接线状态见 doc/spec/34-配置开关清单.md。
 *
 * 配置读写模型（2026-08-11 修正）：
 * 本 Activity 运行在模块 APK 进程，必须把配置写入【框架侧存储】——
 * 经 [App.serviceRef]（XposedService）→ [Prefs.remote] → edit().apply()，
 * 底层写 LibXposed 框架存储（LSPosed 等框架的内部数据库）；
 * hook 端（SystemUI / Launcher / 锁屏时钟进程）经 XposedInterface.getRemotePreferences 读同一份。
 * 注意：框架 binder 注入晚于 onCreate（真机实测异步），写入/回显需等待 [App.addServiceStateListener] 就绪，
 * 不可直接降级本地 XML（hook 端读不到 XML）。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var xmlPrefs: SharedPreferences
    private lateinit var contentFrame: FrameLayout

    /** 当前所在二级大类（null = 正在一级列表页）。 */
    private var currentCategory: String? = null

    /** [spec/64] 当前所在三级「桌面动画参数」组（null = 不在三级页）。 */
    private var currentAnimGroup: String? = null

    /** [2026-09-17 升级提示] 本次启动是否已触发过检查（防重复起线程）。 */
    private var updateCheckStarted = false

    /** [spec/64] 一键预设档位：刚度 / 阻尼 / 透明度时长（ms）；null = 取该组默认值。 */
    private data class AnimPreset(val labelRes: Int, val stiffness: Int?, val damping: Int?, val fade: Int?)

    /**
     * [spec/64] 三档预设，阻尼一律取 100 = 临界阻尼（ζ=1.0），**数学上无过冲**，仅速度不同。
     * 折算关系（与 SwiftUI 同模型）：刚度 = (2π/T)²，T = 感知时长（秒）；阻尼值 = 阻尼比 ζ × 100。
     *
     * 为何不用 ζ<1：欠阻尼必然过冲（`过冲量 = e^(-πζ/√(1-ζ²))`），
     * 表现为「画面已铺满全屏仍在继续胀大再回缩」。小元件位移下 0.6% 过冲不可见，
     * 但应用打开是「图标 → 全屏」的大位移放大，同样的 ζ 会被放大成肉眼可见的运动
     * （ζ=0.70 时过冲 4.6%，1440px 下约 66px）—— 真机实测 ζ<1 的档位均被否决。
     */
    private val animPresets = listOf(
        // 按组取 ZuyQA 原始默认值：打开/打断 580/140/340，关闭 120/87/510
        AnimPreset(R.string.settings_anim_preset_default, null, null, null),
        AnimPreset(R.string.settings_anim_preset_soft, 158, 100, 500),
        AnimPreset(R.string.settings_anim_preset_mid, 220, 100, 420),
        AnimPreset(R.string.settings_anim_preset_crisp, 320, 100, 350),
    )

    /** 二级大类定义。 */
    private data class Category(val id: String, val titleRes: Int, val descRes: Int, val layoutRes: Int)

    private val categories = listOf(
        Category("master", R.string.cat_master, R.string.cat_master_desc, R.layout.view_cat_master),
        Category("material", R.string.cat_material, R.string.cat_material_desc, R.layout.view_cat_material),
        Category("background", R.string.cat_background, R.string.cat_background_desc, R.layout.view_cat_background),
        Category("text", R.string.cat_text, R.string.cat_text_desc, R.layout.view_cat_text),
        Category("mask", R.string.cat_mask, R.string.cat_mask_desc, R.layout.view_cat_mask),
        Category("seedling", R.string.cat_seedling, R.string.cat_seedling_desc, R.layout.view_cat_seedling),
        // [spec/81 2026-09-17] 锁屏时钟分类页已删除（hook 未命中数字 View，功能整体停用，无存活控件）
        // [spec/64] 桌面动画参数（三组开关 + 三级参数页）
        Category("anim", R.string.cat_anim, R.string.cat_anim_desc, R.layout.view_cat_anim),
        // [spec/65] iOS 动态倾斜/透视（实验性，默认关）
        Category("tilt", R.string.cat_tilt, R.string.cat_tilt_desc, R.layout.view_cat_tilt),
        Category("desktop", R.string.cat_desktop, R.string.cat_desktop_desc, R.layout.view_cat_desktop),
        // [2026-09-17] 追踪器分类页已删除（追踪器整体移除，无存活控件）
        Category("log", R.string.cat_log, R.string.cat_log_desc, R.layout.view_cat_log),
        Category("about", R.string.cat_about, R.string.cat_about_desc, R.layout.view_cat_about),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener {
            when {
                // [spec/64] 三级参数页 → 回二级「桌面动画」
                currentAnimGroup != null -> openCategory("anim")
                currentCategory != null -> showCategories()
                else -> finish()
            }
        }

        xmlPrefs = Prefs.read(this)
        contentFrame = findViewById(R.id.settings_content)

        // TEMP-VERIFY: 配置链路真机验证用（验证后移除）——带 auto_enable=true 启动时自动写总开关
        if (intent?.getBooleanExtra("auto_enable", false) == true) {
            writeMasterEnabled(true)
        }

        // 框架就绪后刷新回显（framework 侧为权威值；仅重新绑定当前大类控件）
        App.addServiceStateListener(object : App.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                if (service != null) bindCurrentPage()
            }
        }, false)

        showCategories()

        // [2026-09-17 升级提示] 应用启动即后台查一次最新版本，有新版本弹提示（不阻塞界面）
        checkUpdateOnLaunch()
    }

    // ------------------------------------------------------------ [2026-09-17] 启动升级提示 ------------------------------------------------------------

    /**
     * 启动时检查 GitHub Releases 最新版本，比当前 [appVersionName] 新则弹提示。
     *
     * 网络在子线程（[UpdateChecker.fetchLatest] 阻塞），结果回主线程弹窗；
     * 检查失败/无新版本/该版本已被「忽略此版本」→ 全程静默，不打扰用户。
     */
    private fun checkUpdateOnLaunch() {
        if (updateCheckStarted) return
        updateCheckStarted = true
        Thread {
            val latest = UpdateChecker.fetchLatest() ?: return@Thread
            val current = appVersionName() ?: return@Thread
            if (!UpdateChecker.isNewer(latest.version, current)) return@Thread
            if (xmlPrefs.getString(XML_KEY_IGNORED_UPDATE, null) == latest.version) return@Thread
            runOnUiThread {
                if (!isFinishing && !isDestroyed) showUpdateDialog(current, latest)
            }
        }.start()
    }

    /** 当前安装版本名（如 "0.1.1"）；读取失败返回 null（则本次不提示）。 */
    private fun appVersionName(): String? = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (t: Throwable) {
        Log.w(TAG, "read versionName failed", t)
        null
    }

    /** 升级提示弹窗：去下载（跳 release 页/APK 直链）/ 忽略此版本（记本地，不再提示该版本）/ 以后再说。 */
    private fun showUpdateDialog(current: String, latest: UpdateChecker.Release) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_dialog_title)
            .setMessage(getString(R.string.update_dialog_message, current, latest.version))
            .setPositiveButton(R.string.update_dialog_download) { _, _ ->
                // 优先 APK 直链（一键下载）；无资产则退到 release 页面
                val target = latest.apkUrl ?: latest.pageUrl
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                } catch (t: Throwable) {
                    Log.w(TAG, "open update url failed: $target", t)
                    Toast.makeText(this, R.string.update_dialog_open_failed, Toast.LENGTH_SHORT).show()
                }
            }
            .setNeutralButton(R.string.update_dialog_ignore) { _, _ ->
                xmlPrefs.edit().putString(XML_KEY_IGNORED_UPDATE, latest.version).apply()
            }
            .setNegativeButton(R.string.update_dialog_later, null)
            .show()
    }

    // ------------------------------------------------------------ 一级 / 二级导航 ------------------------------------------------------------

    /** 一级列表页：展示全部大类。 */
    private fun showCategories() {
        currentCategory = null
        currentAnimGroup = null
        supportActionBar?.title = getString(R.string.app_name)
        val listView = layoutInflater.inflate(R.layout.view_categories, contentFrame, false)
        contentFrame.removeAllViews()
        contentFrame.addView(listView)
        val list = listView.findViewById<LinearLayout>(R.id.categoryList)
        list.removeAllViews()
        for (cat in categories) {
            val row = layoutInflater.inflate(R.layout.item_category, list, false)
            row.findViewById<TextView>(R.id.catTitle).text = getString(cat.titleRes)
            row.findViewById<TextView>(R.id.catDesc).text = getString(cat.descRes)
            row.setOnClickListener { openCategory(cat.id) }
            list.addView(row)
        }
    }

    /** 二级设置页：切换到大类子视图并绑定控件。 */
    private fun openCategory(catId: String) {
        val cat = categories.firstOrNull { it.id == catId } ?: return
        currentCategory = catId
        currentAnimGroup = null
        supportActionBar?.title = getString(cat.titleRes)
        val view = layoutInflater.inflate(cat.layoutRes, contentFrame, false)
        contentFrame.removeAllViews()
        contentFrame.addView(view)
        bindCategory(catId)
    }

    /** [spec/64] 三级页：桌面动画参数（打开 / 关闭 / 打断）。 */
    private fun openAnimParams(group: String) {
        currentAnimGroup = group
        supportActionBar?.title = getString(animGroupTitleRes(group))
        val view = layoutInflater.inflate(R.layout.view_anim_params, contentFrame, false)
        contentFrame.removeAllViews()
        contentFrame.addView(view)
        bindAnimParams(view, group)
    }

    /** 按当前层级（三级 / 二级 / 一级）重新绑定控件（首次进入 + 框架就绪后权威值刷新共用）。 */
    private fun bindCurrentPage() {
        val view = contentFrame.getChildAt(0) ?: return
        val group = currentAnimGroup
        if (group != null) {
            bindAnimParams(view, group)
            return
        }
        currentCategory?.let { bindCategory(it) }
    }

    /** 绑定当前大类控件（首次进入 + 框架就绪后权威值刷新共用）。 */
    private fun bindCategory(catId: String) {
        val view = contentFrame.getChildAt(0) ?: return
        when (catId) {
            "master" -> bindMaster(view)
            "material" -> bindMaterial(view)
            "background" -> bindBackground(view)
            "text" -> bindText(view)
            "mask" -> bindMask(view)
            "seedling" -> bindSeedling(view)
            "anim" -> bindAnim(view)
            "tilt" -> bindTilt(view)
            "desktop" -> bindDesktop(view)
            "log" -> bindLog(view)
            "about" -> bindAbout(view)
        }
    }

    // ------------------------------------------------------------ 各二级大类绑定 ------------------------------------------------------------

    /** 总开关。 */
    private fun bindMaster(view: View) {
        val switch = view.findViewById<Switch>(R.id.switchMaster)
        val switchListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writeMasterEnabled(checked)
        }
        switch.setOnCheckedChangeListener(switchListener)
        refreshMasterSwitch(switch, switchListener)
    }

    /** 液态玻璃材质：内部轻模糊 / 鲜艳度 / 高光衰减 / 折射环带高度 / 折射强度。 */
    private fun bindMaterial(view: View) {
        // [2026-09-16 恢复配置化] 模糊半径：0~32 px（默认 8）。原被硬编码 0（清晰透镜），
        // 用户反馈「横幅太透」。>0 时 shader 走 5×5 内部轻模糊（25 次采样，仅作用内部不糊折射环带）。
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekBlurRadius), valueText = view.findViewById(R.id.blurRadiusValue),
            key = Prefs.KEY_BLUR_RADIUS, default = Prefs.DEFAULT_BLUR_RADIUS,
            fromProgress = { p -> p.toFloat() },
            toProgress = { v -> v.roundToInt().coerceIn(0, Prefs.BLUR_RADIUS_UI_MAX) },
            format = { v -> getString(R.string.settings_blur_radius_value, v.roundToInt()) },
        )
        // vibrancy：1.0~2.0（progress 0~100 → 1.0~2.0，默认 1.5）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekVibrancy), valueText = view.findViewById(R.id.vibrancyValue),
            key = Prefs.KEY_VIBRANCY, default = Prefs.DEFAULT_VIBRANCY,
            fromProgress = { p -> 1.0f + p / 100f },
            toProgress = { v -> ((v - 1.0f) * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_vibrancy_value, v) },
        )
        // highlightFalloff：1~4（默认 2，越大越聚光）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekHighlightFalloff), valueText = view.findViewById(R.id.highlightFalloffValue),
            key = Prefs.KEY_HIGHLIGHT_FALLOFF, default = Prefs.DEFAULT_HIGHLIGHT_FALLOFF,
            fromProgress = { p -> 1f + p },
            toProgress = { v -> (v - 1f).roundToInt().coerceIn(0, 3) },
            format = { v -> getString(R.string.settings_highlight_falloff_value, v.roundToInt()) },
        )
        // [2026-08-14 折射自适应] refractionHeight 不再可配置（恒 = 元素短边一半），滑杆移除
        // refractionAmount：0~100 px（折射强度，默认 28）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekRefractionAmount), valueText = view.findViewById(R.id.refractionAmountValue),
            key = Prefs.KEY_REFRACTION_AMOUNT, default = Prefs.DEFAULT_REFRACTION_AMOUNT,
            fromProgress = { p -> p.toFloat() },
            toProgress = { v -> v.roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_refraction_amount_value, v.roundToInt()) },
        )
        // [2026-08-15 材质参数控件补齐] 色散/深度/高光/高光宽度/光角度/视差（读 KEY_*，readMaterialParams
        // 已接线，改动约 1s 内生效）
        // dispersion：0~30 → 0.00~0.30（默认 0 = 关闭色散）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekDispersion), valueText = view.findViewById(R.id.dispersionValue),
            key = Prefs.KEY_DISPERSION, default = Prefs.DEFAULT_DISPERSION,
            fromProgress = { p -> p / 100f },
            toProgress = { v -> (v * 100f).roundToInt().coerceIn(0, 30) },
            format = { v -> getString(R.string.settings_dispersion_value, v) },
        )
        // depth：0~100 → 0~3（默认 1）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekDepth), valueText = view.findViewById(R.id.depthValue),
            key = Prefs.KEY_DEPTH, default = Prefs.DEFAULT_DEPTH,
            fromProgress = { p -> p / 100f * 3f },
            toProgress = { v -> (v / 3f * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_depth_value, v) },
        )
        // highlight：0~100 → 0~2（默认 0.5）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekHighlight), valueText = view.findViewById(R.id.highlightValue),
            key = Prefs.KEY_HIGHLIGHT, default = Prefs.DEFAULT_HIGHLIGHT,
            fromProgress = { p -> p / 100f * 2f },
            toProgress = { v -> (v / 2f * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_highlight_value, v) },
        )
        // highlightWidth：0~60 px（默认 12）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekHighlightWidth), valueText = view.findViewById(R.id.highlightWidthValue),
            key = Prefs.KEY_HIGHLIGHT_WIDTH, default = Prefs.DEFAULT_HIGHLIGHT_WIDTH,
            fromProgress = { p -> p.toFloat() },
            toProgress = { v -> v.roundToInt().coerceIn(0, 60) },
            format = { v -> getString(R.string.settings_highlight_width_value, v.roundToInt()) },
        )
        // highlightFloor：0~100 → 0~1（默认 0.4）。[doc/spec/67] 弧线高光保底：
        // 0 = 纯方向性（圆角弧线因法线与光源垂直而偏暗）、1 = 整圈均匀一条亮线（等于"直接描一条边"）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekHighlightFloor), valueText = view.findViewById(R.id.highlightFloorValue),
            key = Prefs.KEY_HIGHLIGHT_FLOOR, default = Prefs.DEFAULT_HIGHLIGHT_FLOOR,
            fromProgress = { p -> p / 100f },
            toProgress = { v -> (v * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_highlight_floor_value, (v * 100f).roundToInt()) },
        )
        // lightAngle：0~360°（默认 45）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekLightAngle), valueText = view.findViewById(R.id.lightAngleValue),
            key = Prefs.KEY_LIGHT_ANGLE, default = Prefs.DEFAULT_LIGHT_ANGLE,
            fromProgress = { p -> p.toFloat() },
            toProgress = { v -> v.roundToInt().coerceIn(0, 360) },
            format = { v -> getString(R.string.settings_light_angle_value, v.roundToInt()) },
        )
        // parallaxX：-100~100 px（默认 0 = 关闭视差）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekParallaxX), valueText = view.findViewById(R.id.parallaxXValue),
            key = Prefs.KEY_PARALLAX_X, default = Prefs.DEFAULT_PARALLAX_X,
            fromProgress = { p -> p - 100f },
            toProgress = { v -> (v + 100f).roundToInt().coerceIn(0, 200) },
            format = { v -> getString(R.string.settings_parallax_x_value, v.roundToInt()) },
        )
        // parallaxY：-100~100 px（默认 0 = 关闭视差）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekParallaxY), valueText = view.findViewById(R.id.parallaxYValue),
            key = Prefs.KEY_PARALLAX_Y, default = Prefs.DEFAULT_PARALLAX_Y,
            fromProgress = { p -> p - 100f },
            toProgress = { v -> (v + 100f).roundToInt().coerceIn(0, 200) },
            format = { v -> getString(R.string.settings_parallax_y_value, v.roundToInt()) },
        )
    }

    /** 背景与抓屏：背景档位 / 抓屏节流 / 空置抓屏 / 持续抓屏 / 降采样 / 重试 / UID 过滤。 */
    private fun bindBackground(view: View) {
        // 抓屏节流：0~1000ms，步进 16（progress 0~62 → 0~992ms，默认 0=无节流立即抓）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekCaptureMinInterval), valueText = view.findViewById(R.id.captureMinIntervalValue),
            key = Prefs.KEY_BG_CAPTURE_MIN_INTERVAL_MS, default = Prefs.DEFAULT_BG_CAPTURE_MIN_INTERVAL_MS,
            max = 62, step = 16, offset = 0,
            format = { v -> getString(R.string.settings_capture_min_interval_value, v) },
        )

        // 持续抓屏开关（默认开；SystemUI 侧读取，收起时周期兜底抓屏）
        val switchPeriodic = view.findViewById<Switch>(R.id.switchPeriodicCapture)
        val periodicListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_BG_PERIODIC_CAPTURE_ENABLE, checked)
        }
        switchPeriodic.setOnCheckedChangeListener(periodicListener)
        refreshBooleanSwitch(
            switchPeriodic, periodicListener,
            Prefs.KEY_BG_PERIODIC_CAPTURE_ENABLE, Prefs.DEFAULT_BG_PERIODIC_CAPTURE_ENABLE
        )
        // 持续抓屏间隔：100~5000ms（默认 500；clamp 下限 100 防过度抓屏，上限 5000）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekPeriodicCaptureInterval), valueText = view.findViewById(R.id.periodicCaptureIntervalValue),
            key = Prefs.KEY_BG_PERIODIC_CAPTURE_INTERVAL_MS, default = Prefs.DEFAULT_BG_PERIODIC_CAPTURE_INTERVAL_MS,
            max = 4900, step = 1, offset = Prefs.BG_PERIODIC_CAPTURE_INTERVAL_MIN_MS,
            format = { ms -> getString(R.string.settings_periodic_capture_interval_value, ms) },
        )

        // [2026-08-14 语义收窄] 通知横幅抓屏速率滑杆（KEY_PANEL_CAPTURE_HZ，30~240Hz，默认 120；
        // 横幅/内容运动时满速、静止时减半 60Hz；下拉面板已改系统 onBlurReady 驱动不占此速率；
        // 改动 ≤1s 生效，SystemUI 侧 IPC 节流缓存读）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekPanelCaptureHz), valueText = view.findViewById(R.id.panelCaptureHzValue),
            key = Prefs.KEY_PANEL_CAPTURE_HZ, default = Prefs.DEFAULT_PANEL_CAPTURE_HZ,
            max = 210, step = 1, offset = 30,
            format = { v -> getString(R.string.settings_panel_capture_hz_value, v) },
        )

        // 降采样档位 spinner（0.25/0.5/0.75/1.0，默认 0.5）
        bindScaleSpinner(view)

        // 重试间隔：100~1000ms（默认 320）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekCaptureRetryMs), valueText = view.findViewById(R.id.captureRetryMsValue),
            key = Prefs.KEY_BG_CAPTURE_RETRY_MS, default = Prefs.DEFAULT_BG_CAPTURE_RETRY_MS,
            max = 900, step = 1, offset = 100,
            format = { v -> getString(R.string.settings_capture_retry_ms_value, v) },
        )
        // 重试次数：1~5（默认 3）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekCaptureRetryLimit), valueText = view.findViewById(R.id.captureRetryLimitValue),
            key = Prefs.KEY_BG_CAPTURE_RETRY_LIMIT, default = Prefs.DEFAULT_BG_CAPTURE_RETRY_LIMIT,
            max = 4, step = 1, offset = 1,
            format = { v -> getString(R.string.settings_capture_retry_limit_value, v) },
        )

        // [spec/42] Launcher 文件夹背景只抓壁纸层（默认开；改动即时生效）
        val switchUid = view.findViewById<Switch>(R.id.switchUidFilter)
        val uidListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_BG_CAPTURE_UID_FILTER, checked)
        }
        switchUid.setOnCheckedChangeListener(uidListener)
        refreshBooleanSwitch(
            switchUid, uidListener,
            Prefs.KEY_BG_CAPTURE_UID_FILTER, Prefs.DEFAULT_BG_CAPTURE_UID_FILTER
        )

        // [spec/52] 壁纸文件解码背景源开关（LauncherHook 读）
        val switchWallpaper = view.findViewById<Switch>(R.id.switchWallpaperFileSource)
        val wallpaperListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_BG_WALLPAPER_FILE_SOURCE, checked)
        }
        switchWallpaper.setOnCheckedChangeListener(wallpaperListener)
        refreshBooleanSwitch(
            switchWallpaper, wallpaperListener,
            Prefs.KEY_BG_WALLPAPER_FILE_SOURCE, Prefs.DEFAULT_BG_WALLPAPER_FILE_SOURCE
        )
        // 壁纸解码最大边长（512~8192，步进 8）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekWallpaperMaxDim), valueText = view.findViewById(R.id.wallpaperMaxDimValue),
            key = Prefs.KEY_BG_WALLPAPER_MAX_DIM, default = Prefs.DEFAULT_BG_WALLPAPER_MAX_DIM,
            max = 960, step = 8, offset = 512,
            format = { v -> getString(R.string.settings_bg_wallpaper_max_dim_value, v) },
        )

        // [spec/61] 系统控制中心模糊力度（0~200%，默认 100=系统原值；开启时阻止界面缩小）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekCcBlurStrength), valueText = view.findViewById(R.id.ccBlurStrengthValue),
            key = Prefs.KEY_CC_BLUR_STRENGTH, default = Prefs.DEFAULT_CC_BLUR_STRENGTH,
            max = 200, step = 1, offset = 0,
            format = { v -> getString(R.string.settings_cc_blur_strength_value, v) },
        )
        // 保留系统控制中心模糊（默认开）
        val switchKeepBlur = view.findViewById<Switch>(R.id.switchKeepSystemCcBlur)
        val keepBlurListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_KEEP_SYSTEM_CC_BLUR, checked)
        }
        switchKeepBlur.setOnCheckedChangeListener(keepBlurListener)
        refreshBooleanSwitch(
            switchKeepBlur, keepBlurListener,
            Prefs.KEY_KEEP_SYSTEM_CC_BLUR, Prefs.DEFAULT_KEEP_SYSTEM_CC_BLUR
        )

        // [2026-09-16 面板材质底色] 移除系统面板背景那层 MixColor 灰底（默认开；模糊保留）
        val switchRemoveMixColor = view.findViewById<Switch>(R.id.switchRemovePanelMixColor)
        val removeMixColorListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_REMOVE_PANEL_MIX_COLOR, checked)
        }
        switchRemoveMixColor.setOnCheckedChangeListener(removeMixColorListener)
        refreshBooleanSwitch(
            switchRemoveMixColor, removeMixColorListener,
            Prefs.KEY_REMOVE_PANEL_MIX_COLOR, Prefs.DEFAULT_REMOVE_PANEL_MIX_COLOR
        )
    }

    /** 文字与可读性：文字反色 / 弹出通知文字跟随。 */
    private fun bindText(view: View) {
        // 文字背景反转取色开关（doc/spec/21；改动需重启 SystemUI 生效）
        val switchTextContrast = view.findViewById<Switch>(R.id.switchTextContrast)
        val textContrastListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_TEXT_CONTRAST_ENABLE, checked)
        }
        switchTextContrast.setOnCheckedChangeListener(textContrastListener)
        refreshBooleanSwitch(
            switchTextContrast, textContrastListener,
            Prefs.KEY_TEXT_CONTRAST_ENABLE, Prefs.DEFAULT_TEXT_CONTRAST_ENABLE
        )

        // [spec/47] 弹出通知文字跟随整体颜色（与遮罩无关，默认开）
        val switchHeadsUp = view.findViewById<Switch>(R.id.switchHeadsUpTextFollow)
        val headsUpListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_HEADS_UP_TEXT_FOLLOW, checked)
        }
        switchHeadsUp.setOnCheckedChangeListener(headsUpListener)
        refreshBooleanSwitch(
            switchHeadsUp, headsUpListener,
            Prefs.KEY_HEADS_UP_TEXT_FOLLOW, Prefs.DEFAULT_HEADS_UP_TEXT_FOLLOW
        )

        // [spec/60] 文字周期强制刷新间隔（0~5000ms，默认 500；0=关闭周期刷新，仅事件触发；
        // tick 每次读最新值，改动即时生效）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekTextForceRefresh), valueText = view.findViewById(R.id.textForceRefreshValue),
            key = Prefs.KEY_TEXT_FORCE_REFRESH_INTERVAL_MS, default = Prefs.DEFAULT_TEXT_FORCE_REFRESH_INTERVAL_MS,
            max = 5000, step = 1, offset = 0,
            format = { v -> getString(R.string.settings_text_force_refresh_interval_value, v) },
        )
    }

    /** 遮罩：黑遮罩开关 / 不透明度 / 文字颜色。 */
    private fun bindMask(view: View) {
        // [spec/35+40+41] 黑遮罩开关（默认开；改动需重启 SystemUI 生效）
        val switchMask = view.findViewById<Switch>(R.id.switchMask)
        val maskListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_MASK_ENABLE, checked)
        }
        switchMask.setOnCheckedChangeListener(maskListener)
        refreshBooleanSwitch(switchMask, maskListener, Prefs.KEY_MASK_ENABLE, Prefs.DEFAULT_MASK_ENABLE)

        // [spec/63 文字强制白独立开关 2026-08-26]（默认开；与遮罩独立，可只要白字不要压暗 / 单独关解决闪动；改动需重启 SystemUI 生效）
        val switchTextForceWhite = view.findViewById<Switch>(R.id.switchTextForceWhite)
        val textForceWhiteListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_TEXT_FORCE_WHITE_ENABLE, checked)
        }
        switchTextForceWhite.setOnCheckedChangeListener(textForceWhiteListener)
        refreshBooleanSwitch(switchTextForceWhite, textForceWhiteListener, Prefs.KEY_TEXT_FORCE_WHITE_ENABLE, Prefs.DEFAULT_TEXT_FORCE_WHITE_ENABLE)

        // 遮罩不透明度滑杆（0~100 → 0.00~1.00，默认 0.45）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekMaskAlpha), valueText = view.findViewById(R.id.maskAlphaValue),
            key = Prefs.KEY_MASK_ALPHA, default = Prefs.DEFAULT_MASK_ALPHA,
            fromProgress = { p -> p / 100f },
            toProgress = { v -> (v * 100f).roundToInt().coerceIn(0, 100) },
            format = { v -> getString(R.string.settings_mask_alpha_value, v) },
        )

        // 遮罩下文字颜色 spinner（纯白/浅灰/灰 → ARGB int）
        bindMaskTextColorSpinner(view)
    }

    /** 流体云：展开卡片玻璃化 + 判定参数（spec/44）。 */
    private fun bindSeedling(view: View) {
        // 流体云展开卡片玻璃化开关（默认关；改动需重启 SystemUI 生效）
        val switchSeedling = view.findViewById<Switch>(R.id.switchSeedlingCardGlass)
        val seedlingListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_SEEDLING_CARD_GLASS_ENABLE, checked)
        }
        switchSeedling.setOnCheckedChangeListener(seedlingListener)
        refreshBooleanSwitch(
            switchSeedling, seedlingListener,
            Prefs.KEY_SEEDLING_CARD_GLASS_ENABLE, Prefs.DEFAULT_SEEDLING_CARD_GLASS_ENABLE
        )

        // [2026-09-16 用户需求变更] 小胶囊（小岛）也玻璃化（默认开）
        val switchCapsule = view.findViewById<Switch>(R.id.switchSeedlingCapsuleGlass)
        val capsuleListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_SEEDLING_CAPSULE_GLASS, checked)
        }
        switchCapsule.setOnCheckedChangeListener(capsuleListener)
        refreshBooleanSwitch(
            switchCapsule, capsuleListener,
            Prefs.KEY_SEEDLING_CAPSULE_GLASS, Prefs.DEFAULT_SEEDLING_CAPSULE_GLASS
        )

        // 展开大卡片判定高度系数（0.5~3.0，默认 1.2）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekSeedlingExpandRatio), valueText = view.findViewById(R.id.seedlingExpandRatioValue),
            key = Prefs.KEY_SEEDLING_CARD_EXPAND_HEIGHT_RATIO, default = Prefs.DEFAULT_SEEDLING_CARD_EXPAND_HEIGHT_RATIO,
            fromProgress = { p -> 0.5f + p / 100f },
            toProgress = { v -> ((v - 0.5f) * 100f).roundToInt().coerceIn(0, 250) },
            format = { v -> getString(R.string.settings_seedling_expand_ratio_value, v) },
        )
        // 展开大卡片圆角启发式系数（0.05~0.50，默认 0.22）
        bindFloatSeekBar(
            seek = view.findViewById(R.id.seekSeedlingCornerRatio), valueText = view.findViewById(R.id.seedlingCornerRatioValue),
            key = Prefs.KEY_SEEDLING_CARD_CORNER_RATIO, default = Prefs.DEFAULT_SEEDLING_CARD_CORNER_RATIO,
            fromProgress = { p -> 0.05f + p / 1000f },
            toProgress = { v -> ((v - 0.05f) * 1000f).roundToInt().coerceIn(0, 450) },
            format = { v -> getString(R.string.settings_seedling_corner_ratio_value, v) },
        )
        // 透明宿主卡片 background（默认开：用户硬需求，透明让玻璃透出；关闭恢复系统半透明背景）
        val switchForceT = view.findViewById<Switch>(R.id.switchSeedlingForceTransparentBg)
        val forceListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_SEEDLING_CARD_FORCE_TRANSPARENT_BG, checked)
        }
        switchForceT.setOnCheckedChangeListener(forceListener)
        refreshBooleanSwitch(
            switchForceT, forceListener,
            Prefs.KEY_SEEDLING_CARD_FORCE_TRANSPARENT_BG, Prefs.DEFAULT_SEEDLING_CARD_FORCE_TRANSPARENT_BG
        )
    }

    // [spec/81 2026-09-17] bindLockscreen 已删除：锁屏时钟 hook 真机未命中数字 View，
    // 功能整体停用，「锁屏」分类页及其入口一并移除。

    /** [doc/spec/66] 桌面大类：Dock 栏液态玻璃化（默认关；Launcher 侧 TTL 500ms 重读，改动无需重启桌面）。 */
    private fun bindDesktop(view: View) {
        val switchDock = view.findViewById<Switch>(R.id.switchDockGlass)
        val dockListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_DOCK_GLASS_ENABLE, checked)
        }
        switchDock.setOnCheckedChangeListener(dockListener)
        refreshBooleanSwitch(
            switchDock, dockListener,
            Prefs.KEY_DOCK_GLASS_ENABLE, Prefs.DEFAULT_DOCK_GLASS_ENABLE
        )
    }

    // ------------------------------------------------------------ [spec/64] 桌面动画参数 ------------------------------------------------------------

    /** 桌面动画二级页：三组开关 + 三组参数入口。 */
    private fun bindAnim(view: View) {
        bindAnimGroupSwitch(view, R.id.switchAnimOpen, Prefs.ANIM_GROUP_OPEN)
        bindAnimGroupSwitch(view, R.id.switchAnimClose, Prefs.ANIM_GROUP_CLOSE)
        bindAnimGroupSwitch(view, R.id.switchAnimBreak, Prefs.ANIM_GROUP_BREAK)
        view.findViewById<View>(R.id.rowAnimOpen).setOnClickListener { openAnimParams(Prefs.ANIM_GROUP_OPEN) }
        view.findViewById<View>(R.id.rowAnimClose).setOnClickListener { openAnimParams(Prefs.ANIM_GROUP_CLOSE) }
        view.findViewById<View>(R.id.rowAnimBreak).setOnClickListener { openAnimParams(Prefs.ANIM_GROUP_BREAK) }
    }

    /** 单个动画组的启用开关（默认 false = 沿用原版动画）。 */
    private fun bindAnimGroupSwitch(view: View, switchId: Int, group: String) {
        val switch = view.findViewById<Switch>(switchId)
        val key = Prefs.animEnabledKey(group)
        val listener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(key, checked)
        }
        switch.setOnCheckedChangeListener(listener)
        refreshBooleanSwitch(switch, listener, key, Prefs.DEFAULT_ANIM_GROUP_ENABLED)
    }

    /** 三级参数页：启用开关 + 4 组（阻尼/刚度）+ 图标透明度时长。键名按组动态拼装。 */
    private fun bindAnimParams(view: View, group: String) {
        bindAnimGroupSwitch(view, R.id.switchAnimEnabled, group)
        bindAnimPresets(view, group)
        bindAnimDamping(view, R.id.seekAnimXDamping, R.id.animXDampingValue, group, Prefs.ANIM_PARAM_X_DAMPING)
        bindAnimStiffness(view, R.id.seekAnimXStiffness, R.id.animXStiffnessValue, group, Prefs.ANIM_PARAM_X_STIFFNESS)
        bindAnimDamping(view, R.id.seekAnimYDamping, R.id.animYDampingValue, group, Prefs.ANIM_PARAM_Y_DAMPING)
        bindAnimStiffness(view, R.id.seekAnimYStiffness, R.id.animYStiffnessValue, group, Prefs.ANIM_PARAM_Y_STIFFNESS)
        bindAnimDamping(view, R.id.seekAnimWidthDamping, R.id.animWidthDampingValue, group, Prefs.ANIM_PARAM_WIDTH_DAMPING)
        bindAnimStiffness(view, R.id.seekAnimWidthStiffness, R.id.animWidthStiffnessValue, group, Prefs.ANIM_PARAM_WIDTH_STIFFNESS)
        bindAnimDamping(view, R.id.seekAnimHeightDamping, R.id.animHeightDampingValue, group, Prefs.ANIM_PARAM_HEIGHT_DAMPING)
        bindAnimStiffness(view, R.id.seekAnimHeightStiffness, R.id.animHeightStiffnessValue, group, Prefs.ANIM_PARAM_HEIGHT_STIFFNESS)
        // 图标透明度时长：50~1500ms，步进 10（对应 anim_<组>_fade_duration）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekAnimFadeDuration), valueText = view.findViewById(R.id.animFadeDurationValue),
            key = Prefs.animKey(group, Prefs.ANIM_PARAM_FADE_DURATION), default = Prefs.animDefaultFadeDuration(group),
            max = (Prefs.ANIM_FADE_DURATION_MAX - Prefs.ANIM_FADE_DURATION_MIN) / Prefs.ANIM_FADE_DURATION_STEP,
            step = Prefs.ANIM_FADE_DURATION_STEP, offset = Prefs.ANIM_FADE_DURATION_MIN,
            format = { v -> getString(R.string.settings_anim_fade_value, v) },
        )
    }

    /** 阻尼滑杆（10~300，默认按组；运行时 ÷100 → 0.05~5.0，显示实际生效值）。 */
    private fun bindAnimDamping(view: View, seekId: Int, valueId: Int, group: String, param: String) {
        bindIntSeekBarStep(
            seek = view.findViewById(seekId), valueText = view.findViewById(valueId),
            key = Prefs.animKey(group, param), default = Prefs.animDefaultDamping(group),
            max = Prefs.ANIM_DAMPING_UI_MAX - Prefs.ANIM_DAMPING_UI_MIN, step = 1, offset = Prefs.ANIM_DAMPING_UI_MIN,
            format = { v ->
                val actual = (v / Prefs.ANIM_DAMPING_DIVISOR)
                    .coerceIn(Prefs.ANIM_DAMPING_RUNTIME_MIN, Prefs.ANIM_DAMPING_RUNTIME_MAX)
                getString(R.string.settings_anim_damping_value, v, actual)
            },
        )
    }

    /** 刚度滑杆（1~3000，默认按组；运行时 clamp 1~5000）。 */
    private fun bindAnimStiffness(view: View, seekId: Int, valueId: Int, group: String, param: String) {
        bindIntSeekBarStep(
            seek = view.findViewById(seekId), valueText = view.findViewById(valueId),
            key = Prefs.animKey(group, param), default = Prefs.animDefaultStiffness(group),
            max = Prefs.ANIM_STIFFNESS_UI_MAX - Prefs.ANIM_STIFFNESS_UI_MIN, step = 1, offset = Prefs.ANIM_STIFFNESS_UI_MIN,
            format = { v -> getString(R.string.settings_anim_stiffness_value, v) },
        )
    }

    /** [spec/64] 一键预设 Chip + 「同步打开」按钮（后者仅打断组可见）。 */
    private fun bindAnimPresets(view: View, group: String) {
        val chips = view.findViewById<ChipGroup>(R.id.animPresetChips)
        chips.removeAllViews()
        animPresets.forEach { preset ->
            chips.addView(Chip(view.context).apply {
                text = getString(preset.labelRes)
                isCheckable = false
                setOnClickListener { applyAnimPreset(group, preset) }
            })
        }
        // 打断动画大多与打开动画同参，提供手工「同步打开」按钮（不做自动联动）
        val isBreak = group == Prefs.ANIM_GROUP_BREAK
        val syncBtn = view.findViewById<View>(R.id.btnSyncFromOpen)
        val syncDesc = view.findViewById<View>(R.id.txtSyncFromOpenDesc)
        syncBtn.visibility = if (isBreak) View.VISIBLE else View.GONE
        syncDesc.visibility = if (isBreak) View.VISIBLE else View.GONE
        syncBtn.setOnClickListener {
            if (copyAnimGroup(from = Prefs.ANIM_GROUP_OPEN, to = Prefs.ANIM_GROUP_BREAK)) {
                bindCurrentPage()
                Toast.makeText(this, R.string.settings_anim_sync_done, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.settings_anim_sync_not_ready, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 把预设档位写入本组全部 9 个参数，并刷新滑块回显。 */
    private fun applyAnimPreset(group: String, preset: AnimPreset) {
        val stiffness = preset.stiffness ?: Prefs.animDefaultStiffness(group)
        val damping = preset.damping ?: Prefs.animDefaultDamping(group)
        val fade = preset.fade ?: Prefs.animDefaultFadeDuration(group)
        // ANIM_SPRING_PARAMS 顺序为「阻尼,刚度」交替，以 _stiffness 后缀区分
        Prefs.ANIM_SPRING_PARAMS.forEach { param ->
            writePrefInt(Prefs.animKey(group, param), if (param.endsWith("_stiffness")) stiffness else damping)
        }
        writePrefInt(Prefs.animKey(group, Prefs.ANIM_PARAM_FADE_DURATION), fade)
        bindCurrentPage()
        Toast.makeText(
            this,
            getString(R.string.settings_anim_preset_applied, getString(preset.labelRes)),
            Toast.LENGTH_SHORT,
        ).show()
    }

    /**
     * 整组复制弹簧参数：读 [from] 已落盘的值写入 [to]，缺失项回退到 [from] 组的默认值。
     *
     * 框架未就绪时**返回 false 且不写入** —— 不可降级读 [xmlPrefs]：
     * hook 端读不到 XML，且 `anim_*` 键只存在于框架侧存储，
     * 降级读必然拿到默认值，再经 [writePrefInt] 排队落盘将**静默覆盖**用户配置。
     */
    private fun copyAnimGroup(from: String, to: String): Boolean {
        val prefs = frameworkPrefs() ?: return false
        Prefs.ANIM_SPRING_PARAMS.forEach { param ->
            val def = if (param.endsWith("_stiffness")) {
                Prefs.animDefaultStiffness(from)
            } else {
                Prefs.animDefaultDamping(from)
            }
            writePrefInt(Prefs.animKey(to, param), Prefs.readIntCompat(prefs, Prefs.animKey(from, param), def))
        }
        writePrefInt(
            Prefs.animKey(to, Prefs.ANIM_PARAM_FADE_DURATION),
            Prefs.readIntCompat(
                prefs,
                Prefs.animKey(from, Prefs.ANIM_PARAM_FADE_DURATION),
                Prefs.animDefaultFadeDuration(from),
            ),
        )
        return true
    }

    /** 动画组 → 标题资源。 */
    private fun animGroupTitleRes(group: String): Int = when (group) {
        Prefs.ANIM_GROUP_CLOSE -> R.string.settings_anim_close_enabled
        Prefs.ANIM_GROUP_BREAK -> R.string.settings_anim_break_enabled
        else -> R.string.settings_anim_open_enabled
    }

    // ------------------------------------------------------------ [spec/65] iOS 动态倾斜/透视（实验性） ------------------------------------------------------------

    /** 倾斜与透视二级页：开关 + 倾斜强度 + 透视扭曲程度。改动需重启桌面进程生效。 */
    private fun bindTilt(view: View) {
        val switch = view.findViewById<Switch>(R.id.switchAnimTilt)
        val listener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_ANIM_TILT_ENABLED, checked)
        }
        switch.setOnCheckedChangeListener(listener)
        refreshBooleanSwitch(switch, listener, Prefs.KEY_ANIM_TILT_ENABLED, Prefs.DEFAULT_ANIM_TILT_ENABLED)

        // 倾斜强度：0~200%（默认 100）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekAnimTiltStrength), valueText = view.findViewById(R.id.animTiltStrengthValue),
            key = Prefs.KEY_ANIM_TILT_STRENGTH, default = Prefs.DEFAULT_ANIM_TILT_STRENGTH,
            max = Prefs.ANIM_TILT_STRENGTH_MAX, step = 1, offset = 0,
            format = { v -> getString(R.string.settings_anim_tilt_strength_value, v) },
        )
        // 透视扭曲程度：0~150%（默认 100）
        bindIntSeekBarStep(
            seek = view.findViewById(R.id.seekAnimTiltPerspective), valueText = view.findViewById(R.id.animTiltPerspectiveValue),
            key = Prefs.KEY_ANIM_TILT_PERSPECTIVE, default = Prefs.DEFAULT_ANIM_TILT_PERSPECTIVE,
            max = Prefs.ANIM_TILT_PERSPECTIVE_MAX, step = 1, offset = 0,
            format = { v -> getString(R.string.settings_anim_tilt_perspective_value, v) },
        )
    }

    // [2026-09-17] bindTracker 已删除：追踪器（通用运动追踪开关 + 三个遍历间隔 SeekBar）整体移除。

    /** 日志与诊断。 */
    private fun bindLog(view: View) {
        // 模块日志开关（默认开；改动需重启 SystemUI 生效）
        val switchLogs = view.findViewById<Switch>(R.id.switchEnableLogs)
        val logsListener = CompoundButton.OnCheckedChangeListener { _, checked ->
            writePrefBoolean(Prefs.KEY_ENABLE_LOGS, checked)
        }
        switchLogs.setOnCheckedChangeListener(logsListener)
        refreshBooleanSwitch(switchLogs, logsListener, Prefs.KEY_ENABLE_LOGS, Prefs.DEFAULT_ENABLE_LOGS)
    }

    /** 关于：TG/GitHub 完整网址点击跳转浏览器，QQ/开发者点击复制。 */
    private fun bindAbout(view: View) {
        // TG 频道完整网址 → 点击跳转浏览器；失败回退复制
        val tgRow = view.findViewById<View>(R.id.aboutTgRow)
        tgRow.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/c16lg666")))
            } catch (t: Throwable) {
                Log.w(TAG, "about open tg failed, fallback copy", t)
                bindCopyRow(tgRow, getString(R.string.about_tg))
            }
        }
        bindCopyRow(view.findViewById(R.id.aboutQqRow), getString(R.string.about_qq))
        bindCopyRow(view.findViewById(R.id.aboutDeveloperRow), getString(R.string.about_developer))
        // GitHub 完整网址 → 点击跳转浏览器；失败回退复制
        val githubRow = view.findViewById<View>(R.id.aboutGithubRow)
        githubRow.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/AYwlilwYA/ColorOS16-LiquidGlass")))
            } catch (t: Throwable) {
                Log.w(TAG, "about open github failed, fallback copy", t)
                bindCopyRow(githubRow, getString(R.string.about_github))
            }
        }
    }

    /** 给关于条目绑定点击复制（点击复制整行文案到剪贴板）。 */
    private fun bindCopyRow(row: View, text: String) {
        row.setOnClickListener {
            try {
                val cm = getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("about", text))
                Toast.makeText(this, getString(R.string.about_copy_hint), Toast.LENGTH_SHORT).show()
            } catch (t: Throwable) {
                Log.w(TAG, "about copy failed", t)
            }
        }
    }

    // ------------------------------------------------------------ 存储访问 ------------------------------------------------------------

    /** 框架侧存储（与 hook 端 getRemotePreferences 同一份；框架未注入/未就绪时为 null）。 */
    private fun frameworkPrefs(): SharedPreferences? =
        App.serviceRef?.let { Prefs.remote(it) }

    /** 按框架侧存储（权威值）刷新总开关回显；刷新不触发监听器写入。 */
    private fun refreshMasterSwitch(switch: Switch, listener: CompoundButton.OnCheckedChangeListener) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = masterEnabled()
        switch.setOnCheckedChangeListener(listener)
    }

    /** 读总开关：优先框架侧存储，回退本地 XML。 */
    private fun masterEnabled(): Boolean =
        frameworkPrefs()?.getBoolean(Prefs.KEY_MASTER, false)
            ?: xmlPrefs.getBoolean(Prefs.KEY_MASTER, false)

    /** 写总开关：写入框架侧存储（hook 端可读）；框架未就绪时排队等待就绪后再写。 */
    private fun writeMasterEnabled(checked: Boolean) {
        val framework = frameworkPrefs()
        if (framework != null) {
            Log.i(TAG, "write master_enabled=$checked -> framework prefs")
            framework.edit().putBoolean(Prefs.KEY_MASTER, checked).apply()
            return
        }
        Log.w(TAG, "framework service not ready yet, queue write master_enabled=$checked")
        val pending = object : App.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                if (service != null) {
                    App.removeServiceStateListener(this)
                    Log.i(TAG, "queued write master_enabled=$checked -> framework prefs")
                    Prefs.remote(service).edit().putBoolean(Prefs.KEY_MASTER, checked).apply()
                }
            }
        }
        App.addServiceStateListener(pending, true)
    }

    // ------------------------------------------------------------ 通用布尔开关 ------------------------------------------------------------

    /** 按框架侧存储（权威值）刷新布尔开关回显；刷新不触发监听器写入。 */
    private fun refreshBooleanSwitch(
        switch: Switch,
        listener: CompoundButton.OnCheckedChangeListener,
        key: String,
        default: Boolean,
    ) {
        switch.setOnCheckedChangeListener(null)
        switch.isChecked = frameworkPrefs()?.getBoolean(key, default) ?: xmlPrefs.getBoolean(key, default)
        switch.setOnCheckedChangeListener(listener)
    }

    /** 写布尔配置到框架侧存储（hook 端可读）；框架未就绪时排队等待就绪后再写。 */
    private fun writePrefBoolean(key: String, value: Boolean) {
        val framework = frameworkPrefs()
        if (framework != null) {
            Log.i(TAG, "write $key=$value -> framework prefs")
            framework.edit().putBoolean(key, value).apply()
            return
        }
        Log.w(TAG, "framework service not ready yet, queue write $key=$value")
        val pending = object : App.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                if (service != null) {
                    App.removeServiceStateListener(this)
                    Log.i(TAG, "queued write $key=$value -> framework prefs")
                    Prefs.remote(service).edit().putBoolean(key, value).apply()
                }
            }
        }
        App.addServiceStateListener(pending, true)
    }

    // ------------------------------------------------------------ 通用 String 写入（背景档位） ------------------------------------------------------------

    /** 写 String 配置到框架侧存储；框架未就绪时排队等待就绪后再写。 */
    private fun writePrefString(key: String, value: String) {
        val framework = frameworkPrefs()
        if (framework != null) {
            Log.i(TAG, "write $key=$value -> framework prefs")
            framework.edit().putString(key, value).apply()
            return
        }
        Log.w(TAG, "framework service not ready yet, queue write $key=$value")
        val pending = object : App.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                if (service != null) {
                    App.removeServiceStateListener(this)
                    Log.i(TAG, "queued write $key=$value -> framework prefs")
                    Prefs.remote(service).edit().putString(key, value).apply()
                }
            }
        }
        App.addServiceStateListener(pending, true)
    }

    // ------------------------------------------------------------ 液态玻璃材质滑杆 ------------------------------------------------------------

    /** 绑定单个 float 材质滑杆（回显 + 拖动结束写 framework prefs）。 */
    private fun bindFloatSeekBar(
        seek: SeekBar,
        valueText: TextView,
        key: String,
        default: Float,
        fromProgress: (Int) -> Float,
        toProgress: (Float) -> Int,
        format: (Float) -> String,
    ) {
        val current = frameworkPrefs()?.getFloat(key, default) ?: xmlPrefs.getFloat(key, default)
        seek.progress = toProgress(current)
        valueText.text = format(fromProgress(seek.progress))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                valueText.text = format(fromProgress(progress))
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                writePrefFloat(key, fromProgress(sb.progress))
            }
        })
    }

    /** 写 float 配置到框架侧存储（hook 端可读）；框架未就绪时排队等待就绪后再写。 */
    private fun writePrefFloat(key: String, value: Float) {
        val framework = frameworkPrefs()
        if (framework != null) {
            Log.i(TAG, "write $key=$value -> framework prefs")
            framework.edit().putFloat(key, value).apply()
            return
        }
        Log.w(TAG, "framework service not ready yet, queue write $key=$value")
        val pending = object : App.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                if (service != null) {
                    App.removeServiceStateListener(this)
                    Log.i(TAG, "queued write $key=$value -> framework prefs")
                    Prefs.remote(service).edit().putFloat(key, value).apply()
                }
            }
        }
        App.addServiceStateListener(pending, true)
    }

    // ------------------------------------------------------------ 通用 int 滑杆 ------------------------------------------------------------

    /** 绑定单个 int 滑杆（0~max，回显 + 拖动结束写 framework prefs）。 */
    private fun bindIntSeekBar(
        seek: SeekBar,
        valueText: TextView,
        key: String,
        default: Int,
        max: Int,
        format: (Int) -> String,
    ) {
        seek.max = max
        // [2026-08-13 类型修复] int 配置兼容读（存量 Float → getInt 抛；readIntCompat 回退 getFloat）
        val current = frameworkPrefs()?.let { Prefs.readIntCompat(it, key, default) }
            ?: Prefs.readIntCompat(xmlPrefs, key, default)
        seek.progress = current.coerceIn(0, max)
        valueText.text = format(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                valueText.text = format(progress)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                writePrefInt(key, sb.progress)
            }
        })
    }

    /** 带步进/偏移的 int 滑杆（progress → value = progress*step + offset，反向 value → progress）。
     *  [2026-08-13 类型修复] 替代 bindFloatSeekBar 写 int 配置（原 Float 写入 → 读端 getInt ClassCastException）。 */
    private fun bindIntSeekBarStep(
        seek: SeekBar,
        valueText: TextView,
        key: String,
        default: Int,
        max: Int,
        step: Int,
        offset: Int,
        format: (Int) -> String,
    ) {
        seek.max = max
        // [2026-08-13 类型修复] int 配置兼容读（存量 Float → getInt 抛；readIntCompat 回退 getFloat）
        val current = frameworkPrefs()?.let { Prefs.readIntCompat(it, key, default) }
            ?: Prefs.readIntCompat(xmlPrefs, key, default)
        val progress = ((current - offset) / step).coerceIn(0, max)
        seek.progress = progress
        valueText.text = format(progress * step + offset)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                valueText.text = format(progress * step + offset)
            }

            override fun onStartTrackingTouch(sb: SeekBar) {
            }

            override fun onStopTrackingTouch(sb: SeekBar) {
                writePrefInt(key, sb.progress * step + offset)
            }
        })
    }

    /** 写 int 配置到框架侧存储（hook 端可读）；框架未就绪时排队等待就绪后再写。 */
    private fun writePrefInt(key: String, value: Int) {
        val framework = frameworkPrefs()
        if (framework != null) {
            Log.i(TAG, "write $key=$value -> framework prefs")
            framework.edit().putInt(key, value).apply()
            return
        }
        Log.w(TAG, "framework service not ready yet, queue write $key=$value")
        val pending = object : App.ServiceStateListener {
            override fun onServiceStateChanged(service: XposedService?) {
                if (service != null) {
                    App.removeServiceStateListener(this)
                    Log.i(TAG, "queued write $key=$value -> framework prefs")
                    Prefs.remote(service).edit().putInt(key, value).apply()
                }
            }
        }
        App.addServiceStateListener(pending, true)
    }

    // ------------------------------------------------------------ 降采样 spinner ------------------------------------------------------------

    /** 降采样档位 spinner 回显 + 写入（0.25/0.5/0.75/1.0；先 setSelection 再挂监听，避免回显触发写入）。 */
    private fun bindScaleSpinner(view: View) {
        val spinner = view.findViewById<Spinner>(R.id.spinnerCaptureScale)
        val scales = listOf(0.25f, 0.5f, 0.75f, 1.0f)
        val labels = scales.map { getString(R.string.settings_capture_scale_value, it) }
        spinner.adapter = ArrayAdapter(
            this@SettingsActivity, android.R.layout.simple_spinner_item, labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val current = frameworkPrefs()?.getFloat(Prefs.KEY_BG_CAPTURE_SCALE, Prefs.DEFAULT_BG_CAPTURE_SCALE)
            ?: xmlPrefs.getFloat(Prefs.KEY_BG_CAPTURE_SCALE, Prefs.DEFAULT_BG_CAPTURE_SCALE)
        val idx = scales.indexOfFirst { Math.abs(it - current) < 0.01f }.coerceAtLeast(0)
        spinner.setSelection(idx)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                writePrefFloat(Prefs.KEY_BG_CAPTURE_SCALE, scales[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    /** [spec/35] 遮罩下文字颜色 spinner 回显 + 写入（纯白/浅灰/灰 → ARGB int；先 setSelection 再挂监听，避免回显触发写入）。
     *  改动需重启 SystemUI 生效（hook 端 install 时读取并缓存）。 */
    private fun bindMaskTextColorSpinner(view: View) {
        val spinner = view.findViewById<Spinner>(R.id.spinnerMaskTextColor)
        val colors = listOf(
            0xFFFFFFFF.toInt(),   // 纯白
            0xFFE6E6E6.toInt(),   // 浅灰（90% 白）
            0xFFB3B3B3.toInt(),   // 灰（70% 白）
        )
        val labels = listOf(
            getString(R.string.settings_mask_text_color_white),
            getString(R.string.settings_mask_text_color_lightgray),
            getString(R.string.settings_mask_text_color_gray),
        )
        spinner.adapter = ArrayAdapter(
            this@SettingsActivity, android.R.layout.simple_spinner_item, labels
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val current = frameworkPrefs()?.getInt(Prefs.KEY_MASK_TEXT_COLOR, Prefs.DEFAULT_MASK_TEXT_COLOR)
            ?: xmlPrefs.getInt(Prefs.KEY_MASK_TEXT_COLOR, Prefs.DEFAULT_MASK_TEXT_COLOR)
        val idx = colors.indexOfFirst { it == current }.coerceAtLeast(0)
        spinner.setSelection(idx)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                writePrefInt(Prefs.KEY_MASK_TEXT_COLOR, colors[position])
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
            }
        }
    }

    companion object {
        private const val TAG = "LiquidGlassSettings"

        /** [2026-09-17] 已「忽略此版本」的版本号（本地 XML 存储，不写框架侧——纯 UI 本地状态）。 */
        private const val XML_KEY_IGNORED_UPDATE = "ignored_update_version"
    }
}
