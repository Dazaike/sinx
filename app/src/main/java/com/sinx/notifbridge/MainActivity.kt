package com.sinx.notifbridge

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.sinx.notifbridge.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single-screen UI that:
 *  1. Checks / requests notification-listener access
 *  2. Checks / requests POST_NOTIFICATIONS permission (API 33+)
 *  3. Lets the user configure the PC IP and port
 *  4. Starts [KeepAliveService]
 *  5. App picker for the block list
 *  6. Test notification button
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settings: SettingsManager
    private lateinit var sender: PcSender

    // Scoped to this Activity's lifetime so app-load coroutines cancel on finish()
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settings = SettingsManager(this)
        sender   = PcSender(settings)

        setupSettingsFields()
        setupButtons()

        ContextCompat.startForegroundService(
            this,
            Intent(this, KeepAliveService::class.java)
        )

        requestPostNotificationsPermission()
    }

    override fun onResume() {
        super.onResume()
        updateStatusBadge()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiScope.cancel()
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    private fun setupSettingsFields() {
        binding.etIp.setText(settings.pcIp)
        binding.etPort.setText(settings.port.toString())
        binding.switchEnabled.isChecked = settings.isEnabled
        binding.switchFilterSilent.isChecked = settings.filterSilent

        binding.etIp.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) saveSettings(); false
        }
        binding.etIp.setOnFocusChangeListener   { _, hasFocus -> if (!hasFocus) saveSettings() }
        binding.etPort.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) saveSettings() }

        binding.switchEnabled.setOnCheckedChangeListener { _, checked ->
            settings.isEnabled = checked
            Toast.makeText(
                this,
                if (checked) "Forwarding enabled" else "Forwarding paused",
                Toast.LENGTH_SHORT
            ).show()
        }

        binding.switchFilterSilent.setOnCheckedChangeListener { _, checked ->
            settings.filterSilent = checked
            Toast.makeText(
                this,
                if (checked) "Silent notifications will be filtered" else "Silent notifications will be forwarded",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupButtons() {
        binding.btnGrantAccess.setOnClickListener { openNotificationListenerSettings() }
        binding.btnSave.setOnClickListener {
            saveSettings()
            Toast.makeText(this, "Saved → ${settings.endpointUrl}", Toast.LENGTH_LONG).show()
        }
        binding.btnBlockedApps.setOnClickListener { showAppPicker() }
        binding.btnTestNotif.setOnClickListener   { sendTestNotification() }
    }

    // ── Settings persistence ──────────────────────────────────────────────────

    private fun saveSettings() {
        val ip       = binding.etIp.text.toString().trim()
        val portText = binding.etPort.text.toString().trim()

        if (ip.isNotEmpty()) settings.pcIp = ip

        portText.toIntOrNull()?.takeIf { it in 1..65535 }?.let {
            settings.port = it
        } ?: run {
            if (portText.isNotEmpty()) binding.etPort.error = "Port must be 1–65535"
        }
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun isNotificationListenerEnabled(): Boolean {
        val flat      = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val component = ComponentName(this, NotificationListener::class.java).flattenToString()
        return flat?.contains(component) == true
    }

    private fun updateStatusBadge() {
        val ok = isNotificationListenerEnabled()
        binding.tvStatus.text     = if (ok) "✅  Notification access granted" else "⚠️  Notification access not granted"
        binding.btnGrantAccess.isEnabled = !ok
    }

    private fun openNotificationListenerSettings() {
        if (!isNotificationListenerEnabled()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Grant Notification Access")
                .setMessage("Find \"Sinx\" in the list and toggle it ON.")
                .setPositiveButton("Open Settings") { _, _ ->
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun requestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQ_POST_NOTIF
            )
        }
    }

    // ── Test notification ─────────────────────────────────────────────────────

    private fun sendTestNotification() {
        saveSettings()

        val payload = PcSender.Payload(
            app         = "Sinx",
            title       = "Test notification",
            text        = "Your bridge is working! (from ${settings.pcIp}:${settings.port})",
            packageName = packageName
        )

        binding.btnTestNotif.isEnabled = false

        sender.sendTest(payload) { success, error ->
            binding.btnTestNotif.isEnabled = true
            val msg = if (success) "✅  PC received the notification"
                      else         "❌  Failed: ${error ?: "unknown error"}"
            Snackbar.make(binding.root, msg, Snackbar.LENGTH_LONG).show()
        }
    }

    // ── App picker ────────────────────────────────────────────────────────────

    private fun showAppPicker() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_app_picker, null)
        val rvApps     = dialogView.findViewById<RecyclerView>(R.id.rvApps)
        val etSearch   = dialogView.findViewById<TextInputEditText>(R.id.etSearch)
        val tvLoading  = dialogView.findViewById<TextView>(R.id.tvLoading)

        // Show dialog immediately with loading state
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Block apps")
            .setView(dialogView)
            .setPositiveButton("Save", null) // wired below after adapter is ready
            .setNegativeButton("Cancel", null)
            .create()

        dialog.show()

        // Load installed apps on IO, display on Main
        uiScope.launch {
            val apps = withContext(Dispatchers.IO) { loadInstalledApps() }

            if (!dialog.isShowing) return@launch

            val adapter = AppPickerAdapter(
                allApps = apps,
                blocked = settings.blockedPackages.toMutableSet()
            )

            rvApps.layoutManager = LinearLayoutManager(this@MainActivity)
            rvApps.adapter       = adapter

            tvLoading.visibility = View.GONE
            rvApps.visibility    = View.VISIBLE

            // Wire search
            etSearch.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) =
                    adapter.filter(s?.toString() ?: "")
                override fun afterTextChanged(s: Editable?) = Unit
            })

            // Wire Save now that adapter exists
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                settings.blockedPackages = adapter.getBlocked()
                val count = adapter.getBlocked().size
                Toast.makeText(this@MainActivity, "Blocking $count app(s)", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
        }
    }

    private fun loadInstalledApps(): List<AppEntry> {
        val pm     = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { resolve ->
                AppEntry(
                    name        = resolve.loadLabel(pm).toString(),
                    packageName = resolve.activityInfo.packageName,
                    icon        = resolve.loadIcon(pm)
                )
            }
            .sortedBy { it.name.lowercase() }
            .distinctBy { it.packageName }
    }

    companion object {
        private const val REQ_POST_NOTIF = 100
    }
}
