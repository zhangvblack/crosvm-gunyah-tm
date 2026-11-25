package com.gunyah.launcher

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.gunyah.launcher.core.GunyahManager
import com.gunyah.launcher.core.RootShell
import com.gunyah.launcher.core.VmpkgExtractor
import com.gunyah.launcher.ui.Glass
import com.gunyah.launcher.ui.GlassBackgroundView
import java.io.File

/**
 * 极简入口：两个模式（Win11 / Linux），各自独立的镜像 + 启动参数预设，各自独立启动命令。
 * 没有多余的提示卡/面板/浮层。启动成功后进入 [VmDisplayActivity] 显示 guest 桌面。
 */
class MainActivity : BaseActivity() {

    private lateinit var logView: TextView
    private lateinit var imgWin: ImageView
    private lateinit var imgLinux: ImageView
    private lateinit var hlWin: View      // 选中半区高光
    private lateinit var hlLinux: View
    private lateinit var barWin: View     // 选中底部指示条
    private lateinit var barLinux: View
    private lateinit var btnImport: Button
    private lateinit var btnIso: Button
    private lateinit var btnData: Button
    private lateinit var btnBlank: Button
    private lateinit var btnBootMode: Button  // Win11：从 PE 装系统 / 从 C 盘启动 切换
    private lateinit var btnGpuLab: Button       // 显示切换（实验室）：simplefb ⇄ GPU 3D
    private lateinit var hpStatus: TextView      // 大页池状态文字
    private lateinit var hpAction: Button        // 大页池安装/管理按钮
    private lateinit var hpProgress: ProgressBar // 下载进度条（内嵌在按钮槽里）
    // v1.9.68：大页池二合一卡第二行——工具链（在线下载，不再内置 assets）
    private lateinit var toolStatus: TextView
    private lateinit var toolAction: Button
    private lateinit var toolProgress: ProgressBar
    private var toolDownloading = false          // 防止重复触发下载

    // v1.9.70：大页池 + 工具链二合一卡改为「一行 + 每 3 秒轮播切换」，高度减半对齐其他卡
    private val cardFlipHandler = Handler(Looper.getMainLooper())
    private lateinit var hpRow: LinearLayout
    private lateinit var toolRow: LinearLayout
    private var cardPage = 0                       // 0=大页池 1=工具链
    private var cardFlipPausedUntil = 0L            // 用户交互后暂停轮播的截止时间戳
    private val cardFlipTick = object : Runnable {
        override fun run() {
            if (!isFinishing && !isPaused) {
                // 下载/安装进行中不切页，保证进度条可见
                val busy = toolDownloading ||
                        (::hpProgress.isInitialized && hpProgress.visibility == View.VISIBLE) ||
                        (::toolProgress.isInitialized && toolProgress.visibility == View.VISIBLE)
                if (!busy && System.currentTimeMillis() >= cardFlipPausedUntil) {
                    showCardPage(if (cardPage == 0) 1 else 0)
                }
            }
            cardFlipHandler.removeCallbacks(this)
            cardFlipHandler.postDelayed(this, 3000L)
        }
    }
    // v1.9.7：记录大页模块最新状态，供按钮按状态分派（安装 / 一键修复 / 管理）
    private var hpInstalled = false
    private var hpLoaded = false
    // v1.9.12：模块装好待重启加载 .ko，此状态下按钮变「重启手机」
    private var hpNeedReboot = false
    // v1.9.26：池容量目标 < 4G 或未按配置预留，此状态下按钮变「设池并重启」
    private var hpNeedResize = false
    private var hpPoolTargetMB = 0
    private var hpPoolAvailMB = -1

    // 检测卡片：6 个状态格（灯 + 名称 + 值）
    private data class StatCell(val dot: View, val halo: View, val name: TextView, val value: TextView)
    private lateinit var statCells: List<StatCell>
    private val statCellViews = mutableListOf<View>()
    private lateinit var diagSummary: TextView    // 卡片顶部总结（如 5/6 就绪）

    // 自动检测：进界面/切回前台/定时刷新，不再有手动按钮
    private val diagHandler = Handler(Looper.getMainLooper())
    private var diagEverRun = false
    private val diagTick = object : Runnable {
        override fun run() {
            if (!isFinishing && !isPaused) {
                runDiagnostics(silent = diagEverRun)
                // v1.9.78：每 8 秒让工具链卡片对齐本地真实状态（内部在下载中会跳过），
                // 自愈「一次下载失败后卡片停在失败文案、但本地其实完整」的脱节。
                refreshToolStatus(autoDownload = false)
            }
            diagEverRun = true
            diagHandler.removeCallbacks(this)
            diagHandler.postDelayed(this, 8000L)
        }
    }
    private var isPaused = false

    private var mode = MODE_WIN11
    private var curLight = false   // 当前是否浅色主题（跟随系统）

    companion object {
        private const val MODE_WIN11 = 0
        private const val MODE_LINUX = 1
        private const val REQ_IMPORT = 1001
        private const val REQ_ISO = 1002
        private const val REQ_DATA = 1010
        private const val REQ_CDISK = 1003   // v1.9.4：新建C盘二合一 → 选择成品镜像作 C 盘
        private const val REQ_BOOT = 1004    // v1.9.63：启动控制台返回（失败原因回传主页）
        private const val REQ_BACKUP = 1005  // v1.9.72：选择成品镜像打包为 vmpkg 备份
        private const val SP_NAME = "gunyah_vm"
        private const val K_WIN_DISK = "win11_disk"
        private const val K_LINUX_DISK = "linux_disk"
        private const val K_LINUX_ISO = "linux_iso"       // Linux 安装光盘 ISO
        private const val K_LINUX_TARGET = "linux_target" // Linux 安装目标硬盘（空白盘）
        // Win11 装系统：PE 盘复用 K_WIN_DISK，安装光盘 + 空白 C 盘 + 启动目标独立存储
        private const val K_WIN_ISO = "win11_iso"         // Win11 安装光盘 ISO
        private const val K_DATA_DISK = "data_disk"       // 数据盘（额外存储，读写挂载）
        private const val K_WIN_TARGET = "win11_target"   // Win11 安装目标 C 盘（空白盘）
        private const val K_WIN_BOOT = "win11_boot"       // "pe"=从PE启动装系统 / "system"=从装好的C盘启动
        private const val K_GPU_LAB = "gpu_lab"           // 旧版布尔开关（兼容迁移用）
        private const val K_GPU_MODE = "gpu_mode"         // 显示模式：simplefb / iosdrm(DRM 3D)
        private const val K_RES_WIN = "res_win"           // v1.9.38：Win 模式自定义分辨率 "宽x高"，空=用预设
        private const val K_RES_LINUX = "res_linux"       // v1.9.38：Linux 模式自定义分辨率
    }

    /** v1.9.38：按模式读取自定义分辨率；空表示用预设（手机默认）。 */
    private fun customRes(m: Int): String =
        (sp.getString(if (m == MODE_WIN11) K_RES_WIN else K_RES_LINUX, "") ?: "").trim()

    private fun setCustomRes(m: Int, res: String) {
        sp.edit().putString(if (m == MODE_WIN11) K_RES_WIN else K_RES_LINUX, res.trim()).apply()
    }

    /** 当前模式实际生效的分辨率（自定义优先，否则预设）。 */
    private fun effectiveRes(m: Int): String {
        val c = customRes(m)
        return c.ifBlank { if (m == MODE_WIN11) winPreset.resolution else linuxPreset.resolution }
    }

    /** 显示模式：simplefb（稳定）/ iosdrm（GPU 3D·DRM）。花屏的 ios2d/iosgfx 已废弃。 */
    private fun gpuMode(): String {
        val saved = sp.getString(K_GPU_MODE, null)
        val raw = saved ?: (if (sp.getBoolean(K_GPU_LAB, false)) "iosgfx" else "simplefb")
        // v1.9.80：GPU 2D(ios2d) / GPU 3D gfxstream(iosgfx) 花屏不可用，已从 UI 删除；
        // 老存档若停在这两个模式，自动迁回 simplefb，防止升级后仍卡在花屏。
        if (raw == "ios2d" || raw == "iosgfx") {
            sp.edit().putString(K_GPU_MODE, "simplefb").apply()
            return "simplefb"
        }
        return raw
    }
    private fun setGpuMode(mode: String) {
        sp.edit().putString(K_GPU_MODE, mode).putBoolean(K_GPU_LAB, mode != "simplefb").apply()
    }
    private fun gpuModeLabel(): String = when (gpuMode()) {
        "iosdrm" -> "GPU 3D·DRM"
        else -> "simplefb"
    }

    /** v1.9.41：弹窗高度上限（屏幕 80%）——只作上限，不再把窗口固定成这个高度。 */
    private fun dialogMaxH(): Int = (resources.displayMetrics.heightPixels * 0.80f).toInt()

    /**
     * v1.9.42：内容自适应高度的滚动容器（配合 window = WRAP_CONTENT）。
     * 圆角背景固定在「外壳」上，滚动内容在圆角内裁剪——避免圆角画在会滚动的 box 上、
     * 滚动时圆角跟着跑出可视区被截（默认切下方、上滑切上方）。
     */
    private fun wrapDialogContent(content: View): View {
        val maxH = dialogMaxH()
        // 去掉内容自带的圆角背景（改由外壳提供），padding 保留
        content.background = null
        val sv = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
            }
        }
        sv.isFillViewport = false
        sv.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // 固定外壳：圆角背景 + 按背景轮廓裁剪，滚动内容再怎么滑也不会把圆角顶出去
        return FrameLayout(this).apply {
            background = Glass.dialogPanel(this@MainActivity)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            addView(sv, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /**
     * v1.9.43：固定头部 + 下方滚动。header 始终钉在弹窗顶部（标题/当前分辨率），
     * 只有 scrollContent 滚动；总高自适应内容、封顶 maxH，圆角由外壳固定裁剪。
     */
    private fun wrapDialogContentWithHeader(header: View, scrollContent: View): View {
        val maxH = dialogMaxH()
        val d = resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()
        header.background = null
        scrollContent.background = null
        val sv = ScrollView(this).apply {
            isFillViewport = false
            addView(scrollContent, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // LinearLayout 顺序布局：header 在上固定，sv 在下滚动；onMeasure 封顶 maxH，
        // 内容少时贴合内容、内容多时 sv 被压缩并内部滚动。
        val container = object : LinearLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                super.onMeasure(w, View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST))
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22f), dp(22f), dp(22f), dp(14f))
            addView(header, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(sv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return FrameLayout(this).apply {
            background = Glass.dialogPanel(this@MainActivity)
            outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            addView(container, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    /** v1.9.38：校验 "宽x高" 格式，宽/高需在 480..3840 之间且为 2 的倍数（帧缓冲对齐）。 */
    private fun normalizeRes(raw: String): String? {
        val r = raw.trim().lowercase().replace('×', 'x').replace('*', 'x').replace(" ", "")
        val m = Regex("^(\\d+)x(\\d+)$").matchEntire(r) ?: return null
        val w = m.groupValues[1].toIntOrNull() ?: return null
        val h = m.groupValues[2].toIntOrNull() ?: return null
        if (w !in 480..3840 || h !in 480..3840) return null
        return "${w}x${h}"
    }

    private val sp by lazy { getSharedPreferences(SP_NAME, Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dm = resources.displayMetrics
        val pad = (16f * dm.density).toInt()

        // ---- 主题：跟随系统深色/浅色（UI_MODE_NIGHT）----
        val uiMode = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        curLight = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_NO
        Glass.isLight = curLight
        // edge-to-edge：让背景铺到状态栏后面，不再露出系统黑条；窗口背景跟随主题
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(
            if (Glass.isLight) 0xFFEEF2FB.toInt() else 0xFF0A0D18.toInt()))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            val wic = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            wic.isAppearanceLightStatusBars = Glass.isLight   // 浅底用深色状态栏图标
            wic.isAppearanceLightNavigationBars = Glass.isLight
        }
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        // v1.9.36：允许内容/背景延伸进屏幕挖孔区（前置摄像头）。
        // 竖屏挖孔在顶部(短边)系统默认就铺；横屏挖孔转到左/右短边，默认模式会避开留黑块，
        // 显式声明 SHORT_EDGES 后横屏也能铺满，消除左侧摄像头黑条。
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }

        // ---- 液态玻璃背景：渐变 + 流动光晕（水晶动画），铺满含状态栏/挖孔 ----
        val bg = GlassBackgroundView(this, Glass.isLight)

        // 玻璃按钮工厂：固定高度 + 单行不换行，避免文字长短撑变形
        // outlined=true 用清晰高光细边框背景（主操作长条按钮用）
        fun glassButton(label: String, outlined: Boolean = false): Button = Button(this).apply {
            text = label
            background = if (outlined) Glass.outlinedButton(this@MainActivity) else Glass.button(this@MainActivity)
            setTextColor(Glass.textPrimary())
            textSize = 14f
            isAllCaps = false
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            val h = (46f * dm.density).toInt()
            minHeight = h
            maxHeight = h
            setPadding((6f * dm.density).toInt(), 0, (6f * dm.density).toInt(), 0)
            Glass.crystalTouch(this)
        }

        btnImport = glassButton("导入镜像")
        btnIso = glassButton("导入光盘")
        btnBlank = glassButton("系统盘")
        btnData = glassButton("数据盘")
        btnBootMode = glassButton("优先 PE 启动（装系统）", outlined = true)
        // v1.9.32：显示切换 + 原生显示合并为一张玻璃卡（左右两半 + 中间竖线），
        // 省下一条按钮的高度让给启动/停止，避免误点。
        btnGpuLab = glassButton("显示：稳定 simplefb", outlined = true).apply { background = null }
        val btnDisplay = glassButton("原生显示 →", outlined = true).apply { background = null }
        val btnStart = glassButton("启动").apply {
            minHeight = (56f * dm.density).toInt(); maxHeight = minHeight
        }
        val btnStop = glassButton("停止").apply {
            minHeight = (56f * dm.density).toInt(); maxHeight = minHeight
        }
        logView = TextView(this).apply {
            textSize = 12.5f
            setTextColor(Glass.textLog())
            // v1.9.13：单行跑马灯，长日志横向滚动不截断；卡片高度固定，不再挤占下方按钮
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            setHorizontallyScrolling(true)
            isSelected = true
            gravity = Gravity.CENTER_VERTICAL
        }

        btnImport.setOnClickListener { importImage() }
        btnIso.setOnClickListener { onIsoClick() }
        btnBlank.setOnClickListener { onCdiskClick() }
        btnData.setOnClickListener { onDataClick() }
        btnBootMode.setOnClickListener { toggleWinBootMode() }
        btnStart.setOnClickListener { startVm() }
        btnStop.setOnClickListener { stopVm() }
        btnDisplay.setOnClickListener { startActivity(Intent(this, VmDisplayActivity::class.java)) }
        btnGpuLab.setOnClickListener { showGpuLabDialog() }

        // ---- 模式切换：一张玻璃整卡，左右两半，中间分隔线 ----
        val cardH = (108f * dm.density).toInt()   // 高度从 150 降到 108
        val halfW = ((dm.widthPixels - pad * 2) / 2)
        fun half(icoRes: Int, title: String, click: () -> Unit,
                 imgOut: (ImageView) -> Unit, hlOut: (View) -> Unit, barOut: (View) -> Unit): FrameLayout {
            val hl = View(this).apply {
                background = Glass.panelSelected(this@MainActivity, 16f)
                alpha = 0f
            }
            // v1.9.12：统一规格纯图标，无动画、不分深浅/选中态，两个模式视觉大小一致
            val img = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageResource(icoRes)
            }
            // 标题固定，和 Windows 同色，不参与动画
            val titleView = TextView(this).apply {
                text = title
                textSize = 17f
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                setTextColor(Glass.modeTitleColor())
                gravity = Gravity.CENTER
                isSingleLine = true
            }
            val bar = View(this).apply {
                background = GradientDrawable().apply {
                    setColor(Glass.indicator()); cornerRadius = 2f * dm.density
                }
            }
            imgOut(img); hlOut(hl); barOut(bar)
            return FrameLayout(this).apply {
                setOnClickListener { click() }
                addView(hl, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
                // 图标 + 标题垂直居中堆叠；图标缩小、四周留白匀称，不顶卡片边缘（参考图1）
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    addView(img, LinearLayout.LayoutParams(
                        (halfW * 0.40f).toInt(), (cardH * 0.50f).toInt()).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    })
                    addView(titleView, LinearLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                        topMargin = (2f * dm.density).toInt()
                    })
                }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    // 底部给指示条让位，避免标题/图标贴边
                    bottomMargin = (10f * dm.density).toInt()
                })
                addView(bar, FrameLayout.LayoutParams(
                    (36f * dm.density).toInt(), (4f * dm.density).toInt(),
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                    bottomMargin = (8f * dm.density).toInt()
                })
            }
        }
        val leftHalf = half(Glass.winIco(), "Windows",
            { pickMode(MODE_WIN11) }, { imgWin = it }, { hlWin = it }, { barWin = it })
        val rightHalf = half(Glass.linuxIco(), "Linux",
            { pickMode(MODE_LINUX) }, { imgLinux = it }, { hlLinux = it }, { barLinux = it })
        val divider = View(this).apply { setBackgroundColor(Glass.divider()) }
        val modes = FrameLayout(this).apply {
            background = Glass.panel(this@MainActivity, 18f)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, 18f * dm.density)
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, cardH).apply { topMargin = (4f * dm.density).toInt() }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(leftHalf, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(rightHalf, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(divider, FrameLayout.LayoutParams(
                (dm.density).toInt(), (cardH - 28f * dm.density).toInt(), Gravity.CENTER))
        }

        // ---- 大页池状态卡：VM 启动最关键的前置条件，置顶显眼位置 ----
        hpStatus = TextView(this).apply {
            text = "大页池：检测中…"
            textSize = 13f
            setTextColor(Glass.textPrimary())
            // v1.9.27：单行跑马灯——长文案横向滚动不折行、不撑高卡片（复用日志区方案）
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            setHorizontallyScrolling(true)
            isSelected = true
            gravity = Gravity.CENTER_VERTICAL
        }
        hpAction = glassButton("安装大页池模块")
        // v1.9.12：进度条内嵌进按钮槽——下载时按钮文字清空，进度条覆盖在按钮内；不再有单独进度条
        hpProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(Glass.indicator())
        }
        val hpActionSlot = FrameLayout(this).apply {
            addView(hpAction, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(hpProgress, FrameLayout.LayoutParams(
                (86f * dm.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER).apply { gravity = Gravity.CENTER })
        }
        // 第一行：大页池（原样）
        hpRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(hpStatus, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(hpActionSlot, LinearLayout.LayoutParams((132f * dm.density).toInt(),
                (42f * dm.density).toInt()).apply { leftMargin = (8f * dm.density).toInt() })
        }

        // ---- v1.9.68：第二行——工具链状态 + 下载按钮（首次启动在线下载，不再内置）----
        toolStatus = TextView(this).apply {
            text = "工具链：检测中…"
            textSize = 13f
            setTextColor(Glass.textPrimary())
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            setHorizontallyScrolling(true)
            isSelected = true
            gravity = Gravity.CENTER_VERTICAL
        }
        toolAction = glassButton("下载工具链")
        toolProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(Glass.indicator())
        }
        val toolActionSlot = FrameLayout(this).apply {
            addView(toolAction, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(toolProgress, FrameLayout.LayoutParams(
                (86f * dm.density).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER).apply { gravity = Gravity.CENTER })
        }
        toolRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(toolStatus, LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(toolActionSlot, LinearLayout.LayoutParams((132f * dm.density).toInt(),
                (42f * dm.density).toInt()).apply { leftMargin = (8f * dm.density).toInt() })
        }
        toolAction.setOnClickListener { downloadToolchain() }

        // v1.9.70：两行叠放同一槽，每 3 秒轮播切换——卡片高度减半（约 62dp），
        // 与其它卡片对齐；状态文字与按钮都独占整行，不再被挤截断。
        val rowSlot = FrameLayout(this).apply {
            addView(hpRow, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(toolRow, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
                // 初始隐藏第二行
            })
            toolRow.visibility = View.GONE
        }
        val hpCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.panel(this@MainActivity, 16f)
            setPadding((14f * dm.density).toInt(), (10f * dm.density).toInt(),
                (10f * dm.density).toInt(), (10f * dm.density).toInt())
            addView(rowSlot, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (42f * dm.density).toInt()))
        }
        // v1.9.70：点状态文字区手动切页，并暂停轮播 10s，方便用户操作当前页按钮
        fun manualFlip() {
            cardFlipPausedUntil = System.currentTimeMillis() + 10_000L
            showCardPage(if (cardPage == 0) 1 else 0)
        }
        hpStatus.setOnClickListener { manualFlip() }
        toolStatus.setOnClickListener { manualFlip() }
        hpAction.setOnClickListener {
            // v1.9.26：设池并重启优先级最高——池容量不足时，其它都白搭（必 0x9）
            when {
                hpNeedResize -> resizePoolAndReboot()
                hpNeedReboot -> {
                    // v1.9.75：重启前先确保池目标 ≥5G。很多用户看到「重启手机」直接点，
                    // 不知道要先设池，结果重启后还是默认 2G 不够用（卡 MEM_ACCEPT 0x9）。
                    Toast.makeText(this, "正在设置大页池到 5G 并重启…", Toast.LENGTH_SHORT).show()
                    Thread {
                        runCatching { GunyahManager.ensurePoolSizedFor(5120) }
                        runOnUiThread { GunyahManager.rebootDevice() }
                    }.start()
                }
                hpLoaded -> {
                    Toast.makeText(this, "大页池已就绪，可直接启动 PE", Toast.LENGTH_SHORT).show()
                }
                hpInstalled -> repairHugepage()
                else -> installHugepage()
            }
        }

        // ---- 环境检测 + 运行状态卡（玻璃仪表盘）：标题栏 + 2×3 状态格 ----
        fun dp(v: Float) = (v * dm.density).toInt()
        // 状态格工厂：圆点灯（带柔光晕）+ 名称 + 值，整体一块小玻璃
        fun statCell(nameText: String): StatCell {
            val halo = View(this).apply { background = Glass.dot(Glass.textHint()) }
            val dot = View(this).apply { background = Glass.dot(Glass.textHint()) }
            val lamp = FrameLayout(this).apply {
                addView(halo, FrameLayout.LayoutParams(dp(20f), dp(20f), Gravity.CENTER))
                addView(dot, FrameLayout.LayoutParams(dp(9f), dp(9f), Gravity.CENTER))
            }
            val name = TextView(this).apply {
                text = nameText; textSize = 10.5f; setTextColor(Glass.textHint())
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1              // 无限循环滚动，文字不截断
                setHorizontallyScrolling(true)
                isSelected = true                    // 无焦点也能跑马灯
            }
            val value = TextView(this).apply {
                text = "—"; textSize = 12.5f; setTextColor(Glass.textPrimary())
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
                marqueeRepeatLimit = -1
                setHorizontallyScrolling(true)
                isSelected = true
            }
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                background = Glass.chipPanel(this@MainActivity, 13f)
                setPadding(dp(10f), dp(9f), dp(8f), dp(9f))
                addView(LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(lamp, LinearLayout.LayoutParams(dp(20f), dp(20f)))
                    addView(name, LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(4f) })
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(value, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(5f) })
            }
            return StatCell(dot, halo, name, value).also {
                cell.tag = it
            }.also { statCellViews.add(cell) }
        }
        val cs = listOf(
            statCell("ROOT 权限"), statCell("Gunyah 设备"), statCell("内核版本"),
            statCell("内核模块"), statCell("大页池"), statCell("运行状态"),
        )
        statCells = cs.map { it }

        fun statRow(vararg cells: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val m = dp(7f)
            cells.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) leftMargin = m })
            }
        }
        val statGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statRow(statCellViews[0], statCellViews[1], statCellViews[2]),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(statRow(statCellViews[3], statCellViews[4], statCellViews[5]),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(7f) })
        }

        diagSummary = TextView(this).apply {
            text = "检测中…"; textSize = 12f; setTextColor(Glass.indicator())
        }
        val diagCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.panel(this@MainActivity, 18f)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, 18f * dm.density)
                }
            }
            setPadding(dp(13f), dp(11f), dp(13f), dp(12f))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val title = TextView(this@MainActivity).apply {
                    text = "◈ 环境与运行状态"; textSize = 13.5f
                    setTextColor(Glass.textPrimary())
                }
                addView(title)
                addView(diagSummary, LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(10f) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(11f) })
            addView(statGrid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        fun row(vararg views: View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val m = (6f * dm.density).toInt()
            views.forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) leftMargin = m
                })
            }
        }
        // v1.9.14：去掉外层玻璃卡片那层「膜」（半透明背景 + 圆角裁剪），
        // 避免糊住启动/停止按钮；按钮本身已是玻璃材质，直接裸露即可，仍固定在最底部
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(row(btnStart, btnStop), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 上方：导入镜像 + 光盘按钮，固定宽度的小卡片，整行居中（单个时也保持小卡片大小）
        val otherRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            val m = (6f * dm.density).toInt()
            val bw = ((dm.widthPixels - pad * 2 - m * 3) / 4)
            listOf(btnImport, btnIso, btnBlank, btnData).forEachIndexed { i, v ->
                addView(v, LinearLayout.LayoutParams(bw, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    if (i > 0) leftMargin = m
                })
            }
        }

        // 状态栏 / 底部导航条高度：edge-to-edge 后内容需让开，避免顶到状态栏图标 / 手势条
        val sbh = runCatching {
            val id = resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else (28f * dm.density).toInt()
        }.getOrDefault((28f * dm.density).toInt())
        val nbh = runCatching {
            val id = resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) resources.getDimensionPixelSize(id) else (24f * dm.density).toInt()
        }.getOrDefault((24f * dm.density).toInt())

        // v1.9.34：显示切换 + 原生显示 二合一卡——高度对齐大页池卡(62dp)，
        // 背景换成与上方启动模式卡同款的高光细边框(outlinedButton)
        val dispCardH = (62f * dm.density).toInt()
        val displayCard = FrameLayout(this).apply {
            // v1.9.51：高透玻璃边框——让底层签名图透过显示卡隐约显示（显示卡在签名图前面，但透明）
            background = Glass.outlinedTransparent(this@MainActivity, 18f)
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) {
                    o.setRoundRect(0, 0, v.width, v.height, 18f * dm.density)
                }
            }
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(btnGpuLab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
                addView(btnDisplay, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            // 玻璃效果分隔竖线
            addView(View(this@MainActivity).apply { setBackgroundColor(Glass.divider()) },
                FrameLayout.LayoutParams((dm.density).toInt(), (dispCardH - 20f * dm.density).toInt(), Gravity.CENTER))
        }

        // v1.9.35：可滚动内容区——模式卡 → 大页池 → 环境状态 → 日志 → 导入行 → 启动模式 → 显示卡。
        // 横屏高度不足时整块可上下滑动，不再被截断；以后新增卡片只加进这里，不会挤掉底部按钮。
        val scroll = ScrollView(this).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            setPadding(0, 0, 0, (6f * dm.density).toInt())
            clipToPadding = false
        }
        val scrollContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(modes)
            // 大页池状态卡：紧跟模式卡
            addView(hpCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10f * dm.density).toInt()
            })
            // 环境检测 + 运行状态卡
            addView(diagCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10f * dm.density).toInt()
            })
            // 日志：固定高度玻璃卡 + 单行跑马灯，内容再多也不撑高
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Glass.panel(this@MainActivity, 13f)
                setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
                addView(logView, LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10f * dm.density).toInt()
            })
            // 其他按钮：导入镜像 +（光盘ISO/新建空白盘）
            addView(otherRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10f * dm.density).toInt()
            })
            // Win11 启动目标切换（Linux 模式隐藏）
            addView(btnBootMode, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10f * dm.density).toInt()
            })
            // 显示切换 + 原生显示 二合一卡（竖线分隔）
            addView(displayCard, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dispCardH).apply {
                topMargin = (10f * dm.density).toInt()
            })
        }
        scroll.addView(scrollContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // v1.9.35：启动/停止独立固定底栏——竖屏横屏都固定在屏幕最底，不随内容滚动。
        val actionBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(actions, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // 内容列：滚动区（显示卡等）+ 固定底栏按钮
        val contentCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(actionBar, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = (10f * dm.density).toInt()
            })
        }

        // v1.9.51：签名图沉到【最底层】——先 addView 位于内容列之下。
        // 显示卡(high-transparent 高透背景)叠在签名图【前面】，签名透过卡片隐约显示；
        // 签名固定在按钮上方、不随上滑，向上探入显示卡背后，形成"签名在底、卡片在前透出"的效果。
        val box = FrameLayout(this).apply {
            setPadding(pad, sbh + (14f * dm.density).toInt(), pad, (12f * dm.density).toInt())
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.watermark_signature)
                scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                adjustViewBounds = true
                alpha = 0.6f
                isClickable = false
                isFocusable = false
            }, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                bottomMargin = (60f * dm.density).toInt()
            })
            addView(contentCol, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        // 根布局：光晕背景铺底（MATCH_PARENT 含状态栏/摄像头区域），内容叠在上面
        val root = FrameLayout(this).apply {
            addView(bg, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(box, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(root)
        pickMode(MODE_WIN11)
        refreshHpStatus()
        // v1.9.68：首次启动自动在线下载工具链（不再内置 assets），进度显示在二合一卡第二行
        refreshToolStatus(autoDownload = true)
    }

    /**
     * v1.9.13：统一日志写入口。跑马灯是单行，把原文中的换行压成「 · 」分隔，
     * 保证长文案横向连贯滚动不被截断；换文本后重新选中以重启跑马灯动画。
     */
    private fun setLogText(raw: String) {
        logView.text = raw.replace(Regex("[\\r\\n]+"), "  ·  ")
        logView.isSelected = false
        logView.isSelected = true
    }

    /**
     * v1.9.27：大页池卡片文字统一入口。跑马灯是单行，把换行压成「 · 」，
     * 保证长文案横向滚动不折行、卡片高度恒定，不再挤掉启动/停止按钮。
     */
    private fun setHpText(raw: String) {
        hpStatus.text = raw.replace(Regex("[\\r\\n]+"), "  ·  ")
        hpStatus.isSelected = false
        hpStatus.isSelected = true
    }

    /**
     * v1.9.70：二合一卡两页轮播切换（淡入淡出）。
     * 只切换可见性，不碰任何状态文案/按钮逻辑；正在下载/安装时由 tick 跳过切换。
     */
    private fun showCardPage(page: Int) {
        if (isFinishing || !::hpRow.isInitialized || !::toolRow.isInitialized) return
        cardPage = page
        val showHp = page == 0
        val inView = if (showHp) hpRow else toolRow
        val outView = if (showHp) toolRow else hpRow
        if (inView.visibility == View.VISIBLE) return
        outView.animate().alpha(0f).setDuration(180).withEndAction {
            outView.visibility = View.GONE
            outView.alpha = 1f
        }.start()
        inView.alpha = 0f
        inView.visibility = View.VISIBLE
        inView.animate().alpha(1f).setDuration(180).start()
    }

    override fun onResume() {
        super.onResume()
        isPaused = false
        // 进界面/从显示器切回：立即检测一次，并启动 8s 定时刷新（运行状态会变）
        diagTick.run()
        // v1.9.70：二合一卡每 3 秒轮播（大页池 ⇄ 工具链）
        cardFlipHandler.removeCallbacks(cardFlipTick)
        cardFlipTick.run()
    }

    override fun onPause() {
        super.onPause()
        isPaused = true
        diagHandler.removeCallbacks(diagTick)
        cardFlipHandler.removeCallbacks(cardFlipTick)
    }

    /**
     * 环境检测 + 运行状态：6 项真实探测，全部放后台线程（ROOT shell 较慢），逐格回写 UI。
     * 状态灯三色：就绪(绿)/注意(橙)/异常(红)，中性信息用主题蓝。
     * [silent]=true 时定时刷新，不把格子闪回「检测中」。
     */
    private fun runDiagnostics(silent: Boolean = false) {
        if (isFinishing) return
        if (!silent) {
            diagSummary.text = "检测中…"
            diagSummary.setTextColor(Glass.indicator())
            statCells.forEach { setCell(it, Glass.indicator(), "检测中…") }
        }

        Thread {
            // 1. ROOT 权限
            val rootOk = runCatching { RootShell.available(10000) }.getOrDefault(false)
            // 2. Gunyah 设备
            val gunyahOk = runCatching { GunyahManager.gunyahDevReady() }.getOrDefault(false)
            // 3. 内核版本
            val kernel = runCatching { GunyahManager.detectKernelVersion() }.getOrDefault("")
            // 4. 内核模块（gunyah 相关已加载数）
            val loaded = runCatching { GunyahManager.loadedModules() }.getOrDefault(emptySet())
            val keyMods = loaded.count { k ->
                k.contains("gunyah") || k.contains("unmovable") ||
                        k.contains("nproc_guard") || k.contains("udmabuf")
            }
            // 5. 大页池
            val hp = runCatching { GunyahManager.hpModuleInfo() }
                .getOrDefault(GunyahManager.HpModuleInfo())
            // 6. 运行状态
            val vm = runCatching { GunyahManager.vmStatus(this) }
                .getOrDefault(GunyahManager.VmStatus("unknown"))

            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                setCell(statCells[0], if (rootOk) Glass.statusOk() else Glass.statusBad(),
                    if (rootOk) "已授权" else "未授权")
                setCell(statCells[1], if (gunyahOk) Glass.statusOk() else Glass.statusBad(),
                    if (gunyahOk) "就绪" else "缺失")
                setCell(statCells[2], Glass.indicator(),
                    if (kernel.isNotBlank()) kernel else "未知")
                setCell(statCells[3],
                    if (keyMods >= 2) Glass.statusOk() else if (keyMods == 1) Glass.statusWarn() else Glass.statusBad(),
                    "$keyMods 个已加载")
                if (hp.loaded) {
                    val avail = hp.poolAvailMB
                    setCell(statCells[4], Glass.statusOk(),
                        if (avail >= 0) "已就绪 ${avail}MB" else "已就绪")
                } else if (hp.installed) {
                    setCell(statCells[4], Glass.statusWarn(), "待重启生效")
                } else {
                    setCell(statCells[4], Glass.statusBad(), "未安装")
                }
                when {
                    vm.kind == "running" -> setCell(statCells[5], Glass.statusOk(), "运行中")
                    vm.kind == "uefi" -> setCell(statCells[5], Glass.indicator(), "UEFI 引导中")
                    vm.isBooting -> setCell(statCells[5], Glass.indicator(),
                        "启动中 ${vm.idx}/${vm.total}")
                    vm.kind == "failed" -> setCell(statCells[5], Glass.statusBad(), "启动失败")
                    else -> setCell(statCells[5], Glass.textHint(), "已停止")
                }

                // 总结：只把「前置条件」4 项计入就绪度（ROOT/Gunyah/模块/大页），运行状态单独看
                val ready = listOf(rootOk, gunyahOk, keyMods >= 2, hp.loaded).count { it }
                diagSummary.text = "$ready / 4 前置就绪"
                diagSummary.setTextColor(if (ready == 4) Glass.statusOk()
                    else if (ready >= 2) Glass.statusWarn() else Glass.statusBad())
            }
        }.start()
    }

    /** 更新单个状态格：圆点 + 柔光晕 + 值文字同步着色。 */
    private fun setCell(cell: StatCell, color: Int, text: String) {
        cell.dot.background = Glass.dot(color)
        cell.halo.background = Glass.dot(color)
        cell.halo.alpha = 0.28f
        cell.value.setTextColor(color)
        cell.value.text = text
        // setText 会重置跑马灯，重新置 selected 让长文字继续滚动显示完整
        cell.value.isSelected = false
        cell.value.isSelected = true
    }

    /** 后台检测大页池模块状态并更新卡片。ROOT 查询放后台线程，避免卡 UI。 */
    private fun refreshHpStatus() {
        Thread {
            val info = runCatching { GunyahManager.hpModuleInfo() }
                .getOrDefault(GunyahManager.HpModuleInfo())
            val ready = info.loaded
            hpInstalled = info.installed
            hpLoaded = info.loaded
            hpNeedReboot = false   // 重新检测即清掉待重启态
            hpNeedResize = false
            // v1.9.75：池容量下限提到 5G（默认 2G 会让 Win10/11 卡 MEM_ACCEPT 0x9）。
            val targetMB = maxOf(5120, runCatching { configFor(mode).memMB }.getOrDefault(5120))
            val wantPages = (targetMB + 1) / 2
            hpPoolTargetMB = info.poolWantMB * 2      // settings.prop 里的页数 × 2 = MB
            hpPoolAvailMB = info.poolAvailMB
            // 池目标没设到目标值（含 0 / 读不到）→ 需要设池并重启
            val targetShort = hpPoolTargetMB < targetMB
            runOnUiThread {
                if (info.installed && targetShort) {
                    hpNeedResize = true
                    setHpText("大页池容量不足（需 5G+） · " +
                            "当前目标 ${hpPoolTargetMB}MB / 需 ≥${targetMB}MB · " +
                            "点「设池并重启」自动设到 5G，开机早期才留得住连续 2MB")
                    hpStatus.setTextColor(Glass.statusWarn())
                    hpAction.text = "设池并重启"
                    hpAction.setTextColor(Glass.statusWarn())
                } else if (ready) {
                    val availTxt = if (hpPoolAvailMB >= 0) "实测 ${hpPoolAvailMB}MB" else "连续页已预留"
                    setHpText("✓ 大页池已就绪（$availTxt） · " +
                            "池目标 ${hpPoolTargetMB}MB ≥ VM ${targetMB}MB，可启动 4G+")
                    hpStatus.setTextColor(Glass.indicator())
                    hpAction.text = "已就绪"
                    hpAction.setTextColor(Glass.indicator())
                } else if (info.installed) {
                    // v1.9.7：模块被看门狗自锁时，改为一键修复（清标记+立即加载，免重启）
                    setHpText("大页模块被看门狗锁定 · 点右侧一键修复，无需重启")
                    hpStatus.setTextColor(Glass.statusWarn())
                    hpAction.text = "一键修复"
                    hpAction.setTextColor(Glass.textPrimary())
                } else {
                    setHpText("⚠ 大页池未安装 · VM 启动多半卡在 MEM_ACCEPT 0x9")
                    hpStatus.setTextColor(Glass.textLog())
                    hpAction.text = "安装大页池模块"
                    hpAction.setTextColor(Glass.textPrimary())
                }
            }
        }.start()
    }

    /**
     * v1.9.68：检测工具链状态并更新二合一卡第二行。
     * 未就绪且未在下载时，自动触发一次在线下载（首次启动即拉取，不再内置 assets）。
     */
    private fun refreshToolStatus(autoDownload: Boolean = true) {
        if (isFinishing) return
        Thread {
            // v1.9.77：严格校验全部启动核心，不再只看 crosvm 在不在。
            val chk = runCatching { GunyahManager.checkToolchain(this) }.getOrNull()
            val ready = chk?.ok == true
            val missing = chk?.missing.orEmpty()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                if (toolDownloading) return@runOnUiThread
                if (ready) {
                    toolStatus.text = "✓ 工具链已就绪 · crosvm + 固件 + 模块就位，可直接启动"
                    toolStatus.setTextColor(Glass.indicator())
                    toolAction.text = "已就绪"
                    toolAction.setTextColor(Glass.indicator())
                    toolAction.isEnabled = false
                } else {
                    // 区分「完全没下载」与「下了一半缺核心」——后者必须引导重下，否则启动必失败。
                    toolStatus.text = if (missing.isEmpty())
                        "工具链未下载 · 点右侧在线下载（测速选最快线路，约 11MB）"
                    else
                        "⚠ 工具链不完整，缺：${missing.joinToString("、")} · 点右侧重新下载后再启动"
                    toolStatus.setTextColor(if (missing.isEmpty()) Glass.textLog() else Glass.statusWarn())
                    toolAction.text = if (missing.isEmpty()) "下载工具链" else "重新下载"
                    toolAction.setTextColor(Glass.textPrimary())
                    toolAction.isEnabled = true
                    if (autoDownload) downloadToolchain()
                }
            }
        }.start()
    }

    /** v1.9.68：在线下载工具链（多线路测速 + 国内镜像加速），进度内嵌在按钮里。 */
    private fun downloadToolchain() {
        if (isFinishing || toolDownloading) return
        toolDownloading = true
        toolAction.isEnabled = false
        toolAction.text = ""
        toolProgress.progress = 0
        toolProgress.isIndeterminate = true
        toolProgress.visibility = View.VISIBLE
        setLogText("工具链：正在从GitHub下载工具链，请稍等…")
        Thread {
            val (ok, msg) = GunyahManager.downloadAndExtract(
                this,
                onProgress = { done, total ->
                    runOnUiThread {
                        toolProgress.isIndeterminate = total <= 0
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            toolProgress.progress = pct
                            toolStatus.text = "工具链下载中 $pct% · ${done / 1024 / 1024}/${total / 1024 / 1024}MB"
                            toolStatus.isSelected = false; toolStatus.isSelected = true
                        }
                    }
                },
                onState = { s ->
                    runOnUiThread {
                        setLogText("工具链：$s")
                        if (s.contains("测速") || s.contains("切线") || s.contains("线路") ||
                            s.contains("解压") || s.contains("下载")) {
                            toolProgress.isIndeterminate = true
                        }
                        // 新文案已自带主语（「正在从GitHub下载工具链…」），避免重复「工具链：」
                        toolStatus.text = if (s.contains("工具链") || s.contains("GitHub") ||
                            s.contains("磁盘工具")) s else "工具链：$s"
                        toolStatus.isSelected = false; toolStatus.isSelected = true
                    }
                },
            )
            runOnUiThread {
                toolDownloading = false
                toolProgress.visibility = View.GONE
                toolProgress.progress = 0
                toolAction.isEnabled = true
                // v1.9.78：无论下载回调成功与否，卡片一律以「本地真实校验结果」为准，
                // 与启动拦截（ensureToolchain→checkToolchain）用同一判据，杜绝
                // 「提示下载失败、却能正常启动」这种自相矛盾：
                //  - 本地核心齐全（之前下过 / 另一次下载已补齐）→ 显示已就绪，可启动；
                //  - 本地确实缺核心 → 显示失败原因，此时启动也会被拦截。
                val real = runCatching { GunyahManager.checkToolchain(this@MainActivity) }.getOrNull()
                if (real?.ok == true) {
                    toolStatus.text = "✓ 工具链已就绪 · crosvm + 固件 + 模块就位，可直接启动"
                    toolStatus.setTextColor(Glass.indicator())
                    toolAction.text = "已就绪"
                    toolAction.setTextColor(Glass.indicator())
                    toolAction.isEnabled = false
                    if (ok) Toast.makeText(this, "工具链下载完成，全部核心已就位", Toast.LENGTH_SHORT).show()
                    else setLogText("本次在线下载未成功，但本地工具链核心齐全，可正常启动。\n$msg")
                } else {
                    val short = msg.replace("\n", " ")
                    val miss = real?.missing.orEmpty()
                    toolStatus.text = if (miss.isEmpty()) "⚠ $short · 点右侧重下后再启动"
                                      else "⚠ 工具链缺核心：${miss.joinToString("、")} · 点右侧重下后再启动"
                    toolStatus.setTextColor(Glass.statusWarn())
                    toolAction.text = "重新下载"
                    toolAction.setTextColor(Glass.statusWarn())
                    setLogText("工具链：$msg\n核心不齐时启动会被拦截，请联网重下。")
                    Toast.makeText(this, "工具链不完整，请重新下载后再启动", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    /** v1.9.26：把大页池容量设到 ≥ 当前配置内存，然后引导重启（开机早期预留连续 2MB）。 */
    private fun resizePoolAndReboot() {
        if (isFinishing) return
        hpAction.isEnabled = false
        hpAction.text = "设置中…"
        Toast.makeText(this, "正在设置大页池容量…", Toast.LENGTH_SHORT).show()
        Thread {
            val targetMB = maxOf(5120, runCatching { configFor(mode).memMB }.getOrDefault(5120))
            val (changed, msg) = runCatching { GunyahManager.ensurePoolSizedFor(targetMB) }
                .getOrDefault(false to "设置池容量异常")
            runOnUiThread {
                hpAction.isEnabled = true
                setLogText("大页池：$msg\n重启后模块会在开机早期预留连续 2MB 页，再进 PE 装系统。")
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                // 无论是否已经够，都引导重启：设了新目标必须重启才预留到位
                hpNeedResize = false
                hpNeedReboot = true
                hpAction.text = "重启手机"
                hpAction.setTextColor(Glass.statusWarn())
                if (!changed && hpPoolTargetMB >= targetMB) {
                    // 目标本来就够，但当前实测不足——也建议重启趁干净预留
                    setLogText("池目标已达标，但实测 ${hpPoolAvailMB}MB 不足。\n重启手机趁开机内存干净，再进 PE 装系统。")
                }
            }
        }.start()
    }

    /** 安装大页池模块：下载 + 刷入，进度条内嵌在按钮里，日志区回报阶段。 */
    private fun installHugepage() {
        if (isFinishing) return
        hpNeedReboot = false
        hpAction.isEnabled = false
        hpAction.text = ""                 // 按钮文字清空，进度条在按钮内显示
        hpProgress.progress = 0
        hpProgress.isIndeterminate = true
        hpProgress.visibility = View.VISIBLE
        Thread {
            val (ok, msg) = GunyahManager.installHpModule(
                this,
                onState = { s ->
                    runOnUiThread {
                        setLogText(s)
                        // 测速/切线/安装阶段没有字节进度，转圈提示仍在工作中
                        if (s.contains("测速") || s.contains("切线") || s.contains("安装") ||
                            s.contains("下载大页") || s.contains("线路")) {
                            hpProgress.isIndeterminate = true
                        }
                    }
                },
                onProgress = { done, total ->
                    runOnUiThread {
                        hpProgress.isIndeterminate = total <= 0
                        if (total > 0) {
                            hpProgress.progress = (done * 100 / total).toInt().coerceIn(0, 100)
                            setLogText("下载中 ${done / 1024 / 1024} / ${total / 1024 / 1024} MB " +
                                    "(${done * 100 / total}%)")
                        }
                    }
                },
            )
            runOnUiThread {
                hpProgress.visibility = View.GONE   // 完成即隐藏内嵌进度条
                hpAction.isEnabled = true
                hpProgress.progress = 0
                if (ok && msg == "INSTALL_OK_REBOOT") {
                    // v1.9.75：装好模块后立刻把池设到 5G（默认 2G 不够，用户又不知道要手设），
                    // 这样点「重启手机」时池目标已经是 5G，重启即可一次性到位。
                    val poolRes = runCatching { GunyahManager.ensurePoolSizedFor(5120) }
                        .getOrDefault(false to "设池失败")
                    hpNeedReboot = true
                    hpInstalled = true
                    hpAction.text = "重启手机"
                    hpAction.setTextColor(Glass.statusWarn())
                    setHpText("✓ 大页模块已装 · 池已设 5G · 点「重启手机」开机生效")
                    setLogText("✓ 大页模块已安装完成\n大页池已自动设为 5G（${poolRes.second}）\n" +
                            "点「重启手机」重启，开机早期预留连续 2MB，再进 PE 装系统。")
                    Toast.makeText(this, "模块已装 + 池已设 5G，请点「重启手机」", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this, msg, if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG).show()
                    refreshHpStatus()
                    pickMode(mode) // 恢复日志文案
                }
            }
        }.start()
    }

    /** v1.9.7：一键修复大页模块看门狗自锁——清标记 + 立即加载，免重启。 */
    private fun repairHugepage() {
        if (isFinishing) return
        hpAction.isEnabled = false
        hpAction.text = "修复中…"
        Toast.makeText(this, "正在修复大页模块并加载（约几秒，无需重启）…", Toast.LENGTH_SHORT).show()
        Thread {
            val (ok, msg) = runCatching { GunyahManager.hpModuleRepairNow() }
                .getOrDefault(false to "修复异常，请查看 boot.log")
            runOnUiThread {
                hpAction.isEnabled = true
                Toast.makeText(this, msg, if (ok) Toast.LENGTH_LONG else Toast.LENGTH_LONG).show()
                refreshHpStatus()
                if (ok) setLogText("✓ 大页池已就绪：$msg\n现在可以直接点「优先 PE 启动」装系统。")
                pickMode(mode)
            }
        }.start()
    }

    /** 系统深色/浅色切换时跟随重建 UI（配合 Manifest 的 configChanges="uiMode"） */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val uiMode = newConfig.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK
        val light = uiMode == android.content.res.Configuration.UI_MODE_NIGHT_NO
        if (light != curLight) recreate()
    }

    private fun diskFor(m: Int): String =
        sp.getString(if (m == MODE_WIN11) K_WIN_DISK else K_LINUX_DISK, "") ?: ""

    private fun isoPath(): String = sp.getString(K_LINUX_ISO, "") ?: ""
    private fun targetPath(): String = sp.getString(K_LINUX_TARGET, "") ?: ""

    private fun pickMode(m: Int) {
        mode = m
        // v1.9.12：图标固定无动画、不分深浅/选中态；选中态只用玻璃高光 + 底部指示条区分
        val winSel = m == MODE_WIN11
        hlWin.animate().alpha(if (winSel) 1f else 0f).setDuration(260).start()
        hlLinux.animate().alpha(if (!winSel) 1f else 0f).setDuration(260).start()
        barWin.visibility = if (winSel) View.VISIBLE else View.INVISIBLE
        barLinux.visibility = if (!winSel) View.VISIBLE else View.INVISIBLE
        if (winSel) Glass.pulse(barWin) else Glass.pulse(barLinux)
        // Win11 / Linux 都显示光盘 ISO + 新建空白盘（Win11 用 PE 装系统）
        val isLinux = m == MODE_LINUX
        btnIso.visibility = android.view.View.VISIBLE
        btnBlank.visibility = android.view.View.VISIBLE
        btnBootMode.visibility = if (winSel) android.view.View.VISIBLE else android.view.View.GONE
        val winBoot = sp.getString(K_WIN_BOOT, "pe") ?: "pe"
        btnBootMode.text = if (winBoot == "pe") "优先 PE 启动（装系统）" else "优先 C 盘启动（进系统）"
        // v1.9.28：已选光盘时按钮变「移除光盘」，点它取消挂载（不删文件）
        val isoSelected = (sp.getString(if (winSel) K_WIN_ISO else K_LINUX_ISO, "") ?: "").isNotBlank()
        btnIso.text = if (isoSelected) "移除光盘" else "导入光盘"
        btnBlank.text = if (winSel) "系统盘" else "新建安装盘"
        val dataPath = sp.getString(K_DATA_DISK, "") ?: ""
        btnData.text = if (dataPath.isNotBlank()) "移除数据盘" else "数据盘"
        btnImport.text = if (isLinux) "导入成品镜像" else "导入 PE 镜像"
        refreshGpuLabButton()
        val preset = if (m == MODE_WIN11) winPreset else linuxPreset
        val disk = diskFor(m)
        val dispLabel = when (gpuMode()) {
            "iosdrm" -> "GPU 3D·DRM(需显卡驱动)"
            else -> "${preset.displayMode}(稳定)"
        }
        val modeLabel = if (m == MODE_WIN11) "Win on ARM64" else preset.name
        val base = "模式: $modeLabel\n" +
                "镜像: ${if (disk.isNotBlank()) File(disk).name else "未导入"}\n" +
                "内存: ${preset.memMB}MB · CPU: ${preset.cpus} · ${preset.resolution}\n" +
                "显示: $dispLabel · mTHP: ${preset.mthpMode}\n"
        setLogText(if (isLinux) {
            base + "\n── 光盘安装 ISO（可选）──\n" +
                "光盘: ${if (isoPath().isNotBlank()) File(isoPath()).name else "未选"}\n" +
                "目标盘: ${if (targetPath().isNotBlank()) File(targetPath()).name else "未建（可直接导入成品镜像启动）"}\n\n" +
                "装新系统：①「导入光盘 ISO」②「新建空白盘」自定义容量当目标 ③启动，从光盘引导安装。\n" +
                "空白盘保存在 /sdcard/GunyahLauncher/disks/。\n" +
                "直接启动成品 Linux：只「导入成品镜像」即可，无需光盘。"
        } else {
            val iso = sp.getString(K_WIN_ISO, "") ?: ""
            val tgt = sp.getString(K_WIN_TARGET, "") ?: ""
            base + "\n── Win11 装系统（三盘）──\n" +
                "启动目标: ${if (winBoot == "pe") "PE（装系统）" else "C盘（进系统）"}\n" +
                "PE 镜像: ${if (disk.isNotBlank()) File(disk).name else "未导入"}\n" +
                "Win11 ISO: ${if (iso.isNotBlank()) File(iso).name else "未选"}\n" +
                "C 盘: ${if (tgt.isNotBlank()) File(tgt).name else "未建"}\n\n" +
                "装系统：①「导入 PE 镜像」②「导入光盘」③「系统盘」\n" +
                "④启动模式=优先 PE 启动 → 启动 → 进 PE 桌面后运行「一键注入 VirtIO 驱动」前先用系统安装器装 Win11 到 C 盘。\n" +
                "装完切「优先 C 盘启动」即可进系统。PE/C盘/ISO 保存在 /sdcard/GunyahLauncher/disks/。"
        })
    }

    /** Win11 启动目标切换：从 PE 启动（装系统）⇄ 从装好的 C 盘启动（进系统）。 */
    private fun toggleWinBootMode() {
        val cur = sp.getString(K_WIN_BOOT, "pe") ?: "pe"
        val next = if (cur == "pe") "system" else "pe"
        sp.edit().putString(K_WIN_BOOT, next).apply()
        Toast.makeText(this,
            if (next == "pe") "启动目标：PE（装系统）— 需已导入 PE 镜像" else "启动目标：C 盘（进系统）— 需已装好 Win11",
            Toast.LENGTH_SHORT).show()
        pickMode(MODE_WIN11)
    }

    /**
     * v1.9.80 显示模式二选一：稳定 simplefb / GPU 3D·DRM。
     * 之前花屏的 GPU 2D(ios2d) / GPU 3D gfxstream(iosgfx) 已从 UI 删除并迁移老存档。
     * DRM 模式只是宿主侧通路：系统内需自装 Mesa Turnip(DRM) + virtio-gpu vdrm 驱动才能真 3D；
     * Windows 无现成驱动，未装仅 2D/软件渲染或黑屏（自动回退 simplefb）；Linux 可装 mesa-guest.deb。
     */
    private fun showGpuLabDialog() {
        val cur = gpuMode()
        val dm = resources.displayMetrics
        fun dp(v: Float) = (v * dm.density).toInt()

        val title = TextView(this).apply {
            text = "显示模式"
            textSize = 19f; setTextColor(Glass.statusWarn())
        }

        // v1.9.38：分辨率区（适配平板）——放在弹窗最上面。
        // 当前模式实际生效分辨率；手机预设 / 平板 16:10 横屏快捷 + 自定义宽高输入。
        val resTitle = TextView(this).apply {
            text = "📐 分辨率（当前模式 · 重启 VM 生效）"
            textSize = 14f; setTextColor(Glass.indicator())
        }
        val resCur = TextView(this).apply {
            text = "当前：${effectiveRes(mode)}" +
                (if (customRes(mode).isNotBlank()) "（自定义）" else "（预设）")
            textSize = 12f; setTextColor(Glass.textHint())
        }
        val resInput = EditText(this).apply {
            hint = "宽x高，如 1920x1200"
            setText(if (customRes(mode).isNotBlank()) customRes(mode) else "")
            textSize = 14f; setTextColor(Glass.textPrimary()); setHintTextColor(Glass.textHint())
            isSingleLine = true; inputType = InputType.TYPE_CLASS_TEXT
            background = Glass.panel(this@MainActivity, 10f)
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            minHeight = dp(44f)
        }
        // v1.9.41：应用分辨率统一逻辑（快捷按钮与输入框共用）
        fun applyRes(raw: String) {
            val norm = normalizeRes(raw)
            if (norm == null) {
                Toast.makeText(this@MainActivity, "分辨率格式不对：宽x高（480~3840），如 1920x1200", Toast.LENGTH_SHORT).show()
                return
            }
            setCustomRes(mode, norm)
            resInput.setText(norm)
            resCur.text = "当前：$norm（自定义）"
            Toast.makeText(this@MainActivity, "已将${if (mode == MODE_WIN11) "Win11" else "Linux"}分辨率设为 $norm（重启 VM 生效）", Toast.LENGTH_SHORT).show()
        }
        // v1.9.41：快捷按钮点一下直接生效，不再只是填入输入框还得再点「应用」
        fun presetResBtn(label: String, value: String) = Button(this).apply {
            text = label; textSize = 12.5f; isAllCaps = false
            background = Glass.button(this@MainActivity)
            setTextColor(Glass.textPrimary()); minHeight = dp(40f); maxHeight = dp(40f)
            isSingleLine = true
            Glass.crystalTouch(this)
            setOnClickListener { applyRes(value) }
        }
        val btnResPhone = presetResBtn("手机默认", if (mode == MODE_WIN11) winPreset.resolution else linuxPreset.resolution)
        val btnResTablet = presetResBtn("平板横屏 16:10", "1920x1200")
        val btnResApply = Button(this).apply {
            text = "应用分辨率"; textSize = 13.5f; isAllCaps = false
            background = Glass.outlinedButton(this@MainActivity)
            setTextColor(Glass.indicator()); minHeight = dp(44f); maxHeight = dp(44f)
            Glass.crystalTouch(this)
        }

        val msg = TextView(this).apply {
            text = "选择 VM 显示输出方式（重启 VM 生效）：\n" +
                "• 稳定 simplefb：固件帧缓冲，不依赖系统显卡驱动，最稳，推荐日常使用。\n" +
                "• GPU 3D·DRM：3D 图形加速通路，需在系统内装好显卡驱动才能生效。\n\n" +
                "⚠ GPU 3D·DRM 需在系统内安装 Mesa Turnip + virtio-gpu 驱动才能开启 3D；" +
                "Windows 目前没有现成的整合驱动，未装时只有 2D 显示、软件渲染或黑屏（会自动切回 simplefb）。" +
                "Linux 可安装 mesa-guest.deb 开启 3D。"
            textSize = 12.5f; setTextColor(Glass.textLog()); setLineSpacing(3f, 1.16f)
        }

        // dialog 先声明，供下面选项按钮点击时 dismiss
        lateinit var dialog: HapticDialog

        btnResApply.setOnClickListener { applyRes(resInput.text.toString()) }
        fun optionBtn(label: String, gm: String, desc: String): LinearLayout {
            val isCur = gm == cur
            val b = Button(this).apply {
                text = if (isCur) "$label  ✓当前" else label
                background = if (isCur) Glass.outlinedButton(this@MainActivity) else Glass.button(this@MainActivity)
                setTextColor(if (isCur) Glass.indicator() else Glass.textPrimary())
                textSize = 14f; isAllCaps = false; isSingleLine = true
                minHeight = dp(44f); maxHeight = dp(44f)
                Glass.crystalTouch(this)
            }
            val d = TextView(this).apply {
                text = desc; textSize = 11.5f; setTextColor(Glass.textHint())
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(d, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(2f); leftMargin = dp(6f)
                })
                b.setOnClickListener {
                    setGpuMode(gm)
                    dialog.dismiss()
                    Toast.makeText(this@MainActivity, "已选择显示模式：${gpuModeLabel()}（重启 VM 生效）", Toast.LENGTH_SHORT).show()
                    refreshGpuLabButton()
                    pickMode(this@MainActivity.mode)
                }
            }
        }

        val btnCancel = Button(this).apply {
            text = "取消"; background = Glass.button(this@MainActivity)
            setTextColor(Glass.textPrimary()); textSize = 14f; isAllCaps = false
            minHeight = dp(44f); maxHeight = dp(44f); Glass.crystalTouch(this)
        }
        // v1.9.43：固定头部（标题 + 分辨率标题 + 当前分辨率），上滑不隐藏
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(resTitle, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14f) })
            addView(resCur, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) })
        }
        // 下方滚动区
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(resInput, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10f) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btnResPhone, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(btnResTablet, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(8f) })
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) })
            addView(btnResApply, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10f) })
            // 分隔 + 显示模式说明
            addView(msg, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(18f); bottomMargin = dp(14f) })
            addView(optionBtn("显示：稳定 simplefb", "simplefb", "固件帧缓冲 · 最稳 · 推荐"))
            addView(optionBtn("显示：GPU 3D · DRM", "iosdrm", "3D 图形加速 · 需在系统内装显卡驱动"), LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10f) })
            addView(btnCancel, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16f) })
        }
        dialog = HapticDialog(this).apply {
            setContentView(wrapDialogContentWithHeader(header, box))
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout((dm.widthPixels * 0.9f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                if (android.os.Build.VERSION.SDK_INT >= 32) {
                    addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    setBackgroundBlurRadius((28f * dm.density).toInt().coerceAtLeast(1))
                }
            }
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 按当前显示模式刷新实验室按钮的文字/配色。 */
    private fun refreshGpuLabButton() {
        val gm = gpuMode()
        btnGpuLab.text = when (gm) {
            "iosdrm" -> "显示：GPU 3D·DRM"
            else -> "显示：稳定 simplefb"
        }
        btnGpuLab.setTextColor(if (gm == "simplefb") Glass.textPrimary() else Glass.statusWarn())
    }

    // ---- 两个模式各自的启动预设：差异真实反映到 crosvm 命令行 ----
    private data class Preset(
        val name: String,
        val memMB: Int,
        val cpus: Int,
        val resolution: String,
        val displayMode: String,
        val mthpMode: String,
        val mmio: Boolean,
    )

    // 第一版两模式都用 simplefb（旧项目真机验证过、不依赖 guest GPU 驱动），
    // 先把「启动 + 显示桌面」这个里程碑稳稳跑通；3D/gfxstream 后续再开。
    // 两模式的真实差异在：镜像、分辨率、内存、mTHP 策略、VM 名称 —— 生成各自独立的启动命令。
    private val winPreset = Preset(
        name = "Win11 ARM64",
        memMB = 5120, cpus = 4, resolution = "1280x720",
        displayMode = "simplefb", mthpMode = "chunked", mmio = false,
    )
    private val linuxPreset = Preset(
        name = "Linux",
        memMB = 5120, cpus = 4, resolution = "720x1584",
        displayMode = "simplefb", mthpMode = "auto", mmio = false,
    )

    private fun importImage() {
        // 内嵌文件浏览器，直接列 /sdcard，不弹系统 SAF
        startActivityForResult(Intent(this, FilePickerActivity::class.java), REQ_IMPORT)
    }

    private fun pickIso() {
        startActivityForResult(Intent(this, FilePickerActivity::class.java), REQ_ISO)
    }

    /** 数据盘：已挂载时点它卸下，未挂载时选一个镜像/磁盘文件当额外存储（读写挂载）。 */
    private fun onDataClick() {
        val cur = sp.getString(K_DATA_DISK, "") ?: ""
        if (cur.isNotBlank()) {
            sp.edit().putString(K_DATA_DISK, "").apply()
            Toast.makeText(this, "已移除数据盘（文件保留）", Toast.LENGTH_SHORT).show()
            setLogText("已移除数据盘 · 下次启动不再挂载")
            pickMode(mode)
        } else {
            startActivityForResult(Intent(this, FilePickerActivity::class.java), REQ_DATA)
        }
    }

    /** 当前模式已选的光盘 ISO 路径。 */
    private fun curIsoPath(): String =
        sp.getString(if (mode == MODE_WIN11) K_WIN_ISO else K_LINUX_ISO, "") ?: ""

    /**
     * v1.9.28：光盘按钮二合一——已选 ISO 时点它「移除光盘」，未选时点它导入。
     * 移除只清配置引用，不删文件（ISO 动辄数 GB，删文件风险大）。
     * 这样进 PE 装完系统后，可在 APP 里直接把安装盘摘掉，再正常关机冷启动，
     * 避免下次仍从光盘引导回 PE（用户实测恢复完直接重启导致蓝屏的场景）。
     */
    private fun onIsoClick() {
        if (curIsoPath().isNotBlank()) removeIso() else pickIso()
    }

    private fun removeIso() {
        val key = if (mode == MODE_WIN11) K_WIN_ISO else K_LINUX_ISO
        sp.edit().putString(key, "").apply()
        Toast.makeText(this, "已移除光盘（仅取消挂载，ISO 文件保留）", Toast.LENGTH_SHORT).show()
        setLogText("已移除${if (mode == MODE_WIN11) " Win11 安装" else ""}光盘 ISO · 下次启动不再挂载光盘")
        pickMode(mode)
    }

    /**
     * v1.9.4：Win11 模式「新建 C 盘」按钮二合一。
     *  - 成品镜像：选一个已装好 Win11 的 qcow2 直接作 C 盘，并自动切到「从 C 盘启动」；
     *  - 新建安装盘：原来的新建空白盘流程（PE 里装系统用）。
     * Linux 模式不变，仍是直接新建空白盘。
     */
    private fun onCdiskClick() {
        val dm = resources.displayMetrics
        fun dp(v: Float) = (v * dm.density).toInt()
        fun choiceButton(label: String): Button = Button(this).apply {
            text = label
            background = Glass.button(this@MainActivity)
            setTextColor(Glass.textPrimary())
            textSize = 14f
            isAllCaps = false; isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            minHeight = dp(46f); maxHeight = dp(46f)
            layoutParams = LinearLayout.LayoutParams(0, dp(46f), 1f).apply {
                leftMargin = dp(6f); rightMargin = dp(6f)
            }
            Glass.crystalTouch(this)
        }
        val win11 = mode == MODE_WIN11
        val title = TextView(this).apply {
            text = if (win11) "系统盘：选择成品镜像 / 新建安装盘 / 打包备份"
                   else "安装盘：新建空白盘 / 打包备份"
            textSize = 18f; setTextColor(Glass.textPrimary())
        }
        val msg = TextView(this).apply {
            text = if (win11)
                "成品镜像：选一个已装好 Win11 的镜像直接当 C 盘启动（推荐）。\n" +
                "新建安装盘：建空白盘，进 PE 后用 WinNTSetup 装系统。\n" +
                "打包备份：把成品 qcow2 镜像压缩成 vmpkg，便于备份/分享。"
            else
                "新建安装盘：建空白盘，进 PE 后用 WinNTSetup 装系统。\n" +
                "打包备份：把成品 qcow2 镜像压缩成 vmpkg，便于备份/分享。"
            textSize = 13f; setTextColor(Glass.textLog()); setLineSpacing(3f, 1.2f)
        }
        val btnFinished = choiceButton("选择成品镜像")
        val btnBlankNew = choiceButton("新建安装盘")
        val btnBackupNew = choiceButton("打包备份")
        val btnCancel = choiceButton("取消")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.dialogPanel(this@MainActivity)
            setPadding(dp(22f), dp(22f), dp(22f), dp(14f))
            addView(title)
            addView(msg, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f); bottomMargin = dp(16f) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btnFinished); addView(btnBlankNew)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(btnBackupNew); addView(btnCancel)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8f) })
        }
        if (!win11) btnFinished.visibility = View.GONE
        val dialog = HapticDialog(this).apply {
            setContentView(wrapDialogContent(box))
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout((dm.widthPixels * 0.9f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                if (android.os.Build.VERSION.SDK_INT >= 32) {
                    addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    setBackgroundBlurRadius((28f * dm.density).toInt().coerceAtLeast(1))
                }
            }
            setCancelable(true); setCanceledOnTouchOutside(true)
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnBlankNew.setOnClickListener { dialog.dismiss(); createBlank() }
        btnBackupNew.setOnClickListener {
            dialog.dismiss()
            startActivityForResult(Intent(this, FilePickerActivity::class.java), REQ_BACKUP)
        }
        btnFinished.setOnClickListener {
            dialog.dismiss()
            startActivityForResult(Intent(this, FilePickerActivity::class.java), REQ_CDISK)
        }
        dialog.show()
    }

    /** 新建 qcow2 空白盘作为安装目标：自定义名称+容量，落盘到 /sdcard/GunyahLauncher/disks/。 */
    private fun createBlank() {
        val dm = resources.displayMetrics
        fun dp(v: Float) = (v * dm.density).toInt()

        // 共用输入样式：主题色文字 + 主题色下划线
        fun styledInput(): EditText = EditText(this).apply {
            setTextColor(Glass.textPrimary())
            textSize = 16f
            setPadding(dp(4f), dp(6f), dp(4f), dp(6f))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                setColor(Color.TRANSPARENT)
                setStroke(dp(1.5f).coerceAtLeast(1), Glass.indicator())
            }
        }

        val defaultName = "${if (mode == MODE_WIN11) "win11_c" else "linux_target"}_${System.currentTimeMillis() / 1000}"
        // 磁盘名称
        val nameInput = styledInput().apply {
            setText(defaultName)
            setSelection(text.length)
            hint = "磁盘名称"
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }
        // 容量（GB）
        val input = styledInput().apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText("64")
            setSelection(text.length)
            hint = "容量 GB（8~512）"
        }

        fun fieldLabel(text: String) = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(Glass.indicator())
        }

        fun dialogButton(label: String): Button = Button(this).apply {
            text = label
            background = Glass.button(this@MainActivity)
            setTextColor(Glass.textPrimary())
            textSize = 14f
            isAllCaps = false
            isSingleLine = true
            ellipsize = android.text.TextUtils.TruncateAt.END
            minHeight = dp(46f)
            maxHeight = dp(46f)
            val lp = LinearLayout.LayoutParams(0, dp(46f), 1f).apply {
                leftMargin = dp(6f); rightMargin = dp(6f)
            }
            layoutParams = lp
            Glass.crystalTouch(this)
        }

        val title = TextView(this).apply {
            text = "新建安装盘 (qcow2)"
            textSize = 19f
            setTextColor(Glass.textPrimary())
        }
        val msg = TextView(this).apply {
            text = "自定义磁盘名称与容量，创建真正的 qcow2 稀疏盘（支持快照、实际占用更小）。\n保存位置：/sdcard/GunyahLauncher/disks/"
            textSize = 13f
            setTextColor(Glass.textLog())
            setLineSpacing(3f, 1.2f)
        }
        val btnCancel = dialogButton("取消")
        val btnCreate = dialogButton("创建")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.dialogPanel(this@MainActivity)
            setPadding(dp(22f), dp(22f), dp(22f), dp(14f))
            addView(title)
            addView(msg, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f); bottomMargin = dp(14f) })
            addView(fieldLabel("磁盘名称"))
            addView(nameInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f); bottomMargin = dp(12f) })
            addView(fieldLabel("容量 (GB)"))
            addView(input, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f) })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(btnCancel)
                addView(btnCreate)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(20f) })
        }

        val dialog = HapticDialog(this).apply {
            setContentView(wrapDialogContent(box))
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout((dm.widthPixels * 0.88f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                // 背景高斯模糊（毛玻璃）：把弹窗背后的内容糊成色块，而非清晰透出文字。
                // Android 12L+ 系统级 background blur；低版本自动降级为仅高不透明玻璃底。
                if (android.os.Build.VERSION.SDK_INT >= 32) {
                    addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                    setBackgroundBlurRadius((28f * dm.density).toInt().coerceAtLeast(1))
                }
            }
            setCancelable(true)
            setCanceledOnTouchOutside(true)
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        btnCreate.setOnClickListener {
            val name = nameInput.text.toString().trim()
            if (name.isBlank()) {
                nameInput.error = "请输入磁盘名称"
                return@setOnClickListener
            }
            val gb = input.text.toString().trim().toIntOrNull()
            if (gb == null || gb !in 8..512) {
                input.error = "请输入 8~512 之间的整数"
                return@setOnClickListener
            }
            dialog.dismiss()
            // qemu-img 建 qcow2 走磁盘 IO，放后台线程，避免卡 UI
            Thread {
                val result = GunyahManager.createQcow2Disk(this@MainActivity, name, gb)
                runOnUiThread {
                    result.fold(
                        onSuccess = { path ->
                            if (mode == MODE_WIN11) {
                                // Win11 C 盘：独立 key，不清 PE 镜像（三盘并存）
                                sp.edit().putString(K_WIN_TARGET, path).apply()
                            } else {
                                sp.edit().putString(K_LINUX_TARGET, path).apply()
                                // Linux 目标盘选定后清掉成品镜像，避免两者混淆
                                sp.edit().putString(K_LINUX_DISK, "").apply()
                            }
                            Toast.makeText(this@MainActivity,
                                "已新建 ${gb}GB qcow2 安装盘：${File(path).name}", Toast.LENGTH_SHORT).show()
                            pickMode(mode)
                        },
                        onFailure = { e ->
                            Toast.makeText(this@MainActivity,
                                "新建失败：${e.message}", Toast.LENGTH_LONG).show()
                        }
                    )
                }
            }.start()
        }
        dialog.show()
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        // v1.9.63：启动控制台失败自动返回后，把失败原因落到主页日志/Toast
        if (req == REQ_BOOT && res == RESULT_OK) {
            val reason = data?.getStringExtra("boot_fail")
            if (!reason.isNullOrBlank()) {
                setLogText("启动失败：$reason")
                Toast.makeText(this, "启动失败：${reason.take(80)}", Toast.LENGTH_LONG).show()
            }
            return
        }
        if (res != RESULT_OK) return
        val src = data?.getStringExtra(FilePickerActivity.EXTRA_PATH) ?: return
        val name = File(src).name
        if (req == REQ_BACKUP) {
            backupToVmpkg(src)
            return
        }
        if (req == REQ_ISO) {
            sp.edit().putString(if (mode == MODE_WIN11) K_WIN_ISO else K_LINUX_ISO, src).apply()
            Toast.makeText(this,
                "已选择${if (mode == MODE_WIN11) " Win11 安装" else ""}光盘 ISO：$name",
                Toast.LENGTH_SHORT).show()
            pickMode(mode)
            return
        }
        if (req == REQ_DATA) {
            sp.edit().putString(K_DATA_DISK, src).apply()
            Toast.makeText(this, "已挂载数据盘：$name", Toast.LENGTH_SHORT).show()
            setLogText("数据盘已挂载：$name（读写）")
            pickMode(mode)
            return
        }
        if (req == REQ_CDISK) {
            // v1.9.56：成品镜像若是 vmpkg 包装，自动识别解包到 disks/ 并引用其中镜像
            if (VmpkgExtractor.isVmpkg(src)) {
                importWinVmpkg(src)
            } else {
                // v1.9.4：普通成品镜像直接作 C 盘，并自动切到「从 C 盘启动」
                // v1.9.74：旧文件属主可能是 root（FUSE 视图下改不动），选中时顺手修正
                com.gunyah.launcher.core.VmpkgExtractor.fixOwnership(src)
                sp.edit().putString(K_WIN_TARGET, src)
                    .putString(K_WIN_BOOT, "system").apply()
                Toast.makeText(this, "已把 $name 设为 C 盘，并切到「优先 C 盘启动」", Toast.LENGTH_SHORT).show()
                pickMode(mode)
            }
            return
        }
        if (req != REQ_IMPORT) return
        // 不复制到 APP 私有目录：镜像只占一份空间（与 Linux-dsu 一致）。
        // 直接存原始路径，启动脚本的 real_path() 会把 /sdcard 映射成
        // /data/media/0 的真实路径（ext4/f2fs，支持 flock），crosvm 以 root 直接读原文件。
        sp.edit().putString(if (mode == MODE_WIN11) K_WIN_DISK else K_LINUX_DISK, src).apply()
        // 导入成品镜像即放弃光盘安装模式（清掉 ISO/目标盘）—— 仅 Linux；
        // Win11 导入的是 PE 镜像，需与 ISO/C盘三盘并存，不清。
        if (mode == MODE_LINUX) {
            sp.edit().putString(K_LINUX_ISO, "").putString(K_LINUX_TARGET, "").apply()
        }
        Toast.makeText(this, "已为 ${if (mode == MODE_WIN11) "Win11" else "Linux"} 选择 $name", Toast.LENGTH_SHORT).show()
        pickMode(mode)
    }

    /**
     * v1.9.72：把选中的成品镜像（qcow2/raw）打包成 vmpkg，落到
     * /sdcard/GunyahLauncher/backups/，便于备份/分享给别人导入。
     * 打包几个 GB 耗时，用进度弹窗 + 后台线程；v1.9.73 起用 ZSTD 压缩（同 DroidVM 官方）。
     */
    private fun backupToVmpkg(src: String) {
        val dm = resources.displayMetrics
        fun dp(v: Float) = (v * dm.density).toInt()
        val ui = Handler(Looper.getMainLooper())
        val diskName = File(src).name

        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        val tvStatus = TextView(this).apply {
            text = "准备打包…"; textSize = 13f; setTextColor(Glass.textLog())
        }
        val tvTitle = TextView(this).apply {
            text = "打包为 vmpkg 备份"; textSize = 18f; setTextColor(Glass.textPrimary())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.dialogPanel(this@MainActivity)
            setPadding(dp(22f), dp(22f), dp(22f), dp(22f))
            addView(tvTitle)
            addView(tvStatus, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14f) })
            addView(pb, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) })
        }
        val dialog = HapticDialog(this).apply {
            setContentView(box)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout((dm.widthPixels * 0.88f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        dialog.show()

        Thread {
            val outName = diskName.substringBeforeLast('.') + ".vmpkg"
            val outPath = "/sdcard/GunyahLauncher/backups/$outName"
            val result = com.gunyah.launcher.core.VmpkgPacker.pack(
                src, outPath, diskName, filesDir.absolutePath
            ) { pct, text ->
                ui.post {
                    if (!dialog.isShowing) return@post
                    pb.progress = pct.coerceIn(0, 100)
                    tvStatus.text = text
                }
            }
            ui.post {
                runCatching { if (dialog.isShowing) dialog.dismiss() }
                if (!result.ok) {
                    setLogText("vmpkg 打包失败：${result.message}")
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    return@post
                }
                val mb = result.size / 1024 / 1024
                setLogText("vmpkg 打包完成：$outPath（${mb}MB）· 可在文件管理器分享给他人")
                Toast.makeText(this, "打包完成：${File(result.outPath).name}（${mb}MB）\n$outPath", Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    /**
     * v1.9.56：Win 模式成品镜像选择到 vmpkg 包装——后台解压到 disks/、引用主盘、删原包。
     * 解压几个 GB 可能耗时，用进度弹窗 + 后台线程，完成回主线程更新配置。
     */
    private fun importWinVmpkg(src: String) {
        val dm = resources.displayMetrics
        fun dp(v: Float) = (v * dm.density).toInt()
        val ui = Handler(Looper.getMainLooper())

        val pb = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progress = 0
        }
        val tvStatus = TextView(this).apply {
            text = "正在识别 vmpkg…"; textSize = 13f; setTextColor(Glass.textLog())
        }
        val tvTitle = TextView(this).apply {
            text = "解包 vmpkg 成品镜像"; textSize = 18f; setTextColor(Glass.textPrimary())
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.dialogPanel(this@MainActivity)
            setPadding(dp(22f), dp(22f), dp(22f), dp(22f))
            addView(tvTitle)
            addView(tvStatus, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(14f) })
            addView(pb, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) })
        }
        val dialog = HapticDialog(this).apply {
            setContentView(box)
            window?.apply {
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                setLayout((dm.widthPixels * 0.88f).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            setCancelable(false)
            setCanceledOnTouchOutside(false)
        }
        dialog.show()

        Thread {
            val destDir = "/sdcard/GunyahLauncher/disks"
            val result = VmpkgExtractor.extract(src, destDir, filesDir.absolutePath) { pct, text ->
                ui.post {
                    if (!dialog.isShowing) return@post
                    pb.progress = pct.coerceIn(0, 100)
                    tvStatus.text = text
                }
            }
            ui.post {
                runCatching { if (dialog.isShowing) dialog.dismiss() }
                if (!result.ok) {
                    setLogText("vmpkg 解包失败：${result.message}")
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                    return@post
                }
                if (result.mainDisk.isBlank() || !com.gunyah.launcher.core.RootShell.pathExists(
                        result.mainDisk.replace("/sdcard/", "/data/media/0/"))) {
                    setLogText("vmpkg 解包完成但未找到可用镜像：${result.message}")
                    Toast.makeText(this, "vmpkg 里没有可用的磁盘镜像", Toast.LENGTH_LONG).show()
                    return@post
                }
                // 引用主盘作 C 盘并切「优先 C 盘启动」
                sp.edit().putString(K_WIN_TARGET, result.mainDisk)
                    .putString(K_WIN_BOOT, "system").apply()
                // 删除原 vmpkg（含分卷子卷）——仅在成功引用解出镜像后
                deleteVmpkgPackage(src)
                setLogText("vmpkg 解包完成：${result.message} · 已设为 C 盘（优先 C 盘启动）")
                Toast.makeText(this, "已解包并设为 C 盘：${File(result.mainDisk).name}", Toast.LENGTH_LONG).show()
                pickMode(mode)
            }
        }.start()
    }

    /** 删除 vmpkg 主文件及其全部分卷子卷（root 走 /data/media/0 真实路径）。 */
    private fun deleteVmpkgPackage(src: String) {
        try {
            val master = if (src.matches(Regex(".*\\.\\d{3}")))
                src.substring(0, src.length - 4) else src
            val sb = StringBuilder()
            // 主文件
            sb.append("rm -f \"").append(master.replace("/sdcard/", "/data/media/0/")).append("\"\n")
            // 分卷子卷 .001 ~ .999
            sb.append("for f in \"").append(master.replace("/sdcard/", "/data/media/0/"))
                .append("\".[0-9][0-9][0-9]; do [ -f \"\$f\" ] && rm -f \"\$f\"; done\n")
            RootShell.exec(sb.toString(), timeoutMs = 20000)
        } catch (e: Exception) {
            Log.w("MainActivity", "删除 vmpkg 失败", e)
        }
    }

    /** 按当前模式生成独立 Config —— 每个模式一套独立启动命令。 */
    private fun configFor(m: Int): GunyahManager.Config {
        val p = if (m == MODE_WIN11) winPreset else linuxPreset
        val cfg = GunyahManager.loadConfig(this).apply {
            name = if (m == MODE_WIN11) "win11" else "linux"
            memMB = p.memMB
            cpus = p.cpus
            // v1.9.38：自定义分辨率优先（平板适配），未设置则用模式预设
            resolution = effectiveRes(m)
            // v1.9.80：显示模式二选一——simplefb(稳定) / GPU 3D·DRM(iosdrm)。
            // 花屏的 GPU 2D(ios2d)/GPU 3D(iosgfx) 已删（老存档在 gpuMode() 里迁回 simplefb）。
            // GPU 起不来会自动逐级回退 simplefb，不会连累稳定启动。
            displayMode = when (val gm = gpuMode()) {
                "iosdrm" -> gm
                else -> p.displayMode
            }
            mthpMode = p.mthpMode
            mmio = p.mmio
            displayBackend = "android"   // 原生显示，进去就是桌面
            // 统一用工具链自带的 unixnu-bin/crosvm：声音、网络都正常，且它同样
            // 带 AServiceManager_addService + crosvm_display，原生显示可用。
            // 内置自编 crosvm-mmio 仅供 iOS/MMIO 用（virtio-snd/aaudio 音频线程
            // 会 SIGSEGV、帧缓冲大页崩溃），本 APP 跑 Win11/Linux 成品镜像不再集成它。
            pciUseToolchainCrosvm = true
            // 恢复声音：工具链 crosvm 的 virtio-snd/AAudio 实现与内置版不同，
            // 没有内置版那个固定 pc 0x89a020 的音频线程崩溃，声音可正常开启。
            audio = true
            gpu = true
            systemDisk = diskFor(m)
            systemDiskRo = false   // 成品 qcow2 镜像需要可写（增量盘 / 首次开机写入）
            bootDisk = ""
            cdRom = ""
            dataDisk = ""
            // 成品镜像一律用「优先硬盘 + 出厂 NVRAM 重新枚举」启动。
            // 原因：UEFI 启动顺序存在 vars.fd，只要里面残留一条失效的 Boot00xx
            // （例如之前挂 ISO/旧盘时固件写的 CD-ROM / UEFI Misc Device 启动项），
            // 固件开机就直奔它 -> Not Found -> 掉 PXE，无论新盘内容多正确。
            // 设为 disk 会走 GunyahManager 的「抽干净 vars.fd + 硬盘 bootindex=1」
            // 分支，固件当新机器重新枚举，才能扫到新盘的 \EFI\BOOT\BOOTAA64.EFI。
            bootOrder = "disk"
            // Linux 模式：装系统时挂光盘 ISO + 空白目标盘，优先从光盘引导（覆盖上面的 disk）。
            if (m == MODE_LINUX) {
                val iso = isoPath()
                val target = targetPath()
                if (iso.isNotBlank()) {
                    cdRom = iso
                    bootOrder = "cdrom"      // 光盘排 bootindex=1，用出厂 NVRAM 重新枚举
                }
                if (target.isNotBlank()) {
                    systemDisk = target
                    systemDiskRo = false     // 安装目标盘必须可写
                }
            }
            // Win11 模式：三盘装系统。PE 盘(K_WIN_DISK) + Win11 ISO(K_WIN_ISO) + C盘(K_WIN_TARGET)。
            // 从 PE 启动：PE 作启动盘(bootindex=1)，ISO 作光驱(bootindex=2)，C盘作数据/目标盘(可见可写)。
            // 从 C 盘启动：只挂装好的 C 盘作系统盘(bootindex=1)，不挂 PE/ISO。
            if (m == MODE_WIN11) {
                val winIso = sp.getString(K_WIN_ISO, "") ?: ""
                val winTarget = sp.getString(K_WIN_TARGET, "") ?: ""
                val winBoot = sp.getString(K_WIN_BOOT, "pe") ?: "pe"
                if (winBoot == "pe") {
                    // PE 启动盘：K_WIN_DISK 已在上面 systemDisk=diskFor(m) 设置，这里改挂到 bootDisk 作 bootindex=1
                    val pe = diskFor(m)
                    bootDisk = pe
                    bootDiskRo = false
                    systemDisk = ""          // PE 启动不需要额外系统盘，避免重复挂 PE
                    if (winIso.isNotBlank()) cdRom = winIso
                    if (winTarget.isNotBlank()) {
                        dataDisk = winTarget  // C 盘作为安装目标，PE 里能看到
                    }
                    bootOrder = "disk"       // 启动盘(PE)=bootindex=1，干净 NVRAM 重新枚举
                } else {
                    // 从装好的 C 盘启动：C 盘作系统盘，清掉 PE/ISO
                    systemDisk = winTarget
                    systemDiskRo = false
                    bootDisk = ""
                    cdRom = ""
                    dataDisk = ""
                    bootOrder = "disk"
                }

            }
        }
        return cfg
    }

    private fun startVm() {
        val cfg = configFor(mode)
        // v1.5.8：Win11 / Linux 两个模式都全屏铺满，不再用底部黑条让位。
        getSharedPreferences("native_disp", Context.MODE_PRIVATE).edit()
            .putBoolean("fullscreen_mode", true).apply()

        // v1.9.63：点「启动」立即进控制台——不再等前置校验/大页采集跑完。
        // 控制台先显示「正在准备启动环境」，真正的校验+下发在下面后台线程做，结果通过
        // BootLaunchState 传给控制台；失败则控制台红字约 3 秒后自动返回主页。
        ConfigHolder.last = parseRes(cfg.resolution)
        BootLaunchState.begin()  // 重置：launched=false, failReason=null
        // 提前清空旧 boot.log，避免控制台一进来误读上一次的「UEFI 就绪 / 崩溃」marker。
        // start() 内部还会再清一次（无妨），这里清是为了抢占时序。
        runCatching {
            val priv = GunyahManager.privateDir(this)
            java.io.File(priv, "boot.log").writeText("")
            java.io.File(priv, com.gunyah.launcher.core.BootLog.ROTATED_TAIL).delete()
        }
        startActivityForResult(
            Intent(this, BootActivity::class.java).putExtra("mode", mode), REQ_BOOT
        )

        Thread {
            // /sdcard 镜像 APP 无直读权限，File.exists() 会误判，改用 root 探测真实存在。
            // v1.9.27：与启动脚本 real_path() 保持一致——/sdcard 映射到 /data/media/0，
            // 否则 FUSE 路径 test -f 可能误判（明明文件在却说不存在，或反之）。
            fun realPath(p: String): String = when {
                p.startsWith("/sdcard/") -> "/data/media/0/" + p.removePrefix("/sdcard/")
                p.startsWith("/storage/emulated/0/") -> "/data/media/0/" + p.removePrefix("/storage/emulated/0/")
                else -> p
            }
            fun exists(p: String) = try {
                val rp = realPath(p)
                com.gunyah.launcher.core.RootShell
                    .exec("test -f '${rp.replace("'", "'\\''")}' && echo OK", timeoutMs = 8000)
                    .stdout.contains("OK")
            } catch (t: Throwable) { false }
            fun fail(reason: String) { BootLaunchState.fail(reason) }

            // 没有任何盘可用：直接判定失败（控制台会显示原因并返回）
            if (cfg.systemDisk.isBlank() && cfg.bootDisk.isBlank()) {
                val winBoot = sp.getString(K_WIN_BOOT, "pe") ?: "pe"
                fail(when {
                    mode == MODE_WIN11 && winBoot == "pe" -> "优先 PE 启动需先「导入 PE 镜像」"
                    mode == MODE_WIN11 -> "优先 C 盘启动需先「系统盘」并装好 Win11"
                    mode == MODE_LINUX && cfg.cdRom.isNotBlank() -> "光盘安装需先「新建空白盘」作为目标"
                    else -> "请先为该模式导入成品镜像"
                })
                return@Thread
            }
            // 启动盘或系统盘至少有一个存在即可（PE 启动用 bootDisk，C 盘启动用 systemDisk）
            val mainDisk = if (cfg.bootDisk.isNotBlank()) cfg.bootDisk else cfg.systemDisk
            if (!exists(mainDisk)) { fail("镜像文件不存在或已移动，请重新选择"); return@Thread }
            if (cfg.cdRom.isNotBlank() && !exists(cfg.cdRom)) {
                fail("光盘 ISO 不存在或已移动，请重新选择"); return@Thread
            }
            // v1.9.27：数据盘（PE 装系统的目标盘）丢了要显式提醒（不阻断启动，仅日志）
            if (cfg.dataDisk.isNotBlank() && !exists(cfg.dataDisk)) {
                val dn = try { java.io.File(cfg.dataDisk).name } catch (_: Throwable) { cfg.dataDisk }
                runOnUiThread {
                    setLogText("⚠ 安装盘文件已丢失：$dn · PE 里将看不到它。请点「系统盘」→新建安装盘重建后再启动")
                }
            }
            // v1.9.77：启动前严格核对全部核心（crosvm + 固件 + 显示库 + 内核模块），
            // 不全则在线重下一次；仍不全就明确拦下，不让用户带着半套工具链进控制台白等。
            val (tok, tmsg) = GunyahManager.ensureToolchain(this)
            if (!tok) {
                fail("工具链核心不齐，启动已阻止 → $tmsg\n请回主页点「重新下载」，下载完成再启动")
                return@Thread
            }
            val (ok, msg) = GunyahManager.start(this, cfg)
            if (ok) BootLaunchState.launched() else fail(msg)
        }.start()
    }

    private fun stopVm() {
        Thread {
            val (ok, msg) = GunyahManager.stop(this)
            runOnUiThread { Toast.makeText(this, if (ok) "已停止" else "停止: $msg", Toast.LENGTH_SHORT).show() }
        }.start()
    }

    private fun parseRes(res: String): Pair<Int, Int> {
        val r = res.trim().lowercase().replace('×', 'x').replace('*', 'x')
        val w = r.substringBefore('x').toIntOrNull() ?: 720
        val h = r.substringAfter('x', "1584").toIntOrNull() ?: 1584
        return w to h
    }
}
