package com.lagradost.cloudstream3.utils.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.HorizontalScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.TextViewCompat
import androidx.appcompat.widget.AppCompatButton
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.utils.diagnostics.DiagnosticText

/**
 * A dedicated provider-diagnostic PAGE in the activity content, NOT an AlertDialog.
 * It overlays the settings content only: system bars and CloudStream bottom navigation
 * remain visible. The application's Logcat screen is never modified by this class.
 */
object DiagnosticDialog {
    private const val PAGE_TAG = "cloudstream-provider-diagnostic-page"

    /** Search matches complete multi-line events, not isolated wrapped lines. */
    internal fun filterReport(report: String, query: String, ctx: Context? = null): String =
        DiagnosticSearch.filter(report, query, ctx)

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CloudStream provider diagnostic", text))
        Toast.makeText(context, DiagnosticText.get(context, "copy_toast"), Toast.LENGTH_SHORT).show()
    }

    fun show(context: Context) {
        val activity = context as? Activity ?: return
        val root = activity.findViewById<FrameLayout>(R.id.homeRoot) ?: return
        // Don't stack pages or periodic refresh loops if the menu is tapped twice.
        if (root.findViewWithTag<View>(PAGE_TAG) != null) return

        root.post {
            if (activity.isFinishing || activity.isDestroyed || root.findViewWithTag<View>(PAGE_TAG) != null) {
                return@post
            }

            var full = false
            var selected = 0
            val padding = dp(activity, 16)
            val page = LinearLayout(activity).apply {
                tag = PAGE_TAG
                orientation = LinearLayout.VERTICAL
                setPadding(padding, dp(activity, 12), padding, dp(activity, 8))
                setBackgroundColor(resolveBackground(activity))
                isClickable = true // Prevent taps from reaching Settings underneath this page.
                isFocusableInTouchMode = true
            }

            val heading = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val back = Button(activity).apply {
                text = "‹"
                contentDescription = DiagnosticText.get(activity, "close_descr")
                isAllCaps = false
                textSize = 24f
                minWidth = dp(activity, 44)
                minimumWidth = dp(activity, 44)
            }
            heading.addView(back, LinearLayout.LayoutParams(dp(activity, 52), dp(activity, 48)))
            heading.addView(TextView(activity).apply {
                text = DiagnosticText.get(activity, "page_title")
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            page.addView(heading)

            // Full-width controls on separate rows: no squeezed/wrapped "Important" label.
            val modeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val important = Button(activity).apply {
                text = DiagnosticText.get(activity, "important")
                isAllCaps = false
            }
            val trace = Button(activity).apply {
                text = DiagnosticText.get(activity, "full_trace")
                isAllCaps = false
            }
            modeRow.addView(important, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
            modeRow.addView(trace, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
            page.addView(modeRow)

            // Compact category tabs; the stable category IDs still drive reports and exports.
            val categoryRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val categoryTabs = ProviderTrace.sections.mapIndexed { index, section ->
                AppCompatButton(activity).apply {
                    text = DiagnosticText.tab(activity, section)
                    contentDescription = DiagnosticText.section(activity, section)
                    isAllCaps = false
                    textSize = 13f
                    maxLines = 1
                    minWidth = dp(activity, 56)
                    minimumWidth = dp(activity, 56)
                    setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
                    setTextColor(Color.parseColor("#E5E2EB"))
                    backgroundTintList = null
                    background = categoryBackground(activity)
                    isSelected = index == selected
                    categoryRow.addView(this, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 48)
                    ).apply { marginEnd = dp(activity, 6) })
                }
            }
            val categoryScroll = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                setPadding(0, dp(activity, 4), 0, dp(activity, 4))
                addView(categoryRow)
            }
            page.addView(categoryScroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 56)
            ))

            // Search is local to Diagnose. Raw Android Logcat and its UI are untouched.
            val searchRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val search = EditText(activity).apply {
                hint = DiagnosticText.get(activity, "search_hint")
                contentDescription = DiagnosticText.get(activity, "search_descr")
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                textSize = 15f
                setPadding(dp(activity, 8), 0, dp(activity, 8), 0)
            }
            searchRow.addView(search, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
            val clearSearch = Button(activity).apply {
                text = "×"
                contentDescription = DiagnosticText.get(activity, "clear_search_descr")
                isAllCaps = false
                textSize = 18f
            }
            searchRow.addView(clearSearch, LinearLayout.LayoutParams(dp(activity, 48), dp(activity, 44)))
            page.addView(searchRow)

            val body = TextView(activity).apply {
                textSize = 13f
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
                // The last event must be readable above the fixed action bar.
                setPadding(dp(activity, 4), dp(activity, 12), dp(activity, 4), dp(activity, 28))
            }
            val scroll = ScrollView(activity).apply {
                isFillViewport = true
                clipToPadding = false
                setPadding(0, 0, 0, dp(activity, 8))
                addView(body)
            }
            // Only log content scrolls. Action buttons remain pinned above bottom navigation.
            page.addView(scroll, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            ))

            val actions = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val copyButton = AppCompatButton(activity).apply { text = DiagnosticText.get(activity, "copy"); isAllCaps = false }
            val saveButton = AppCompatButton(activity).apply { text = DiagnosticText.get(activity, "save"); isAllCaps = false }
            val clearButton = AppCompatButton(activity).apply { text = DiagnosticText.get(activity, "clear"); isAllCaps = false }
            val closeButton = AppCompatButton(activity).apply { text = DiagnosticText.get(activity, "close"); isAllCaps = false }
            listOf(copyButton, saveButton, clearButton, closeButton).forEach {
                // Four equal touch targets; fit Malay labels and large font settings too.
                it.minWidth = 0
                it.minimumWidth = 0
                it.setPadding(dp(activity, 4), 0, dp(activity, 4), 0)
                it.maxLines = 1
                it.textSize = 14f
                TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(
                    it, 10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP
                )
                actions.addView(it, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
            }
            page.addView(actions)

            val handler = Handler(Looper.getMainLooper())
            var lastContent = ""
            fun visibleReport(): String = filterReport(
                ProviderTrace.report(activity, ProviderTrace.sections[selected], !full),
                search.text?.toString().orEmpty(), activity
            )
            fun exportReport(): String = DiagnosticReportMetadata.wrap(
                activity, visibleReport(), ProviderTrace.sections[selected], full,
                search.text?.toString().orEmpty()
            )
            fun refresh() {
                if (page.parent == null) return
                // Both the visual selection and report use the SAME mode snapshot.
                important.text = if (full) DiagnosticText.get(activity, "important") else "✓ " + DiagnosticText.get(activity, "important")
                trace.text = if (full) "✓ " + DiagnosticText.get(activity, "full_trace") else DiagnosticText.get(activity, "full_trace")
                important.alpha = if (full) 0.72f else 1f
                trace.alpha = if (full) 1f else 0.72f
                important.setTypeface(null, if (full) Typeface.NORMAL else Typeface.BOLD)
                trace.setTypeface(null, if (full) Typeface.BOLD else Typeface.NORMAL)
                val content = visibleReport()
                if (lastContent != content) {
                    val previousScroll = scroll.scrollY
                    body.text = content
                    lastContent = content
                    scroll.post { if (page.parent != null) scroll.scrollTo(0, previousScroll) }
                }
            }

            var backCallback: OnBackPressedCallback? = null
            val update = object : Runnable {
                override fun run() {
                    if (page.parent == null) return
                    refresh()
                    handler.postDelayed(this, 1500)
                }
            }
            fun closePage() {
                handler.removeCallbacks(update)
                backCallback?.remove()
                backCallback = null
                (page.parent as? ViewGroup)?.removeView(page)
            }

            back.setOnClickListener { closePage() }
            closeButton.setOnClickListener { closePage() }
            important.setOnClickListener {
                full = false
                scroll.scrollTo(0, 0)
                refresh()
            }
            trace.setOnClickListener {
                full = true
                scroll.scrollTo(0, 0)
                refresh()
            }
            categoryTabs.forEachIndexed { index, tab ->
                tab.setOnClickListener {
                    if (selected != index) {
                        selected = index
                        categoryTabs.forEachIndexed { tabIndex, button ->
                            button.isSelected = tabIndex == selected
                        }
                        scroll.scrollTo(0, 0)
                        refresh()
                    }
                    // Reveal the full tab after touch or D-pad selection without moving log content.
                    tab.requestRectangleOnScreen(Rect(0, 0, tab.width, tab.height), false)
                }
            }
            // Export the filtered report body plus fresh context; Copy and Save share this path.
            copyButton.setOnClickListener { copy(activity, exportReport()) }
            saveButton.setOnClickListener {
                val host = activity as? MainActivity
                if (host == null) {
                    Toast.makeText(activity, DiagnosticText.get(activity, "save_failed"), Toast.LENGTH_LONG).show()
                } else {
                    host.saveDiagnosticReport(exportReport(), ProviderTrace.sections[selected], full)
                }
            }
            clearSearch.setOnClickListener { search.text?.clear() }
            search.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    scroll.scrollTo(0, 0)
                    refresh()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
            clearButton.setOnClickListener {
                ProviderTrace.clear()
                scroll.scrollTo(0, 0)
                refresh()
            }

            // Activity root is already inside system-bar-safe content on normal phones.
            // Explicitly account for edge-to-edge layouts and for the actual nav position.
            val rootOnScreen = IntArray(2)
            root.getLocationOnScreen(rootOnScreen)
            val visible = Rect()
            activity.window.decorView.getWindowVisibleDisplayFrame(visible)
            val safeTop = (visible.top - rootOnScreen[1]).coerceAtLeast(0).coerceAtMost(root.height / 3)
            var bottomMargin = 0
            val nav = activity.findViewById<View>(R.id.nav_view)
            if (nav?.visibility == View.VISIBLE && nav.height > 0) {
                val navOnScreen = IntArray(2)
                nav.getLocationOnScreen(navOnScreen)
                val navTopInRoot = navOnScreen[1] - rootOnScreen[1]
                if (navTopInRoot in 1 until root.height) {
                    bottomMargin = root.height - navTopInRoot
                }
            }
            if (bottomMargin == 0 && visible.bottom > 0) {
                bottomMargin = (rootOnScreen[1] + root.height - visible.bottom).coerceAtLeast(0)
            }
            root.addView(page, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                topMargin = safeTop
                this.bottomMargin = bottomMargin
            })
            if (activity is ComponentActivity) {
                backCallback = object : OnBackPressedCallback(true) {
                    override fun handleOnBackPressed() { closePage() }
                }.also { activity.onBackPressedDispatcher.addCallback(activity, it) }
            }
            page.requestFocus()
            refresh()
            handler.postDelayed(update, 1500)
        }
    }

    /** Rounded outlined tabs with purple selection and a distinct keyboard/TV focus border. */
    private fun categoryBackground(context: Context): StateListDrawable {
        fun shape(fill: String, border: String, focused: Boolean = false) = GradientDrawable().apply {
            cornerRadius = dp(context, 8).toFloat()
            setColor(Color.parseColor(fill))
            setStroke(dp(context, if (focused) 2 else 1), Color.parseColor(border))
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused, android.R.attr.state_selected), shape("#494257", "#D5C5F2", true))
            addState(intArrayOf(android.R.attr.state_focused), shape("#26232E", "#D5C5F2", true))
            addState(intArrayOf(android.R.attr.state_pressed), shape("#5A506B", "#A99ABB"))
            addState(intArrayOf(android.R.attr.state_selected), shape("#494257", "#494257"))
            addState(intArrayOf(), shape("#00000000", "#45454D"))
        }
    }

    private fun resolveBackground(context: Context): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }
}
