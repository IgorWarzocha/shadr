package dev.howaboua.phonedigest

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
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
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var generation = 0
    private var queuedAt = 0L
    private var connectDraft: ((ConnectionSettings, () -> Boolean) -> Unit)? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (screen == "background") show("background")
    }
    private val scanPairing = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) importPairing(result.contents)
        else if (screen == "connect") findViewById<TextView>(R.id.feedback)?.visibility = View.GONE
    }
    private val pickPairing = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null && screen == "connect") {
            val attempt = ++generation
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
            connectDraft?.invoke(settings) { true }
        } catch (_: IllegalArgumentException) { pairingMessage("This QR code didn't work. Try a new pairing QR code.") }
    }
    private val refresh = object : Runnable {
        override fun run() {
            if (screen == "allow" && notificationAccess() != NotificationAccess.REQUIRED) {
                show(SetupFlow.connected(notificationAccess(), BackgroundAccess.ready(this@SetupActivity)).name.lowercase())
            }
            if (screen == "background" && BackgroundAccess.ready(this@SetupActivity)) show("done")
            if (screen == "main") {
                findViewById<TextView>(R.id.status)?.text = statusText()
                findViewById<MaterialButton>(R.id.allow_access)?.visibility = if (notificationAccess() == NotificationAccess.REQUIRED) View.VISIBLE else View.GONE
                findViewById<MaterialButton>(R.id.background_settings)?.visibility = if (!BackgroundAccess.ready(this@SetupActivity)) View.VISIBLE else View.GONE
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
                    "connect" -> if (store.configured) show("settings") else finish()
                    "allow", "background", "done" -> show("main")
                    else -> finish()
                }
            }
        })
        val light = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = light; isAppearanceLightNavigationBars = light }
        store = Store(this)
        val restored = state?.getString("screen")?.takeIf { it in setOf("connect", "allow", "background", "settings") || (it in setOf("done", "main") && BackgroundAccess.ready(this)) }
        show(restored ?: SetupFlow.initial(store.configured, notificationAccess(), BackgroundAccess.ready(this)).name.lowercase())
    }
    private fun show(target: String) {
        generation++
        connectDraft = null
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(currentFocus?.windowToken, 0)
        screen = target
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(20), dp(24), dp(24)) }
        val scroll = ScrollView(this).apply { isFillViewport = true; isFocusableInTouchMode = true; addView(body) }
        val root: View = scroll
        setContentView(root)
        scroll.requestFocus()
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        ViewCompat.requestApplyInsets(root)
        root.post { ViewCompat.requestApplyInsets(root) }
        when (target) {
            "connect" -> connect()
            "allow" -> {
                title("Allow notification access")
                text("Phone inbox needs access to send your notifications to your inbox.")
                button("Allow notification access", R.id.allow_access) { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                if (SetupFlow.restrictedSettingsHelp(android.os.Build.VERSION.SDK_INT, notificationAccess())) {
                    text("If Android says 'App was denied access', open Settings > Apps > Phone inbox > ⋮ > Allow restricted settings. Then enable notification access.")
                    button("Open app settings", R.id.open_app_settings) {
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
                    }
                }
                text("Return here after allowing access.")
            }
            "background" -> backgroundSetup()
            "done" -> { title("You're ready"); text("Notifications will send in the background. ${clearingStatus()}"); button("Open Phone inbox", R.id.continue_setup) { show("main") } }
            "main" -> main()
            "settings" -> { title("Settings"); button("Connection", R.id.edit_connection) { show("connect") }; button("Bot request setup", R.id.background_settings) { show("background") }; button("Back", R.id.back) { show("main") } }
        }
    }
    private fun connect() {
        title("Connect your inbox")
        text("Scan the QR code from your inbox to connect.")
        val scan = button("Scan QR", R.id.scan_pairing) {
            generation++
            scanPairing.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setCaptureActivity(PairingScanActivity::class.java).setBeepEnabled(false)
                .setOrientationLocked(false).setPrompt("Scan your Phone inbox pairing QR"))
        }
        val image = button("Import QR image", R.id.import_pairing) {
            generation++
            pickPairing.launch("image/*")
        }
        val manualToggle = TextView(this).apply {
            id = R.id.enter_manual
            text = "Enter details manually"
            textSize = 14f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
            minimumHeight = dp(48)
            gravity = android.view.Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
        }
        body.addView(manualToggle, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        val manual = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        body.addView(manual)
        val parent = body; body = manual
        val (savedAddress, savedToken) = ConnectionDraft.manualPrefill(store.connection)
        val endpoint = field("Server address", savedAddress, R.id.endpoint)
        val token = field("Phone token", savedToken, R.id.phone_token, true)
        val connect = button("Connect", R.id.test_connection) {
            val draft = ConnectionDraft.manual(endpoint.text.toString(), token.text.toString(), store.connection, store.zone)
            connectDraft?.invoke(draft) {
                draft == ConnectionDraft.manual(endpoint.text.toString(), token.text.toString(), store.connection, draft.zone)
            }
        }
        body = parent
        val message = feedback()
        manualToggle.setOnClickListener {
            manual.visibility = if (manual.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        connectDraft = { draft, stillMatches ->
            val attempt = ConnectionAttempt(draft)
            val revision = ++generation
            val previous = store.session()
            listOf(scan, image, manualToggle, connect).forEach { it.isEnabled = false }
            currentFocus?.clearFocus()
            (body.parent as ScrollView).requestFocus()
            (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(body.windowToken, 0)
            message.visibility = View.VISIBLE; message.text = "Connecting to your inbox…"
            executor.submit {
                val result = attempt.authenticate()
                handler.post {
                    if (!isDestroyed && revision == generation) {
                        listOf(scan, image, manualToggle, connect).forEach { it.isEnabled = true }
                        try {
                            var saved = false
                            store.ifCurrent(previous) {
                                saved = attempt.commit(result, stillMatches()) {
                                    store.saveSettings(it, result.pushConfig)
                                }
                            }
                            if (saved) {
                                Posting.schedule(this)
                                PushRegistration.refresh(this)
                                show(SetupFlow.connected(notificationAccess(), BackgroundAccess.ready(this)).name.lowercase())
                            } else message.text = if (result.connected) "Details changed. Please connect again." else result.message
                        } catch (_: Exception) { message.text = "Couldn't save your connection. Please try again." }
                    }
                }
            }
        }
        if (store.configured) button("Back", R.id.back) { show("settings") }
    }
    private fun notificationAccess() = SetupFlow.access(
        packageName in NotificationManagerCompat.getEnabledListenerPackages(this), ShadeListener.bound != null
    )
    private fun backgroundSetup() {
        title("Enable bot requests")
        text("Allow a brief, silent notification when a bot request is being processed. Phone inbox won't keep a permanent notification or poll while idle.")
        if (!BackgroundAccess.notificationsEnabled(this)) {
            button("Allow notifications", R.id.allow_service_notifications) {
                if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                }
            }
        }
        if (store.pushConfig == null) text("Push notifications aren't set up for this inbox yet. Finish Firebase setup on your server, then try again.")
        else if (store.pushRegistrationFailed) text("Couldn't enable bot requests. Check your connection and try again.")
        else if (!store.pushRegistered) text("Connecting bot requests to this phone…")
        button("Try again", R.id.retry_push) { PushRegistration.refresh(this); Posting.schedule(this); show("background") }
        button("Open app settings", R.id.open_app_settings) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }
        button("Back", R.id.back) { show("main") }
    }
    private fun clearingStatus() = if (store.paused) "Bot clearing is paused. Notifications still send." else "Bots can clear your notifications."
    private fun statusText(): String {
        val posted = if (store.lastPost == 0L) "Not yet" else java.time.format.DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(java.time.ZoneId.systemDefault()).format(Instant.ofEpochMilli(store.lastPost))
        val connection = when {
            notificationAccess() == NotificationAccess.REQUIRED -> "Notification access needed\nAllow access to send your notifications."
            !BackgroundAccess.notificationsEnabled(this) -> "Notifications need to be enabled\nEnable bot requests to finish setup."
            !store.pushRegistered -> "Bot requests aren't ready\nOpen bot request setup to finish connecting."
            notificationAccess() == NotificationAccess.WAITING_FOR_LISTENER -> "Starting notification access\nKeep notification access enabled."
            store.postState == PostState.FAILED -> "Connection problem\nCheck your network. We'll retry."
            else -> "Connected\nNotifications send in the background."
        }
        return "$connection\n\nLast sent: $posted\n${clearingStatus()}"
    }
    private fun main() {
        title("Phone inbox")
        text(statusText()).id = R.id.status
        val message = feedback()
        button("Send now", R.id.post_now) { queuedAt = System.currentTimeMillis(); Posting.schedule(this); message.visibility = View.VISIBLE; message.text = "Sending to your inbox…" }
        val pause = MaterialSwitch(this).apply { id = R.id.pause_clearing; text = "Pause bot clearing"; isChecked = store.paused }
        body.addView(pause)
        pause.setOnCheckedChangeListener { _, checked ->
            try {
                store.paused = checked
                findViewById<TextView>(R.id.status)?.text = statusText()
                Posting.schedule(this)
            } catch (_: Exception) { show("main"); pairingMessage("Couldn't save your choice. Please try again.") }
        }
        button("Allow notification access", R.id.allow_access) { show("allow") }.visibility = if (notificationAccess() == NotificationAccess.REQUIRED) View.VISIBLE else View.GONE
        button("Bot request setup", R.id.background_settings) { show("background") }.visibility = if (!BackgroundAccess.ready(this)) View.VISIBLE else View.GONE
        button("Settings", R.id.settings) { show("settings") }
    }
    private fun title(value: String) { text(value, 28f) }
    private fun text(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setPadding(0, dp(12), 0, dp(16)); body.addView(this) }
    private fun feedback() = text("").apply { id = R.id.feedback; visibility = View.GONE; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
    private fun field(label: String, value: String, viewId: Int, secret: Boolean = false): TextInputEditText {
        val container = TextInputLayout(this).apply {
            hint = label
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val edit = TextInputEditText(container.context).apply { id = viewId; setText(value); setSingleLine(); inputType = InputType.TYPE_CLASS_TEXT or if (secret) InputType.TYPE_TEXT_VARIATION_PASSWORD else InputType.TYPE_TEXT_VARIATION_URI; typeface = android.graphics.Typeface.DEFAULT }
        container.addView(edit); body.addView(container, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) }); return edit
    }
    private fun button(value: String, viewId: Int, action: () -> Unit) = MaterialButton(this).apply { id = viewId; text = value
        if (viewId in setOf(R.id.back, R.id.settings, R.id.edit_connection, R.id.import_pairing, R.id.open_app_settings, R.id.background_settings)) {
            backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
            strokeWidth = dp(1); strokeColor = android.content.res.ColorStateList.valueOf(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline))
        }
        minimumHeight = dp(56); setOnClickListener { action() }; body.addView(this, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }
    override fun onResume() {
        super.onResume()
        if (store.configured) { PushRegistration.refresh(this); Posting.schedule(this) }
        if (screen == "background") show("background")
        handler.post(refresh)
    }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }
    override fun onDestroy() { generation++; executor.shutdownNow(); handler.removeCallbacksAndMessages(null); super.onDestroy() }
    override fun onSaveInstanceState(out: Bundle) { out.putString("screen", screen); super.onSaveInstanceState(out) }
}
