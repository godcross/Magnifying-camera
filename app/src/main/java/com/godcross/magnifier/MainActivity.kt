package com.godcross.magnifier

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.godcross.magnifier.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.roundToInt

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    private enum class SliderMode { ZOOM, EXPOSURE, FOCUS }

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: Prefs
    private lateinit var steady: SteadyDetector
    private lateinit var captureExecutor: ExecutorService

    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var backCameras: List<CameraInfo> = emptyList()

    private var sliderMode = SliderMode.ZOOM
    private var sliderDragging = false
    private var torchWanted = false
    private var manualFocusActive = false
    private var manualFocusFraction = 0f
    private var minFocusDiopters = 0f
    private var focusCalibrated = false
    private var capturing = false
    private var frozen = false
    private var statusBase = ""

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result[Manifest.permission.CAMERA] == true || hasCameraPermission()) {
                binding.permissionPanel.visibility = View.GONE
                startCamera()
            } else {
                binding.permissionPanel.visibility = View.VISIBLE
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        steady = SteadyDetector(this)
        captureExecutor = Executors.newSingleThreadExecutor()

        applyInsets()
        setupControls()
        setupPreviewGestures()

        if (hasCameraPermission()) startCamera() else requestPermissions()
    }

    override fun onResume() {
        super.onResume()
        steady.start()
        refreshThumbnail()
    }

    override fun onPause() {
        steady.stop()
        super.onPause()
    }

    override fun onDestroy() {
        captureExecutor.shutdown()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- permissions

    private fun hasCameraPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            perms += Manifest.permission.WRITE_EXTERNAL_STORAGE
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    // ---------------------------------------------------------------- layout

    private fun applyInsets() {
        val topPad = binding.topBar.paddingTop
        val bottomPad = binding.bottomPanel.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.topBar.updatePadding(top = topPad + bars.top)
            binding.bottomPanel.updatePadding(bottom = bottomPad + bars.bottom)
            insets
        }
    }

    private fun setChip(button: MaterialButton, on: Boolean) {
        val bg = if (on) ContextCompat.getColor(this, R.color.chip_on) else ContextCompat.getColor(this, R.color.chip_off)
        button.backgroundTintList = ColorStateList.valueOf(bg)
        button.setTextColor(if (on) Color.BLACK else Color.WHITE)
    }

    private fun setupControls() {
        binding.btnGrant.setOnClickListener { requestPermissions() }

        binding.btnTorch.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            torchWanted = !torchWanted
            cam.cameraControl.enableTorch(torchWanted)
        }

        binding.btnStab.setOnClickListener {
            prefs.stabilization = !prefs.stabilization
            renderToggles()
            bindCamera()
        }

        binding.btnSteady.setOnClickListener {
            if (!steady.available) {
                toast("This phone has no gyroscope")
                return@setOnClickListener
            }
            prefs.steadyShot = !prefs.steadyShot
            renderToggles()
        }

        binding.btnBurst.setOnClickListener {
            prefs.burstCount = when (prefs.burstCount) { 1 -> 3; 3 -> 5; else -> 1 }
            renderToggles()
        }

        binding.btnTimer.setOnClickListener {
            prefs.timerSeconds = when (prefs.timerSeconds) { 0 -> 2; 2 -> 5; 5 -> 10; else -> 0 }
            renderToggles()
        }

        binding.btnGrid.setOnClickListener {
            prefs.grid = !prefs.grid
            renderToggles()
        }

        binding.btnLens.setOnClickListener {
            if (backCameras.size <= 1) return@setOnClickListener
            prefs.lensIndex = (prefs.lensIndex + 1) % backCameras.size
            bindCamera()
        }

        binding.btnFreeze.setOnClickListener { toggleFreeze() }
        binding.frozenView.onSingleTap = null

        binding.shutter.setOnClickListener { onShutter() }

        binding.thumbnail.setOnClickListener {
            startActivity(Intent(this, GalleryActivity::class.java))
        }

        binding.sliderModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            sliderMode = when (checkedId) {
                R.id.modeExposure -> SliderMode.EXPOSURE
                R.id.modeFocus -> SliderMode.FOCUS
                else -> SliderMode.ZOOM
            }
            syncSlider()
        }

        binding.slider.addOnSliderTouchListener(object : com.google.android.material.slider.Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: com.google.android.material.slider.Slider) {
                sliderDragging = true
            }

            override fun onStopTrackingTouch(slider: com.google.android.material.slider.Slider) {
                sliderDragging = false
            }
        })

        binding.slider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            when (sliderMode) {
                SliderMode.ZOOM -> camera?.cameraControl?.setLinearZoom(value)
                SliderMode.EXPOSURE -> applyExposureFraction(value)
                SliderMode.FOCUS -> applyManualFocus(value)
            }
            updateSliderLabel()
        }

        binding.btnReset.setOnClickListener {
            val cam = camera ?: return@setOnClickListener
            when (sliderMode) {
                SliderMode.ZOOM -> cam.cameraControl.setZoomRatio(1f)
                SliderMode.EXPOSURE -> cam.cameraControl.setExposureCompensationIndex(0)
                SliderMode.FOCUS -> {
                    clearManualFocus()
                    cam.cameraControl.cancelFocusAndMetering()
                    showHint("Auto focus", 1500)
                }
            }
            binding.root.postDelayed({ syncSlider() }, 150)
        }

        steady.onSteadyChanged = { isSteady ->
            binding.steadyDot.backgroundTintList =
                ColorStateList.valueOf(if (isSteady) Color.rgb(102, 187, 106) else Color.rgb(239, 83, 80))
        }
        if (!steady.available) binding.steadyDot.visibility = View.GONE

        renderToggles()
    }

    private fun renderToggles() {
        setChip(binding.btnTorch, torchWanted)
        setChip(binding.btnStab, prefs.stabilization)
        setChip(binding.btnSteady, prefs.steadyShot && steady.available)
        setChip(binding.btnBurst, prefs.burstCount > 1)
        binding.btnBurst.text = if (prefs.burstCount > 1) "Best of ${prefs.burstCount}" else "Single shot"
        setChip(binding.btnTimer, prefs.timerSeconds > 0)
        binding.btnTimer.text = if (prefs.timerSeconds > 0) "Timer ${prefs.timerSeconds}s" else "Timer off"
        setChip(binding.btnGrid, prefs.grid)
        binding.gridOverlay.visibility = if (prefs.grid) View.VISIBLE else View.GONE
        setChip(binding.btnFreeze, frozen)
        binding.btnFreeze.text = if (frozen) "Live" else "Freeze"
        setChip(binding.btnLens, false)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupPreviewGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val cam = camera ?: return false
                val zs = cam.cameraInfo.zoomState.value ?: return false
                val target = (zs.zoomRatio * detector.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                cam.cameraControl.setZoomRatio(target)
                return true
            }
        })
        val taps = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                focusAt(e.x, e.y)
                return true
            }
        })
        binding.previewView.setOnTouchListener { _, event ->
            scale.onTouchEvent(event)
            if (!scale.isInProgress) taps.onTouchEvent(event)
            true
        }
    }

    // ---------------------------------------------------------------- camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                backCameras = try {
                    CameraSelector.DEFAULT_BACK_CAMERA.filter(provider.availableCameraInfos)
                } catch (e: Exception) {
                    emptyList()
                }
                if (backCameras.isEmpty()) backCameras = provider.availableCameraInfos
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Camera start failed", e)
                toast("Could not open camera: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun cameraId(info: CameraInfo): String = try {
        Camera2CameraInfo.from(info).cameraId
    } catch (e: Exception) {
        ""
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        if (backCameras.isEmpty()) {
            toast("No camera found")
            return
        }
        if (prefs.lensIndex !in backCameras.indices) prefs.lensIndex = 0
        val info = backCameras[prefs.lensIndex]

        camera?.cameraInfo?.let {
            it.zoomState.removeObservers(this)
            it.torchState.removeObservers(this)
        }
        clearManualFocus(applyToCamera = false)

        val bound = tryBind(provider, info, prefs.stabilization) ||
            (prefs.stabilization && tryBind(provider, info, false))
        if (!bound) {
            toast("Camera could not start")
            return
        }
        val cam = camera ?: return

        cam.cameraInfo.zoomState.observe(this) { onZoomChanged(it) }
        cam.cameraInfo.torchState.observe(this) { state ->
            torchWanted = state == TorchState.ON
            setChip(binding.btnTorch, torchWanted)
        }
        binding.btnTorch.isEnabled = cam.cameraInfo.hasFlashUnit()
        binding.btnTorch.alpha = if (cam.cameraInfo.hasFlashUnit()) 1f else 0.4f
        if (torchWanted && cam.cameraInfo.hasFlashUnit()) cam.cameraControl.enableTorch(true)

        val c2 = Camera2CameraInfo.from(cam.cameraInfo)
        minFocusDiopters = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        focusCalibrated = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION) !=
            CameraMetadata.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_UNCALIBRATED
        binding.modeFocus.isEnabled = minFocusDiopters > 0f
        binding.modeExposure.isEnabled = cam.cameraInfo.exposureState.isExposureCompensationSupported

        binding.btnLens.visibility = if (backCameras.size > 1) View.VISIBLE else View.GONE
        val focal = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        binding.btnLens.text = "Lens ${prefs.lensIndex + 1}/${backCameras.size}"

        buildPresets()
        updateStatus(focal)
        syncSlider()
        renderToggles()
    }

    /** Builds and binds preview + full-quality still capture. Returns false if the device refused. */
    private fun tryBind(provider: ProcessCameraProvider, info: CameraInfo, stabilize: Boolean): Boolean {
        val targetId = cameraId(info)
        val selector = CameraSelector.Builder()
            .addCameraFilter { list -> list.filter { cameraId(it) == targetId } }
            .build()

        val previewRes = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
        val captureRes = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
            .build()

        val previewBuilder = Preview.Builder().setResolutionSelector(previewRes)
        val captureBuilder = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(98)
            .setResolutionSelector(captureRes)

        val c2 = Camera2CameraInfo.from(info)
        val oisModes = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        val oisSupported = oisModes?.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
        val eisSupported = try {
            Preview.getPreviewCapabilities(info).isStabilizationSupported
        } catch (e: Exception) {
            false
        }

        var oisOn = false
        var eisOn = false
        if (stabilize) {
            if (oisSupported) {
                Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                )
                Camera2Interop.Extender(captureBuilder).setCaptureRequestOption(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON,
                )
                oisOn = true
            }
            if (eisSupported) {
                previewBuilder.setPreviewStabilizationEnabled(true)
                eisOn = true
            }
        }

        val preview = previewBuilder.build()
        preview.setSurfaceProvider(binding.previewView.surfaceProvider)
        val capture = captureBuilder.build()

        return try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, selector, preview, capture)
            imageCapture = capture
            val parts = mutableListOf<String>()
            parts += when {
                !stabilize -> "Stabilization off"
                oisOn && eisOn -> "OIS + EIS on"
                oisOn -> "OIS on"
                eisOn -> "EIS on"
                else -> "No hardware stabilization"
            }
            statusBase = parts.joinToString(" · ")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Bind failed (stabilize=$stabilize)", e)
            false
        }
    }

    private fun updateStatus(focalMm: Float?) {
        val res = imageCapture?.resolutionInfo?.resolution
        val parts = mutableListOf(statusBase)
        if (res != null) {
            val mp = res.width.toLong() * res.height / 1_000_000.0
            parts += String.format(Locale.US, "%.1f MP", mp)
        }
        if (focalMm != null) parts += String.format(Locale.US, "%.1f mm lens", focalMm)
        if (minFocusDiopters > 0f && focusCalibrated) {
            parts += "closest ${formatDistance(1f / minFocusDiopters)}"
        }
        binding.statusText.text = parts.joinToString(" · ")
    }

    // ---------------------------------------------------------------- zoom

    private fun buildPresets() {
        val row = binding.presetRow
        row.removeAllViews()
        val zs = camera?.cameraInfo?.zoomState?.value ?: return
        val stops = mutableListOf<Float>()
        if (zs.minZoomRatio < 0.95f) stops += zs.minZoomRatio
        stops += listOf(1f, 2f, 4f, 8f, 16f).filter { it <= zs.maxZoomRatio + 0.01f }
        if (stops.lastOrNull()?.let { it < zs.maxZoomRatio - 0.5f } == true) stops += zs.maxZoomRatio

        for (ratio in stops) {
            val b = MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle)
            b.text = if (ratio == zs.maxZoomRatio && ratio !in listOf(1f, 2f, 4f, 8f, 16f)) {
                String.format(Locale.US, "Max %.0f×", ratio)
            } else {
                formatRatio(ratio)
            }
            b.setTextColor(Color.WHITE)
            b.setAllCaps(false)
            b.minWidth = 0
            b.minimumWidth = 0
            b.setPadding(dp(10), 0, dp(10), 0)
            b.setOnClickListener { camera?.cameraControl?.setZoomRatio(ratio) }
            row.addView(b)
        }
    }

    private fun onZoomChanged(zs: ZoomState) {
        binding.zoomText.text = formatRatio(zs.zoomRatio)
        if (sliderMode == SliderMode.ZOOM && !sliderDragging) {
            binding.slider.value = zs.linearZoom.coerceIn(0f, 1f)
            updateSliderLabel()
        }
    }

    private fun formatRatio(r: Float): String =
        if (r < 10f) String.format(Locale.US, "%.1f×", r) else String.format(Locale.US, "%.0f×", r)

    // ---------------------------------------------------------------- exposure

    private fun applyExposureFraction(fraction: Float) {
        val cam = camera ?: return
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) return
        val range = state.exposureCompensationRange
        val index = (range.lower + fraction * (range.upper - range.lower)).roundToInt()
        cam.cameraControl.setExposureCompensationIndex(index)
    }

    private fun exposureFraction(): Float {
        val state = camera?.cameraInfo?.exposureState ?: return 0.5f
        val range = state.exposureCompensationRange
        val span = range.upper - range.lower
        if (span <= 0) return 0.5f
        return ((state.exposureCompensationIndex - range.lower).toFloat() / span).coerceIn(0f, 1f)
    }

    // ---------------------------------------------------------------- focus

    private fun focusAt(x: Float, y: Float) {
        val cam = camera ?: return
        if (frozen) return
        if (manualFocusActive) {
            clearManualFocus()
            if (sliderMode == SliderMode.FOCUS) syncSlider()
        }
        val point = binding.previewView.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .disableAutoCancel()
            .build()
        showFocusRing(x, y, Color.WHITE)
        val future = cam.cameraControl.startFocusAndMetering(action)
        future.addListener({
            val ok = try {
                future.get().isFocusSuccessful
            } catch (e: Exception) {
                false
            }
            showFocusRing(x, y, if (ok) Color.rgb(102, 187, 106) else Color.rgb(239, 83, 80))
            showHint(if (ok) "Focus locked here · Focus › Auto to release" else "Couldn't focus — move back a little", 2500)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun showFocusRing(x: Float, y: Float, color: Int) {
        val ring = binding.focusRing
        ring.backgroundTintList = ColorStateList.valueOf(color)
        ring.x = x - ring.width / 2f
        ring.y = y - ring.height / 2f
        ring.visibility = View.VISIBLE
        ring.alpha = 1f
        ring.animate().cancel()
        ring.animate().alpha(0f).setStartDelay(1200).setDuration(400).start()
    }

    private fun applyManualFocus(fraction: Float) {
        val cam = camera ?: return
        if (minFocusDiopters <= 0f) return
        if (!manualFocusActive) cam.cameraControl.cancelFocusAndMetering()
        manualFocusActive = true
        manualFocusFraction = fraction
        // 0 = infinity, 1 = closest the lens can focus.
        val diopters = fraction * minFocusDiopters
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(
            CaptureRequestOptions.Builder()
                .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, diopters)
                .build(),
        )
    }

    private fun clearManualFocus(applyToCamera: Boolean = true) {
        if (manualFocusActive && applyToCamera) {
            camera?.let { Camera2CameraControl.from(it.cameraControl).clearCaptureRequestOptions() }
        }
        manualFocusActive = false
    }

    // ---------------------------------------------------------------- slider

    private fun syncSlider() {
        val value = when (sliderMode) {
            SliderMode.ZOOM -> camera?.cameraInfo?.zoomState?.value?.linearZoom ?: 0f
            SliderMode.EXPOSURE -> exposureFraction()
            SliderMode.FOCUS -> if (manualFocusActive) manualFocusFraction else 0f
        }
        binding.slider.value = value.coerceIn(0f, 1f)
        binding.btnReset.text = if (sliderMode == SliderMode.FOCUS) "Auto" else "Reset"
        updateSliderLabel()
    }

    private fun updateSliderLabel() {
        val text = when (sliderMode) {
            SliderMode.ZOOM -> "Zoom " + formatRatio(camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f)
            SliderMode.EXPOSURE -> {
                val state = camera?.cameraInfo?.exposureState
                if (state == null || !state.isExposureCompensationSupported) {
                    "—"
                } else {
                    val range = state.exposureCompensationRange
                    val index = (range.lower + binding.slider.value * (range.upper - range.lower)).roundToInt()
                    val ev = index * state.exposureCompensationStep.toFloat()
                    String.format(Locale.US, "%+.1f EV", ev)
                }
            }
            SliderMode.FOCUS -> {
                if (!manualFocusActive) {
                    "Auto"
                } else {
                    val diopters = binding.slider.value * minFocusDiopters
                    when {
                        diopters <= 0.01f -> "∞"
                        focusCalibrated -> formatDistance(1f / diopters)
                        else -> "${(binding.slider.value * 100).roundToInt()}% near"
                    }
                }
            }
        }
        binding.sliderValue.text = text
    }

    private fun formatDistance(meters: Float): String {
        val cm = meters * 100f
        val inches = cm / 2.54f
        return if (cm < 100f) {
            String.format(Locale.US, "%.1f cm (%.1f in)", cm, inches)
        } else {
            String.format(Locale.US, "%.2f m (%.0f in)", meters, inches)
        }
    }

    // ---------------------------------------------------------------- freeze

    private fun toggleFreeze() {
        if (frozen) {
            frozen = false
            binding.frozenView.visibility = View.GONE
            binding.frozenView.setImageBitmap(null)
        } else {
            val bmp = binding.previewView.bitmap
            if (bmp == null) {
                toast("Preview not ready")
                return
            }
            frozen = true
            binding.frozenView.setImageBitmap(bmp)
            binding.frozenView.visibility = View.VISIBLE
            showHint("Frozen · pinch to zoom · shutter saves this view", 2500)
        }
        renderToggles()
    }

    // ---------------------------------------------------------------- capture

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_CAMERA
        ) {
            if (event?.repeatCount == 0) onShutter()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun onShutter() {
        if (capturing) return
        if (frozen) {
            saveFrozen()
            return
        }
        val capture = imageCapture ?: return
        capturing = true
        binding.shutter.isEnabled = false

        lifecycleScope.launch {
            try {
                val timer = prefs.timerSeconds
                if (timer > 0) {
                    binding.countdownText.visibility = View.VISIBLE
                    for (i in timer downTo 1) {
                        binding.countdownText.text = i.toString()
                        delay(1000)
                    }
                    binding.countdownText.visibility = View.GONE
                }

                val useSteady = prefs.steadyShot && steady.available
                val count = prefs.burstCount.coerceAtLeast(1)

                if (count == 1) {
                    if (useSteady) waitForSteady(4000)
                    flash()
                    PhotoStore.captureToGallery(this@MainActivity, capture, captureExecutor)
                } else {
                    var best: Shot? = null
                    var bestScore = -1.0
                    for (i in 1..count) {
                        showHint("Shot $i of $count…", 0)
                        if (useSteady) waitForSteady(if (i == 1) 4000 else 1500)
                        flash()
                        val shot = PhotoStore.captureToMemory(capture, captureExecutor)
                        val score = withContext(Dispatchers.Default) { Sharpness.score(shot.jpeg) }
                        Log.d(TAG, "Shot $i sharpness=$score")
                        if (score > bestScore) {
                            bestScore = score
                            best = shot
                        }
                    }
                    showHint("Saving sharpest…", 0)
                    best?.let { PhotoStore.saveShot(this@MainActivity, it) }
                }
                binding.shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                showHint("Saved", 1200)
                refreshThumbnail()
            } catch (e: Exception) {
                Log.e(TAG, "Capture failed", e)
                showHint("Capture failed: ${e.message}", 3000)
            } finally {
                binding.countdownText.visibility = View.GONE
                capturing = false
                binding.shutter.isEnabled = true
            }
        }
    }

    private suspend fun waitForSteady(timeoutMs: Long) {
        if (steady.isSteadyFor(300)) return
        showHint("Hold still…", 0)
        val ok = steady.awaitSteady(timeoutMs)
        if (!ok) showHint("Still moving — taking it anyway", 1200)
    }

    private fun saveFrozen() {
        val drawable = binding.frozenView.drawable as? android.graphics.drawable.BitmapDrawable ?: return
        val bmp = drawable.bitmap ?: return
        capturing = true
        lifecycleScope.launch {
            try {
                PhotoStore.saveBitmap(this@MainActivity, bmp)
                showHint("Frozen view saved (screen resolution)", 2000)
                refreshThumbnail()
            } catch (e: Exception) {
                showHint("Save failed: ${e.message}", 3000)
            } finally {
                capturing = false
            }
        }
    }

    private fun flash() {
        val v = binding.flashOverlay
        v.animate().cancel()
        v.alpha = 0.6f
        v.animate().alpha(0f).setDuration(220).start()
    }

    private fun refreshThumbnail() {
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.IO) {
                PhotoStore.list(this@MainActivity).firstOrNull()?.let {
                    PhotoStore.loadThumbnail(this@MainActivity, it.uri, dp(56) * 2)
                }
            }
            if (bmp != null) binding.thumbnail.setImageBitmap(bmp) else binding.thumbnail.setImageDrawable(null)
        }
    }

    // ---------------------------------------------------------------- helpers

    private val hideHint = Runnable { binding.hintText.visibility = View.GONE }

    /** durationMs = 0 keeps the hint up until replaced. */
    private fun showHint(text: String, durationMs: Long) {
        binding.hintText.removeCallbacks(hideHint)
        binding.hintText.text = text
        binding.hintText.visibility = View.VISIBLE
        if (durationMs > 0) binding.hintText.postDelayed(hideHint, durationMs)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()

    companion object {
        private const val TAG = "MagnifierCam"
    }
}
