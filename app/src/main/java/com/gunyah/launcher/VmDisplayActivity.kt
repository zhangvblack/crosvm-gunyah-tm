package com.gunyah.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.content.ServiceConnection
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import android.crosvm.ICrosvmAndroidDisplayService
import com.gunyah.launcher.core.EvdevEncoder
import com.gunyah.launcher.core.GunyahManager
import com.gunyah.launcher.core.ImeCaptureEditText
import com.gunyah.launcher.core.INativeDisplayService
import com.gunyah.launcher.core.InputServiceClient
import com.gunyah.launcher.core.KeyCodeMapper
import com.gunyah.launcher.core.NativeDisplayRootService
import com.gunyah.launcher.core.NativeInputBridge
import com.gunyah.launcher.ui.Glass
import com.topjohnwu.superuser.ipc.RootService
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

/**
 * v2.0 内置原生显示器（替代独立 display_app）。
 *
 * 一条链路全部在主程序里（v2.0.10 架构）：
 *   自有 root 服务(InputSocketHost) 在 VM 启动前 bind+listen 4 路 unix socket
 *     → crosvm 启动时 connect（crosvm 是 connect 方）
 *   本页 SurfaceView → 自有 root 服务拿到 crosvm_display binder → setSurface
 *   本页触摸/按键 → NativeInputBridge → AIDL writeInput → root 服务直写 socket → crosvm
 *
 * 输入不再经过 native touchd / APP 直连本地 TCP（那层在真机被 SELinux 拦，
 * 是「能显示不能操作」的根因）。socket 由 root 服务进程常驻持有，与 APP 页面
 * 生命周期解耦；任何一步失败自动落回内置 VNC，不会出现「有画面没输入」的死局。
 */
class VmDisplayActivity : BaseActivity(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "NativeDisplay"
        const val SERVICE_NAME = "crosvm_display"
        /**
         * v2.0.24 输入模式（0/1 与旧版兼容）：
         *  0 = 触摸板：整屏当笔记本触摸板，相对位移移动光标（默认，替代旧的「鼠标」档）
         *  1 = 多点触控：绝对触点，留给 iOS/触控系统的手势
         *  2 = 直接点按：旧的「按哪到哪」绝对指针，保留为可随时切回的一档
         * 老用户存的 0 会自然变成触摸板，1 仍是多点触控，无需迁移配置。
         */
        const val MODE_TOUCHPAD = 0
        const val MODE_MULTITOUCH = 1
        const val MODE_ABSOLUTE = 2
        private const val SP_NAME = "native_disp"
        private const val SP_MODE = "input_mode"
        private const val SP_TP_GAIN = "tp_gain"
        // v2.0.27：软件箭头默认不画（桌面系统自带指针），开关按「当前启动盘组合」分别记忆。
        // 旧版全局键 tp_cursor 不再读取：升级后一律回到默认关，立刻消除双箭头。
        private const val SP_TP_CURSOR_PROFILE = "tp_cursor_"
        private const val SP_TP_CURSOR_SIZE = "tp_cursor_size"  // v2.0.26 箭头大小档位
        // v2.0.26：v2.0.25 的箭头约 26dp 高 + 2.5dp 描边，实测偏大、盖在浅色桌面上不自然。
        // 以尖端 (0,0) 为锚整体缩放：小 0.58 / 中 0.75 / 大 0.92（约 15 / 19 / 24 dp 高）。
        // 长按「指针」循环，持久化；默认「小」。
        private val CURSOR_SCALES = floatArrayOf(0.58f, 0.75f, 0.92f)
        private val CURSOR_SIZE_NAMES = arrayOf(Lang.t("小"), Lang.t("中"), Lang.t("大"))
        private const val CURSOR_SIZE_DEFAULT = 0
        // v2.0.24 触摸板手势参数
        private const val TP_TAP_SLOP_DP = 10f       // 位移小于它判「轻点」
        private const val TP_TAP_TIMEOUT_MS = 240L   // 轻点最长按下时长
        // v3.1：单指双击唤醒宿主输入法——两次轻点间隔上限
        private const val DT_DOUBLE_MS = 360L
        private const val TP_LONG_PRESS_MS = 260L    // 按住不动超过它 → 进入「待判定」（随后滑动可转拖动）
        private const val TP_RIGHT_LONG_MS = 620L    // 按住不动超过它且未滑动 → 右键单击（弹出上下文菜单）
        private const val TP_WHEEL_STEP_DP = 20f     // 双指每滑动这么多 dp 发一格滚轮
        private val TP_GAINS = floatArrayOf(0.7f, 1.0f, 1.6f)  // 长按「模式」循环：慢/中/快
        // v2.0.18：键盘入口贴边收纳。0=浮球，1/2/3/4=贴左/右/上/下边缘的透明细杠条。
        private const val SP_DOCK_EDGE = "dock_edge"
        private const val SP_DOCK_POS = "dock_pos"
        private const val SP_BALL_X = "ball_x"
        private const val SP_BALL_Y = "ball_y"
        private const val SP_DOCK_TIP = "dock_tip"
        private const val EDGE_SNAP_DP = 46f    // 浮球中心离边小于它 → 吸附收纳
        private const val UNSNAP_DP = 42f       // 细条向屏内拖超过它 → 还原浮球
        private const val DOCK_THICK_DP = 24f    // 竖杠条厚度：贴边一条细杠
        private const val DOCK_LONG_DP = 120f    // 竖杠条长度
        // v2.0.20：键盘窗口自由拖动位置（按屏幕宽高比例记忆）+ 显示器防误退锁
        private const val SP_KB_OFF_X = "kb_off_x"
        private const val SP_KB_OFF_Y = "kb_off_y"
        // v2.0.22：纯软件防误退锁（不再用系统屏幕固定，见 1308 起注释）。
        // 沿用 display_lock2：用户上次手动点过 🔓 解锁仍被记住；没点过的默认上锁。
        private const val SP_DISPLAY_LOCK = "display_lock2"
        // v1.5.7：Linux 输入驱动正常，全屏铺满无需底部让位；Win11 需让位保证任务栏可点。
        private const val SP_FULLSCREEN = "fullscreen_mode"
        private const val DRAG_SLOP_DP = 12f    // 超过它才判定为「拖动工具条」，轻点仍是按钮
        // v2.0.16：按键反馈配色（图2 效果）
        // v3.0：手机输入法浅色风格——白键 / 浅灰按下 / 深色字
        private val KB_BG = 0xFFFFFFFF.toInt()
        private val KB_BG_DOWN = 0xFFD6DBE1.toInt()
        private val KB_TEXT = 0xFF1B1B1F.toInt()
        private val KB_TEXT_ACCENT = 0xFF0A84FF.toInt()
        private val KB_PANEL_BG = 0xFFE8EAED.toInt()
        private val PREVIEW_BG = 0xFF37474F.toInt()
    }

    private lateinit var surfaceView: SurfaceView
    private lateinit var panel: LinearLayout
    private lateinit var ball: TextView
    // v2.0.18：键盘入口形态。dockEdge=0 是 44dp 浮球；1/2/3/4 是贴左/右/上/下的
    // 半透明模糊细杠条。同一个 View 切形态，避免两个入口并存互相抢显隐。
    // v2.0.40：默认贴右边收纳（2），不再默认浮球；点细条出键盘，向屏内拖还原浮球。
    private var dockEdge = 2
    private var dockPos = 0.45f      // 细条中心在所贴边上的比例
    private var ballRx = 0.93f       // 浮球中心相对屏幕宽/高的比例（还原位置）
    private var ballRy = 0.44f
    private var entryReady = false   // 首次布局完成前不贴边，交给 root.post 的 applyEntryShape
    // 拖动中禁止布局监听重定位，否则每次改 margin 都会被拉回记忆位置（拖不动）。
    @Volatile private var entryDragging = false
    private lateinit var rootFrame: FrameLayout
    // v2.0.17：键盘 4 向旋转只转这一层（键盘 + 浮出气泡一起转）；
    // 画面 SurfaceView 与 ⌨ 浮球在 rootFrame 里，方向保持不变。
    private lateinit var kbHost: FrameLayout
    // v2.0.15：全键盘视图引用，统一调 padding/字号，不重建面板。
    private val keyViews = ArrayList<TextView>()
    private var numpadView: LinearLayout? = null
    private var numBtn: TextView? = null
    private var rotateBtn: TextView? = null
    // v2.0.20：🔒 防误退锁按钮与其状态
    private var lockBtn: TextView? = null
    private var displayLocked = true   // v2.0.22：默认上锁（纯软件锁，不占屏幕底部）
    private var lockToast: Toast? = null
    // v1.5.4：device owner 设置较重（root 临时禁 gms + dpm），每个进程只需尝试一次；
    // 成功则记 ownerReady，之后仅补设 lockTaskFeatures，不再跑重脚本。
    @Volatile private var ownerTried = false
    @Volatile private var ownerReady = false
    // v1.5.7：Linux 模式全屏（不让位），Win11 模式底部留窄边保证任务栏可点
    private var fullscreenMode = false
    // v2.0.20：键盘窗口位置（相对屏幕宽/高的比例），拖动工具条改变，跨启动记忆
    private var kbOffX = 0f
    private var kbOffY = 0f
    // v2.0.16：按键按下时浮出的放大字符预览（图2），以及按下态。
    private var keyPreview: TextView? = null
    private var kbRotation = 0   // 0 / 90 / 180 / 270，默认图1 布局（0°）
    // v2.0.17：⌫ 长按连续删除
    private var repeatActive = false
    private var repeatRunnable: Runnable? = null
    private var repeatStart: Runnable? = null

    private var displayService: ICrosvmAndroidDisplayService? = null
    private var rootService: INativeDisplayService? = null
    private var surface: Surface? = null
    private var bound = false
    private var fellBack = false
    private var surfaceSet = false
    // 出过一次画面就永久取消自动兜底：切后台会 surfaceDestroyed 把 surfaceSet 清零，
    // 但那不是失败。旧版据此误判并写回配置，导致「第二次再也进不去原生显示」。
    private var everSurfaceSet = false
    // v2.0.4：crosvm 的 setSurface 每个 surface 生命周期只接受一次。
    // surfaceSent 是硬闸门；needsSend 由 surfaceCreated 置位；hasSavedFrame
    // 用于 surface 重建后立刻恢复最后一帧（静态桌面不会重新绘制）。
    private var surfaceSent = false
    private var needsSend = false
    private var hasSavedFrame = false
    private var guestW = 0
    private var guestH = 0
    // v2.0.14：输入设备的坐标基准，来自 native.res（脚本写入的 宽 高 = 720x1584），与
    // --input 的 width/height 严格一致。绝不能被 displayConfig 覆盖：crosvm 的 DisplayConfig
    // 报的是 1280x800，而设备声明的 ABS 范围是 720x1584，用错会把触点 clamp 到边角。
    private var inputW = 0
    private var inputH = 0
    // v2.0.14：true=7790 是 absolute-mouse(发 EV_ABS)；false=旧工具链回退的相对 mouse。
    // 由脚本探测 crosvm 能力后写 work/input.abs；读不到默认 true（新版工具链）。
    @Volatile private var absPointer = true
    private val ui = Handler(Looper.getMainLooper())
    private var alive = true
    // v2.0.7 双输入模式 → v2.0.24 三档：默认触摸板（笔记本手感）；多点触控留给
    // iOS/触控系统；直接点按保留旧的绝对指针。持久化，下次进原生显示沿用上次选择。
    @Volatile     private var inputMode = MODE_TOUCHPAD
    private var modeToggle: TextView? = null
    // v2.0.24 触摸板状态机：只在 UI 线程读写（setOnTouchListener 回调），无需加锁。
    private var tpVX = 0f; private var tpVY = 0f      // 虚拟光标（guest ABS 坐标，浮点累积）
    private var tpInit = false                        // 是否已与 guest 光标对齐
    private var tpLastX = 0f; private var tpLastY = 0f
    private var tpDownX = 0f; private var tpDownY = 0f
    private var tpDownTime = 0L
    private var tpMoved = false
    private var tpDragging = false
    private var tpLongPress: Runnable? = null
    // v3.2：单指长按（未滑动）→ 右键菜单。tpLongFired 标记长按计时器已触发，
    // 与 tpDragging 互斥：按住不动到 620ms 发右键；按住后滑动仍是左键拖动。
    private var tpRightLongPress: Runnable? = null
    private var tpLongFired = false
    // v3.2：绝对指针（直接点按）模式的单指长按右键状态
    private var absLongPress: Runnable? = null
    private var absDownX = 0; private var absDownY = 0
    private var absLongFired = false
    private var tpTwoFinger = false
    private var tpTwoCX = 0f; private var tpTwoCY = 0f
    private var tpTwoMoved = false
    private var tpTwoDownTime = 0L
    private var tpTwoTapDone = false
    private var tpWheelAccX = 0f; private var tpWheelAccY = 0f
    private var tpGainIdx = 1                        // 默认中速 ×1.0
    // v3.0：三指轻点 = 右键（原双指右键改到这里）；双指轻点 = 唤醒宿主输入法。
    private var tpThreeFinger = false
    private var tpThreeMoved = false
    private var tpThreeDownTime = 0L
    // 宿主输入法捕获框（透明 1dp，两指轻点时聚焦并弹出系统键盘）
    private var imeEdit: ImeCaptureEditText? = null
    @Volatile private var imeShowing = false
    // v3.0：多点触控模式下的双指轻点检测（独立状态，不干扰 sendMultiTouch）
    private var mtTwoDown = false; private var mtTwoMoved = false
    private var mtTwoDownTime = 0L; private var mtTwoCX = 0f; private var mtTwoCY = 0f
    // v3.1：单指双击唤醒宿主输入法（独立于输入模式，物理鼠标不触发）
    private var dtLastUpMs = 0L
    private var dtLastX = 0f; private var dtLastY = 0f
    private var dtDownX = 0f; private var dtDownY = 0f
    private var dtMoved = false
    // v2.0.25 软件光标：guest 不画指针的系统（固件/GRUB/部分 Linux）靠它知道光标在哪。
    // 只在触摸板模式显示 —— 只有这个模式 APP 才持有光标位置。v3.1 起默认关，
    // 需要时到虚拟键盘工具条「指针」自行打开（仅本次有效）。
    private var cursorView: CursorOverlayView? = null
    private var cursorBtn: TextView? = null
    // v2.0.27：默认不画。guest 自带指针时 APP 那个箭头是多余且不同步的第二个箭头。
    private var cursorVisible = false
    private var cursorKey = SP_TP_CURSOR_PROFILE + "default"   // 当前启动盘组合对应的记忆键
    private var cursorSizeIdx = CURSOR_SIZE_DEFAULT   // v2.0.26：默认「小」
    // v2.0.3：把链路每一步的结果直接显示在状态栏，不用 logcat 也能一次定位
    @Volatile private var diag = Lang.t("初始化…")

    // v3.3：区分「冷启动首次进入」与「从后台返回」。只有后者才注入唤醒事件，
    // 避免首次启动时 socket 还没连上就发无用事件，也避免干扰开机自检画面。
    private var hasResumedOnce = false
    // v3.4：唤醒去抖——onResume 与 onWindowFocusChanged 都会来，电源键绝不能连按两次
    // （Win11 连按两次电源键会进睡眠，反而更黑）。3 秒内只唤醒一轮。
    private var lastWakeAt = 0L

    private val rootConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            rootService = INativeDisplayService.Stub.asInterface(service)
            diag = Lang.t("root✓ 服务…")
            Log.i(TAG, "root service connected; diag=${runCatching { rootService?.diagnostics(SERVICE_NAME) }.getOrNull()}")
            Thread({ attachDisplay() }, "native-attach").apply { isDaemon = true }.start()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            rootService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fullScreen()
        readGuestResolution()
        loadInputMode()
        loadEntry()
        loadKbOffsetAndLock()

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            // v1.9.76：可聚焦，作为 imeEdit 失焦后的焦点归宿，避免输入法框霸占焦点吞鼠键。
            isFocusable = true
            isFocusableInTouchMode = true
            // v2.0.19：键盘层会整体旋转，内容可能越过自身 layout 框；关掉裁剪，
            // 否则旋转到 90/270° 时贴左/贴右的键盘会被父容器裁掉一部分。
            clipChildren = false
            clipToPadding = false
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootFrame = root

        surfaceView = SurfaceView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.CENTER }
            // v2.0.3 黑屏根因：guest 帧缓冲已折算成 720×1584，SurfaceView 却仍是
            // 全屏 1440×3168。crosvm 的 android_display 后端要求 Surface 尺寸与
            // 帧缓冲一致，对不上就 setSurface 成功也不出画面。
            // v1.7.8：横竖都铺满全屏 —— 竖屏持有 720x1584、横屏持有 1584x720，
            // 两个方向都 MATCH_PARENT 铺满容器、零黑边。见 updateSurfaceLayout()。
            // setFixedSize 让 Surface = 帧缓冲尺寸；触摸 e.x/e.y 是 View 局部坐标，
            // scales() 仍按 View 宽高换算，所以不需要额外黑边偏移，输入基准 inputW/H 不动。
            if (guestW > 0 && guestH > 0) holder.setFixedSize(guestW, guestH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setSurfaceLifecycle(SurfaceView.SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)
            }
            holder.addCallback(this@VmDisplayActivity)
            // 关键：只有一个 SurfaceView，不再叠第二层 cursor surface 吞触摸
            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()
        }
        attachTouch(surfaceView)
        root.addView(surfaceView)

        // v2.0.25 软件光标：普通 View 叠加，**不是第二个 SurfaceView**。历史教训见
        // 249 行注释：早期叠过 cursor surface，结果把触摸吞了才删掉。普通 View 默认
        // 不 clickable、onTouchEvent 返回 false，触摸会自然穿透到下层 SurfaceView；
        // 加在 surfaceView 之后、kbHost 之前，键盘弹出时光标被键盘盖住，不挡按键。
        cursorView = CursorOverlayView(this).apply {
            val d = resources.displayMetrics.density
            val size = (28 * d).roundToInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            isClickable = false
            isFocusable = false
            // v1.8.3：View 默认 VISIBLE 且锚在左上角(0,0)，进入显示器后若不触摸/不旋转，
            // updateCursorOverlay() 不会被调用，箭头就一直挂在左上角。这里创建即隐藏，
            // 只有工具条「指针」打开且 tpInit 后才由 updateCursorOverlay 显示。
            visibility = View.INVISIBLE
        }
        root.addView(cursorView)

        // v2.0.15：顶部检测状态卡片已删除（用户要求）。同时停掉每秒轮询
        // startStatusLoop()，省电、省 binder。诊断改为「长按 ⌨ 浮球 / 面板 ⓘ」临时查看，
        // 平时画面零遮挡。

        // v2.0.17：键盘放进独立的全屏容器。旋转只作用于 kbHost，画面与浮球不动。
        // kbHost 不 clickable，触摸落在键盘外时自然穿透到下层 SurfaceView。
        kbHost = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            clipChildren = false
            clipToPadding = false
        }
        panel = buildPanel()
        panel.visibility = View.GONE
        kbHost.addView(panel)
        applyKbDensity()
        root.addView(kbHost)

        ball = buildBall()
        root.addView(ball)

        // v3.0：宿主输入法捕获框——透明、1dp、可聚焦。两指同时轻点屏幕时聚焦并弹出系统输入法，
        // 输入文字经 ImeCaptureEditText → ImeKeyBridge 转成 evdev 键码发给 guest。
        imeEdit = ImeCaptureEditText(this).apply {
            layoutParams = FrameLayout.LayoutParams(1, 1).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            alpha = 0.01f
            isFocusable = true
            isFocusableInTouchMode = true
            sinkProvider = { NativeInputBridge.sink(NativeInputBridge.CH_KEYBOARD) }
            setOnEditorActionListener { v, _, _ ->
                // 输入法「完成」键：收起键盘
                (v as? ImeCaptureEditText)?.reset()
                hideIme()
                true
            }
        }
        root.addView(imeEdit)

        setContentView(root)
        // v2.0.18：布局完成后再按实测宽高应用入口形态（收纳条要贴准边缘）。
        root.post {
            updateSurfaceLayout()
            applyEntryShape()
            applyKbRotation()          // 内含 applyKbOffset()：恢复上次拖到的位置
            applyLockState()           // 内含手势排除区：锁定时仅屏蔽左右边缘返回手势（不占底部）
            updateCursorOverlay()      // v1.8.3：进入时按当前 cursorVisible 刷新箭头（默认关）
        }
        // 状态栏隐藏、旋转、键盘展开等导致窗口实测尺寸变化后自动重算，
        // 不再依赖一次性 post（这也是旧版四角露黑边的原因之一）。
        root.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob ->
            updateSurfaceLayout()
            // 拖动中每一帧都会触发布局回调；此时必须跳过，否则球会被拉回记忆位置。
            if (!entryDragging) positionEntry()
            // v2.0.19：窗口宽高真的变了才重算旋转 pivot —— 首次布局完成时补一次，
            // 兜住 root.post 早于实测宽高、pivot 仍是默认中心的情况。
            if (r - l != or_ - ol || b - t != ob - ot) {
                applyKbRotation()
                // v2.0.20：手势排除区按实测宽高算，横竖屏/系统栏变化后要重设。
                if (displayLocked) applyLockExclusion()
            }
        }

        // v2.0.10：输入 socket 由 root 服务进程托管。VM 启动时已先 ensureListeningAndWait，
        // 这里再兜底一次（幂等）：覆盖「直接打开显示器」「root 服务被回收后重建」等情况。
        InputServiceClient.ensureListening(this)
        NativeInputBridge.start()
        bindRoot()
        // v3.0：按用户要求移除启动引导提示语，进入显示器后画面零遮挡。
    }

    // ---------------- 全屏：铺满、含摄像头挖孔区、零遮挡 ----------------
    private fun fullScreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 原生显示器里不能让系统软键盘弹出来：它会挡住画面、拦截按键，
        // 而且手机没有实体键盘时，软键盘只发「文字」、不发扬端键事件，
        // GRUB/Fedora 安装程序根本收不到方向键。直接禁止 IME 弹出。
        runCatching {
            window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        // 真机崩溃修复：onCreate 阶段 DecorView 还没安装，此时读 window.insetsController
        // 会走到 PhoneWindow.getInsetsController() 里的 mDecor.getWindowInsetsController()，
        // mDecor 为 null -> NullPointerException。所有依赖 DecorView 的沉浸式设置
        // 一律 post 到 setContentView / attach 之后执行。
        window.decorView.post { runCatching { applyImmersive() } }
    }

    /** 必须在 DecorView 已安装后调用；onWindowFocusChanged 会再补一次，双保险。 */
    private fun applyImmersive() {
        if (isFinishing || isDestroyed) return
        val decor = window.decorView ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { window.setDecorFitsSystemWindows(false) }
            // 用 View 的 controller：未 attached 时返回 null（不会像 Window 那样 NPE），
            // 此时退回传统 flags，保证任何 ROM 都能铺满全屏、无遮挡。
            val ctrl = decor.windowInsetsController
            if (ctrl != null) {
                ctrl.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                ctrl.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                applyLegacyFullscreen(decor)
            }
        } else {
            applyLegacyFullscreen(decor)
        }
    }

    @Suppress("DEPRECATION")
    private fun applyLegacyFullscreen(decor: View) {
        decor.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
    }

    /** 脚本写入 work/native.res = "宽 高"；读不到就按 1:1（autoRes 本来就跟随宿主）。 */
    private fun readGuestResolution() {
        runCatching {
            val f = File(File(GunyahManager.privateDir(this), "work"), "native.res")
            val parts = f.readText().trim().split(Regex("\\s+"))
            guestW = parts.getOrNull(0)?.toIntOrNull() ?: 0
            guestH = parts.getOrNull(1)?.toIntOrNull() ?: 0
            // 输入基准独立保存；attachDisplay() 里的 displayConfig 只许改画面尺寸 guestW/H，
            // 不许动 inputW/H，否则触摸坐标会按 1280x800 发到 720x1584 的设备上被 clamp。
            inputW = guestW
            inputH = guestH
            // 脚本探测结果：新版工具链写 1(absolute-mouse)，旧版写 0(相对 mouse)。
            absPointer = runCatching {
                File(File(GunyahManager.privateDir(this), "work"), "input.abs")
                    .readText().trim() != "0"
            }.getOrDefault(true)
        }
        Log.i(TAG, "guest resolution = ${guestW}x${guestH}; input basis = ${inputW}x${inputH}; absPointer=$absPointer")
    }

    /**
     * v2.0.16：修掉「四角都有黑边、不全屏」。
     *
     * 根因：v2.0.15 用 resources.displayMetrics 算等比尺寸。沉浸式全屏窗口实测是
     * 1440x3168，displayMetrics 却返回去掉系统栏的 1440x3024；min() 缩放后得到
     * 1375x3024 居中，于是上下左右全部露黑（用户看到的四角黑边）。
     *
     * 做法：一律用 rootFrame 的实测宽高；竖屏帧缓冲 720x1584 与手机屏 1440x3168
     * 比例天然一致，直接 MATCH_PARENT 铺满 —— 零黑边、零变形。横屏比例差太大，
     * 仍等比居中（保留 v2.0.15 的横屏不变形修复）。v2.0.17 按用户要求移除「铺满」档位。
     *
     * 触摸不用改：OnTouchListener 绑在 SurfaceView 上，e.x/e.y 是 View 局部坐标，
     * scales() 按 View 实测宽高换算到 inputW/H，输入基准与 --input ABS 范围一律不动。
     */
    /**
     * v1.5.5【根治底部任务栏点不到】：非 device owner 的 startLockTask（屏幕固定）下，
     * 系统会在屏幕底部强画一条导航条窗口，它盖在 guest 画面上、吃掉那一整条触摸 ——
     * 用户能"看到"Windows 任务栏却点不到。手势排除区只影响系统手势，管不了这条窗口，
     * 所以反复调排除区 dp 都没用。
     *
     * 根治手段：owner 未就绪时，把画面绘制区限制在底部导航条【上方】，让 guest 任务栏
     * 落在可点区。scales() 按 surfaceView 实测高度换算，触摸与画面自动一致（点哪到哪）。
     * owner 就绪（LOCK_TASK_FEATURE_NONE 已隐藏导航条）则让位=0，恢复全屏无黑边。
     */
    private fun currentNavSafe(): Int {
        // v1.5.8：用户确认两个系统（Win11 / Linux）的任务栏都在画面底部，
        // 底部黑条既不好看也没必要 —— 两个模式一律全屏铺满，不再让位。
        // 导航条遮挡问题交由 device owner 的 LOCK_TASK_FEATURE_NONE 从根本上隐藏。
        return 0
    }

    private fun updateSurfaceLayout() {
        if (!::surfaceView.isInitialized || !::rootFrame.isInitialized) return
        val sw = rootFrame.width.takeIf { it > 0 }
            ?: rootFrame.measuredWidth.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val shTotal = rootFrame.height.takeIf { it > 0 }
            ?: rootFrame.measuredHeight.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        val navSafe = currentNavSafe()
        val sh = shTotal - navSafe
        val bw = if (guestW > 0) guestW else 720
        val bh = if (guestH > 0) guestH else 1584
        if (sw <= 0 || sh <= 0 || bw <= 0 || bh <= 0) return
        val lp = surfaceView.layoutParams as? FrameLayout.LayoutParams ?: return
        val containerRatio = sw.toFloat() / sh
        val frameRatio = bw.toFloat() / bh
        // v1.8.0 全屏根治：黑边根源是 guest buffer 方向与手机不一致。
        // 已让 Windows 从源头用横屏 16:9 buffer(1280x720，guest 按整块画满)，Linux 仍按
        // 宿主方向自动。这里只按"方向是否一致"决定：
        //   同方向（都横 / 都竖）→ 铺满容器。此时 guest 内容本身铺满 buffer，最多是
        //     16:9→19.5:9 约 23% 的横向拉伸，无黑边，远好过旧的 6.4:1 扁带。
        //   方向相反（横 buffer 进竖屏）→ 等比居中，不把横屏内容拧成竖条。
        // 触摸 scales() 按 View 实测宽高换算，铺满后点哪到哪仍成立。
        val sameOrientation = (containerRatio > 1f && frameRatio > 1f) ||
            (containerRatio < 1f && frameRatio < 1f)
        val near = kotlin.math.abs(containerRatio - frameRatio) / frameRatio < 0.06f
        if (sameOrientation || near) {
            val grav = Gravity.TOP or Gravity.START
            if (lp.width != ViewGroup.LayoutParams.MATCH_PARENT ||
                lp.height != sh || lp.gravity != grav || lp.topMargin != 0
            ) {
                lp.width = ViewGroup.LayoutParams.MATCH_PARENT
                lp.height = sh
                lp.gravity = grav
                lp.topMargin = 0
                surfaceView.layoutParams = lp
            }
            Log.i(TAG, "surface 铺满 ${sw}x${sh}(navSafe=$navSafe) frame ${bw}x${bh}")
            return
        }
        // 横屏比例差太大：等比居中，避免把宽屏 buffer 拉变形；高度仍限制在可用区内。
        val scale = minOf(sw.toFloat() / bw, sh.toFloat() / bh)
        val w = (bw * scale).roundToInt().coerceAtMost(sw)
        val h = (bh * scale).roundToInt().coerceAtMost(sh)
        val grav = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        val topM = ((sh - h) / 2).coerceAtLeast(0)
        if (lp.width == w && lp.height == h && lp.gravity == grav && lp.topMargin == topM) return
        lp.width = w; lp.height = h; lp.gravity = grav; lp.topMargin = topM
        surfaceView.layoutParams = lp
        Log.i(TAG, "surface 等比居中 ${w}x${h} in ${sw}x${sh}(navSafe=$navSafe) frame ${bw}x${bh}")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // configChanges 已声明 orientation，旋转不重建 Activity，在这里重算。
        updateSurfaceLayout()
        rootFrame.post { updateCursorOverlay() }  // v2.0.25：布局完成后重算箭头偏移
        if (!entryDragging) positionEntry()   // v2.0.18：旋转后按新宽高重新贴边
        applyKbDensity()
        applyKbRotation()                     // v2.0.19：宽高变了重算 4 向旋转的 pivot
        if (displayLocked) applyLockExclusion()   // v2.0.20：按新宽高重设手势排除区
        runCatching { applyImmersive() }
    }

    private fun bindRoot() {
        try {
            bound = true
            RootService.bind(Intent(this, NativeDisplayRootService::class.java), rootConn)
        } catch (t: Throwable) {
            Log.e(TAG, "bind root failed", t)
            scheduleFallback(Lang.t("root 服务绑定失败"))
        }
    }

    /** 后台等 crosvm 注册服务，拿到 binder 后 setSurface；失败给足时间再兜底。 */
    private fun attachDisplay() {
        val binder = runCatching { rootService?.waitForDisplayBinder(SERVICE_NAME) }.getOrNull()
        if (binder == null) {
            Log.w(TAG, "display binder not found")
            diag = Lang.t("root✓ 服务✗（无 crosvm_display）")
            ui.post { scheduleFallback(Lang.t("未找到 crosvm_display 服务")) }
            return
        }
        diag = Lang.t("root✓ 服务✓ 设置…")
        val svc = ICrosvmAndroidDisplayService.Stub.asInterface(binder)
        // v2.0.4 黑屏根因：旧版在这里轮询 40 次反复调 setSurface，surfaceCreated /
        // surfaceChanged 又各调一次。参考实现明确记录 crosvm 每个 surface 生命周期
        // 只接受一次 setSurface，且 IllegalArgumentException 是「已送达」的正常返回
        // （异常来自客户端 binder 回复路径）。旧版把 IAE 当失败继续重试，等于把
        // 刚配好的 display 拆掉 —— 注释原文 "the real cause of the black screen"。
        // 现在：绝不轮询重试，surfaceSent 保证只发一次。
        ui.post {
            displayService = svc
            runCatching {
                val dc = svc.displayConfig
                if (dc != null && dc.width > 0 && dc.height > 0 &&
                    (dc.width != guestW || dc.height != guestH)) {
                    Log.i(TAG, "displayConfig=${dc.width}x${dc.height} dpi=${dc.dpi}")
                    // 竖屏帧缓冲保护：simplefb 的真实尺寸由脚本写进 native.res（guestW×guestH，
                    // 与手机屏同比例如 720×1584）。部分 Windows 镜像的 crosvm 在未配置 surface
                    // 时 displayConfig 会误报一个横屏尺寸（如 1024×768 / 1280×800），采纳它会把
                    // Surface 变成横条、上下大黑边。输入基准 inputW/H 早已坚持 native.res，
                    // 画面基准也必须一致：native.res 是竖屏而 dc 报横屏时，忽略这次回报，
                    // 坚持竖屏帧缓冲 → updateSurfaceLayout 走 portraitFit 全屏铺满、零黑边。
                    val nativePortrait = guestH > guestW
                    val dcLandscape = dc.width > dc.height
                    // v1.7.8：竖屏帧缓冲保护只在【手机竖屏持有】时生效。
                    // 手机横屏持有（全屏 1584x720）时 dc 报的横屏尺寸是 guest 的真实方向，
                    // 必须采纳：让 Surface buffer 与 guest 帧缓冲一致、横屏零黑边铺满。
                    // 若此时仍强制竖屏 buffer，横屏 View 铺满会把竖屏内容横向拉变形。
                    val phonePortrait = resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
                    if (nativePortrait && dcLandscape && phonePortrait) {
                        Log.i(TAG, "忽略横屏 displayConfig(${dc.width}x${dc.height})，" +
                            "保持竖屏帧缓冲 ${guestW}x${guestH} 全屏铺满")
                        // 立即按竖屏帧缓冲重排一次，确保 View 铺满（不走等比居中黑边）
                        runCatching { surfaceView.holder.setFixedSize(guestW, guestH) }
                        updateSurfaceLayout()
                    } else {
                        guestW = dc.width; guestH = dc.height
                        runCatching { surfaceView.holder.setFixedSize(dc.width, dc.height) }
                        // v2.0.15：帧缓冲变了就重算等比居中尺寸，横屏不变形。
                        updateSurfaceLayout()
                    }
                }
                diag = "root✓ 服务✓ ${guestW}x${guestH} 设置…"
            }
            // surface 已建好就现在发；还没建好则由 surfaceCreated / surfaceChanged 发。
            // 两条路径都受 surfaceSent 保护，只会真正调用一次 setSurface。
            if (!trySendSurface()) Log.i(TAG, "surface not ready yet, deferring to surfaceChanged")
        }
    }

    /**
     * 每个 surface 生命周期只调用一次 setSurface。
     * IllegalArgumentException 必须当作成功：crosvm 已收到并配置好 surface，
     * 异常来自客户端 transact/reply；此时若重试或隐藏 SurfaceView，会触发
     * removeSurface 把 display 拆掉，画面就永久黑了。
     */
    private fun trySendSurface(): Boolean {
        val svc = displayService ?: return false
        if (surfaceSent) return true
        val s = surface
        if (s == null || !s.isValid) return false
        return try {
            svc.setSurface(s, false)
            markSent(svc)
            true
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "setSurface IllegalArgumentException (known/benign) — treated as delivered", e)
            markSent(svc)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setSurface failed: ${t.javaClass.simpleName} ${t.message}")
            diag = "root✓ 服务✓ ${guestW}x${guestH} 设置✗ ${t.javaClass.simpleName}"
            false
        }
    }

    private fun markSent(svc: ICrosvmAndroidDisplayService) {
        surfaceSent = true
        needsSend = false
        surfaceSet = true
        everSurfaceSet = true
        hasSavedFrame = true
        diag = "root✓ 服务✓ ${guestW}x${guestH} 设置✓"
        Log.i(TAG, "setSurface delivered")
        // v1.9.23 修复「退出显示界面回桌面再进来黑屏」：
        // guest 用 damage-driven compositor，静态桌面不会主动推帧。退出显示界面
        // (finish) 再进来是【全新 Activity 实例】，实例字段 hasSavedFrame 复位为
        // false，旧代码 `if (hasSavedFrame)` 于是不画回 → 黑屏，但 guest 一直在跑。
        // crosvm 端 savedFrame 在独立进程、跨 Activity 存活，这里无条件画回：
        // 有帧就显示，首次冷启动没帧则 no-op，无害。
        runCatching { svc.drawSavedFrameForSurface(false) }
        // setSurface 在 crosvm 端可能异步关联新 surface，稍后再补画一帧确保落上去。
        ui.postDelayed({
            if (!isFinishing && !isDestroyed && surfaceSet && surfaceSent) {
                runCatching { svc.drawSavedFrameForSurface(false) }
            }
        }, 350)
        Log.i(TAG, "display connected (状态卡片已移除, 长按 ⌨ 看诊断)")
    }

    // ---------------- 兜底：任何失败都自动落回内置 VNC ----------------
    private fun scheduleFallback(reason: String) {
        if (fellBack || everSurfaceSet || isFinishing || isDestroyed) return
        // 给 25 秒：crosvm 启动慢时服务注册会晚，别一上来就跳走。
        // everSurfaceSet：已经出过画面就永不再自动兜底（切后台 / 返回 APP 不算失败）。
        // v2.0.3：若脚本能力探测已判定回退 VNC（写了 display.backend=vnc），
        // 说明根本不会有 crosvm_display 服务，别让用户干等 25 秒黑屏，1 秒后直接切。
        val backend = runCatching {
            File(File(GunyahManager.privateDir(this), "work"), "display.backend").readText().trim()
        }.getOrDefault("")
        val waitMs = if (backend == "vnc") 1200L else 25_000L
        ui.postDelayed({
            if (fellBack || everSurfaceSet || !alive || surfaceSet) return@postDelayed
            doFallback(reason)
        }, waitMs)
    }

    private fun doFallback(reason: String) {
        if (fellBack) return
        fellBack = true
        Log.w(TAG, "display fallback triggered: $reason")
        // 本 APP 原生显示模式不启动 VNC server，没有内置 VNC 查看器；
        // 兜底只记录原因并退出显示器页，退回配置页让用户重进，不修改持久配置。
        Toast.makeText(this, "原生显示未就绪（$reason）· 请返回重进或重启 VM", Toast.LENGTH_LONG).show()
        finish()
    }

    // ---------------- Surface ----------------
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceCreated")
        surface = holder.surface
        needsSend = true
        surfaceSent = false
        NativeInputBridge.start()
        // 此时 displayService 可能还没拿到（binder 晚于 surface），返回 false 即可；
        // attachDisplay 拿到后会在 ui.post 里再调一次 trySendSurface。
        trySendSurface()
    }

    override fun surfaceChanged(holder: SurfaceHolder, f: Int, w: Int, h: Int) {
        Log.i(TAG, "surfaceChanged ${w}x${h}, needsSend=$needsSend, sent=$surfaceSent")
        surface = holder.surface
        // 布局变化、旋转、setFixedSize 都会触发这里。已经发过就绝不再发：
        // 重复 setSurface 会让 crosvm 抛 IAE 并拆掉 display（黑屏根因）。
        if (needsSend && !surfaceSent) trySendSurface()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i(TAG, "surfaceDestroyed")
        runCatching {
            displayService?.let { svc ->
                // 先留最后一帧，再 removeSurface。重建后由 markSent 画回去。
                runCatching { svc.saveFrameForSurface(false); hasSavedFrame = true }
                runCatching { svc.removeSurface(false) }
            }
        }
        surface = null
        surfaceSet = false
        surfaceSent = false
        needsSend = false
    }

    // ---------------- 输入 ----------------
    /** 输入坐标缩放：基准是设备声明的 ABS 范围 inputW/H，不是会被 displayConfig 改写的 guestW/H。 */
    private fun scales(): PointF {
        val w = surfaceView.width.takeIf { it > 0 } ?: 1
        val h = surfaceView.height.takeIf { it > 0 } ?: 1
        val bw = if (inputW > 0) inputW else guestW
        val bh = if (inputH > 0) inputH else guestH
        return PointF(
            if (bw > 0) bw.toFloat() / w else 1f,
            if (bh > 0) bh.toFloat() / h else 1f,
        )
    }

    /** 设备声明的 ABS 上限，用于把坐标 clamp 在合法范围内（防 guest input core 截到边角）。 */
    private fun inputBounds(): Pair<Int, Int> = Pair(
        (if (inputW > 0) inputW else guestW).coerceAtLeast(1),
        (if (inputH > 0) inputH else guestH).coerceAtLeast(1),
    )

    @SuppressLint("ClickableViewAccessibility")
    private fun attachTouch(v: View) {
        v.setOnTouchListener { _, e ->
            val s = scales()
            val (bw, bh) = inputBounds()
            // v2.0.13 关键修复保留：必须用 isFromSource()（内部 (source & mask) == mask），
            // 不能用 `source and SOURCE_MOUSE != 0`（SOURCE_TOUCHSCREEN=0x1002 与
            // SOURCE_MOUSE=0x2002 共有 0x2 低位，按位与会把手误判成鼠标）。
            val isPhysicalPointer =
                e.isFromSource(android.view.InputDevice.SOURCE_MOUSE) ||
                    e.isFromSource(android.view.InputDevice.SOURCE_TOUCHPAD)
            // v3.2：单指双击唤醒宿主输入法已移除 —— 在 Win 里双击是「打开文件」，
            // 会和 guest 操作冲突。宿主输入法改为点 ⌨ 浮球 → 菜单里的「宿主输入法」入口。
            // detectDoubleTapImeWake 保留但不调用（双指轻点唤醒仍由下面各模式内保留）。
            when {
                // 多点触控模式（手指）：保留 7788 触摸屏设备给 iOS/手势，绝不删除。
                // 坐标基准已改为 inputW/H，与 multi-touch[width,height] 声明一致。
                inputMode == MODE_MULTITOUCH && !isPhysicalPointer -> {
                    detectMultiTouchImeWake(e)   // v3.0：双指同时轻点 → 唤醒宿主输入法
                    EvdevEncoder.sendMultiTouch(
                        NativeInputBridge.sink(NativeInputBridge.CH_TOUCH), e, s.x, s.y)
                }
                // v2.0.24 触摸板模式（手指）：整块屏当笔记本触摸板，见 handleTouchpad()。
                // 同样走 7790，但由 APP 维护虚拟光标、把手指位移增量累加后下发，
                // 不要求 crosvm 具备 REL_X/REL_Y —— 因此启动链 / 输入设备一律不用改。
                inputMode == MODE_TOUCHPAD && !isPhysicalPointer -> {
                    handleTouchpad(e, s.x, s.y, bw, bh)
                }
                // 直接点按（按哪到哪）以及物理鼠标/触摸板：全部走 7790 的 usb-tablet 绝对指针。
                // v2.0.14：7790 已从相对 mouse 换成 absolute-mouse，能力位图只有 EV_ABS，
                // 必须发 ABS_X/ABS_Y，发 EV_REL 相对位移会被 guest 按能力位图直接丢弃。
                // 绝对指针「按哪到哪」，不需要 APP 维护虚拟光标、不存在漂移/不同步。
                else -> {
                    val sink = NativeInputBridge.sink(NativeInputBridge.CH_MOUSE)
                    if (!absPointer) {
                        // 旧工具链回退：7790 仍是相对 mouse，原样转发 MotionEvent（v2.0.13 行为）。
                        // 探测到二进制没有 absolute-mouse 解析器时才走这里，保证 VM 一定起得来。
                        EvdevEncoder.sendMouse(sink, e)
                    } else {
                        val tx = (e.x * s.x).roundToInt().coerceIn(0, bw - 1)
                        val ty = (e.y * s.y).roundToInt().coerceIn(0, bh - 1)
                        // v3.2：手指「直接点按」模式，单指长按不动 → 右键菜单。
                        // 物理鼠标（isPhysicalPointer）有真实右键，不启用，避免误触。
                        if (!isPhysicalPointer) {
                            when (e.actionMasked) {
                                MotionEvent.ACTION_DOWN -> {
                                    absDownX = e.x.roundToInt(); absDownY = e.y.roundToInt()
                                    absLongFired = false
                                    absLongPress?.let { ui.removeCallbacks(it) }
                                    val rp = Runnable {
                                        if (!absLongFired) {
                                            absLongFired = true
                                            // 菜单弹在手指落点：先移光标，再右键
                                            val ax = (absDownX * s.x).roundToInt().coerceIn(0, bw - 1)
                                            val ay = (absDownY * s.y).roundToInt().coerceIn(0, bh - 1)
                                            EvdevEncoder.sendAbsoluteMouse(sink, ax, ay, null)
                                            EvdevEncoder.sendRightClick(sink)
                                        }
                                    }
                                    absLongPress = rp
                                    ui.postDelayed(rp, TP_RIGHT_LONG_MS)
                                }
                                MotionEvent.ACTION_MOVE -> {
                                    if (kotlin.math.abs(e.x - absDownX) + kotlin.math.abs(e.y - absDownY)
                                        > TP_TAP_SLOP_DP * resources.displayMetrics.density) {
                                        absLongPress?.let { ui.removeCallbacks(it) }; absLongPress = null
                                    }
                                }
                                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                    absLongPress?.let { ui.removeCallbacks(it) }; absLongPress = null
                                }
                                else -> Unit
                            }
                        }
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN ->
                                EvdevEncoder.sendAbsoluteMouse(sink, tx, ty, true)
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                EvdevEncoder.sendAbsoluteMouse(sink, tx, ty, false)
                            MotionEvent.ACTION_MOVE ->
                                EvdevEncoder.sendAbsoluteMouse(sink, tx, ty, null)
                            MotionEvent.ACTION_SCROLL -> {
                                val sx = e.getAxisValue(MotionEvent.AXIS_HSCROLL).toInt()
                                val sy = e.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt()
                                EvdevEncoder.sendAbsoluteWheel(sink, sx, sy)
                            }
                            else -> Unit
                        }
                    }
                }
            }
            hideIme()
            true
        }
    }

    /** v3.0 多点触控模式：两指同时轻点（未滑动、短按）→ 唤醒宿主输入法。 */
    private fun detectMultiTouchImeWake(e: MotionEvent) {
        val d = resources.displayMetrics.density
        val slop = TP_TAP_SLOP_DP * d
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mtTwoDown = false; mtTwoMoved = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 2) {
                    mtTwoDown = true; mtTwoMoved = false
                    mtTwoDownTime = e.eventTime
                    mtTwoCX = (e.getX(0) + e.getX(1)) / 2f
                    mtTwoCY = (e.getY(0) + e.getY(1)) / 2f
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (mtTwoDown && e.pointerCount >= 2) {
                    val cx = (e.getX(0) + e.getX(1)) / 2f
                    val cy = (e.getY(0) + e.getY(1)) / 2f
                    if (kotlin.math.abs(cx - mtTwoCX) + kotlin.math.abs(cy - mtTwoCY) > slop) {
                        mtTwoMoved = true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (mtTwoDown && !mtTwoMoved &&
                    e.eventTime - mtTwoDownTime < TP_TAP_TIMEOUT_MS + 80L) {
                    wakeIme()
                }
                if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    mtTwoDown = false; mtTwoMoved = false
                }
            }
        }
    }

    /**
     * v3.1：单指双击 → 唤醒宿主系统输入法。
     * 两次「轻点」（位移小于 slop、间隔小于 DT_DOUBLE_MS、位置接近）判定为双击。
     * 独立于 inputMode，只在检测到 isPhysicalPointer=false 时调用，不干扰各模式的点击/滚轮。
     * 注意：触摸板模式下 ACTION_UP 会同时发一次左键单击——这是期望的（双击 = 两次单击 +
     * 弹输入法），宿主输入法由 wakeIme 聚焦 imeEdit 弹出，打字经 ImeKeyBridge 转 evdev 给 guest。
     */
    private fun detectDoubleTapImeWake(e: MotionEvent) {
        val slop = TP_TAP_SLOP_DP * resources.displayMetrics.density
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.pointerCount == 1) {
                    dtDownX = e.x; dtDownY = e.y; dtMoved = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (e.pointerCount == 1 &&
                    kotlin.math.abs(e.x - dtDownX) + kotlin.math.abs(e.y - dtDownY) > slop) {
                    dtMoved = true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // 第二指介入 → 不是单指双击，重置序列
                dtLastUpMs = 0L; dtMoved = true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_CANCEL) {
                    dtLastUpMs = 0L
                } else if (e.pointerCount == 1 && !dtMoved) {
                    val now = e.eventTime
                    val near = kotlin.math.abs(e.x - dtLastX) + kotlin.math.abs(e.y - dtLastY) < slop * 2
                    if (dtLastUpMs > 0L && now - dtLastUpMs < DT_DOUBLE_MS && near) {
                        wakeIme()          // 单指双击命中 → 弹宿主输入法
                        dtLastUpMs = 0L    // 防三连击重复触发
                    } else {
                        dtLastUpMs = now
                        dtLastX = e.x; dtLastY = e.y
                    }
                } else {
                    dtLastUpMs = 0L
                }
            }
        }
    }

    /**
     * v2.0.24：把整块手机屏当笔记本触摸板用。
     *
     * 为什么不直接发 EV_REL：当前 7790 是 `absolute-mouse`，能力位图只有
     * EV_ABS(ABS_X,ABS_Y) + EV_KEY(BTN_*) + EV_REL(WHEEL,HWHEEL)，**没有 REL_X/REL_Y**，
     * 发相对位移会被 guest input core 按能力位图直接丢弃（见 EvdevEncoder 注释）。
     * 所以这里在 APP 内维护一个虚拟光标：手指位移增量累加到它，再走已验证可用的
     * EV_ABS 绝对通道下发。guest 看到的是「光标从当前位置按增量移动」，正是触摸板手感，
     * 而 crosvm / --input / 启动脚本 / 启动盘 / 内核一律不用改，也不需要重启手机。
     *
     * absPointer=false（旧工具链回退，7790 是相对 mouse）时更简单：增量直接交给
     * sendMouseMotion 发 EV_REL，连虚拟光标都不用维护。
     *
     * 手势（对齐笔记本触摸板）：
     *   单指滑动     → 移动光标（手指不必对准目标）
     *   单指轻点     → 左键单击（在光标当前位置，不跳到手指落点）
     *   按住不动再滑 → 左键按住拖动
     *   双指滑动     → 滚轮（上下 / 左右）
     *   双指轻点     → 右键单击
     */
    private fun handleTouchpad(e: MotionEvent, sx: Float, sy: Float, bw: Int, bh: Int) {
        val sink = NativeInputBridge.sink(NativeInputBridge.CH_MOUSE)
        val d = resources.displayMetrics.density
        val slop = TP_TAP_SLOP_DP * d
        val step = TP_WHEEL_STEP_DP * d
        val gain = TP_GAINS[tpGainIdx.coerceIn(0, TP_GAINS.size - 1)]
        val now = e.eventTime

        fun cancelLongPress() {
            tpLongPress?.let { ui.removeCallbacks(it) }
            tpLongPress = null
            tpRightLongPress?.let { ui.removeCallbacks(it) }
            tpRightLongPress = null
        }
        /** 一帧绝对坐标；相对设备（旧工具链）不需要它。 */
        fun absFrame(button: Boolean?) {
            if (absPointer) {
                EvdevEncoder.sendAbsoluteMouse(
                    sink,
                    tpVX.roundToInt().coerceIn(0, bw - 1),
                    tpVY.roundToInt().coerceIn(0, bh - 1),
                    button,
                )
            }
        }
        /** 在当前光标位置单击（buttonRight=true 为右键）。 */
        fun click(buttonRight: Boolean) {
            if (buttonRight) {
                EvdevEncoder.sendRightClick(sink)
            } else if (absPointer) {
                absFrame(true); absFrame(false)
            } else {
                EvdevEncoder.sendMouseButton(sink, true)
                EvdevEncoder.sendMouseButton(sink, false)
            }
        }
        fun buttonDown(down: Boolean) {
            if (absPointer) absFrame(down)
            else EvdevEncoder.sendMouseButton(sink, down)
        }
        // absolute-mouse 与 mouse 的能力位图都含 REL_WHEEL/HWHEEL，滚轮协议通用。
        fun wheel(h: Int, v: Int) = EvdevEncoder.sendAbsoluteWheel(sink, h, v)

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelLongPress()
                tpDownX = e.x; tpDownY = e.y
                tpLastX = e.x; tpLastY = e.y
                tpDownTime = now
                tpMoved = false; tpDragging = false; tpLongFired = false
                tpTwoFinger = false; tpTwoMoved = false; tpTwoTapDone = false
                tpThreeFinger = false; tpThreeMoved = false
                tpWheelAccX = 0f; tpWheelAccY = 0f
                // 首次进入：把 guest 光标拉到屏幕中心并与本地虚拟光标对齐，之后两边
                // 一直同步。否则第一次滑动会从 guest 一个未知位置突然跳过来。
                if (!tpInit) {
                    tpVX = bw / 2f; tpVY = bh / 2f
                    tpInit = true
                    absFrame(null)
                    updateCursorOverlay()   // v2.0.25：对齐后立刻显示箭头
                }
                // 260ms：按住不动先进「拖动预备」（按下左键）。若之后一直不滑动，
                // 620ms 的长按计时器会把它转成右键菜单（释放左键 + 右键单击）。
                val lp = Runnable {
                    if (!tpMoved && !tpTwoFinger && !tpThreeFinger && !tpDragging) {
                        tpDragging = true
                        buttonDown(true)
                    }
                }
                tpLongPress = lp
                ui.postDelayed(lp, TP_LONG_PRESS_MS)
                // v3.2：620ms 长按 → 右键菜单（弹上下文菜单）。只有全程未滑动才算。
                val lrp = Runnable {
                    if (!tpMoved && !tpTwoFinger && !tpThreeFinger && !tpLongFired) {
                        // 若 260ms 已按下左键，先释放，避免右键时残留左键按下态
                        if (tpDragging) { buttonDown(false); tpDragging = false }
                        tpLongFired = true
                        click(true)               // 右键单击 = 弹菜单
                    }
                }
                tpRightLongPress = lrp
                ui.postDelayed(lrp, TP_RIGHT_LONG_MS)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount >= 3) {
                    // 第三指落下：取消双指状态，转三指轻点（右键）
                    tpThreeFinger = true
                    tpThreeMoved = false
                    tpThreeDownTime = now
                    tpTwoCX = (e.getX(0) + e.getX(1) + e.getX(2)) / 3f
                    tpTwoCY = (e.getY(0) + e.getY(1) + e.getY(2)) / 3f
                    tpTwoFinger = false
                    tpTwoTapDone = true
                    cancelLongPress()
                    if (tpDragging) { buttonDown(false); tpDragging = false }
                } else if (e.pointerCount == 2) {
                    cancelLongPress()
                    // 单指按住拖动中途来了第二指：先松开左键，别让 guest 卡在按下态
                    if (tpDragging) { buttonDown(false); tpDragging = false }
                    tpTwoFinger = true
                    tpTwoMoved = false
                    tpTwoTapDone = false
                    tpTwoDownTime = now
                    tpTwoCX = (e.getX(0) + e.getX(1)) / 2f
                    tpTwoCY = (e.getY(0) + e.getY(1)) / 2f
                    tpWheelAccX = 0f; tpWheelAccY = 0f
                    tpThreeFinger = false; tpThreeMoved = false
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (tpThreeFinger && e.pointerCount >= 3) {
                    // 三指：只记录是否移动，不发滚轮/光标
                    val cx = (e.getX(0) + e.getX(1) + e.getX(2)) / 3f
                    val cy = (e.getY(0) + e.getY(1) + e.getY(2)) / 3f
                    if (kotlin.math.abs(cx - tpTwoCX) + kotlin.math.abs(cy - tpTwoCY) > slop) {
                        tpThreeMoved = true
                    }
                } else if (tpTwoFinger && e.pointerCount >= 2) {
                    val cx = (e.getX(0) + e.getX(1)) / 2f
                    val cy = (e.getY(0) + e.getY(1)) / 2f
                    val dcx = cx - tpTwoCX
                    val dcy = cy - tpTwoCY
                    tpTwoCX = cx; tpTwoCY = cy
                    if (kotlin.math.abs(dcx) + kotlin.math.abs(dcy) > slop) tpTwoMoved = true
                    // 自然滚动：手指往哪滑内容往哪走（双指上滑 → 页面向下 → REL_WHEEL +1）
                    tpWheelAccX += -dcx
                    tpWheelAccY += -dcy
                    while (kotlin.math.abs(tpWheelAccY) >= step) {
                        val v = if (tpWheelAccY > 0) 1 else -1
                        wheel(0, v)
                        tpWheelAccY -= (if (tpWheelAccY > 0) 1f else -1f) * step
                    }
                    while (kotlin.math.abs(tpWheelAccX) >= step) {
                        val h = if (tpWheelAccX > 0) 1 else -1
                        wheel(h, 0)
                        tpWheelAccX -= (if (tpWheelAccX > 0) 1f else -1f) * step
                    }
                } else if (!tpTwoFinger && e.pointerCount == 1) {
                    val dx = e.x - tpLastX
                    val dy = e.y - tpLastY
                    tpLastX = e.x; tpLastY = e.y
                    if (kotlin.math.abs(e.x - tpDownX) + kotlin.math.abs(e.y - tpDownY) > slop) {
                        tpMoved = true
                        cancelLongPress()
                    }
                    if (dx != 0f || dy != 0f) {
                        // v2.0.25：虚拟光标两种工具链都维护 —— 绝对通道靠它下发坐标，
                        // 相对通道虽不下发，但软件箭头需要它来定位。
                        tpVX = (tpVX + dx * sx * gain).coerceIn(0f, (bw - 1).toFloat())
                        tpVY = (tpVY + dy * sy * gain).coerceIn(0f, (bh - 1).toFloat())
                        if (absPointer) {
                            absFrame(null)
                        } else {
                            EvdevEncoder.sendMouseMotion(
                                sink,
                                (dx * sx * gain).roundToInt(),
                                (dy * sy * gain).roundToInt(),
                            )
                        }
                        updateCursorOverlay()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (tpThreeFinger && e.pointerCount >= 3) {
                    // 三指抬起：轻点 = 右键（原双指右键移到这里）
                    if (!tpThreeMoved && now - tpThreeDownTime < TP_TAP_TIMEOUT_MS) {
                        click(true)
                    }
                    tpThreeFinger = false
                    tpTwoFinger = false
                    tpMoved = true
                    tpTwoTapDone = true
                } else if (tpTwoFinger && !tpTwoTapDone) {
                    if (!tpTwoMoved && now - tpTwoDownTime < TP_TAP_TIMEOUT_MS) {
                        wakeIme()                // 双指轻点 = 唤醒宿主输入法
                        tpTwoTapDone = true
                    }
                    tpTwoFinger = false
                    tpMoved = true              // 回到单指时别再判成轻点
                    // 用剩下那根指的坐标做新基准，避免抬指瞬间光标跳一下
                    val idx = if (e.actionIndex == 0) 1 else 0
                    if (idx < e.pointerCount) {
                        tpLastX = e.getX(idx); tpLastY = e.getY(idx)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                cancelLongPress()
                if (tpThreeFinger && !tpThreeMoved && now - tpThreeDownTime < TP_TAP_TIMEOUT_MS) {
                    click(true)                  // 三指几乎同时抬起 = 右键
                } else if (tpTwoFinger && !tpTwoTapDone &&
                    !tpTwoMoved && now - tpTwoDownTime < TP_TAP_TIMEOUT_MS) {
                    wakeIme()                    // 两指几乎同时抬起 = 唤醒输入法
                    tpTwoTapDone = true
                }
                if (tpDragging) {
                    buttonDown(false)
                } else if (!tpMoved && !tpTwoFinger && !tpThreeFinger &&
                    e.actionMasked == MotionEvent.ACTION_UP &&
                    now - tpDownTime < TP_TAP_TIMEOUT_MS) {
                    click(false)                 // 轻点 = 左键单击
                }
                tpDragging = false
                tpTwoFinger = false
                tpThreeFinger = false
                tpMoved = false
                tpLongFired = false
            }
        }
    }

    /**
     * v2.0.25 软件鼠标箭头；v2.0.26 整体缩小并支持三档大小。
     *
     * 只画一个 28dp 的小 View（28dp 是绘制上界，不是显示尺寸），靠 translationX/Y 移动，
     * 重绘范围只有自身。箭头尖端落在 (0,0)，正是 guest 光标位置。
     * 白填充 + 黑描边，深/浅背景都能看清。
     */
    private class CursorOverlayView(ctx: Context) : View(ctx) {
        private val d = ctx.resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; style = Paint.Style.FILL
        }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; style = Paint.Style.STROKE
            strokeWidth = 2.5f * d
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
        private val arrow = Path()
        private val unit = d * 1.15f

        init {
            // 标准鼠标箭头轮廓（单位 dp，尖端在 0,0）
            arrow.moveTo(0f, 0f)
            arrow.lineTo(0f, 19f * unit)
            arrow.lineTo(4.6f * unit, 14.6f * unit)
            arrow.lineTo(8.2f * unit, 22.5f * unit)
            arrow.lineTo(11.2f * unit, 21.1f * unit)
            arrow.lineTo(7.6f * unit, 13.4f * unit)
            arrow.lineTo(14.2f * unit, 13.4f * unit)
            arrow.close()
        }

        /**
         * v2.0.26：以尖端 (0,0) 为锚缩放。pivot 默认是 View 中心（14dp），不改成 0 的话
         * 缩放会让尖端偏移 (1-scale)*14dp，箭头就和 guest 光标错位了。
         * 白填充、黑描边一起等比变细，小箭头配细边更自然。
         */
        fun setSizeScale(scale: Float) {
            pivotX = 0f
            pivotY = 0f
            scaleX = scale
            scaleY = scale
        }

        /** localX/Y 是相对 SurfaceView 左上角的局部坐标；ox/oy 是 SurfaceView 在 root 中的偏移（横屏等比居中时非 0）。 */
        fun setPosition(localX: Float, localY: Float, ox: Float, oy: Float) {
            translationX = ox + localX
            translationY = oy + localY
        }

        override fun onDraw(canvas: Canvas) {
            // 先描黑边再填白，尖角处也能留出均匀轮廓
            canvas.drawPath(arrow, stroke)
            canvas.drawPath(arrow, fill)
        }

        // 关键：绝不消费触摸，事件继续穿透到下层 SurfaceView 的 OnTouchListener。
        // 历史教训：早期用第二个 SurfaceView 画光标，把触摸全吞了，已删除。
        override fun onTouchEvent(event: MotionEvent): Boolean = false
    }

    /** 把虚拟光标（guest ABS 坐标）映射回 View 局部坐标，并更新叠加层位置 / 可见性。 */
    private fun updateCursorOverlay() {
        val cv = cursorView ?: return
        // v2.0.26：每次都重新应用缩放，避免 loadInputMode / 配置变更的先后顺序影响默认档位。
        cv.setSizeScale(CURSOR_SCALES[cursorSizeIdx.coerceIn(0, CURSOR_SCALES.size - 1)])
        // 只在触摸板模式显示：只有这个模式 APP 才持有光标位置；且要与 guest 对齐过（tpInit）。
        val show = inputMode == MODE_TOUCHPAD && cursorVisible && tpInit
        cv.visibility = if (show) View.VISIBLE else View.INVISIBLE
        if (!show) return
        val s = scales()
        val localX = if (s.x != 0f) tpVX / s.x else tpVX
        val localY = if (s.y != 0f) tpVY / s.y else tpVY
        val ox = if (::surfaceView.isInitialized) surfaceView.left.toFloat() else 0f
        val oy = if (::surfaceView.isInitialized) surfaceView.top.toFloat() else 0f
        cv.setPosition(localX, localY, ox, oy)
    }

    /**
     * v2.0.27：把「当前启动盘组合」折成一个稳定的记忆键。
     *
     * guest 有没有自带指针，APP 侧检测不到：原生显示只交出一个 Surface，帧缓冲由
     * crosvm 直写，APP 拿不到像素；simplefb 也没有硬件光标通道（AIDL 那条主 APP
     * 从未启用，早期叠 cursor surface 还因吞触摸被删）。硬做图像识别会误判 + 耗电。
     * 所以用「按盘记忆」代替自动检测：桌面系统盘默认关，遇到没指针的固件/GRUB
     * 界面点开一次，之后这套盘一直开；换盘互不干扰。
     */
    private fun cursorProfileKey(): String = runCatching {
        val c = GunyahManager.loadConfig(this)
        val raw = listOf(c.bootDisk, c.systemDisk, c.cdRom, c.mmio, c.displayBackend)
            .joinToString("|")
        SP_TP_CURSOR_PROFILE + Integer.toHexString(raw.hashCode())
    }.getOrDefault(SP_TP_CURSOR_PROFILE + "default")

    /** 工具条「指针」按钮文字直接带状态，一眼能看出当前画不画。 */
    private fun cursorLabel(): String = if (cursorVisible) Lang.t("指针 开") else Lang.t("指针 关")

    /** v3.1 工具条「指针」点按：开/关软件箭头，仅本次有效，不再跨启动记忆（默认关）。 */
    private fun toggleCursor() {
        cursorVisible = !cursorVisible
        cursorBtn?.apply {
            text = this@VmDisplayActivity.cursorLabel()
            alpha = if (cursorVisible) 1f else 0.4f
        }
        updateCursorOverlay()
        Toast.makeText(
            this,
            if (cursorVisible)
                Lang.t("软件箭头：开（本次有效；没指针的固件/GRUB 界面用）")
            else
                Lang.t("软件箭头：关（用系统自带指针，不再出现两个箭头）"),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** v2.0.26 长按「指针」：循环箭头大小 小/中/大，持久化。返回后立刻重绘，不用重启原生显示。 */
    private fun cycleCursorSize() {
        cursorSizeIdx = (cursorSizeIdx + 1) % CURSOR_SCALES.size
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit()
                .putInt(SP_TP_CURSOR_SIZE, cursorSizeIdx).apply()
        }
        updateCursorOverlay()
        val tail = if (cursorVisible) "" else Lang.t("（箭头当前为关，点按「指针」打开后可见）")
        Toast.makeText(
            this,
            "软件光标大小：${CURSOR_SIZE_NAMES[cursorSizeIdx]}（×${CURSOR_SCALES[cursorSizeIdx]}）$tail",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 两指轻点后的一小段保护窗口内不自动收输入法，避免刚弹出就被 attachTouch 末尾的 hideIme 关掉。 */
    @Volatile private var imeWakeGuardUntil = 0L

    /** v3.0：两指同时轻点屏幕 → 聚焦透明输入框并弹出宿主系统输入法。 */
    private fun wakeIme() {
        val edit = imeEdit ?: return
        imeWakeGuardUntil = SystemClock.elapsedRealtime() + 1500L
        runCatching {
            edit.reset()
            edit.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
            imeShowing = true
        }
    }

    private fun hideIme() {
        // 保护窗口内不收：双指刚唤醒输入法时，抬手事件也会走 attachTouch 末尾的 hideIme。
        if (SystemClock.elapsedRealtime() < imeWakeGuardUntil) return
        runCatching {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = imeEdit?.windowToken ?: window.decorView.windowToken
            runCatching { imm.hideSoftInputFromWindow(token, 0) }
            // v1.9.76：关键——只收键盘不够，必须让透明捕获框失焦。否则焦点一直留在
            // imeEdit，回到原生显示后物理鼠键/触摸被它吃掉，表现为「鼠键失灵，只能重启 Win」。
            imeEdit?.clearFocus()
            rootFrame.requestFocus()
            imeShowing = false
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            // v2.0.22：返回键优先收起键盘 —— 上锁时也要能收，否则展开的键盘会
            // 一直卡在屏幕底部，挡住 guest 的 Windows 任务栏 / Linux 开始菜单。
            if (panel.visibility == View.VISIBLE) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    hideKeyPreview()
                    stopRepeatDelete()
                    panel.visibility = View.GONE
                }
                return true
            }
            // 上锁只拦「返回动作」，不再占用屏幕底部（锁已是纯软件锁，见下方注释）。
            if (displayLocked) {
                if (event.action == KeyEvent.ACTION_DOWN) showLockToast()
                return true
            }
        }
        val code = KeyCodeMapper.androidToEvdev(event.keyCode)
        if (code != null) {
            val sink = NativeInputBridge.sink(NativeInputBridge.CH_KEYBOARD)
            when (event.action) {
                KeyEvent.ACTION_DOWN -> EvdevEncoder.sendKey(sink, code.toShort(), true)
                KeyEvent.ACTION_UP -> EvdevEncoder.sendKey(sink, code.toShort(), false)
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** v2.0.20：手势导航的返回在部分 ROM 上不走 KeyEvent，这里再兜一层。 */
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // 手势返回不走 KeyEvent，这里同样先收键盘，再判锁。
        if (panel.visibility == View.VISIBLE) {
            hideKeyPreview()
            stopRepeatDelete()
            panel.visibility = View.GONE
            return
        }
        if (displayLocked) {
            showLockToast()
            return
        }
        super.onBackPressed()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        // v2.0.13：同 attachTouch，避免把手指的 SOURCE_TOUCHSCREEN 误判成 SOURCE_MOUSE
        if (event.isFromSource(android.view.InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(android.view.InputDevice.SOURCE_TOUCHPAD)) {
            EvdevEncoder.sendMouse(NativeInputBridge.sink(NativeInputBridge.CH_MOUSE), event)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * v2.0.16：支持 Shift 组合（小键盘的 + 和 * 走 Shift+主键盘符号键）。
     * 顺序与参考实现 InputForwarder 一致：Shift↓ → 键↓ … 键↑ → Shift↑。
     */
    private fun sendKeyTap(k: KbKey) {
        val sink = NativeInputBridge.sink(NativeInputBridge.CH_KEYBOARD)
        // v1.9.64：粘滞 Shift —— 字母页字母键在 Shift 待发状态下自动大写
        val needShift = k.shift || (kbShiftArmed && k.code in kbLetterCodes)
        if (needShift) {
            EvdevEncoder.sendKey(sink, KeyCodeMapper.KEY_LEFTSHIFT.toShort(), true)
        }
        EvdevEncoder.sendKey(sink, k.code.toShort(), true)
        ui.postDelayed({
            EvdevEncoder.sendKey(sink, k.code.toShort(), false)
            if (needShift) {
                EvdevEncoder.sendKey(sink, KeyCodeMapper.KEY_LEFTSHIFT.toShort(), false)
            }
        }, 45)
        // 粘滞：发出一个字母后自动解除大写状态并刷新标签
        if (needShift && !k.shift && k.code in kbLetterCodes) {
            kbShiftArmed = false
            rebuildKeyRows()
        }
    }

    // ---------------- 键盘入口：浮球 ⇄ 贴边透明细条（v2.0.18） ----------------
    /**
     * 同一个 View 两种形态，避免两个入口并存互相抢显隐：
     *  - 浮球：44dp ⌨，点一下出/收全键盘，长按看诊断，可自由拖动；
     *  - 收纳：拖到任一屏幕边缘松手 → 吸附成 13dp 厚的半透明模糊细条，只占一条边，
     *    点它出键盘，沿边可滑动换位置，向屏内拖 42dp 以上还原浮球。
     * 形态、所贴边、位置全部持久化；旋转/窗口尺寸变化自动重新贴边。
     */
    /** 键盘入口自动淡出：3 秒不动 → 半透明；触摸 → 恢复。 */
    private var autoHide: Runnable? = null
    private fun scheduleAutoHide() {
        cancelAutoHide()
        val r = Runnable {
            if (::ball.isInitialized) {
                ball.animate().alpha(0.35f).setDuration(500).start()
            }
        }
        autoHide = r
        ui.postDelayed(r, 3000)
    }
    private fun cancelAutoHide() {
        autoHide?.let { ui.removeCallbacks(it) }
        autoHide = null
        if (::ball.isInitialized) ball.alpha = 1f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildBall(): TextView = TextView(this).apply {
        text = "🐟"
        setTextColor(Color.WHITE)
        textSize = 26f
        gravity = Gravity.CENTER
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFFF8FB1.toInt(), 0xFF7BD3EA.toInt())
        ).apply { shape = GradientDrawable.OVAL }
        val d = resources.displayMetrics.density
        val size = (58 * d).roundToInt()
        layoutParams = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        alpha = 0.95f
        var downX = 0f; var downY = 0f; var ox = 0f; var oy = 0f; var moved = false
        var undocked = false   // 本次拖动是否已从细条还原成浮球
        var longPress: Runnable? = null
        fun cancelLongPress() { longPress?.let { ui.removeCallbacks(it) }; longPress = null }
        setOnTouchListener { v, e ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    ox = lp.leftMargin.toFloat(); oy = lp.topMargin.toFloat()
                    moved = false; undocked = false
                    entryDragging = true
                    cancelAutoHide()
                    // v2.0.15：状态卡片删了，长按 ⌨ 临时看链路诊断（不占画面）。
                    longPress = Runnable { showDiagnostics(); longPress = null }
                    longPress?.let { ui.postDelayed(it, 600) }
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX; val dy = e.rawY - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > 8f) {
                        moved = true; cancelLongPress()
                    }
                    val W = frameW(); val H = frameH()
                    // 入口固定为贴边竖杠条：只沿所贴边滑动换位置，不再还原浮球
                    if (dockEdge == 1 || dockEdge == 2) {
                        dockPos = ((oy + dy) + v.height / 2f) / H
                    } else {
                        dockPos = ((ox + dx) + v.width / 2f) / W
                    }
                    dockPos = dockPos.coerceIn(0.06f, 0.94f)
                    lp.leftMargin = when (dockEdge) {
                        1 -> 0
                        2 -> W - v.width
                        else -> (W * dockPos - v.width / 2f).roundToInt()
                            .coerceIn(0, (W - v.width).coerceAtLeast(0))
                    }
                    lp.topMargin = when (dockEdge) {
                        3 -> 0
                        4 -> H - v.height
                        else -> (H * dockPos - v.height / 2f).roundToInt()
                            .coerceIn(0, (H - v.height).coerceAtLeast(0))
                    }
                    v.layoutParams = lp
                    true
                }
                MotionEvent.ACTION_UP -> {
                    cancelLongPress()
                    entryDragging = false
                    val W = frameW(); val H = frameH()
                    if (dockEdge == 0) {
                        if (!moved) {
                            showBallMenu(v)
                        } else {
                            val cx = lp.leftMargin + v.width / 2f
                            val cy = lp.topMargin + v.height / 2f
                            val dl = cx; val dr = W - cx; val dt = cy; val db = H - cy
                            val near = minOf(dl, dr, dt, db)
                            if (!undocked && near < EDGE_SNAP_DP * d) {
                                dockEdge = when (near) {
                                    dl -> 1; dr -> 2; dt -> 3; else -> 4
                                }
                                dockPos = (if (dockEdge <= 2) cy / H else cx / W)
                                    .coerceIn(0.06f, 0.94f)
                                applyEntryShape()
                                entryBounce()
                                saveEntry()
                                showDockTipOnce()
                            } else {
                                ballRx = (cx / W).coerceIn(0f, 1f)
                                ballRy = (cy / H).coerceIn(0f, 1f)
                                positionEntry()
                                saveEntry()
                            }
                        }
                    } else {
                        if (!moved) showBallMenu(v) else saveEntry()
                    }
                    v.performClick(); scheduleAutoHide(); true
                }
                MotionEvent.ACTION_CANCEL -> { cancelLongPress(); entryDragging = false; true }
                else -> true
            }
        }
    }

    /** 按 dockEdge 应用形态：宽高、背景、文字、模糊；随后按实测宽高贴边定位。 */
    @SuppressLint("NewApi")
    private fun applyEntryShape() {
        if (!::ball.isInitialized || !::rootFrame.isInitialized) return
        val d = resources.displayMetrics.density
        val lp = ball.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or Gravity.START
        lp.rightMargin = 0; lp.bottomMargin = 0
        if (dockEdge == 0) {
            val size = (58 * d).roundToInt()
            lp.width = size; lp.height = size
            ball.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFFFF8FB1.toInt(), 0xFF7BD3EA.toInt())
            ).apply { shape = GradientDrawable.OVAL }   // 与原浮球完全一致，不做外观负优化
            ball.text = "🐟"
            ball.setTextColor(Color.WHITE)
            ball.textSize = 26f
            ball.alpha = 0.95f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching { ball.setRenderEffect(null) }
            }
        } else {
            val thick = (DOCK_THICK_DP * d).roundToInt()
            val long = (DOCK_LONG_DP * d).roundToInt()
            val vertical = dockEdge == 1 || dockEdge == 2
            lp.width = if (vertical) thick else long
            lp.height = if (vertical) long else thick
            ball.background = buildDockGlass(vertical, thick, d)
            ball.text = ""      // 细条太窄，用中间一条小白杠代替文字，不会溢出边界
            ball.alpha = 1f
            // 背景毛玻璃：API31+ 再叠一层轻模糊；低版本靠半透明渐变 + 高光细边模拟。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    ball.setRenderEffect(
                        RenderEffect.createBlurEffect(2.2f, 2.2f, Shader.TileMode.CLAMP)
                    )
                }
            }
        }
        ball.layoutParams = lp
        entryReady = true
        positionEntry()
        scheduleAutoHide()
    }

    /** 半透明渐变 + 细白边 + 居中小白杠 = 模糊透明「杠条」（不依赖系统窗口模糊）。 */
    private fun buildDockGlass(vertical: Boolean, thick: Int, d: Float): LayerDrawable {
        val glass = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xE6407FA8.toInt(), 0xC0326E92.toInt(), 0x9E2A5F80.toInt())
        ).apply {
            cornerRadius = thick / 2f
            setStroke((1.6f * d).roundToInt().coerceAtLeast(2), 0xFF8FD4F2.toInt())
        }
        val gw = if (vertical) (3 * d).roundToInt() else (24 * d).roundToInt()
        val gh = if (vertical) (24 * d).roundToInt() else (3 * d).roundToInt()
        val grip = GradientDrawable().apply {
            cornerRadius = minOf(gw, gh) / 2f
            setColor(0xD9FFFFFF.toInt())
        }
        return LayerDrawable(arrayOf(glass, grip)).apply {
            setLayerSize(1, gw, gh)
            setLayerGravity(1, Gravity.CENTER)
        }
    }

    /** 只重算 left/top margin；幂等，供旋转与窗口尺寸变化反复调用。 */
    private fun positionEntry() {
        if (!entryReady || !::ball.isInitialized || !::rootFrame.isInitialized) return
        val W = frameW(); val H = frameH()
        val lp = ball.layoutParams as FrameLayout.LayoutParams
        val w = lp.width; val h = lp.height
        if (w <= 0 || h <= 0) return
        val left: Int
        val top: Int
        if (dockEdge == 0) {
            left = (W * ballRx - w / 2f).roundToInt()
                .coerceIn(0, (W - w).coerceAtLeast(0))
            top = (H * ballRy - h / 2f).roundToInt()
                .coerceIn(0, (H - h).coerceAtLeast(0))
        } else {
            when (dockEdge) {
                1 -> {
                    left = 0
                    top = (H * dockPos - h / 2f).roundToInt().coerceIn(0, (H - h).coerceAtLeast(0))
                }
                2 -> {
                    left = W - w
                    top = (H * dockPos - h / 2f).roundToInt().coerceIn(0, (H - h).coerceAtLeast(0))
                }
                3 -> {
                    left = (W * dockPos - w / 2f).roundToInt().coerceIn(0, (W - w).coerceAtLeast(0))
                    top = 0
                }
                else -> {
                    left = (W * dockPos - w / 2f).roundToInt().coerceIn(0, (W - w).coerceAtLeast(0))
                    top = H - h
                }
            }
        }
        val g = Gravity.TOP or Gravity.START
        if (lp.leftMargin == left && lp.topMargin == top && lp.gravity == g) return
        lp.gravity = g; lp.leftMargin = left; lp.topMargin = top
        ball.layoutParams = lp
    }

    private fun frameW(): Int = rootFrame.width.takeIf { it > 0 }
        ?: resources.displayMetrics.widthPixels

    private fun frameH(): Int = rootFrame.height.takeIf { it > 0 }
        ?: resources.displayMetrics.heightPixels

    private fun togglePanel() {
        panel.visibility = if (panel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        ball.alpha = if (panel.visibility == View.VISIBLE) 1.0f
        else if (dockEdge == 0) 0.85f else 1f
        if (panel.visibility == View.GONE) {
            hideKeyPreview()
            stopRepeatDelete()
        }
    }

    /**
     * v3.2：点 ⌨ 浮球/细条弹出菜单，两个入口二选一：
     *  ① 自带虚拟键盘 —— 原 togglePanel()（APP 内置键盘，直接发 evdev，不依赖宿主）
     *  ② 宿主输入法 —— wakeIme()（调起手机系统输入法，打字经 ImeKeyBridge 转 evdev）
     * 这样把「宿主输入法」从画面双击迁移到浮球，避免 Win 里双击=打开文件被误触发。
     *
     * v1.9.5：换成自定义玻璃卡片菜单（复用 Glass 设计系统），摒弃系统默认灰色 PopupMenu：
     *  深色玻璃底 + 白色高光描边 + 大圆角，每项带线性图标 + 主副标题 + 分隔线 + 按下态，
     *  弹出有淡入放大动画，配色/字体全部走 Glass 主题色，深色模式与宿主画面协调。
     */
    private fun showBallMenu(anchor: View) {
        val density = resources.displayMetrics.density
        fun px(v: Float) = (v * density).roundToInt().coerceAtLeast(1)

        val isEn = Lang.isEnglish()
        val titleText = if (isEn) "Choose input method" else "选择输入方式"
        val main1 = Lang.t("自带虚拟键盘")
        val sub1 = if (isEn) "Built-in keys, no system IME needed"
                   else "APP 内置键盘 · 无需系统输入法"
        val main2 = Lang.t("宿主输入法")
        val sub2 = if (isEn) "Use your phone's system keyboard"
                   else "调用手机系统输入法打字"

        // ---- 卡片容器：深色高不透明玻璃 + 白色高光描边 + 大圆角 ----
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Glass.dialogPanel(this@VmDisplayActivity, 20f)
            setPadding(px(6f), px(4f), px(6f), px(6f))
            elevation = px(14f).toFloat()
        }

        // 标题
        card.addView(TextView(this).apply {
            text = titleText
            setTextColor(Glass.textHint())
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            letterSpacing = 0.08f
            setPadding(px(14f), px(12f), px(14f), px(8f))
        })

        // 分隔线工厂
        fun divider() = View(this).apply {
            setBackgroundColor(Glass.divider())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1).apply {
                leftMargin = px(12f); rightMargin = px(12f)
            }
        }
        card.addView(divider())

        // 线性图标：代码绘制，风格与玻璃系统统一（键盘 / 输入法）
        fun iconView(kind: Int): View = object : View(this) {
            private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                color = Glass.indicator()
                strokeWidth = px(1.9f).toFloat()
                strokeCap = Paint.Cap.ROUND
                strokeJoin = Paint.Join.ROUND
            }
            private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Glass.indicator()
            }
            override fun onMeasure(w: Int, h: Int) =
                setMeasuredDimension(px(24f), px(24f))
            override fun onDraw(c: Canvas) {
                super.onDraw(c)
                val s = px(24f).toFloat()
                if (kind == 0) {
                    // 键盘：圆角矩形 + 两行键帽点
                    val l = s * 0.06f; val t = s * 0.24f
                    val r = s * 0.94f; val b = s * 0.76f
                    c.drawRoundRect(l, t, r, b, s * 0.16f, s * 0.16f, line)
                    val rad = px(1.2f).toFloat()
                    for (row in 0..1) for (col in 0..3) {
                        val ix = l + (r - l) * (col + 1) / 5f
                        val iy = t + (b - t) * (row + 1) / 3f
                        c.drawCircle(ix, iy, rad, dot)
                    }
                } else {
                    // 宿主输入法：输入框 + 大写 A
                    val l = s * 0.06f; val t = s * 0.14f
                    val r = s * 0.94f; val b = s * 0.86f
                    c.drawRoundRect(l, t, r, b, s * 0.2f, s * 0.2f, line)
                    val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Glass.indicator()
                        textSize = s * 0.5f
                        typeface = Typeface.DEFAULT_BOLD
                        textAlign = Paint.Align.CENTER
                    }
                    val fm = tp.fontMetrics
                    c.drawText("A", s / 2f,
                        s / 2f - (fm.ascent + fm.descent) / 2f, tp)
                }
            }
        }

        var popupRef: PopupWindow? = null

        fun addItem(kind: Int, main: String, sub: String, action: () -> Unit) {
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = px(13f).toFloat()
                setColor(0x00000000)
            }
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                background = bg
                setPadding(px(10f), px(9f), px(10f), px(9f))
                setOnTouchListener { v, e ->
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> {
                            bg.setColor(0x2AFFFFFF.toInt())
                            v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(80).start()
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            bg.setColor(0x00000000)
                            v.animate().scaleX(1f).scaleY(1f).setDuration(150).start()
                        }
                    }
                    false
                }
                setOnClickListener { popupRef?.dismiss(); action() }
            }

            // 图标底：圆形淡蓝玻璃
            val iconBox = FrameLayout(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0x263A6BFF.toInt())
                    setStroke(px(1f), 0x4A7FB6FF.toInt())
                }
                addView(iconView(kind),
                    FrameLayout.LayoutParams(px(24f), px(24f), Gravity.CENTER))
            }
            row.addView(iconBox, FrameLayout.LayoutParams(px(38f), px(38f)))

            // 主标题 + 副标题说明
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            texts.addView(TextView(this).apply {
                text = main
                setTextColor(Glass.textPrimary())
                textSize = 15.5f
                typeface = Typeface.DEFAULT_BOLD
            })
            texts.addView(TextView(this).apply {
                text = sub
                setTextColor(Glass.textHint())
                textSize = 11.5f
                setPadding(0, px(2f), 0, 0)
            })
            row.addView(texts,
                LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    leftMargin = px(12f)
                })

            card.addView(row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        addItem(0, main1, sub1) { togglePanel() }
        card.addView(divider())
        addItem(1, main2, sub2) { wakeIme() }

        val popup = PopupWindow(
            card,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = px(12f).toFloat()
        }
        popupRef = popup

        // 定位：水平居中于 anchor 并夹在屏幕内；垂直固定在屏幕下方「天明提示」区域上方
        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val cw = card.measuredWidth
        val ch = card.measuredHeight
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val sw = resources.displayMetrics.widthPixels
        val sh = resources.displayMetrics.heightPixels
        val margin = px(12f)
        var x = loc[0] + anchor.width / 2 - cw / 2
        x = x.coerceIn(margin, (sw - cw - margin).coerceAtLeast(margin))
        // 让菜单底边落在屏幕 90% 高度处，正好压在桌面底部「天明提示」语上方
        var y = (sh * 0.90f - ch).roundToInt()
        y = y.coerceIn(margin, (sh - ch - margin).coerceAtLeast(margin))

        // 弹出动画：淡入 + 轻微放大回弹
        card.alpha = 0f
        card.scaleX = 0.9f
        card.scaleY = 0.9f
        card.pivotX = cw / 2f
        card.pivotY = ch / 2f

        runCatching {
            popup.showAtLocation(anchor.rootView, Gravity.NO_GRAVITY, x, y)
            card.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setDuration(190).setInterpolator(DecelerateInterpolator()).start()
        }
    }

    /** 收纳/解收纳时的轻弹动画，让形态切换不生硬。 */
    private fun entryBounce() {
        runCatching {
            ball.animate().cancel()
            ball.scaleX = 0.62f; ball.scaleY = 0.62f; ball.alpha = 0.45f
            ball.animate().scaleX(1f).scaleY(1f)
                .alpha(if (dockEdge == 0) 0.85f else 1f)
                .setDuration(180).start()
        }
    }

    /** v3.0：按用户要求去提示语，贴边收纳不再弹任何气泡。 */
    private fun showDockTipOnce() { /* no-op: 提示语已移除 */ }

    private fun loadEntry() {
        runCatching {
            val sp = getSharedPreferences(SP_NAME, MODE_PRIVATE)
            // 键盘入口固定贴右边缘细竖杠条（2）：忽略旧存档里可能的浮球(0)或其它边，
            // 用户明确要求不要宽浮球、就贴边一条竖杠。可记忆的只剩沿边位置 dockPos。
            dockEdge = 2
            dockPos = sp.getFloat(SP_DOCK_POS, 0.45f).coerceIn(0.06f, 0.94f)
            ballRx = sp.getFloat(SP_BALL_X, 0.93f).coerceIn(0f, 1f)
            ballRy = sp.getFloat(SP_BALL_Y, 0.44f).coerceIn(0f, 1f)
        }
    }

    private fun saveEntry() {
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit()
                .putInt(SP_DOCK_EDGE, dockEdge)
                .putFloat(SP_DOCK_POS, dockPos)
                .putFloat(SP_BALL_X, ballRx)
                .putFloat(SP_BALL_Y, ballRy)
                .apply()
        }
    }

    // ---------------- 单页全键盘（v2.0.15，按确认的设计稿 v3） ----------------
    // 主键盘数字行已删除，数字全部交给右侧小键盘；「切回VNC」入口已移除。
    // 所有键走已验证可用的 7789 键盘通道 sendKeyTap()，不碰输入桥 / 启动脚本 / --input。
    private data class KbKey(
        val label: String, val code: Int, val weight: Float = 1f, val shift: Boolean = false,
        // v3.0：action=特殊动作键（page/post/enter 等），icon=图标键（win/menu），不走普通发键
        val action: String? = null, val icon: String? = null
    )

    private fun kbLetters(s: String, base: Int): List<KbKey> =
        s.mapIndexed { i, c -> KbKey(c.toString(), base + i) }

    // ---------------- v3.0 手机输入法风格键盘：字母页 / 快捷键页 / F 功能页 ----------------
    // 所有键仍走已验证可用的键盘通道 sendKeyTap()，不碰输入桥 / 启动脚本 / --input。
    // 页面通过第三行最左的 ⇄ 切换；⇄ 在字母页是「进快捷键页」，在快捷键/功能页是「回字母页」。
    private var kbPage = 0   // 0=字母, 1=快捷键, 2=F1-F12, 3=数字/符号
    // 键盘按键区容器（翻页时只重建这里，不动工具条）
    private var kbKeysHost: LinearLayout? = null

    // v1.9.64：字母页粘滞 Shift（手机输入法风格）。点 ⇧ 后下一个字母自动大写，发出后复位。
    private var kbShiftArmed = false
    // v1.9.65：Shift 锁定 —— 长按 ⇧ 让 Shift 持续按住（配合鼠标点电源→重启进 WinRE）。
    // 与粘滞互斥：锁定时 kbShiftArmed 恒为 false；锁定态下再短按 ⇧ 解除。
    private var kbShiftLocked = false
    // 长按计时器：按下 ⇧ 350ms 后触发锁定；提前抬起则当短按（粘滞切换）。
    private var shiftLongPressPending = false
    private val shiftLongPressRunnable = Runnable {
        if (shiftLongPressPending) {
            shiftLongPressPending = false
            lockShift()
        }
    }
    // 字母键按 QWERTY 物理位置编码，分三段，不能按字母序连续区间判断
    private val kbLetterCodes: Set<Int> by lazy {
        (KeyCodeMapper.KEY_Q..KeyCodeMapper.KEY_P).toSet() +
            (KeyCodeMapper.KEY_A..KeyCodeMapper.KEY_L).toSet() +
            (KeyCodeMapper.KEY_Z..KeyCodeMapper.KEY_M).toSet()
    }

    /** 锁定 Shift：发一个不抬起的 Shift-down，guest 会认为 Shift 一直按住。 */
    private fun lockShift() {
        if (kbShiftLocked) return
        kbShiftLocked = true
        kbShiftArmed = false
        val sink = NativeInputBridge.sink(NativeInputBridge.CH_KEYBOARD)
        EvdevEncoder.sendKey(sink, KeyCodeMapper.KEY_LEFTSHIFT.toShort(), true)
        rebuildKeyRows()
    }

    /** 解除锁定：补发 Shift-up，guest 恢复无修饰键状态。 */
    private fun unlockShift() {
        if (!kbShiftLocked) return
        kbShiftLocked = false
        val sink = NativeInputBridge.sink(NativeInputBridge.CH_KEYBOARD)
        EvdevEncoder.sendKey(sink, KeyCodeMapper.KEY_LEFTSHIFT.toShort(), false)
        rebuildKeyRows()
    }

    /** 主键页：字母 + 修饰键 + 方向键，一页搞定。 */
    private val letterRows: List<List<KbKey>> by lazy {
        val K = KeyCodeMapper
        listOf(
            kbLetters("qwertyuiop", K.KEY_Q),
            kbLetters("asdfghjkl", K.KEY_A),
            listOf(KbKey("⇧", 0, 1.1f, action = "shift")) +
                kbLetters("zxcvbnm", K.KEY_Z) +
                listOf(KbKey("⌫", K.KEY_BACKSPACE, 1.4f)),
            listOf(
                KbKey("Ctrl", K.KEY_LEFTCTRL, 1.2f),
                KbKey("Alt", K.KEY_LEFTALT, 1.1f),
                KbKey("Esc", K.KEY_ESC, 1.1f),
                KbKey("Tab", K.KEY_TAB, 1.1f),
                KbKey("空格", K.KEY_SPACE, 2.6f),
                KbKey("↵", K.KEY_ENTER, 1.2f),
            ),
            listOf(
                KbKey("?123", 0, 1.3f, action = "page_num"),
                KbKey("F1-12", 0, 1.4f, action = "page_func"),
                KbKey("◀", K.KEY_LEFT), KbKey("▲", K.KEY_UP),
                KbKey("▼", K.KEY_DOWN), KbKey("▶", K.KEY_RIGHT),
            ),
        )
    }

    /** 符号页：按 PC 键盘排布，第二行开始就是标准符号位。 */
    private val numPadRows: List<List<KbKey>> by lazy {
        val K = KeyCodeMapper
        listOf(
            kbLetters("1234567890", K.KEY_1),
            listOf(
                KbKey("-", K.KEY_MINUS), KbKey("=", K.KEY_EQUAL),
                KbKey("[", K.KEY_LEFTBRACE), KbKey("]", K.KEY_RIGHTBRACE),
                KbKey("\\", K.KEY_BACKSLASH), KbKey(";", K.KEY_SEMICOLON),
                KbKey("'", K.KEY_APOSTROPHE), KbKey(",", K.KEY_COMMA),
                KbKey(".", K.KEY_DOT), KbKey("/", K.KEY_SLASH),
            ),
            listOf(
                KbKey("`", K.KEY_GRAVE, shift = true), KbKey("~", K.KEY_GRAVE),
                KbKey("!", K.KEY_1, shift = true), KbKey("@", K.KEY_2, shift = true),
                KbKey("#", K.KEY_3, shift = true), KbKey("$", K.KEY_4, shift = true),
                KbKey("%", K.KEY_5, shift = true), KbKey("^", K.KEY_6, shift = true),
                KbKey("⌫", K.KEY_BACKSPACE, 1.4f),
            ),
            listOf(
                KbKey("&", K.KEY_7, shift = true), KbKey("*", K.KEY_8, shift = true),
                KbKey("(", K.KEY_9, shift = true), KbKey(")", K.KEY_0, shift = true),
                KbKey("_", K.KEY_MINUS, shift = true), KbKey("+", K.KEY_EQUAL, shift = true),
                KbKey("{", K.KEY_LEFTBRACE, shift = true), KbKey("}", K.KEY_RIGHTBRACE, shift = true),
                KbKey("|", K.KEY_BACKSLASH, shift = true),
            ),
            listOf(
                KbKey("abc", 0, 1.6f, action = "page_letters"),
                KbKey("Ctrl", K.KEY_LEFTCTRL, 1.2f),
                KbKey("Alt", K.KEY_LEFTALT, 1.1f),
                KbKey("空格", K.KEY_SPACE, 3f),
                KbKey("↵", K.KEY_ENTER, 1.4f),
            ),
        )
    }

    /** F 功能页：F1-F12 一屏，abc 回主键页。 */
    private val funcRows: List<List<KbKey>> by lazy {
        val K = KeyCodeMapper
        listOf(
            (1..6).map { KbKey("F$it", K.KEY_F1 + it - 1) },
            (7..12).map { KbKey("F$it", if (it <= 10) K.KEY_F1 + it - 1 else if (it == 11) K.KEY_F11 else K.KEY_F12) },
            listOf(
                KbKey("⇧F10", K.KEY_F10, 1.6f, shift = true),
                KbKey("abc", 0, 1.6f, action = "page_letters"),
                KbKey("?123", 0, 1.4f, action = "page_num"),
            ),
        )
    }

    private fun currentRows(): List<List<KbKey>> = when (kbPage) {
        1 -> numPadRows
        3 -> funcRows
        else -> letterRows
    }

    private fun buildPanel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(KB_PANEL_BG.toInt())
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM }
        val d = resources.displayMetrics.density

        // v2.0.20 顶部窄工具条：模式 / 小键盘 / 旋转 / 🔒 / 诊断 / 收起。
        // 「铺满」「紧凑」按用户要求移除，默认固定图1 布局。无「切回VNC」。
        // 工具条同时是键盘窗口的拖动把手：轻点走各按钮，按住超过 12dp 则移动整个键盘层。
        val bar = DragBar(this@VmDisplayActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((4 * d).roundToInt(), (2 * d).roundToInt(),
                (4 * d).roundToInt(), (2 * d).roundToInt())
        }
        // 模式按钮给 1.8 份宽度，装得下「模式: 多点触控」不挤成两行。
        val mt = this@VmDisplayActivity.barButton(this@VmDisplayActivity.modeLabel(), 1.8f) {
            this@VmDisplayActivity.toggleInputMode()
        }
        // v2.0.24：长按循环触摸板光标速度。返回 true 消费长按，不会误触发上面的 click 切模式。
        mt.setOnLongClickListener {
            this@VmDisplayActivity.cycleTpGain()
            true
        }
        modeToggle = mt
        bar.addView(mt)
        val rb = this@VmDisplayActivity.barButton(Lang.t("旋转 0°")) {
            this@VmDisplayActivity.rotateKeyboard()
        }
        rotateBtn = rb
        bar.addView(rb)
        // v2.0.20：🔒 上锁 / 🔓 解锁（防误退回虚拟机界面或安卓桌面）
        val lb = this@VmDisplayActivity.barButton(this@VmDisplayActivity.lockLabel(), 0.7f) {
            this@VmDisplayActivity.toggleDisplayLock()
        }
        lockBtn = lb
        bar.addView(lb)
        // v2.0.27 软件光标开关：默认关（桌面系统自带指针），点按开/关并按盘记忆。
        val cb = this@VmDisplayActivity.barButton(this@VmDisplayActivity.cursorLabel(), 0.9f) {
            this@VmDisplayActivity.toggleCursor()
        }
        // v2.0.26：长按循环箭头大小。返回 true 消费长按，不会误触发上面的 click 开关。
        cb.setOnLongClickListener {
            this@VmDisplayActivity.cycleCursorSize()
            true
        }
        cb.alpha = if (this@VmDisplayActivity.cursorVisible) 1f else 0.4f
        this@VmDisplayActivity.cursorBtn = cb
        bar.addView(cb)
        bar.addView(this@VmDisplayActivity.barButton("ⓘ", 0.6f) { this@VmDisplayActivity.showDiagnostics() })
        bar.addView(this@VmDisplayActivity.barButton(Lang.t("收起 ×"), 0.9f) {
            this@VmDisplayActivity.hideKeyPreview()
            this@VmDisplayActivity.stopRepeatDelete()
            this@VmDisplayActivity.panel.visibility = View.GONE
        })
        addView(bar)

        // v3.0：单栏手机输入法风格按键区（无右侧小键盘），翻页只重建这里。
        val keysHost = LinearLayout(this@VmDisplayActivity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        this@VmDisplayActivity.kbKeysHost = keysHost
        this@VmDisplayActivity.rebuildKeyRows()
        addView(keysHost)
    }

    /** v3.0：按当前页重建按键行（keyViews 由 keyButton 重新收集）。 */
    private fun rebuildKeyRows() {
        val host = kbKeysHost ?: return
        keyViews.clear()
        host.removeAllViews()
        val d = resources.displayMetrics.density
        currentRows().forEach { row -> host.addView(buildKeyRow(row)) }
        // 行距：手机输入法风格紧凑
        for (i in 0 until host.childCount) {
            (host.getChildAt(i).layoutParams as? LinearLayout.LayoutParams)?.let {
                it.topMargin = if (i == 0) 0 else (3 * d).roundToInt()
                host.getChildAt(i).layoutParams = it
            }
        }
        applyKbDensity()
    }

    /** v3.0：翻页。action 由 keyButton 触发。 */
    private fun switchKbPage(page: Int) {
        if (kbPage == page) return
        kbPage = page
        hideKeyPreview()
        stopRepeatDelete()
        rebuildKeyRows()
    }

    private fun buildKeyRow(keys: List<KbKey>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            keys.forEach { k -> addView(keyButton(k)) }
        }

    /**
     * v2.0.19：图1 布局——工具条是铺满整行的深色通栏，按钮按 weight 等分、
     * 绿色文字、淡分块，不再是挤在左边的一小撮方块。功能键位仍是 v2.0.18 那 5 个
     * （模式 / 小键盘 / 旋转 / ⓘ / 收起），「铺满」「紧凑」不回来。
     */
    private fun barButton(label: String, weight: Float = 1f, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(KB_TEXT_ACCENT.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
            val d = resources.displayMetrics.density
            setPadding((2 * d).roundToInt(), (5 * d).roundToInt(),
                (2 * d).roundToInt(), (5 * d).roundToInt())
            setBackgroundColor(0xFFF5F6F8.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight
            ).apply { setMargins(1, 1, 1, 2) }
            setOnClickListener { onClick() }
        }

    /**
     * v2.0.20：键盘窗口的拖动把手。
     *
     * 轻点（位移 < 12dp）完全按原来的按钮点击走；按住超过阈值才由父容器接管，
     * 子按钮会收到 ACTION_CANCEL 因而不误触发 click。拖动改的是 kbHost 的
     * translationX/Y —— 它在 rotation 之后、以屏幕坐标应用，所以四个旋转角度下
     * 拖动方向都跟手指一致，也不影响 v2.0.19 的 pivot 计算。
     */
    private inner class DragBar(context: Context) : LinearLayout(context) {
        private var downX = 0f
        private var downY = 0f
        private var startTx = 0f
        private var startTy = 0f
        private var dragging = false
        private val slop = DRAG_SLOP_DP * resources.displayMetrics.density

        private fun begin(e: MotionEvent) {
            downX = e.rawX; downY = e.rawY
            startTx = if (::kbHost.isInitialized) kbHost.translationX else 0f
            startTy = if (::kbHost.isInitialized) kbHost.translationY else 0f
            dragging = false
        }

        private fun exceeded(e: MotionEvent): Boolean =
            kotlin.math.abs(e.rawX - downX) + kotlin.math.abs(e.rawY - downY) > slop

        private fun startDrag() {
            dragging = true
            hideKeyPreview()
            stopRepeatDelete()
        }

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> begin(ev)
                MotionEvent.ACTION_MOVE -> if (!dragging && exceeded(ev)) {
                    startDrag()
                    return true
                }
            }
            return false
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { begin(e); return true }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging && exceeded(e)) startDrag()
                    if (dragging) moveKbWindow(startTx + e.rawX - downX, startTy + e.rawY - downY)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 父容器拦截 MOVE 时，系统会把当前事件转成 CANCEL 交给自己；
                    // 这里只收尾保存，不回滚位置，随后的 MOVE 会重新进入拖动，手感连续。
                    if (dragging) { saveKbOffset(); dragging = false }
                    return true
                }
            }
            return super.onTouchEvent(e)
        }
    }

    /** 按当前旋转角度把键盘中心约束在屏幕内，保证任何角度至少还有半屏可见。 */
    private fun moveKbWindow(tx: Float, ty: Float) {
        if (!::kbHost.isInitialized) return
        val W = frameW().toFloat()
        val H = frameH().toFloat()
        if (W <= 0 || H <= 0) return
        val h = (panel.height.takeIf { it > 0 }
            ?: (220 * resources.displayMetrics.density).roundToInt()).toFloat()
        val (minX, maxX, minY, maxY) = when (kbRotation) {
            90 -> floatArrayOf(-h / 2, W - h / 2, -H / 2, H / 2)
            180 -> floatArrayOf(-W / 2, W / 2, -h / 2, H - h / 2)
            270 -> floatArrayOf(-(W - h / 2), h / 2, -H / 2, H / 2)
            else -> floatArrayOf(-W / 2, W / 2, -(H - h / 2), h / 2)
        }
        val nx = tx.coerceIn(minX, maxX)
        val ny = ty.coerceIn(minY, maxY)
        kbHost.translationX = nx
        kbHost.translationY = ny
        kbOffX = nx / W
        kbOffY = ny / H
    }

    /** 从持久化比例恢复位置；旋转 / 屏幕尺寸变化后由 applyKbRotation() 调用。 */
    private fun applyKbOffset() {
        val W = frameW().toFloat()
        val H = frameH().toFloat()
        if (W > 0 && H > 0) moveKbWindow(kbOffX * W, kbOffY * H)
    }

    private fun loadKbOffsetAndLock() {
        runCatching {
            val sp = getSharedPreferences(SP_NAME, MODE_PRIVATE)
            kbOffX = sp.getFloat(SP_KB_OFF_X, 0f)
            kbOffY = sp.getFloat(SP_KB_OFF_Y, 0f)
            displayLocked = sp.getBoolean(SP_DISPLAY_LOCK, true)
            fullscreenMode = sp.getBoolean(SP_FULLSCREEN, false)
        }
    }

    private fun saveKbOffset() {
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit()
                .putFloat(SP_KB_OFF_X, kbOffX)
                .putFloat(SP_KB_OFF_Y, kbOffY)
                .apply()
        }
        Log.i(TAG, "kb window offset = ($kbOffX, $kbOffY)")
    }

    // ---------------- v2.0.20 / v2.0.21：🔒 防误退锁 ----------------
    private fun lockLabel(): String = if (displayLocked) "🔒" else "🔓"

    private fun toggleDisplayLock() {
        displayLocked = !displayLocked
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit()
                .putBoolean(SP_DISPLAY_LOCK, displayLocked).apply()
        }
        if (displayLocked) applySoftLock() else clearSoftLock()
        applyLockState()
        Toast.makeText(
            this,
            if (displayLocked)
                Lang.t("🔒 已上锁（系统级屏幕固定）：上下左右宿主机手势全锁，状态栏下拉/返回桌面全部拦截。点 🔓 解锁")
            else
                Lang.t("🔓 已解锁：宿主机手势与返回恢复正常"),
            Toast.LENGTH_LONG
        ).show()
    }

    /**
     * v1.5.3：无条件恢复 startLockTask 作为上下手势的硬锁主力。
     *
     * 教训复盘：v1.5.1 无条件 startLockTask 时上下是锁住的（用户当时只抱怨任务栏点不到），
     * v1.5.2 误把它绑到 device owner 上，而登录过账号的设备 dpm 设 owner 必失败 →
     * 降级成纯软件锁 → 用户这次反馈「只有左右有效，上下还是宿主机状态栏/任务栏」。
     * 因此屏幕固定绝不能再依赖 device owner 是否成功。
     *
     * device owner 仍尝试（干净设备能设上就顺带关系统 UI 隐藏底部导航条，两全），
     * 但它只是「锦上添花」，不是进锁的前提。
     */
    /**
     * v1.5.4【根治底部任务栏点不到】拿到 device owner 并启用 LOCK_TASK_FEATURE_NONE。
     *
     * 根因：非 owner 的 startLockTask 会被系统在底部强画一条 navigation bar（SYSTEM_UI 窗口），
     * 它盖在 guest 画面上、吃掉整条 Windows 任务栏（含开始菜单）的触摸 —— 即用户反复反馈的
     * 「底部这么大一片点不到」。只有 owner + LOCK_TASK_FEATURE_NONE 能把状态栏/导航栏/手势
     * 全隐藏：guest 拿满整块屏，任务栏可点，同时上下手势依旧锁死，两全。
     *
     * dpm 的检查是「设备不能有账号」，登录过账号的设备直接 dpm 会失败；root 下临时禁用账号
     * provider（gms/gsf/vending）绕过检查，设完立即恢复，账号数据不丢。
     * 幂等 + 每进程只跑一次重脚本；默认进入（syncSoftLock）与手动上锁都会调到这里。
     */
    private fun ensureDeviceOwner() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            ?: return
        val admin = android.content.ComponentName(
            this, com.gunyah.launcher.core.DeviceOwnerReceiver::class.java)
        if (dpm.isDeviceOwnerApp(packageName)) ownerReady = true
        if (!ownerReady && !ownerTried) {
            ownerTried = true
            runCatching {
                val cmp = "$packageName/com.gunyah.launcher.core.DeviceOwnerReceiver"
                val script = buildString {
                    append("cmd device_policy get-owner 2>/dev/null | grep -q '$packageName' && { echo ALREADY_OWNER; exit 0; }\n")
                    append("dpm set-device-owner $cmp >/dev/null 2>&1 && { echo OWNER_SET_DIRECT; exit 0; }\n")
                    append("DISABLED=''\n")
                    append("for p in com.google.android.gsf com.google.android.gms com.google.android.gsf.login com.android.vending; do\n")
                    append("  pm path \"\$p\" >/dev/null 2>&1 || continue\n")
                    append("  pm disable-user --user 0 \"\$p\" >/dev/null 2>&1 && DISABLED=\"\$DISABLED \$p\"\n")
                    append("done\n")
                    append("dpm set-device-owner $cmp 2>&1\n")
                    append("rc=\$?\n")
                    append("for x in \$DISABLED; do pm enable \"\$x\" >/dev/null 2>&1; done\n")
                    append("echo \"SET_RC=\$rc RESTORED=\$DISABLED\"\n")
                    append("exit \$rc\n")
                }
                val r = com.gunyah.launcher.core.RootShell.exec(script, timeoutMs = 30000)
                Log.i(TAG, "ensure-device-owner rc=${r.code} out=${r.stdout.take(240)}")
                if (dpm.isDeviceOwnerApp(packageName)) ownerReady = true
            }
        }
        if (ownerReady) {
            runCatching {
                dpm.setLockTaskPackages(admin, arrayOf(packageName))
                dpm.setLockTaskFeatures(admin, 0) // LOCK_TASK_FEATURE_NONE
                Log.i(TAG, "lockTaskFeatures=NONE applied (bottom nav hidden)")
            }
        } else {
            Log.w(TAG, "device owner unavailable; bottom nav bar may cover guest taskbar")
        }
    }

    private fun applySoftLock() {
        // 1) root 写 lock_task_packages 白名单：非 owner 也能免「固定此应用?」确认框直接进入。
        //    v1.5.1 实测有效，这是 startLockTask 能静默生效的关键。
        runCatching {
            val pkg = packageName
            val r = com.gunyah.launcher.core.RootShell.exec(
                "cur=\$(settings get secure lock_task_packages 2>/dev/null)\n" +
                    "case \"\$cur\" in\n" +
                    "  *$pkg*) : ;;\n" +
                    "  null|'') settings put secure lock_task_packages \"$pkg\" ;;\n" +
                    "  *) settings put secure lock_task_packages \"\$cur,$pkg\" ;;\n" +
                    "esac", timeoutMs = 8000)
            Log.i(TAG, "lock-task whitelist rc=${r.code}")
        }
        // 2) 【根治底部任务栏点不到】强制拿到 device owner + 隐藏系统 UI。详见 ensureDeviceOwner()。
        ensureDeviceOwner()
        // 3) 软件锁措施（手势排除区已缩小，见 applyLockExclusion）
        runCatching { applyImmersive() }
        applyLockExclusion()
        // 4) 无条件进入系统级屏幕固定：这是唯一能同时锁住【顶部下拉状态栏】+【底部上滑回桌面】的机制。
        //    不再依赖 device owner —— 否则账号设备会降级成只有左右有效的软件锁。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val inLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { am?.lockTaskModeState ?: 0 }.getOrDefault(0) != 0
            } else {
                @Suppress("DEPRECATION")
                runCatching { am?.isInLockTaskMode ?: false }.getOrDefault(false)
            }
            if (!inLock) runCatching { startLockTask() }
            Log.i(TAG, "hard lock on (screen pinning, unconditional)")
        }
        // v1.5.5：上锁后按底部导航条高度重算画面让位，保证 guest 任务栏可点。
        runCatching { updateSurfaceLayout() }
    }

    private fun clearSoftLock() {
        // 先解系统屏幕固定
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            runCatching { stopLockTask() }
        }
        if (::rootFrame.isInitialized && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { rootFrame.setSystemGestureExclusionRects(emptyList<Rect>()) }
        }
        runCatching { applyImmersive() }
        // v1.5.5：解锁后撤掉让位，画面恢复全屏铺满。
        runCatching { updateSurfaceLayout() }
        Log.i(TAG, "hard lock off")
    }

    /** 从后台回来 / 窗口尺寸变化后补一次锁：手势排除区 + 系统屏幕固定（若被系统解除）。幂等。 */
    private fun syncSoftLock() {
        if (!displayLocked || !::rootFrame.isInitialized) return
        // v1.5.4：默认进入显示器走的就是这里（applyLockState→syncSoftLock），必须在此
        // 确保 device owner + 隐藏底部导航条，否则任务栏仍被 SYSTEM_UI 导航条盖住点不到。
        ensureDeviceOwner()
        applyLockExclusion()
        // v1.5.3：与 applySoftLock 一致，无条件补锁，不依赖 device owner
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val inLock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                runCatching { am?.lockTaskModeState ?: 0 }.getOrDefault(0) != 0
            } else {
                @Suppress("DEPRECATION")
                runCatching { am?.isInLockTaskMode ?: false }.getOrDefault(false)
            }
            if (!inLock) runCatching { startLockTask() }
        }
        // v1.5.5：默认进入显示器也按底部导航条高度重算让位（任务栏落到可点区）。
        runCatching { updateSurfaceLayout() }
    }

    private fun applyLockState() {
        lockBtn?.text = lockLabel()
        lockBtn?.setBackgroundColor(
            if (displayLocked) 0xFF5A2630.toInt() else 0xFF1B232B.toInt()
        )
        lockBtn?.setTextColor(
            if (displayLocked) 0xFFFFC4BC.toInt() else 0xFF9BE7A0.toInt()
        )
        // 首次进入默认上锁时，这里就要真正 startLockTask（syncSoftLock 幂等含此逻辑）
        syncSoftLock()
        Log.i(TAG, "display lock = $displayLocked")
    }

    /**
     * v1.5.0：上锁时四边都放手势排除区，防止误触「滑回宿主机 / 下拉状态栏」。
     *  - 左右：拦系统返回手势（侧滑返回）
     *  - 顶部：拦下拉通知栏
     *  - 底部：拦上滑回桌面（注意 Android 对底部中央有系统强制白名单，
     *    系统不允许 App 完全占死底部中央的回桌面手势，这里尽量铺满两侧与大部分宽度）
     *
     * 手势排除区只影响系统手势，不影响普通触摸事件透传给 guest，
     * 因此 Windows 任务栏 / Linux 开始菜单的点击仍然正常。
     */
    private fun applyLockExclusion() {
        if (!::rootFrame.isInitialized || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        runCatching {
            if (!displayLocked) {
                rootFrame.setSystemGestureExclusionRects(emptyList<Rect>())
                return
            }
            val W = frameW()
            val H = frameH()
            if (W <= 0 || H <= 0) return
            val d = resources.displayMetrics.density
            val side = (80 * d).roundToInt()     // 左右返回手势宽度（保持）
            // v1.5.2：上下排除区从 56dp 缩到 12dp。原先 56dp 太宽，一旦 device owner
            // 未生效、系统仍画导航条，这条大排除区会吃掉 guest 任务栏点击。缩小后只留
            // 极窄兜底，主防线交给 device-owner 的 LOCK_TASK_FEATURE_NONE（导航条直接隐藏）。
            val vert = (12 * d).roundToInt()
            val rects = mutableListOf(
                Rect(0, 0, side, H),             // 左边缘
                Rect(W - side, 0, W, H),         // 右边缘
                Rect(0, 0, W, vert),             // 上边缘（极窄兜底）
                Rect(0, H - vert, W, H),         // 下边缘（极窄兜底）
            )
            rootFrame.setSystemGestureExclusionRects(rects)
        }
    }

    private fun showLockToast() {
        lockToast?.cancel()
        lockToast = Toast.makeText(
            this, Lang.t("🔒 已上锁（防误退）：点工具条 🔓 解锁后返回键才生效；屏幕底部仍可正常点"), Toast.LENGTH_SHORT
        )
        lockToast?.show()
    }

    /**
     * v2.0.16 图2 效果 + v2.0.17 震动 / 长按连删。
     * 按下瞬间：按键高亮 + 上方浮出放大字符气泡 + 12ms 轻震 + 立即发键；
     * ⌫ 另外在 400ms 后进入连续删除，抬手停止。
     */
    @SuppressLint("ClickableViewAccessibility")
    /** 键帽圆角背景：普通白 / 功能浅灰，按下加深。 */
    private fun keyBg(pressed: Boolean, accent: Boolean): GradientDrawable {
        val d = resources.displayMetrics.density
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 6f * d
            setColor((if (pressed) KB_BG_DOWN else if (accent) 0xFFDCE9FF.toInt() else KB_BG).toInt())
        }
    }

    /** v3.0：纯图形图标键——Windows 四格窗口 / 三横菜单，不带任何文字。返回一个自带居中的图标 Drawable。 */
    private fun iconDrawable(kind: String): android.graphics.drawable.Drawable {
        val d = resources.displayMetrics.density
        val s = (22 * d).roundToInt()
        return when (kind) {
            "win" -> {
                // 四格窗口 LOGO（Windows 蓝），用两层填满的方块拼 2×2，整体居中
                val half = (8.6f * d).roundToInt()
                val blue = 0xFF00A4EF.toInt()
                val cell = { GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; cornerRadius = 1f * d; setColor(blue); setSize(half, half)
                } }
                android.graphics.drawable.LayerDrawable(arrayOf(cell(), cell(), cell(), cell())).apply {
                    val o = ((s - half * 2) / 2f).roundToInt()
                    setLayerSize(0, half, half); setLayerGravity(0, Gravity.TOP or Gravity.START)
                    setLayerInset(0, o, o, 0, 0)
                    setLayerSize(1, half, half); setLayerGravity(1, Gravity.TOP or Gravity.END)
                    setLayerInset(1, 0, o, o, 0)
                    setLayerSize(2, half, half); setLayerGravity(2, Gravity.BOTTOM or Gravity.START)
                    setLayerInset(2, o, 0, 0, o)
                    setLayerSize(3, half, half); setLayerGravity(3, Gravity.BOTTOM or Gravity.END)
                    setLayerInset(3, 0, 0, o, o)
                }
            }
            else -> {
                // 三横菜单 LOGO
                val w = (18 * d).roundToInt()
                val h = (2.4f * d).roundToInt()
                val dark = 0xFF444444.toInt()
                val bar = { GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE; setColor(dark); cornerRadius = h / 2f; setSize(w, h)
                } }
                android.graphics.drawable.LayerDrawable(arrayOf(bar(), bar(), bar())).apply {
                    setLayerSize(0, w, h); setLayerGravity(0, Gravity.CENTER); setLayerInset(0, 0, (5 * d).roundToInt(), 0, 0)
                    setLayerSize(1, w, h); setLayerGravity(1, Gravity.CENTER)
                    setLayerSize(2, w, h); setLayerGravity(2, Gravity.CENTER); setLayerInset(2, 0, 0, 0, (5 * d).roundToInt())
                }
            }
        }
    }

    /** 图标键背景：圆角键帽 + 居中图形叠加（保证图形在键帽正中）。 */
    private fun iconKeyBg(pressed: Boolean, icon: String): android.graphics.drawable.Drawable {
        val s = (26 * resources.displayMetrics.density).roundToInt()
        return android.graphics.drawable.LayerDrawable(arrayOf(keyBg(pressed, false), iconDrawable(icon))).apply {
            setLayerGravity(0, Gravity.FILL)
            setLayerSize(1, s, s)
            setLayerGravity(1, Gravity.CENTER)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun keyButton(k: KbKey): TextView =
        TextView(this).apply {
            val isAction = k.action != null
            val isIcon = k.icon != null
            val isShiftKey = k.action == "shift"
            // v1.9.65：Shift 待发/锁定时字母键显示大写。锁定态 Shift 键显示「⇧锁」并高亮。
            val shownLabel = when {
                isShiftKey && kbShiftLocked -> "⇧锁"
                !isIcon && (kbShiftArmed || kbShiftLocked) && k.code in kbLetterCodes -> k.label.uppercase()
                else -> k.label
            }
            val shiftActive = isShiftKey && (kbShiftArmed || kbShiftLocked)
            text = if (isIcon) "" else shownLabel
            setTextColor((if (isAction || shiftActive) KB_TEXT_ACCENT else KB_TEXT).toInt())
            gravity = Gravity.CENTER
            textSize = if (shownLabel.length >= 3) 12f else 16f
            isAllCaps = false
            background = if (isIcon) iconKeyBg(false, k.icon!!) else keyBg(false, isAction || shiftActive)
            layoutParams = LinearLayout.LayoutParams(
                0, (44 * resources.displayMetrics.density).roundToInt(), k.weight
            ).apply { setMargins((2 * resources.displayMetrics.density).roundToInt(), 0,
                (2 * resources.displayMetrics.density).roundToInt(), 0) }
            val isDelete = k.code == KeyCodeMapper.KEY_BACKSPACE
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        v.background = if (isIcon) iconKeyBg(true, k.icon!!) else keyBg(true, isAction)
                        if (!isIcon) showKeyPreview(v, shownLabel)
                        hapticTick()
                        when {
                            // v1.9.65：⇧ 长按锁定 / 短按粘滞 —— DOWN 先挂起，不在按下瞬间切换
                            isShiftKey -> {
                                shiftLongPressPending = true
                                ui.removeCallbacks(shiftLongPressRunnable)
                                ui.postDelayed(shiftLongPressRunnable, 350L)
                            }
                            isAction -> handleKeyAction(k.action!!)
                            k.code == 0 -> Unit   // 空占位，不发键
                            else -> sendKeyTap(k)
                        }
                        if (isDelete) scheduleRepeatDelete()
                        v.performClick()
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        v.background = if (isIcon) iconKeyBg(false, k.icon!!) else keyBg(false, isAction)
                        hideKeyPreview()
                        stopRepeatDelete()
                        if (isShiftKey) {
                            ui.removeCallbacks(shiftLongPressRunnable)
                            // 计时未触发就抬手 = 短按（粘滞切换）；已触发则是长按锁定，保持不动
                            if (shiftLongPressPending) {
                                shiftLongPressPending = false
                                if (e.actionMasked == MotionEvent.ACTION_UP) handleKeyAction("shift")
                            }
                        }
                        true
                    }
                    else -> true
                }
            }
        }.also { keyViews.add(it) }

    /** v3.0：处理翻页动作键。 */
    private fun handleKeyAction(action: String) {
        when (action) {
            "page_shortcut" -> switchKbPage(1)
            "page_func" -> switchKbPage(3)
            "page_num" -> switchKbPage(1)
            "page_letters" -> switchKbPage(0)
            // v1.9.65：短按 ⇧ —— 锁定时解锁，否则切换粘滞大写
            "shift" -> {
                if (kbShiftLocked) unlockShift()
                else {
                    kbShiftArmed = !kbShiftArmed
                    rebuildKeyRows()
                }
            }
        }
    }

    /** 按键震动反馈：18ms 轻震。v1.8.4 起走全局节流器，避免与全局点击震动叠加成两下。 */
    private fun hapticTick() {
        ClickHaptic.tick(this, 18, 100)
    }

    /** ⌫ 长按：先发一次，400ms 后每 ~90ms 发一对 down/up，直到抬手。 */
    private fun scheduleRepeatDelete() {
        stopRepeatDelete()
        val start = Runnable { startRepeatLoop() }
        repeatStart = start
        ui.postDelayed(start, 400)
    }

    private fun startRepeatLoop() {
        repeatActive = true
        val sink = NativeInputBridge.sink(NativeInputBridge.CH_KEYBOARD)
        val code = KeyCodeMapper.KEY_BACKSPACE.toShort()
        val r = object : Runnable {
            override fun run() {
                if (!repeatActive) return
                EvdevEncoder.sendKey(sink, code, true)
                ui.postDelayed({ EvdevEncoder.sendKey(sink, code, false) }, 40)
                ui.postDelayed(this, 90)
            }
        }
        repeatRunnable = r
        r.run()
    }

    private fun stopRepeatDelete() {
        repeatActive = false
        repeatStart?.let { ui.removeCallbacks(it) }
        repeatStart = null
        repeatRunnable?.let { ui.removeCallbacks(it) }
        repeatRunnable = null
    }

    /**
     * 键盘 4 向旋转：0°贴底 / 90°贴左 / 180°贴顶(倒置) / 270°贴右。
     * 整块 kbHost 一起转，按键文字与浮出气泡方向一致；画面与 ⌨ 浮球不转。
     * 纯手动按钮，与手机物理方向完全无关（系统横屏在 guest 设置里改，手机仍竖屏）。
     */
    private fun rotateKeyboard() {
        kbRotation = (kbRotation + 90) % 360
        applyKbRotation()
        rotateBtn?.text = "旋转 ${kbRotation}°"
        Toast.makeText(
            this,
            "键盘 ${kbRotation}°：0°贴底 / 90°贴左 / 180°贴顶 / 270°贴右（与手机方向无关）",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * v2.0.19 旋转「直接消失」根因修复。
     *
     * 旧版只做 `kbHost.rotation = 90`。kbHost 是 MATCH_PARENT 全屏容器，默认 pivot
     * 在它自己的中心 (W/2, H/2)；贴底的键盘中心是 (W/2, H-h/2)，绕屏幕中心转 90°
     * 后会被甩到 x≈(W+H)/2 的屏幕外（1440×3168 时约 860px 出界）—— 用户看到的
     * 就是「点旋转键盘直接消失」。
     *
     * 现在不改 layout、不加 translation（避免依赖变换顺序），只把 pivot 设到按实测
     * 宽高算出的点，让旋转后键盘中心正好落在目标边的中点。屏幕坐标顺时针旋转
     * R90(dx,dy)=(-dy,dx)，令 pivot=(px,py) 满足 pivot+Rθ(C-pivot)=T：
     *   90°  T=(h/2, H/2)    → px=(H+W)/4,      py=(3H-W)/4
     *   180° T=(W/2, h/2)    → px=W/2,          py=H/2
     *   270° T=(W-h/2, H/2)  → px=(3W-H)/4,     py=(3H-W)/4
     * h（键盘高度）被消掉，所以不需要等键盘测量，也不受折叠小键盘影响。
     * 气泡用未旋转布局坐标加在 kbHost 里，随同一变换一起转，位置始终贴合按键。
     */
    private fun applyKbRotation() {
        if (!::kbHost.isInitialized || !::rootFrame.isInitialized) return
        val w = kbHost.width.takeIf { it > 0 } ?: rootFrame.width
        val h = kbHost.height.takeIf { it > 0 } ?: rootFrame.height
        if (w <= 0 || h <= 0) return
        val W = w.toFloat(); val H = h.toFloat()
        val (px, py) = when (kbRotation) {
            90 -> Pair((H + W) / 4f, (3f * H - W) / 4f)
            180 -> Pair(W / 2f, H / 2f)
            270 -> Pair((3f * W - H) / 4f, (3f * H - W) / 4f)
            else -> Pair(W / 2f, H / 2f)
        }
        kbHost.pivotX = px
        kbHost.pivotY = py
        kbHost.rotation = kbRotation.toFloat()
        // v2.0.20：旋转后按新角度重新 clamp 已拖动的位置，键盘不会飞出屏外。
        applyKbOffset()
        Log.i(TAG, "kb rotation $kbRotation° pivot=(${px.roundToInt()},${py.roundToInt()}) in ${w}x${h}")
    }

    /** 累加 left/top 得到子 View 在 host 内的「未旋转」布局坐标。
     *  不依赖 getLocationInWindow —— 父 View 有 rotation 时它的返回值含义会变复杂。 */
    private fun layoutOffsetIn(v: View, host: View): Pair<Int, Int> {
        var x = 0
        var y = 0
        var cur: View? = v
        while (cur != null && cur !== host) {
            x += cur.left
            y += cur.top
            cur = cur.parent as? View
        }
        return Pair(x, y)
    }

    /** 图2：灰底圆角、白色粗体放大字符，浮在按键正上方；第一行空间不够则落到下方。
     *  v2.0.17：气泡加进 kbHost（与键盘同层），旋转时跟着一起转，位置始终贴合按键。 */
    private fun showKeyPreview(anchor: View, label: String) {
        hideKeyPreview()
        if (!::kbHost.isInitialized || anchor.width <= 0) return
        val d = resources.displayMetrics.density
        val bw = (anchor.width * 1.3f).roundToInt().coerceAtLeast((46 * d).roundToInt())
        val bh = (anchor.height * 1.1f).roundToInt().coerceAtLeast((42 * d).roundToInt())
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = if (label.length > 2) 15f else 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            elevation = 8 * d
            background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(PREVIEW_BG)
            }
            isClickable = false
            isFocusable = false
            layoutParams = FrameLayout.LayoutParams(bw, bh)
        }
        kbHost.addView(tv)
        keyPreview = tv
        val (ax, ay) = layoutOffsetIn(anchor, kbHost)
        val x = ax + anchor.width / 2 - bw / 2
        var y = ay - bh - (4 * d).roundToInt()
        if (y < 0) y = ay + anchor.height + (4 * d).roundToInt()
        val lp = tv.layoutParams as FrameLayout.LayoutParams
        lp.gravity = Gravity.TOP or Gravity.LEFT
        lp.leftMargin = x.coerceIn(0, (kbHost.width - bw).coerceAtLeast(0))
        lp.topMargin = y.coerceIn(0, (kbHost.height - bh).coerceAtLeast(0))
        tv.layoutParams = lp
        tv.scaleX = 0.72f; tv.scaleY = 0.72f; tv.alpha = 0f
        tv.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(110).start()
    }

    private fun hideKeyPreview() {
        keyPreview?.let {
            runCatching { if (::kbHost.isInitialized) kbHost.removeView(it) }
        }
        keyPreview = null
    }

    // ---------------- 键盘密度：v2.0.17 固定图1 标准档 ----------------
    /** 「紧凑 / 铺满」档位已按用户要求移除，键盘高度固定为图1 的标准档。 */
    private fun applyKbDensity() {
        if (!::panel.isInitialized) return
        val d = resources.displayMetrics.density
        val pv = (6 * d).roundToInt()
        val m = (2 * d).roundToInt()
        for (v in keyViews) {
            v.setPadding(v.paddingLeft, pv, v.paddingRight, pv)
            v.textSize = if (v.text.length >= 3) 12f * 0.82f else 12f
            (v.layoutParams as? LinearLayout.LayoutParams)?.let { lp ->
                lp.setMargins(m, m, m, m)
                v.layoutParams = lp
            }
        }
        panel.setPadding(
            (4 * d).roundToInt(), (4 * d).roundToInt(),
            (4 * d).roundToInt(), (6 * d).roundToInt()
        )
    }

    /** 竖屏可一键折叠小键盘，让主键变大；横屏空间够，默认展开。 */
    private fun numpadLabel(): String =
        if (numpadView?.visibility == View.GONE) "小键盘 ×" else "小键盘 ✓"

    private fun toggleNumpad() {
        numpadView?.let {
            it.visibility = if (it.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        numBtn?.text = numpadLabel()
        Toast.makeText(
            this,
            if (numpadView?.visibility == View.GONE) "小键盘已折叠" else "小键盘已展开：数字键直接出数字（不受 NumLock 影响）",
            Toast.LENGTH_SHORT
        ).show()
    }

    /** 状态卡片已删；长按 ⌨ 或点 ⓘ 临时看链路，不占画面。 */
    private fun showDiagnostics() {
        val r = NativeInputBridge.channelsReady
        val msg = buildString {
            append(Lang.t("画面 ")).append(if (surfaceSet) "✓" else "✗")
                .append(Lang.t(" 服务 ")).append(if (NativeInputBridge.connected) "✓" else "✗")
                .append(Lang.t(" 写入✓")).append(NativeInputBridge.writeOk)
                .append(" ✗").append(NativeInputBridge.writeFail)
            append("\n").append(if (absPointer) "usb-tablet " else Lang.t("相对mouse(降级) "))
                .append(if (inputW > 0) "${inputW}x${inputH}" else "?")
                .append(" / 画面 ${guestW}x${guestH}")
            append("\n").append(modeLabel())
            if (inputMode == MODE_TOUCHPAD) {
                append(" 速度×${TP_GAINS[tpGainIdx.coerceIn(0, TP_GAINS.size - 1)]}")
                append(Lang.t(" 箭头")).append(
                    if (cursorVisible) CURSOR_SIZE_NAMES[cursorSizeIdx.coerceIn(0, CURSOR_SCALES.size - 1)]
                    else Lang.t("关")
                )
            }
            // v2.0.27：显示当前这套启动盘的记忆键尾，确认「按盘记忆」确实分了档。
            append("\n").append(Lang.t("软件箭头按盘记忆: "))
                .append(cursorKey.removePrefix(SP_TP_CURSOR_PROFILE))
                .append(" / ").append(if (cursorVisible) Lang.t("开") else Lang.t("关（默认）"))
            append("\n").append(NativeInputBridge.inputDiagnostics.replace(" | ", "\n"))
            append(Lang.t("\n通道 触摸/键盘/鼠标/开关: "))
                .append(if (r.getOrNull(0) == true) "✓" else "✗")
                .append(if (r.getOrNull(1) == true) "✓" else "✗")
                .append(if (r.getOrNull(2) == true) "✓" else "✗")
                .append(if (r.getOrNull(3) == true) "✓" else "✗")
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------------- 输入模式切换（v2.0.7） ----------------

    private fun modeLabel(): String = "模式: " + if (inputMode == MODE_MULTITOUCH) "多点触控" else "触摸板"

    /**
     * v3.0：按用户要求删除「直接点按」模式（它与多点触控同理），
     * 只保留两档循环：触摸板 ⇄ 多点触控。MODE_ABSOLUTE(2) 常量仅为兼容旧配置读取，
     * 运行时不再产生该值（loadInputMode 会把旧的 2 归到触摸板）。
     */
    private fun toggleInputMode() {
        inputMode = if (inputMode == MODE_TOUCHPAD) MODE_MULTITOUCH else MODE_TOUCHPAD
        // 切回触摸板时重新与 guest 光标对齐（期间 guest 光标可能被别的输入移动过）。
        if (inputMode == MODE_TOUCHPAD) tpInit = false
        saveInputMode()
        modeToggle?.text = modeLabel()
        updateCursorOverlay()   // v2.0.25：切出触摸板时隐藏箭头，切回时恢复
    }

    /** v2.0.24：长按「模式」按钮循环触摸板光标速度（慢 0.7 / 中 1.0 / 快 1.6），不改变模式。 */
    private fun cycleTpGain() {
        tpGainIdx = (tpGainIdx + 1) % TP_GAINS.size
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit()
                .putInt(SP_TP_GAIN, tpGainIdx).apply()
        }
        val names = arrayOf(Lang.t("慢"), Lang.t("中"), Lang.t("快"))
        Toast.makeText(
            this, "触摸板光标速度：${names[tpGainIdx]}（×${TP_GAINS[tpGainIdx]}）", Toast.LENGTH_SHORT
        ).show()
    }

    private fun loadInputMode() {
        runCatching {
            val sp = getSharedPreferences(SP_NAME, MODE_PRIVATE)
            // v3.0：删掉直接点按(2)，旧值 2 归到触摸板(0)，仅两档 0/1。
            val savedMode = sp.getInt(SP_MODE, MODE_TOUCHPAD)
            inputMode = if (savedMode == MODE_ABSOLUTE) MODE_TOUCHPAD else savedMode.coerceIn(0, 1)
            tpGainIdx = sp.getInt(SP_TP_GAIN, 1).coerceIn(0, TP_GAINS.size - 1)
            // v3.1：软件箭头强制默认关，不再读取历史记忆（含按盘记忆）。
            // 每次进入显示器都是关，需要时到虚拟键盘工具条「指针」自行打开，仅本次有效。
            cursorKey = cursorProfileKey()
            cursorVisible = false
            cursorSizeIdx = sp.getInt(SP_TP_CURSOR_SIZE, CURSOR_SIZE_DEFAULT)
                .coerceIn(0, CURSOR_SCALES.size - 1)
        }
    }

    private fun saveInputMode() {
        runCatching {
            getSharedPreferences(SP_NAME, MODE_PRIVATE).edit().putInt(SP_MODE, inputMode).apply()
        }
    }

    /**
     * v3.4：从后台返回显示器时唤醒 guest 息屏（v1.9.3 的鼠标+Shift 对真睡眠无效，这里加强）。
     *
     * 根因：Win11 长时间息屏可能从「关屏」进到 S3 睡眠/Modern Standby，
     * 此时普通鼠标/Shift 唤不醒，只有电源键事件（KEY_POWER）能唤醒。
     * 但电源键连按两次会进睡眠，所以必须去抖：3 秒内只发一轮，且一轮内只按一次。
     *
     * 策略（一轮，分 3 个延时批次，确保 socket 刚重连也能送达）：
     *  - 电源键短按 1 次（唤醒睡眠的关键；单独 down/up，绝不成对连按）
     *  - 鼠标 +14,+14 再 -14,-14（净位移 0，光标不飘，唤醒仅关屏的情形）
     *  - Shift 轻点（Windows 通用「任意键唤醒」兜底）
     * 全程 runCatching，socket 未连接时 enqueue 静默丢弃，不影响显示。
     */
    private fun wakeGuestDisplay() {
        val now = System.currentTimeMillis()
        if (now - lastWakeAt < 3000) return   // 去抖：onResume + focus 双重触发时只发一轮
        lastWakeAt = now

        fun mouseNudge() {
            runCatching {
                val mouse = NativeInputBridge.sink(NativeInputBridge.CH_MOUSE)
                EvdevEncoder.sendMouseMotion(mouse, 14, 14)
                ui.postDelayed({ runCatching { EvdevEncoder.sendMouseMotion(mouse, -14, -14) } }, 70)
            }
        }
        fun shiftTap() {
            runCatching {
                val kb = NativeInputBridge.sink(NativeInputBridge.CH_KEYBOARD)
                val shift = KeyCodeMapper.KEY_LEFTSHIFT.toShort()
                EvdevEncoder.sendKey(kb, shift, true)
                ui.postDelayed({ runCatching { EvdevEncoder.sendKey(kb, shift, false) } }, 70)
            }
        }
        // v1.9.76：移除 KEY_POWER 唤醒——guest 本来就醒着时，这一下电源键反而
        // 会把 Windows 按进睡眠，导致回到界面后鼠键全部失灵、只能重启 guest。
        // 只用「鼠标移动 + Shift 轻点」这种安全的「任意键唤醒」，醒着时无害、睡眠时能点亮。

        // 第 1 批：立即，鼠标+Shift（仅关屏时最快点亮）
        mouseNudge(); shiftTap()
        // 第 2 批：900ms，补一轮鼠标+Shift，确保刚重连的通道也能点亮
        ui.postDelayed({ mouseNudge(); shiftTap() }, 900)
        // 第 3 批：1800ms，最后一轮兜底
        ui.postDelayed({ mouseNudge(); shiftTap() }, 1800)
    }

    /** v2.0.22：默认上锁（纯软件锁），回前台补一次手势排除区，无系统弹窗。 */
    override fun onResume() {
        super.onResume()
        // v3.3：仅「从后台返回」时唤醒一次 guest 息屏；冷启动首次进入跳过。
        val returning = hasResumedOnce
        hasResumedOnce = true
        ui.postDelayed({
            if (!isFinishing && !isDestroyed) {
                syncSoftLock()
                if (returning) wakeGuestDisplay()
            }
        }, 700)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 此时 DecorView 一定已 attached，重新压一次沉浸式
        // （从后台回来、或用户下滑唤出系统栏后恢复全屏）
        if (hasFocus) {
            runCatching { applyImmersive() }
            hideIme()
            // 从后台回来后系统可能重置手势排除区，锁着就补一次（纯软件锁，无弹窗）。
            syncSoftLock()
            // v3.4：从后台返回时补一轮唤醒（与 onResume 双触发由 3 秒去抖保证只发一轮）
            if (hasResumedOnce) {
                ui.postDelayed({ if (!isFinishing && !isDestroyed) wakeGuestDisplay() }, 500)
            }
        }
    }

    override fun onDestroy() {
        alive = false
        hideKeyPreview()
        stopRepeatDelete()
        hideIme()
        imeEdit?.sinkProvider = null
        lockToast?.cancel()
        if (displayLocked && ::rootFrame.isInitialized &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            runCatching { rootFrame.setSystemGestureExclusionRects(emptyList<Rect>()) }
        }
        runCatching { ball.animate().cancel() }
        ui.removeCallbacksAndMessages(null)
        // surfaceDestroyed 已处理 saveFrame / removeSurface，这里不重复拆 display。
        NativeInputBridge.stop()
        if (bound) runCatching { RootService.unbind(rootConn) }
        super.onDestroy()
    }
}
