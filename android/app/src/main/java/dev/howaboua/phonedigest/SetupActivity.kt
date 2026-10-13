package dev.howaboua.phonedigest

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.app.NotificationManagerCompat
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import java.time.Instant
import java.util.concurrent.Executors
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class SetupActivity : AppCompatActivity() {
    private lateinit var store: Store
    private lateinit var body: LinearLayout
    private var screen = "connect"
    private var pickerKind = "noise"
    private var pickerSelection: MutableSet<String>? = null
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var queuedAt = 0L
    private var applyPairing: ((ConnectionSettings) -> Unit)? = null
    private val scanPairing = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) importPairing(result.contents)
        else if (screen == "connect") pairingMessage("No pairing details imported. Try scanning again or choose a QR image.")
    }
    private val pickPairing = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && screen == "connect") {
            val attempt = ++generation
            findViewById<MaterialButton>(R.id.test_connection)?.isEnabled = true
            pairingMessage("Reading pairing QR…")
            executor.submit {
                val result = runCatching { PairingImage.read(contentResolver, uri) }
                handler.post {
                    if (!isDestroyed && attempt == generation) {
                        result.fold(::importPairing) { pairingMessage("Could not read pairing QR. Choose a clear image containing one QR code.") }
                    }
                }
            }
        }
    }
    private fun pairingMessage(value: String) {
        findViewById<TextView>(R.id.feedback)?.apply { visibility = View.VISIBLE; text = value }
    }
    private fun importPairing(raw: String) {
        if (screen != "connect") return
        try {
            val settings = PairingPayload.parse(raw, store.zone)
            generation++ // Any previous test must not commit after a new import.
            findViewById<MaterialButton>(R.id.test_connection)?.isEnabled = true
            applyPairing?.invoke(settings)
            pairingMessage("Pairing details imported. Check the server address, then press Test and connect.")
        } catch (e: IllegalArgumentException) { pairingMessage(e.message ?: "Invalid pairing QR.") }
    }
    private val refresh = object : Runnable {
        override fun run() {
            if (screen == "allow" && notificationAccess() != NotificationAccess.REQUIRED) show("done")
            if (screen == "main") {
                findViewById<TextView>(R.id.status)?.text = statusText()
                findViewById<MaterialButton>(R.id.allow_access)?.visibility = if (notificationAccess() == NotificationAccess.REQUIRED) View.VISIBLE else View.GONE
                if (store.lastPost > queuedAt && queuedAt > 0) {
                    findViewById<TextView>(R.id.feedback)?.visibility = View.GONE
                    queuedAt = 0
                }
            }
            handler.postDelayed(this, 1500)
        }
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (!BuildConfig.DEBUG) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    "settings" -> show("main")
                    "advanced", "connect" -> if (store.configured) show("settings") else finish()
                    "picker" -> show("advanced")
                    "allow", "done" -> show("main")
                    else -> finish()
                }
            }
        })
        val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        store = Store(this)
        pickerKind = state?.getString("pickerKind") ?: "noise"
        pickerSelection = state?.getStringArrayList("pickerSelection")?.toMutableSet()
        show(state?.getString("screen") ?: SetupFlow.initial(store.configured, notificationAccess()).name.lowercase())
    }
    private fun show(target: String) {
        generation++
        applyPairing = null
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        if (target != "picker") pickerSelection = null
        screen = target
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; isFocusableInTouchMode = true; addView(body) }
        setContentView(scroll)
        scroll.requestFocus()
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        ViewCompat.requestApplyInsets(scroll)
        scroll.post { ViewCompat.requestApplyInsets(scroll) }
        when (target) {
            "connect" -> connect()
            "allow" -> { title("Allow notification access"); text("Phone inbox needs access to send your notifications to your inbox."); button("Allow notification access", R.id.allow_access) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }; text("Return here after allowing access.") }
            "done" -> { title("You're ready"); text("Notifications will post in the background. Dry-run is on: nothing will be dismissed."); button("Open inbox status", R.id.continue_setup) { show("main") } }
            "main" -> main()
            "settings" -> { title("Settings"); button("Connection", R.id.edit_connection) { show("connect") }; button("Advanced", R.id.advanced_policy) { show("advanced") }; button("Back", R.id.back) { show("main") } }
            "advanced" -> advanced()
            "picker" -> picker(pickerKind)
        }
    }
    private fun connect() {
        title("Connect your inbox")
        text("Scan a pairing QR or enter your server details.")
        button("Scan QR", R.id.scan_pairing) {
            generation++
            findViewById<MaterialButton>(R.id.test_connection)?.isEnabled = true
            scanPairing.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(PairingScanActivity::class.java).setBeepEnabled(false)
                .setOrientationLocked(false).setPrompt("Scan your Phone inbox pairing QR"))
        }
        button("Import QR image", R.id.import_pairing) {
            generation++
            findViewById<MaterialButton>(R.id.test_connection)?.isEnabled = true
            pickPairing.launch("image/*")
        }
        val endpoint = field("Server address", store.endpoint, R.id.endpoint,
            helper = "Include the port if needed. http:// or https:// is optional.", placeholder = "100.64.0.1:8787")
        val token = field("Phone token", store.token, R.id.phone_token, true)
        val accessToggle = MaterialSwitch(this).apply { id = R.id.access_toggle; text = "Use Access credentials"; isChecked = store.accessId.isNotBlank() }
        body.addView(accessToggle)
        val access = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(access)
        val parent = body; body = access
        val accessId = field("Access client ID", store.accessId, R.id.access_id)
        val accessSecret = field("Access client secret", store.accessSecret, R.id.access_secret, true)
        body = parent
        fun updateAccess() {
            val address = ServerAddress.normalize(endpoint.text.toString())
            val remote = runCatching { java.net.URI(address).let { it.scheme == "https" && it.host != null } }.getOrDefault(false) && ConnectionMode.infer(address) == ConnectionMode.CLOUDFLARE
            accessToggle.visibility = if (remote) View.VISIBLE else View.GONE
            access.visibility = if (remote && accessToggle.isChecked) View.VISIBLE else View.GONE
        }
        accessToggle.setOnCheckedChangeListener { _, _ -> updateAccess() }
        endpoint.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {} ; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { updateAccess() }; override fun afterTextChanged(s: Editable?) {} })
        updateAccess()
        applyPairing = { settings ->
            endpoint.setText(settings.endpoint)
            token.setText(settings.token)
            accessId.setText(settings.accessId)
            accessSecret.setText(settings.accessSecret)
            accessToggle.isChecked = settings.accessId.isNotBlank()
            updateAccess()
            val scroll = body.parent as ScrollView
            currentFocus?.clearFocus()
            scroll.requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(scroll.windowToken, 0)
        }
        val message = feedback()
        lateinit var test: MaterialButton
        test = button("Test and connect", R.id.test_connection) {
            val address = ServerAddress.normalize(endpoint.text.toString())
            val draft = ConnectionSettings(ConnectionMode.infer(address), address, token.text.toString().trim(), store.zone,
                if (accessToggle.isChecked) accessId.text.toString().trim() else "", if (accessToggle.isChecked) accessSecret.text.toString().trim() else "")
            try {
                draft.validate()
                val attempt = ++generation
                test.isEnabled = false; message.visibility = View.VISIBLE; message.text = "Testing connection…"
                executor.submit {
                    val result = try { ConnectionTest.check(draft) } catch (_: Exception) { ConnectionResult(false, "Could not connect. Check your address, network and credentials.") }
                    handler.post {
                        if (!isDestroyed && attempt == generation) {
                            test.isEnabled = true
                            if (address != ServerAddress.normalize(endpoint.text.toString()) || draft.token != token.text.toString().trim() || draft.accessId != (if (accessToggle.isChecked) accessId.text.toString().trim() else "") || draft.accessSecret != (if (accessToggle.isChecked) accessSecret.text.toString().trim() else "")) message.text = "Details changed. Test again."
                            else if (result.connected) {
                                try { store.saveSettings(draft, store.extraProtected, store.extraAuthenticators, store.smsPackages, allowedNoise(), store.liveEnabled); Posting.schedule(this, true); show(SetupFlow.connected(notificationAccess()).name.lowercase()) }
                                catch (_: IllegalStateException) { message.text = "Could not save. Please try again." }
                            } else message.text = result.message
                        }
                    }
                }
            } catch (e: IllegalArgumentException) { message.visibility = View.VISIBLE; message.text = e.message; endpoint.requestFocus() }
        }
        if (store.configured) button("Back", R.id.back) { show("settings") }
    }
    private fun notificationAccess() = SetupFlow.access(
        packageName in NotificationManagerCompat.getEnabledListenerPackages(this), ShadeListener.bound != null
    )
    private fun statusText(): String {
        val posted = if (store.lastPost == 0L) "Not yet" else java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(store.lastPost))
        val waiting = ShadeListener.bound?.activeNotifications?.size?.toString() ?: "Unknown"
        val connection = when (notificationAccess()) {
            NotificationAccess.REQUIRED -> "Notification access needed\nAllow access to send your notifications."
            NotificationAccess.WAITING_FOR_LISTENER -> "Starting notification access\nKeep notification access enabled."
            NotificationAccess.CONNECTED -> if (store.postState == PostState.FAILED) "Connection problem\nCheck your network. We'll retry." else "Connected\nNotifications send in the background."
        }
        return "$connection\n\nLast post: $posted\nItems waiting: $waiting\n${if (store.liveAllowed && store.serverLive) "Live dismissal on" else "Dry-run on. Nothing will be dismissed."}"
    }
    private fun main() {
        title("Phone inbox")
        text(statusText()).id = R.id.status
        val message = feedback()
        button("Post now", R.id.post_now) { queuedAt = System.currentTimeMillis(); Posting.schedule(this, true); message.visibility = View.VISIBLE; message.text = "Post queued. Status updates after the inbox responds." }
        button("Allow notification access", R.id.allow_access) { show("allow") }.visibility = if (notificationAccess() == NotificationAccess.REQUIRED) View.VISIBLE else View.GONE
        button("Settings", R.id.settings) { show("settings") }
    }
    private fun advanced() {
        title("Advanced")
        text("Code candidates are redacted across apps. Authenticator digits are removed. Pattern matching cannot catch every code.")
        text("Known banks, authenticators, system apps and device administrators cannot be selected as noise.")
        button("Choose noise apps", R.id.noise_packages) { picker("noise") }
        button("Protect more apps", R.id.protected_packages) { picker("protect") }
        button("Redact all digits in apps", R.id.authenticator_packages) { picker("redact") }
        val live = MaterialSwitch(this).apply { id = R.id.live_dismissal; text = "Live dismissal"; isChecked = store.liveEnabled; isEnabled = System.currentTimeMillis() >= store.installedAt + Policy.WEEK }
        body.addView(live)
        if (!live.isEnabled) text("Available after seven days. Until then, nothing is dismissed.")
        val message = feedback()
        button("Save", R.id.save_settings) { try { store.saveSettings(store.connection, store.extraProtected, store.extraAuthenticators, store.smsPackages, allowedNoise(), live.isChecked && live.isEnabled); show("settings") } catch (e: IllegalArgumentException) { message.visibility = View.VISIBLE; message.text = e.message } }
        button("Back", R.id.back) { show("settings") }
    }
    private fun protected(pkg: String) = AppProtection(this, store).contains(pkg)
    private fun allowedNoise() = AppProtection(this, store).allowedNoise()
    private fun picker(kind: String) {
        generation++; screen = "picker"
        body.removeAllViews()
        title(when (kind) { "noise" -> "Noise apps"; "protect" -> "Protected apps"; else -> "Digit redaction" })
        val selected = pickerSelection ?: (when (kind) { "noise" -> allowedNoise(); "protect" -> store.extraProtected; else -> store.extraAuthenticators }).toMutableSet()
        pickerKind = kind; pickerSelection = selected
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(intent, 0).distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(packageManager).toString().lowercase() }
        for (app in apps) {
            val pkg = app.activityInfo.packageName
            val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(ImageView(this).apply { setImageDrawable(app.loadIcon(packageManager)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(40), dp(40)))
            row.addView(com.google.android.material.checkbox.MaterialCheckBox(this).apply {
                text = app.loadLabel(packageManager); isChecked = pkg in selected
                isEnabled = kind != "noise" || !protected(pkg)
                if (!isEnabled) { text = "${app.loadLabel(packageManager)} · Protected"; isChecked = false }
                setOnCheckedChangeListener { _, checked -> if (checked) selected.add(pkg) else selected.remove(pkg) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            body.addView(row)
        }
        button("Save selection", R.id.save_settings) {
            when (kind) { "noise" -> store.noise = selected.filterNot(::protected).toSet(); "protect" -> store.extraProtected = selected; else -> store.extraAuthenticators = selected }
            store.noise = allowedNoise(); show("advanced")
        }
        button("Back", R.id.back) { show("advanced") }
    }
    private fun title(value: String) { text(value, 28f) }
    private fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setPadding(0, dp(12), 0, dp(16)); body.addView(this) }
    private fun feedback() = text("").apply { id = R.id.feedback; visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private fun field(label: String, value: String, viewId: Int, secret: Boolean = false, helper: String? = null, placeholder: String? = null): TextInputEditText {
        val container = TextInputLayout(this).apply {
            hint = label
            helperText = helper
            placeholderText = placeholder
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = TextInputEditText(container.context).apply { id = viewId; setText(value); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI }
        container.addView(edit); body.addView(container, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }); return edit
    }
    private fun button(value: String, viewId: Int, action: () -> Unit) = MaterialButton(this).apply { id = viewId; text = value
        if (viewId in setOf(R.id.back, R.id.settings, R.id.edit_connection, R.id.advanced_policy, R.id.noise_packages, R.id.protected_packages, R.id.authenticator_packages)) {
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            strokeWidth = dp(1); strokeColor = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
        }
        minimumHeight = dp(56); setOnClickListener { action() }; body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { generation++; executor.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("screen", screen); out.putString("pickerKind", pickerKind); pickerSelection?.let { out.putStringArrayList("pickerSelection", ArrayList(it)) }; super.onSaveInstanceState(out) }
}
