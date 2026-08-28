package com.example.nsfwshield

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.nsfwshield.core.AppDetector
import com.example.nsfwshield.security.PinManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var statusIndicator: android.view.View
    private lateinit var btnStart: MaterialButton
    private lateinit var btnStop: MaterialButton
    private lateinit var swStrict: SwitchMaterial
    private lateinit var tgSensitivity: MaterialButtonToggleGroup
    private lateinit var btnTargetApps: MaterialButton
    private lateinit var btnPinSettings: MaterialButton
    private lateinit var btnDiagnostics: MaterialButton
    private lateinit var projectionLauncher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        statusIndicator = findViewById(R.id.statusIndicator)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        swStrict = findViewById(R.id.swStrict)
        tgSensitivity = findViewById(R.id.tgSensitivity)
        btnTargetApps = findViewById(R.id.btnTargetApps)
        btnPinSettings = findViewById(R.id.btnPinSettings)
        btnDiagnostics = findViewById(R.id.btnDiagnostics)

        projectionLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                startProtectionService(result.resultCode, result.data!!)
            } else {
                toast(getString(R.string.perm_capture_denied))
            }
        }

        updateStatusUi()
        bindControls()
    }

    override fun onResume() {
        super.onResume()
        updateStatusUi()
    }

    private fun bindControls() {
        btnStart.setOnClickListener { onStartClicked() }
        btnStop.setOnClickListener { onStopClicked() }

        swStrict.isChecked = Prefs.isStrict(this)
        swStrict.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked && !Prefs.isStrict(this)) {
                if (PinManager.isPinSet(this)) {
                    promptPin(
                        title = getString(R.string.pin_enter),
                        onVerified = { Prefs.setStrict(this, true) },
                        onCancelled = { swStrict.isChecked = false }
                    )
                } else {
                    Prefs.setStrict(this, true)
                }
            } else if (!isChecked && Prefs.isStrict(this)) {
                if (PinManager.isPinSet(this)) {
                    promptPin(
                        title = getString(R.string.pin_enter),
                        onVerified = { Prefs.setStrict(this, false) },
                        onCancelled = { swStrict.isChecked = true }
                    )
                } else {
                    Prefs.setStrict(this, false)
                }
            }
        }

        when (Prefs.sensitivity(this)) {
            Prefs.SENSITIVITY_CONSERVATIVE -> tgSensitivity.check(R.id.btnSensConservative)
            Prefs.SENSITIVITY_BALANCED -> tgSensitivity.check(R.id.btnSensBalanced)
            Prefs.SENSITIVITY_LESS -> tgSensitivity.check(R.id.btnSensLess)
        }
        tgSensitivity.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newSens = when (checkedId) {
                R.id.btnSensConservative -> Prefs.SENSITIVITY_CONSERVATIVE
                R.id.btnSensBalanced -> Prefs.SENSITIVITY_BALANCED
                R.id.btnSensLess -> Prefs.SENSITIVITY_LESS
                else -> Prefs.SENSITIVITY_CONSERVATIVE
            }
            changeSensitivityWithPinIfNeeded(newSens)
        }

        btnTargetApps.setOnClickListener { onTargetAppsClicked() }
        btnPinSettings.setOnClickListener { onPinSettingsClicked() }
        btnDiagnostics.setOnClickListener { showDiagnostics() }
    }

    // ---------- Start / Stop ----------

    private fun onStartClicked() {
        if (!hasOverlayPermission()) {
            showOverlayPermissionDialog()
            return
        }
        if (!AppDetector.hasUsageAccess(this)) {
            showUsageAccessDialog()
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }

    private fun startProtectionService(resultCode: Int, data: Intent) {
        val intent = Intent(this, ProtectionService::class.java).apply {
            action = ProtectionService.ACTION_START
            putExtra(ProtectionService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ProtectionService.EXTRA_RESULT_DATA, data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        Prefs.setProtectionOn(this, true)
        updateStatusUi()
    }

    private fun onStopClicked() {
        if (PinManager.isPinSet(this)) {
            promptPin(title = getString(R.string.pin_enter), onVerified = { stopProtectionService() })
        } else {
            stopProtectionService()
        }
    }

    private fun stopProtectionService() {
        val intent = Intent(this, ProtectionService::class.java).apply {
            action = ProtectionService.ACTION_STOP
        }
        startService(intent)
        Prefs.setProtectionOn(this, false)
        updateStatusUi()
    }

    // ---------- Sensitivity (PIN-gated in strict mode) ----------

    private fun changeSensitivityWithPinIfNeeded(newSens: Int) {
        val guarded = { Prefs.setSensitivity(this, newSens) }
        if (Prefs.isStrict(this) && PinManager.isPinSet(this)) {
            promptPin(
                title = getString(R.string.pin_enter),
                onVerified = guarded,
                onCancelled = { restoreSensitivityToggle() }
            )
        } else {
            guarded()
        }
    }

    private fun restoreSensitivityToggle() {
        when (Prefs.sensitivity(this)) {
            Prefs.SENSITIVITY_CONSERVATIVE -> tgSensitivity.check(R.id.btnSensConservative)
            Prefs.SENSITIVITY_BALANCED -> tgSensitivity.check(R.id.btnSensBalanced)
            Prefs.SENSITIVITY_LESS -> tgSensitivity.check(R.id.btnSensLess)
        }
    }

    // ---------- Target apps ----------

    private val targetAppCandidates = listOf(
        "com.twitter.android" to "X (Twitter)",
        "org.telegram.messenger" to "Telegram",
        "org.telegram.plus" to "Telegram Plus",
        "com.android.chrome" to "Chrome",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.instagram.android" to "Instagram",
        "com.facebook.katana" to "Facebook",
        "com.whatsapp" to "WhatsApp",
    )

    private fun onTargetAppsClicked() {
        val guarded = { showTargetAppsDialog() }
        if (Prefs.isStrict(this) && PinManager.isPinSet(this)) {
            promptPin(title = getString(R.string.pin_enter), onVerified = guarded)
        } else {
            guarded()
        }
    }

    private fun showTargetAppsDialog() {
        val current = Prefs.targetPackages(this).toMutableSet()
        val items = targetAppCandidates.map { it.first }
        val labels = targetAppCandidates.map { it.second }
        val checked = items.map { it in current }.toBooleanArray()

        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_multiple_choice, labels)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.target_apps)
            .setAdapter(adapter, null)
            .setPositiveButton(R.string.ok) { _, _ ->
                val lv = dialog.listView
                val selected = mutableSetOf<String>()
                items.forEachIndexed { i, pkg -> if (lv.isItemChecked(i)) selected.add(pkg) }
                Prefs.setTargetPackages(this, selected)
                toast("تم حفظ التطبيقات المستهدفة")
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        dialog.show()
        val lv = dialog.listView
        checked.forEachIndexed { i, c -> lv.setItemChecked(i, c) }
    }

    // ---------- PIN settings ----------

    private fun onPinSettingsClicked() {
        if (PinManager.isPinSet(this)) {
            promptPin(
                title = getString(R.string.pin_enter),
                onVerified = { showPinManagementOptions() }
            )
        } else {
            showCreatePinDialog()
        }
    }

    private fun showPinManagementOptions() {
        val options = arrayOf("تغيير PIN", "حذف PIN")
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_settings)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showChangePinDialog()
                    1 -> {
                        PinManager.clearPin(this)
                        toast("تم حذف PIN")
                    }
                }
            }
            .show()
    }

    private fun showCreatePinDialog() {
        promptNewPin(
            title = getString(R.string.pin_new),
            confirmTitle = getString(R.string.pin_confirm),
            onSet = { pin ->
                PinManager.setPin(this, pin, overwrite = false)
                toast(getString(R.string.pin_set_success))
            }
        )
    }

    private fun showChangePinDialog() {
        promptNewPin(
            title = getString(R.string.pin_new),
            confirmTitle = getString(R.string.pin_confirm),
            onSet = { newPin ->
                promptPin(
                    title = getString(R.string.pin_enter) + " (الحالي)",
                    onVerified = {
                        if (PinManager.setPin(this, newPin, overwrite = true)) {
                            toast(getString(R.string.pin_set_success))
                        }
                    }
                )
            }
        )
    }

    // ---------- Diagnostics ----------

    private fun showDiagnostics() {
        val status = if (ProtectionService.isRunning) "تعمل" else "متوقفة"
        val cls = ProtectionService.classifierStatus.ifEmpty { "غير محمّل" }
        val app = ProtectionService.foregroundApp ?: "غير معروف"
        val score = if (ProtectionService.lastScore < 0) "—" else "%.3f".format(ProtectionService.lastScore)
        val msg = """
            ${getString(R.string.diag_status)}: $status
            ${getString(R.string.diag_classifier)}: $cls
            ${getString(R.string.diag_frames)}: ${ProtectionService.framesAnalyzed}
            ${getString(R.string.diag_inference)}: ${ProtectionService.lastInferenceMs} ms
            ${getString(R.string.diag_last_score)}: $score
            ${getString(R.string.diag_foreground)}: $app
            Usage access: ${if (AppDetector.hasUsageAccess(this)) "نعم" else "لا"}
            Overlay: ${if (hasOverlayPermission()) "نعم" else "لا"}
        """.trimIndent()
        AlertDialog.Builder(this)
            .setTitle(R.string.diagnostics)
            .setMessage(msg)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    // ---------- Permissions ----------

    private fun hasOverlayPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true

    private fun showOverlayPermissionDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_overlay_title)
            .setMessage(R.string.perm_overlay_msg)
            .setPositiveButton(R.string.grant) { _, _ ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showUsageAccessDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.perm_usage_title)
            .setMessage(R.string.perm_usage_msg)
            .setPositiveButton(R.string.perm_usage_open) { _, _ -> AppDetector.openUsageAccessSettings(this) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- PIN prompt helpers ----------

    private fun promptPin(
        title: String,
        onVerified: () -> Unit,
        onCancelled: (() -> Unit)? = null,
    ) {
        val view = LayoutInflater.from(this).inflate(R.layout.pin_input, null)
        val et = view.findViewById<EditText>(R.id.etPin)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val pin = et.text?.toString()?.toCharArray() ?: CharArray(0)
                if (PinManager.verify(this, pin)) {
                    onVerified()
                } else {
                    toast(getString(R.string.pin_wrong))
                    onCancelled?.invoke()
                }
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onCancelled?.invoke() }
            .setCancelable(false)
            .show()
    }

    private fun promptNewPin(title: String, confirmTitle: String, onSet: (CharArray) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.pin_input, null)
        val et = view.findViewById<EditText>(R.id.etPin)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val first = et.text?.toString()?.toCharArray() ?: CharArray(0)
                if (first.isEmpty()) { toast(getString(R.string.pin_wrong)); return@setPositiveButton }
                promptConfirmPin(confirmTitle, first, onSet)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun promptConfirmPin(title: String, first: CharArray, onSet: (CharArray) -> Unit) {
        val view = LayoutInflater.from(this).inflate(R.layout.pin_input, null)
        val et = view.findViewById<EditText>(R.id.etPin)
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(R.string.ok) { _, _ ->
                val second = et.text?.toString()?.toCharArray() ?: CharArray(0)
                if (first.contentEquals(second)) {
                    onSet(first)
                } else {
                    toast(getString(R.string.pin_mismatch))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- UI helpers ----------

    private fun updateStatusUi() {
        val on = Prefs.isProtectionOn(this)
        tvStatus.text = getString(if (on) R.string.protection_status_on else R.string.protection_status_off)
        statusIndicator.setBackgroundColor(getColor(if (on) R.color.success_500 else R.color.error_500))
        btnStart.isEnabled = !on
        btnStop.isEnabled = on
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
