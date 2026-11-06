package com.lanqi.weiassist.phone

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * The card that asks the user to allow a tap, floating at the bottom of the screen over whatever app is in front:
 * a badge and the session it is for, the question, what was typed (scrolling inside a capped box, however long it
 * is, so the buttons always stay on screen), and Cancel / Allow. [danger] (a payment, or a spot the app can't see)
 * makes Allow red. Plain views: it is drawn from the accessibility service, outside any activity.
 */
internal object ConfirmCard {
    private class Palette(dark: Boolean) {
        val card = if (dark) 0xFF1F2125.toInt() else Color.WHITE
        val text = if (dark) 0xFFECEDEF.toInt() else 0xFF1B1B1F.toInt()
        val muted = if (dark) 0xFF9AA0A6.toInt() else 0xFF6B6F76.toInt()
        val box = if (dark) 0xFF2A2D33.toInt() else 0xFFF3F4F6.toInt()
        val boxText = if (dark) 0xFFD4D6DA.toInt() else 0xFF3A3D42.toInt()
        val quiet = if (dark) 0xFF2F3238.toInt() else 0xFFECEEF1.toInt()
        val accent = if (dark) 0xFF6EA8FE.toInt() else 0xFF2563EB.toInt()
        val danger = if (dark) 0xFFF87171.toInt() else 0xFFDC2626.toInt()
        val onAccent = if (dark) 0xFF0B1220.toInt() else Color.WHITE
    }

    fun build(
        context: Context, title: String, session: String, detailLabel: String, detail: String, danger: Boolean,
        onAnswer: (Boolean) -> Unit,
    ): View {
        val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val p = Palette(dark)
        val dp = context.resources.displayMetrics.density
        fun Int.dp() = (this * dp).toInt()
        fun rounded(color: Int, radius: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radius.dp().toFloat() }
        val tone = if (danger) p.danger else p.accent

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, 24)
            elevation = 16.dp().toFloat()
            setPadding(20.dp(), 18.dp(), 20.dp(), 16.dp())
        }

        // Badge, and which session is asking.
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = context.getString(if (danger) R.string.confirm_badge_danger else R.string.confirm_badge)
                textSize = 12f; setTextColor(tone); typeface = Typeface.DEFAULT_BOLD
                background = rounded(tone and 0x00FFFFFF or 0x24000000, 999)
                setPadding(10.dp(), 4.dp(), 10.dp(), 4.dp())
            })
            if (session.isNotBlank()) addView(TextView(context).apply {
                text = session; textSize = 12f; setTextColor(p.muted)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(10.dp(), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        })

        card.addView(TextView(context).apply {
            text = title; textSize = 19f; setTextColor(p.text); typeface = Typeface.DEFAULT_BOLD
            setLineSpacing(2.dp().toFloat(), 1f)
            maxLines = 4; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 12.dp(), 0, 0)
        })

        if (detail.isNotBlank()) {
            if (detailLabel.isNotBlank()) card.addView(TextView(context).apply {
                text = detailLabel; textSize = 12f; setTextColor(p.muted)
                setPadding(0, 14.dp(), 0, 6.dp())
            })
            else card.addView(View(context), LinearLayout.LayoutParams(1, 12.dp()))
            val maxBox = (context.resources.displayMetrics.heightPixels * 0.42).toInt()
            card.addView(object : ScrollView(context) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
                    super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(maxBox, MeasureSpec.AT_MOST))
            }.apply {
                background = rounded(p.box, 14)
                isScrollbarFadingEnabled = false
                isVerticalScrollBarEnabled = true
                clipToOutline = true
                addView(TextView(context).apply {
                    text = detail; textSize = 14f; setTextColor(p.boxText)
                    setLineSpacing(3.dp().toFloat(), 1f)
                    setPadding(14.dp(), 12.dp(), 14.dp(), 12.dp())
                    setTextIsSelectable(false)
                })
            })
        }

        // Cancel and Allow, side by side, the same size.
        card.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 16.dp(), 0, 0)
            fun button(label: Int, bg: Int, fg: Int, answer: Boolean) = TextView(context).apply {
                text = context.getString(label); textSize = 16f; setTextColor(fg); typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = RippleDrawable(ColorStateList.valueOf(0x33000000), rounded(bg, 14), rounded(Color.BLACK, 14))
                isClickable = true; isFocusable = false
                setOnClickListener { onAnswer(answer) }
            }
            addView(button(R.string.confirm_cancel, p.quiet, p.text, false),
                LinearLayout.LayoutParams(0, 50.dp(), 1f).apply { marginEnd = 6.dp() })
            addView(button(R.string.confirm_allow, tone, p.onAccent, true),
                LinearLayout.LayoutParams(0, 50.dp(), 1f).apply { marginStart = 6.dp() })
        })

        // Float it: a margin all round, and room for its shadow.
        return FrameLayout(context).apply {
            setPadding(12.dp(), 12.dp(), 12.dp(), 16.dp())
            clipToPadding = false
            addView(card)
        }
    }
}
