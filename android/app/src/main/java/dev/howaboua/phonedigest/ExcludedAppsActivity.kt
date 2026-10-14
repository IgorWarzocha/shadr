package dev.howaboua.phonedigest

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors

class ExcludedAppsActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private data class Choice(val packageName: String, val label: String)
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        val store = Store(this)
        val saved = store.excludedApps
        val selected = (state?.getStringArrayList("selected")?.toSet() ?: saved).toMutableSet()
        val packages = packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toMutableSet()
        packages.addAll(saved)
        packages.addAll(selected)
        packages.addAll(ShadeListener.bound?.activeNotifications?.map { it.packageName } ?: emptyList())
        val choices = packages.map { pkg ->
            val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
                catch (_: android.content.pm.PackageManager.NameNotFoundException) { "Unavailable app" }
            Choice(pkg, label)
        }.sortedWith(compareBy<Choice> { it.label.lowercase() }.thenBy { it.packageName })
        val padding = (24 * resources.displayMetrics.density).toInt()
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(padding, padding, padding, padding) }
        val scroll = ScrollView(this).apply { isFocusableInTouchMode = true; addView(body) }
        setContentView(scroll)
        scroll.requestFocus()
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        ViewCompat.requestApplyInsets(scroll)
        fun text(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, 12, 0, 16); body.addView(this)
        }
        text("Excluded apps", 28f)
        text("Selected apps' notifications stay on this phone. Your assistant can't read or clear them.")
        text("Already shared notifications leave your inbox after the next successful send with notification access. This can't erase earlier receipts or copies your assistant already has.")
        fun count(packages: Set<String>) = "${packages.size} ${if (packages.size == 1) "app" else "apps"} excluded."
        val status = text("${count(saved)} Changes apply after Save.")
        status.accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        val searchBox = TextInputLayout(this).apply { hint = "Search apps" }
        val search = TextInputEditText(searchBox.context).apply { id = R.id.app_search; setSingleLine() }
        searchBox.addView(search); body.addView(searchBox)
        val save = MaterialButton(this).apply { id = R.id.save_exclusions; text = "Save"; body.addView(this) }
        val back = MaterialButton(this).apply {
            id = R.id.back; text = "Back"
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            strokeWidth = resources.displayMetrics.density.toInt().coerceAtLeast(1)
            strokeColor = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
            setOnClickListener { finish() }; body.addView(this)
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; body.addView(this) }
        val rows = choices.map { choice ->
            MaterialCheckBox(this).apply {
                text = "${choice.label}\n${choice.packageName}"
                contentDescription = choice.packageName
                isChecked = choice.packageName in selected
                setOnCheckedChangeListener { _, checked -> if (checked) selected.add(choice.packageName) else selected.remove(choice.packageName) }
                list.addView(this)
            } to choice
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val query = s.toString().trim()
                rows.forEach { (row, choice) -> row.visibility = if (choice.label.contains(query, true) || choice.packageName.contains(query, true)) android.view.View.VISIBLE else android.view.View.GONE }
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        save.setOnClickListener {
            val draft = selected.toSet()
            save.isEnabled = false; back.isEnabled = false
            rows.forEach { it.first.isEnabled = false }
            status.text = "Saving… An upload already started may finish first."
            executor.submit {
                val result = runCatching { store.saveExcludedApps(draft); Posting.schedule(this) }
                runOnUiThread {
                    if (!isDestroyed) {
                        save.isEnabled = true; back.isEnabled = true
                        rows.forEach { it.first.isEnabled = true }
                        status.text = if (result.isSuccess) "${count(draft)} Saved. Your inbox will update after the next successful send." else "Couldn't save. Please try again."
                    }
                }
            }
        }
        selection = selected
    }
    private var selection = mutableSetOf<String>()
    override fun onSaveInstanceState(out: Bundle) { out.putStringArrayList("selected", ArrayList(selection)); super.onSaveInstanceState(out) }
    override fun onDestroy() { executor.shutdown(); super.onDestroy() }
}
