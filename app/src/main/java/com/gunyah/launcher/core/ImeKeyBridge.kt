package com.gunyah.launcher.core

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import java.io.OutputStream

/**
 * 宿主输入法 → guest 键盘的桥接。
 *
 * 系统输入法产出的是 Unicode 文本，而 crosvm 的键盘设备只认 evdev scan code。
 * 这里把可打印的 ASCII 字符按「US 键盘布局」映射成 scan code + 是否需要 Shift，
 * 走已验证可用的 7790 键盘通道逐个发出。
 *
 * 中文 / emoji 等无法用单个按键还原的字符会被跳过（guest 端需要剪贴板同步才能输入）。
 */
object ImeKeyBridge {
    private const val SHIFT = 42 // KEY_LEFTSHIFT

    /** 返回 (evdev scanCode, 是否需要按住 Shift)。只覆盖 US 布局可用单键表示的字符。 */
    fun map(c: Char): Pair<Int, Boolean>? = when (c) {
        'a', 'A' -> KeyCodeMapper.KEY_A to c.isUpperCase()
        'b', 'B' -> KeyCodeMapper.KEY_B to c.isUpperCase()
        'c', 'C' -> KeyCodeMapper.KEY_C to c.isUpperCase()
        'd', 'D' -> KeyCodeMapper.KEY_D to c.isUpperCase()
        'e', 'E' -> KeyCodeMapper.KEY_E to c.isUpperCase()
        'f', 'F' -> KeyCodeMapper.KEY_F to c.isUpperCase()
        'g', 'G' -> KeyCodeMapper.KEY_G to c.isUpperCase()
        'h', 'H' -> KeyCodeMapper.KEY_H to c.isUpperCase()
        'i', 'I' -> KeyCodeMapper.KEY_I to c.isUpperCase()
        'j', 'J' -> KeyCodeMapper.KEY_J to c.isUpperCase()
        'k', 'K' -> KeyCodeMapper.KEY_K to c.isUpperCase()
        'l', 'L' -> KeyCodeMapper.KEY_L to c.isUpperCase()
        'm', 'M' -> KeyCodeMapper.KEY_M to c.isUpperCase()
        'n', 'N' -> KeyCodeMapper.KEY_N to c.isUpperCase()
        'o', 'O' -> KeyCodeMapper.KEY_O to c.isUpperCase()
        'p', 'P' -> KeyCodeMapper.KEY_P to c.isUpperCase()
        'q', 'Q' -> KeyCodeMapper.KEY_Q to c.isUpperCase()
        'r', 'R' -> KeyCodeMapper.KEY_R to c.isUpperCase()
        's', 'S' -> KeyCodeMapper.KEY_S to c.isUpperCase()
        't', 'T' -> KeyCodeMapper.KEY_T to c.isUpperCase()
        'u', 'U' -> KeyCodeMapper.KEY_U to c.isUpperCase()
        'v', 'V' -> KeyCodeMapper.KEY_V to c.isUpperCase()
        'w', 'W' -> KeyCodeMapper.KEY_W to c.isUpperCase()
        'x', 'X' -> KeyCodeMapper.KEY_X to c.isUpperCase()
        'y', 'Y' -> KeyCodeMapper.KEY_Y to c.isUpperCase()
        'z', 'Z' -> KeyCodeMapper.KEY_Z to c.isUpperCase()

        '1' -> KeyCodeMapper.KEY_1 to false
        '2' -> KeyCodeMapper.KEY_2 to false
        '3' -> KeyCodeMapper.KEY_3 to false
        '4' -> KeyCodeMapper.KEY_4 to false
        '5' -> KeyCodeMapper.KEY_5 to false
        '6' -> KeyCodeMapper.KEY_6 to false
        '7' -> KeyCodeMapper.KEY_7 to false
        '8' -> KeyCodeMapper.KEY_8 to false
        '9' -> KeyCodeMapper.KEY_9 to false
        '0' -> KeyCodeMapper.KEY_0 to false

        ' ' -> KeyCodeMapper.KEY_SPACE to false
        '\n', '\r' -> KeyCodeMapper.KEY_ENTER to false
        '\t' -> KeyCodeMapper.KEY_TAB to false

        '`' -> KeyCodeMapper.KEY_GRAVE to false
        '-' -> KeyCodeMapper.KEY_MINUS to false
        '=' -> KeyCodeMapper.KEY_EQUAL to false
        '[' -> KeyCodeMapper.KEY_LEFTBRACE to false
        ']' -> KeyCodeMapper.KEY_RIGHTBRACE to false
        '\\' -> KeyCodeMapper.KEY_BACKSLASH to false
        ';' -> KeyCodeMapper.KEY_SEMICOLON to false
        '\'' -> KeyCodeMapper.KEY_APOSTROPHE to false
        ',' -> KeyCodeMapper.KEY_COMMA to false
        '.' -> KeyCodeMapper.KEY_DOT to false
        '/' -> KeyCodeMapper.KEY_SLASH to false

        '~' -> KeyCodeMapper.KEY_GRAVE to true
        '!' -> KeyCodeMapper.KEY_1 to true
        '@' -> KeyCodeMapper.KEY_2 to true
        '#' -> KeyCodeMapper.KEY_3 to true
        '$' -> KeyCodeMapper.KEY_4 to true
        '%' -> KeyCodeMapper.KEY_5 to true
        '^' -> KeyCodeMapper.KEY_6 to true
        '&' -> KeyCodeMapper.KEY_7 to true
        '*' -> KeyCodeMapper.KEY_8 to true
        '(' -> KeyCodeMapper.KEY_9 to true
        ')' -> KeyCodeMapper.KEY_0 to true
        '_' -> KeyCodeMapper.KEY_MINUS to true
        '+' -> KeyCodeMapper.KEY_EQUAL to true
        '{' -> KeyCodeMapper.KEY_LEFTBRACE to true
        '}' -> KeyCodeMapper.KEY_RIGHTBRACE to true
        '|' -> KeyCodeMapper.KEY_BACKSLASH to true
        ':' -> KeyCodeMapper.KEY_SEMICOLON to true
        '"' -> KeyCodeMapper.KEY_APOSTROPHE to true
        '<' -> KeyCodeMapper.KEY_COMMA to true
        '>' -> KeyCodeMapper.KEY_DOT to true
        '?' -> KeyCodeMapper.KEY_SLASH to true

        else -> null
    }

    // 长文本（粘贴）不能一次性灌进去：guest 的输入队列会溢出，表现为「只进去后半段」。
    // 改成后台单线程逐字符发，每字符 flush 一次并留 2ms 间隔。
    private val typer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ime-type").apply { isDaemon = true }
    }

    /** 逐个字符发出：Shift↓ → 键↓ → 键↑ → Shift↑（与已验证的 sendKeyTap 顺序一致）。 */
    fun type(text: String, sink: OutputStream) {
        val payload = text
        typer.execute {
            for (c in payload) {
                val (code, needShift) = map(c) ?: continue
                try {
                    if (needShift) EvdevEncoder.sendKey(sink, SHIFT.toShort(), true)
                    EvdevEncoder.sendKey(sink, code.toShort(), true)
                    EvdevEncoder.sendKey(sink, code.toShort(), false)
                    if (needShift) EvdevEncoder.sendKey(sink, SHIFT.toShort(), false)
                    sink.flush()
                } catch (t: Throwable) {
                    break
                }
                try { Thread.sleep(2) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun backspace(sink: OutputStream) {
        val code = KeyCodeMapper.KEY_BACKSPACE.toShort()
        EvdevEncoder.sendKey(sink, code, true)
        EvdevEncoder.sendKey(sink, code, false)
    }
}

/**
 * 透明的输入法捕获框。布局里 1dp、透明、可聚焦；两指轻点时 requestFocus + showSoftInput 唤起系统输入法。
 *
 * 覆写 onCreateInputConnection：
 *  - commitText：转成 evdev 键发出，但不真正写进 EditText（始终保持空，避免上屏内容堆积）；
 *  - deleteSurroundingText：发退格；
 *  - sendKeyEvent：回车 / 退格 / 方向键等直接映射转发。
 */
class ImeCaptureEditText @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : EditText(context, attrs) {
    /** 由 Activity 注入：返回当前 guest 键盘通道的输出流。 */
    var sinkProvider: (() -> OutputStream)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        return object : InputConnectionWrapper(base, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    val sink = sinkProvider?.invoke()
                    if (sink != null) ImeKeyBridge.type(text.toString(), sink)
                }
                // 不写入内容：setComposingText 后清空，保持输入框永远为空。
                clearComposingText()
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // 拼音候选过程中的中间串不发给 guest，只在 commitText 时上屏。
                return super.setComposingText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                val sink = sinkProvider?.invoke()
                if (sink != null && beforeLength > 0) {
                    repeat(beforeLength) { ImeKeyBridge.backspace(sink) }
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event == null) return super.sendKeyEvent(event)
                val code = KeyCodeMapper.androidToEvdev(event.keyCode)
                if (code != null && event.action == KeyEvent.ACTION_DOWN) {
                    val sink = sinkProvider?.invoke()
                    if (sink != null) {
                        EvdevEncoder.sendKey(sink, code.toShort(), true)
                        EvdevEncoder.sendKey(sink, code.toShort(), false)
                        return true
                    }
                }
                return super.sendKeyEvent(event)
            }
        }
    }

    /** 供 Activity 在 focus 前把光标/文本清干净。 */
    fun reset() {
        setText("")
        setSelection(0)
    }
}
