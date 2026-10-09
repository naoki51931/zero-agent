package com.zeroagent.app

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** A user-controlled floating instruction bubble over Chrome. */
object GuidanceOverlay {
    private var view: LinearLayout? = null
    private var windowManager: WindowManager? = null

    fun show(context: Context, steps: List<String>) {
        dismiss()
        if (steps.isEmpty() || !Settings.canDrawOverlays(context)) return
        val app = context.applicationContext
        val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        var index = 0
        val layout = LinearLayout(app).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 20, 28, 20)
            background = GradientDrawable().apply {
                setColor(Color.rgb(28, 36, 55))
                cornerRadius = 28f
            }
            elevation = 12f
        }
        val text = TextView(app).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            maxLines = 6
        }
        val buttons = LinearLayout(app).apply { orientation = LinearLayout.HORIZONTAL }
        val next = Button(app).apply { text = "次へ" }
        val close = Button(app).apply { text = "閉じる"; setOnClickListener { dismiss() } }
        fun update() {
            text.setText("ZERO AGENT  ${index + 1}/${steps.size}\n${steps[index]}")
            next.setText(if (index == steps.lastIndex) "完了" else "次へ")
        }
        next.setOnClickListener {
            if (index == steps.lastIndex) dismiss() else { index++; update() }
        }
        buttons.addView(next)
        buttons.addView(close)
        layout.addView(text)
        layout.addView(buttons)
        update()
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 100
            width = app.resources.displayMetrics.widthPixels * 9 / 10
        }
        wm.addView(layout, params)
        view = layout
        windowManager = wm
    }

    fun dismiss() {
        view?.let { runCatching { windowManager?.removeView(it) } }
        view = null
        windowManager = null
    }
}
