package com.lagradost.cloudstream3.utils.diagnostics

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import com.lagradost.cloudstream3.R

/**
 * A dedicated provider-diagnostic PAGE in the activity content, NOT an AlertDialog.
 * It overlays the settings content only: system bars and CloudStream bottom navigation
 * remain visible. The application's Logcat screen is never modified by this class.
 */
object DiagnosticDialog {
    private const val PAGE_TAG = "cloudstream-provider-diagnostic-page"

    /** Search only the already-sanitized report. A matching Live Status session
     * heading retains its process history, not just the line with the provider name. */
    internal fun filterReport(report: String, query: String): String {
        val term = query.trim()
        if (term.isEmpty()) return report
        val lines = report.lines()
        val output = mutableListOf<String>()
        val sessionHeading = Regex("^[–—-]\\s+.+[•].*Sesi\\s*#")
        var currentHeading: String? = null
        var group = mutableListOf<String>()
        fun flush() {
            val header = currentHeading
            if (header != null && header.contains(term, ignoreCase = true)) {
                output.add(header)
                output.addAll(group)
            } else {
                val hits = group.filter { it.contains(term, ignoreCase = true) }
                if (hits.isNotEmpty()) {
                    if (header != null) output.add(header)
                    output.addAll(hits)
                }
            }
            group = mutableListOf()
        }
        lines.forEach { line ->
            if (sessionHeading.containsMatchIn(line)) {
                flush()
                currentHeading = line
            } else {
                group.add(line)
            }
        }
        flush()
        val nonBlank = output.filter { it.isNotBlank() }
        return buildString {
            appendLine("SEARCH: $term")
            appendLine("Matching lines: ${nonBlank.size}")
            appendLine()
            if (nonBlank.isEmpty()) append("No matching diagnostic events.")
            else append(nonBlank.joinToString("\n"))
        }
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("CloudStream provider diagnostic", text))
        Toast.makeText(context, "Diagnostic copied", Toast.LENGTH_SHORT).show()
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
                contentDescription = "Close Diagnostic"
                isAllCaps = false
                textSize = 24f
                minWidth = dp(activity, 44)
                minimumWidth = dp(activity, 44)
            }
            heading.addView(back, LinearLayout.LayoutParams(dp(activity, 52), dp(activity, 48)))
            heading.addView(TextView(activity).apply {
                text = "Provider Diagnostic"
                textSize = 20f
                setTypeface(null, Typeface.BOLD)
                maxLines = 1
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            page.addView(heading)

            // Full-width controls on separate rows: no squeezed/wrapped "Important" label.
            val modeRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            val important = Button(activity).apply {
                text = "Important"
                isAllCaps = false
            }
            val trace = Button(activity).apply {
                text = "Full trace"
                isAllCaps = false
            }
            modeRow.addView(important, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
            modeRow.addView(trace, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
            page.addView(modeRow)

            val sections = Spinner(activity)
            sections.adapter = ArrayAdapter(
                activity, android.R.layout.simple_spinner_item, ProviderTrace.sections
            ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            page.addView(sections, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 48)
            ))

            // Search is local to Diagnose. Raw Android Logcat and its UI are untouched.
            val searchRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val search = EditText(activity).apply {
                hint = "Search diagnostic..."
                contentDescription = "Search diagnostic logs"
                isSingleLine = true
                inputType = InputType.TYPE_CLASS_TEXT
                textSize = 15f
                setPadding(dp(activity, 8), 0, dp(activity, 8), 0)
            }
            searchRow.addView(search, LinearLayout.LayoutParams(0, dp(activity, 44), 1f))
            val clearSearch = Button(activity).apply {
                text = "×"
                contentDescription = "Clear diagnostic search"
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
            val copyButton = Button(activity).apply { text = "Copy"; isAllCaps = false }
            val clearButton = Button(activity).apply { text = "Clear"; isAllCaps = false }
            val closeButton = Button(activity).apply { text = "Close"; isAllCaps = false }
            listOf(copyButton, clearButton, closeButton).forEach {
                actions.addView(it, LinearLayout.LayoutParams(0, dp(activity, 48), 1f))
            }
            page.addView(actions)

            val handler = Handler(Looper.getMainLooper())
            var lastContent = ""
            fun visibleReport(): String = filterReport(
                ProviderTrace.report(ProviderTrace.sections[selected], !full),
                search.text?.toString().orEmpty()
            )
            fun refresh() {
                if (page.parent == null) return
                // Both the visual selection and report use the SAME mode snapshot.
                important.text = if (full) "Important" else "✓ Important"
                trace.text = if (full) "✓ Full trace" else "Full trace"
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
            sections.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                    selected = position
                    scroll.scrollTo(0, 0)
                    refresh()
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            }
            // Copy what the user actually sees after Search / category / mode filtering.
            copyButton.setOnClickListener { copy(activity, visibleReport()) }
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

    private fun resolveBackground(context: Context): Int {
        val value = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, value, true)
        return if (value.resourceId != 0) context.getColor(value.resourceId) else value.data
    }
}
