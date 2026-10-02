package com.kostysetinin.gametranslate.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.kostysetinin.gametranslate.R
import com.kostysetinin.gametranslate.capture.ScreenCaptureService
import com.kostysetinin.gametranslate.databinding.ActivityMainBinding
import com.kostysetinin.gametranslate.overlay.RegionPicker
import com.kostysetinin.gametranslate.overlay.SessionState
import com.kostysetinin.gametranslate.prefs.CaptureRegion
import com.kostysetinin.gametranslate.prefs.OcrScript
import com.kostysetinin.gametranslate.prefs.SettingsStore
import com.kostysetinin.gametranslate.prefs.TargetLanguage
import com.kostysetinin.gametranslate.translate.GeminiTranslator
import com.kostysetinin.gametranslate.translate.TranslationException
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var store: SettingsStore
    private val translator = GeminiTranslator()
    private val regionPicker by lazy { RegionPicker(this) }
    private var running = false

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) launchProjection() else toast(getString(R.string.need_notifications))
        }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            toast(getString(R.string.capture_declined))
            return@registerForActivityResult
        }
        val service = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_START
            putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
            putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
        }
        ContextCompat.startForegroundService(this, service)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.versionText.text = getString(R.string.version_label, packageVersion())
        store = SettingsStore(this)
        bindForm(store.load())

        binding.startButton.setOnClickListener { begin() }
        binding.stopButton.setOnClickListener { stopCapture() }
        binding.testButton.setOnClickListener { testKey() }
        binding.regionButton.setOnClickListener { pickRegion() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    SessionState.running.collect { value ->
                        running = value
                        renderRunning()
                    }
                }
                launch {
                    SessionState.status.collect { binding.statusText.text = it }
                }
            }
        }
    }

    override fun onDestroy() {
        regionPicker.dismiss()
        super.onDestroy()
    }

    private fun bindForm(settings: com.kostysetinin.gametranslate.prefs.TranslateSettings) {
        binding.apiKey.setText(settings.apiKey)
        bindSpinner(
            spinner = binding.modelSpinner,
            labels = GeminiTranslator.MODELS.map { it.second },
            selected = GeminiTranslator.MODELS.indexOfFirst { it.first == settings.model }.coerceAtLeast(0),
        ) { position ->
            store.saveModel(GeminiTranslator.MODELS[position].first)
        }
        bindSpinner(
            spinner = binding.scriptSpinner,
            labels = OcrScript.entries.map { it.label },
            selected = OcrScript.entries.indexOf(settings.script).coerceAtLeast(0),
        ) { position ->
            store.saveScript(OcrScript.entries[position])
        }
        bindSpinner(
            spinner = binding.targetSpinner,
            labels = TargetLanguage.entries.map { it.label },
            selected = TargetLanguage.entries.indexOf(settings.target).coerceAtLeast(0),
        ) { position ->
            store.saveTarget(TargetLanguage.entries[position])
        }
        binding.intervalSlider.value = settings.intervalMs.toFloat()
        binding.intervalValue.text = intervalLabel(settings.intervalMs)
        binding.intervalSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val ms = value.toLong()
            store.saveInterval(ms)
            binding.intervalValue.text = intervalLabel(ms)
        }
        binding.showOriginal.isChecked = settings.showOriginal
        binding.showOriginal.setOnCheckedChangeListener { _, checked -> store.saveShowOriginal(checked) }
        renderRegion(settings.region)
    }

    private fun bindSpinner(
        spinner: android.widget.Spinner,
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        val adapter = ArrayAdapter(this, R.layout.spinner_item, labels)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(selected)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                onPick(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun begin() {
        persistKey()
        if (store.load().apiKey.isBlank()) {
            binding.apiKey.requestFocus()
            toast(getString(R.string.need_key))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            toast(getString(R.string.need_overlay))
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchProjection()
    }

    private fun launchProjection() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun stopCapture() {
        if (!running) return
        startService(Intent(this, ScreenCaptureService::class.java).setAction(ScreenCaptureService.ACTION_STOP))
    }

    private fun pickRegion() {
        if (running) {
            toast(getString(R.string.stop_before_region))
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            toast(getString(R.string.need_overlay))
            return
        }
        regionPicker.show(
            onResult = { region ->
                store.saveRegion(region)
                renderRegion(region)
            },
            onCancel = {},
        )
    }

    private fun testKey() {
        persistKey()
        val settings = store.load()
        if (settings.apiKey.isBlank()) {
            binding.testResult.text = getString(R.string.need_key)
            return
        }
        binding.testButton.isEnabled = false
        binding.testResult.text = getString(R.string.testing_key)
        lifecycleScope.launch {
            val message = try {
                val translated = translator.translate(
                    lines = listOf("Hello, traveler."),
                    targetLanguageName = settings.target.promptName,
                    apiKey = settings.apiKey,
                    model = settings.model,
                )
                getString(R.string.key_works, translated.firstOrNull().orEmpty())
            } catch (error: TranslationException) {
                error.message ?: getString(R.string.key_failed)
            }
            binding.testResult.text = message
            binding.testButton.isEnabled = true
        }
    }

    private fun persistKey() {
        store.saveApiKey(binding.apiKey.text?.toString().orEmpty())
    }

    private fun renderRunning() {
        binding.startButton.isEnabled = !running
        binding.stopButton.isEnabled = running
        binding.regionButton.isEnabled = !running
        binding.statusText.text = SessionState.status.value
    }

    private fun renderRegion(region: CaptureRegion) {
        binding.regionValue.text = if (region.isFullScreen()) {
            getString(R.string.region_full)
        } else {
            val width = ((region.right - region.left) * 100).toInt()
            val height = ((region.bottom - region.top) * 100).toInt()
            getString(R.string.region_custom, width, height)
        }
    }

    private fun intervalLabel(ms: Long): String {
        val seconds = ms / 1000.0
        return getString(R.string.interval_value, seconds)
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun packageVersion(): String {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
        return info.versionName ?: "1.3.0"
    }
}
