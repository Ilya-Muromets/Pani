/*
 * Copyright 2024 Ilya Chugunov
 *
 * This file has been modified by Ilya Chugunov and is
 * distributed under the MIT License. The modifications include:
 * - Multi-camera RAW frame and metadata streaming.
 * - Local storage integration for data capture.
 * - UI redesign with settings menu and exposure readouts.
 *
 * The original code was licensed under the Apache License, Version 2.0:
 * [Original Apache License Notice]
 *
 * --------------------------------------------------------------------------------
 *
 * The original code and its copyright notice:
 *
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package info.ilyac.pani.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import info.ilyac.pani.R
import fragmentargs.CameraFragmentArgs
import info.ilyac.pani.databinding.FragmentCameraBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.properties.Delegates
import kotlin.random.Random


class CameraFragment : Fragment() {


    private val TAG = CameraFragment::class.java.simpleName
    lateinit var saveFolderDir: File
    private val imageBufferSize = 42 // Max number of images in imageQueue

    // Navigation/UI variables, post to main loop to update live
    var navSelectedCamera = 0 // 0 - Wide, 1 - Ultrawide, 2 - Tele, 3 - 2X, 4 - 10X

    var navISO: Int by Delegates.observable(0) { property, oldValue, newValue ->
        if (::navEditISO.isInitialized) {
            Handler(Looper.getMainLooper()).post {
                navEditISO.setText(newValue.toString())
            }
        }
    }
    var navISOmin: Int = 0
    var navISOmax: Int = 99999
    var navExposure: Long by Delegates.observable(0L) { property, oldValue, newValue ->
        if (::navEditExposure.isInitialized) {
            Handler(Looper.getMainLooper()).post {
                navEditExposure.setText(String.format("%.1f", (1e9 / newValue).toFloat()))
            }
        }
    }
    var navExposuremin: Long = 0
    var navExposuremax: Long = 999999
    var navFocus: Float by Delegates.observable(0f) { property, oldValue, newValue ->
        if (::navEditFocus.isInitialized) {
            Handler(Looper.getMainLooper()).post {
                navEditFocus.setText(String.format("%.2f", newValue))
            }
        }
    }
    var navFocusmin: Float = 0.0F
    var navFocusmax: Float = 10.0F

    var navLockAF = true
    var navLockAE = true
    var navLockOIS = false
    var navManualFocus = false
    var navManualExposure = false
    var navFilename = "0"
    var navMaxFPS = 22F
    var navMaxFrames = 999
    var navFullResolution = true

    // set up lateinit navigation elements to access later

    lateinit var navEditExposure: EditText
    lateinit var navEditISO: EditText
    lateinit var navEditFocus: EditText
    lateinit var navFramesValue: TextView

    lateinit var navEditFilename: EditText
    lateinit var navEditMaxFPS: EditText
    lateinit var navEditMaxFrames: EditText
    lateinit var navSwitchLockAE: Switch
    lateinit var navSwitchLockAF: Switch
    lateinit var navSwitchLockOIS: Switch
    lateinit var navSwitchFullRes: Switch
    lateinit var navRadioCamera: RadioGroup
    lateinit var navResetButton: Button
    lateinit var navDrawerButton: ImageButton
    lateinit var navISOButton: Button
    lateinit var navExposureButton: Button
    lateinit var navFocusButton: Button
    lateinit var navISOSeekbar: SeekBar
    lateinit var navExposureSeekbar: SeekBar
    lateinit var navFocusSeekbar: SeekBar

    lateinit var navSwitchManualE: Switch
    lateinit var navSwitchManualF: Switch


    var currentISO: Int = 42
    var currentFrameDuration: Long = 42
    var currentExposure: Long = 42
    var currentFocus: Float = 1.0F

    var sensorAccValues = mutableListOf<List<Float>>()
    var sensorRotValues = mutableListOf<List<Float>>()

    var capturingBurst = AtomicBoolean(false)
    var numCapturedFrames = AtomicInteger(0)
    var maxCapturedTimestamp = AtomicLong(0)


    private fun setupSeekbars() {
        val isoRange = characteristicsP.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        if (isoRange != null) {
            navISOmax = maxOf(isoRange.upper, 1)
            navISOmin = maxOf(isoRange.lower, 1)
            navISO = navISO.coerceIn(navISOmin, navISOmax)
            navISOSeekbar.max = (log2((navISOmax).toDouble()) * 100).toInt()
            navISOSeekbar.min = (log2((navISOmin).toDouble()) * 100).toInt()
            navISOSeekbar.progress = (log2((navISO).toDouble()) * 100).toInt()
        }

        val exposureRange = characteristicsP.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        if (exposureRange != null) {
            navExposuremax = maxOf(exposureRange.upper, 1)
            navExposuremin = maxOf(exposureRange.lower, 1)
            navExposure = navExposure.coerceIn(navExposuremin, navExposuremax)
            navExposureSeekbar.max = (log2(500000000.toDouble()) * 100).toInt() // hardcode to 0.5s max
            navExposureSeekbar.min = (log2(navExposuremin.toDouble()) * 100).toInt()
            navExposureSeekbar.progress = (log2(navExposure.toDouble()) * 100).toInt()
        }

        val minFocusDistance = characteristicsP.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
        if (minFocusDistance != null) {
            navFocusmax = minFocusDistance
            navFocusmin = 0.0F
            navFocus = navFocus.coerceIn(0F, minFocusDistance)
            navFocusSeekbar.max = (navFocusmax * 100).toInt()
            navFocusSeekbar.min = 0
            navFocusSeekbar.progress = (navFocus * 100).toInt()
        }
    }

    private fun saveSettings() {
        val sharedPref = requireActivity().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putInt("navSelectedCamera", navSelectedCamera)
            putInt("navISO", navISO)
            putInt("navISOmin", navISOmin)
            putInt("navISOmax", navISOmax)
            putLong("navExposure", navExposure)
            putLong("navExposuremin", navExposuremin)
            putLong("navExposuremax", navExposuremax)
            putFloat("navFocus", navFocus)
            putFloat("navFocusmin", navFocusmin)
            putFloat("navFocusmax", navFocusmax)
            putBoolean("navLockAF", navLockAF)
            putBoolean("navLockAE", navLockAE)
            putBoolean("navLockOIS", navLockOIS)
            putBoolean("navManualFocus", navManualFocus)
            putBoolean("navManualExposure", navManualExposure)
            putString("navFilename", navFilename)
            putFloat("navMaxFPS", navMaxFPS)
            putInt("navMaxFrames", navMaxFrames)
            putString("physicalCameraID", physicalCameraID)
            putBoolean("navFullResolution", navFullResolution)
            apply()
        }
    }

    private fun resetSharedPreferences() {
        val sharedPref = requireActivity().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        sharedPref.edit().clear().apply()
    }

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private val args: CameraFragmentArgs by navArgs()
    private val cameraManager: CameraManager by lazy {
        requireContext().applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private var physicalCameraID = "2"
    private lateinit var imageReader: ImageReader
    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
    private val imageReaderHandler = Handler(imageReaderThread.looper)
    private lateinit var camera: CameraDevice
    private lateinit var session: CameraCaptureSession
    private lateinit var captureRequest: CaptureRequest.Builder
    private lateinit var captureCallback: CameraCaptureSession.CaptureCallback
    private lateinit var characteristicsP: CameraCharacteristics


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        navISOSeekbar = fragmentCameraBinding.navIsoSeekbar
        navExposureSeekbar = fragmentCameraBinding.navExposureSeekbar
        navFocusSeekbar = fragmentCameraBinding.navFocusSeekbar

        navEditExposure = fragmentCameraBinding.navExposure
        navEditISO = fragmentCameraBinding.navIso
        navEditFocus = fragmentCameraBinding.navFocus
        navEditFilename = fragmentCameraBinding.navFilenameInput
        navEditMaxFPS = fragmentCameraBinding.navMaxFps
        navEditMaxFrames = fragmentCameraBinding.navMaxFrames
        navSwitchLockAE = fragmentCameraBinding.navLockAE
        navSwitchLockAF = fragmentCameraBinding.navLockAF
        navSwitchLockOIS = fragmentCameraBinding.navLockOIS
        navSwitchFullRes = fragmentCameraBinding.navFullResolution
        navRadioCamera = fragmentCameraBinding.navRadioCamera
        navResetButton = fragmentCameraBinding.navReset
        navDrawerButton = fragmentCameraBinding.navDrawerButton

        navISOButton = fragmentCameraBinding.navIsoButton
        navExposureButton = fragmentCameraBinding.navExposureButton
        navFocusButton = fragmentCameraBinding.navFocusButton

        fragmentCameraBinding.viewFinder.post {
            val width = fragmentCameraBinding.viewFinder.width
            val height = width * 4032 / 3024 // 4:3 aspect ratio
            fragmentCameraBinding.viewFinder.layoutParams =
                fragmentCameraBinding.viewFinder.layoutParams.apply {
                    this.width = width
                    this.height = height
                }
        }

        fragmentCameraBinding.viewFinder.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceDestroyed(holder: SurfaceHolder) {}
            override fun surfaceChanged(
                holder: SurfaceHolder,
                format: Int,
                width: Int,
                height: Int
            ) {}

            override fun surfaceCreated(holder: SurfaceHolder) {
                view.post { initializeCamera() }
            }
        })
    }


    private fun restartCameraStream() {
        if (::session.isInitialized) {
            session.abortCaptures()
        }
        lifecycleScope.launch(Dispatchers.Main) { startCameraStream() }
    }


    @SuppressLint("ClickableViewAccessibility")
    private suspend fun startCameraStream() {
        characteristicsP = cameraManager.getCameraCharacteristics(physicalCameraID)
        setupSeekbars()

        // log camera sizes
        val sizes = characteristicsP.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(ImageFormat.RAW_SENSOR)
        for (size in sizes) {
            Log.d(TAG, "CameraSize: ${size.width}x${size.height}")
        }

        val size = characteristicsP.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(args.pixelFormat).maxByOrNull { it.height * it.width }!!
        imageReader = ImageReader.newInstance(
            size.width, size.height, args.pixelFormat, imageBufferSize,
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_WRITE_RARELY
        )
        val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)
        session = createCaptureSession(camera, targets, cameraHandler)

        // Move captureRequest to a class-level variable
        captureRequest = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(fragmentCameraBinding.viewFinder.holder.surface)
        }

        captureRequest.apply {
            if (navManualExposure) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, navExposure)
                set(CaptureRequest.SENSOR_SENSITIVITY, navISO)
            }
            when {
                navManualFocus -> {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, navFocus)
                }

                navLockAF -> set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_AUTO
                )

                else -> set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                )
            }
            if (navLockOIS) {
                set(
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                )
                set(
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                )
            }
        }

        // Move captureCallback to a class-level variable
        captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                currentISO = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: currentISO
                currentExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: currentExposure
                currentFrameDuration = result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: currentFrameDuration
                currentFocus = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: currentFocus

                requireActivity().runOnUiThread {
                    if (!navManualExposure) {
                        navISO = currentISO
                        navExposure = currentExposure
                        navISOSeekbar.progress = (log2((currentISO).toDouble()) * 100).toInt()
                        navExposureSeekbar.progress = (log2(currentExposure.toDouble()) * 100).toInt()
                    }

                    if (!navManualFocus) {
                        navFocus = currentFocus
                        navFocusSeekbar.progress = (currentFocus * 100).toInt()
                    }
                }
            }
        }

        session.setRepeatingRequest(captureRequest.build(), captureCallback, cameraHandler)

        fragmentCameraBinding.viewFinder.setOnTouchListener { _, motionEvent ->
            if (motionEvent.action == MotionEvent.ACTION_DOWN) {
                captureRequest.set(
                    CaptureRequest.CONTROL_AF_TRIGGER,
                    CaptureRequest.CONTROL_AF_TRIGGER_START
                )
                session.capture(captureRequest.build(), captureCallback, cameraHandler)
            }
            true
        }

        // Update the captureButton touch listener to use the new methods
        fragmentCameraBinding.captureButton.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    startBurstCapture()
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    stopBurstCapture()
                }
            }
            true
        }
    }

    fun startBurstCapture() {
        if (capturingBurst.get()) { // already capturing
            return
        }

        numCapturedFrames.set(0)
        capturingBurst.set(true)
        createDataFolder(navFilename)
        fragmentCameraBinding.overlay.background = ContextCompat.getDrawable(requireContext(), R.drawable.border_red)
        session.stopRepeating()
        val burstDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val cameraName = arrayOf("MAIN", "UW", "TELE", "2X", "5X").getOrElse(navSelectedCamera) { "MAIN" }
        lifecycleScope.launch(burstDispatcher) {
            takeBurst(imageReader, cameraName, characteristicsP, AtomicInteger(0))
        }
    }

    fun stopBurstCapture() {
        capturingBurst.set(false)
        saveMotion()
        saveCharacteristics(characteristicsP)
        session.setRepeatingRequest(
            captureRequest.build(),
            captureCallback,
            cameraHandler
        )
        fragmentCameraBinding.overlay.background = null
    }

    private fun setButtonSeekerbarState(active: Boolean, button: Button, seekbar: SeekBar) {
        if (active) {
            button.background = ContextCompat.getDrawable(requireContext(), R.drawable.button_background_selector_active)
            seekbar.background = ContextCompat.getDrawable(requireContext(), R.drawable.seekbar_background_active)
        } else {
            button.background = ContextCompat.getDrawable(requireContext(), R.drawable.button_background_selector)
            seekbar.background = ContextCompat.getDrawable(requireContext(), R.drawable.seekbar_background)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        // load settings
        val sharedPref = requireActivity().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        navSelectedCamera = sharedPref.getInt("navSelectedCamera", navSelectedCamera)
        navISO = sharedPref.getInt("navISO", navISO)
        navISOmin = sharedPref.getInt("navISOmin", navISOmin)
        navISOmax = sharedPref.getInt("navISOmax", navISOmax)
        navExposure = sharedPref.getLong("navExposure", navExposure)
        navExposuremin = sharedPref.getLong("navExposuremin", navExposuremin)
        navExposuremax = sharedPref.getLong("navExposuremax", navExposuremax)
        navFocus = sharedPref.getFloat("navFocus", navFocus)
        navFocusmin = sharedPref.getFloat("navFocusmin", navFocusmin)
        navFocusmax = sharedPref.getFloat("navFocusmax", navFocusmax)
        navLockAF = sharedPref.getBoolean("navLockAF", navLockAF)
        navLockAE = sharedPref.getBoolean("navLockAE", navLockAE)
        navLockOIS = sharedPref.getBoolean("navLockOIS", navLockOIS)
        navManualFocus = sharedPref.getBoolean("navManualFocus", navManualFocus)
        navManualExposure = sharedPref.getBoolean("navManualExposure", navManualExposure)
        navFilename = sharedPref.getString("navFilename", navFilename) ?: navFilename
        navMaxFPS = sharedPref.getFloat("navMaxFPS", navMaxFPS)
        navMaxFrames = sharedPref.getInt("navMaxFrames", navMaxFrames)
        physicalCameraID = sharedPref.getString("physicalCameraID", physicalCameraID).toString()
        navFullResolution = sharedPref.getBoolean("navFullResolution", navFullResolution)

        // Open the selected camera
        camera = openCamera(cameraManager, args.cameraId, cameraHandler)

        try {
            startCameraStream()
        } catch (e: Exception) {
            Log.e("CameraFragment", "Error starting camera stream", e)
            // reset preferences in case they broke the camera pipeline
            resetSharedPreferences()
        }


        // Set up motion listeners for gyro/accelerometer
        val sensorManager =
            requireContext().getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val linearAccelerationSensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

        val sensorEventListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (capturingBurst.get() || (event.timestamp <= maxCapturedTimestamp.get())) {
                    when (event.sensor.type) {
                        Sensor.TYPE_LINEAR_ACCELERATION -> { // time, x, y, z
                            sensorAccValues.add(
                                listOf(
                                    event.timestamp.toFloat(),
                                    event.values[0],
                                    event.values[1],
                                    event.values[2]
                                )
                            )
                        }

                        Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                            val quaternion = FloatArray(4)
                            SensorManager.getQuaternionFromVector(
                                quaternion,
                                event.values
                            ) // time, x, y, z, w
                            sensorRotValues.add(
                                listOf(
                                    event.timestamp.toFloat(),
                                    quaternion[0],
                                    quaternion[1],
                                    quaternion[2],
                                    quaternion[3]
                                )
                            )
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
        }

        sensorManager.registerListener(
            sensorEventListener,
            linearAccelerationSensor,
            SensorManager.SENSOR_DELAY_FASTEST
        )
        sensorManager.registerListener(
            sensorEventListener,
            rotationVectorSensor,
            SensorManager.SENSOR_DELAY_FASTEST
        )

        // Set up UI listeners (buttons, switches, etc)

        val drawerLayout = requireActivity().findViewById<DrawerLayout>(R.id.drawer_layout)
        drawerLayout.setScrimColor(Color.TRANSPARENT)

        // set UI values to those loaded from preferences
        setButtonSeekerbarState(navManualExposure, navISOButton, navISOSeekbar)
        setButtonSeekerbarState(navManualExposure, navExposureButton, navExposureSeekbar)
        setButtonSeekerbarState(navManualFocus, navFocusButton, navFocusSeekbar)

        navEditMaxFPS.setText(navMaxFPS.toString())
        navEditMaxFrames.setText(navMaxFrames.toString())

        navSwitchLockAE.isChecked = navLockAE
        navSwitchLockAF.isChecked = navLockAF
        navSwitchLockOIS.isChecked = navLockOIS
        navSwitchFullRes.isChecked = navFullResolution

        val checkedRadioButtonId = when (navSelectedCamera) {
            0 -> R.id.nav_camera_main
            1 -> R.id.nav_camera_uw
            2 -> R.id.nav_camera_tele
            3 -> R.id.nav_camera_2X
            4 -> R.id.nav_camera_10X
            else -> R.id.nav_camera_main // Default case if needed
        }
        navRadioCamera.check(checkedRadioButtonId)

        // Code for switches/inputs

        navFramesValue = requireActivity().findViewById(R.id.frames_value)

        navEditFilename.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() && actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navFilename = v.text.toString()
                saveSettings()
            }
            navEditFilename.isCursorVisible = false // turn off cursor when done
            false
        }

        navEditFilename.setOnClickListener {
            navEditFilename.isCursorVisible = true // turn on cursor when editing
        }

        navEditExposure.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() && actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navExposure = (1 / v.text.toString().toFloat() * 1e9).toLong().coerceIn(navExposuremin, navExposuremax) // to nanoseconds
                saveSettings()
                restartCameraStream()
            }
            navEditExposure.isCursorVisible = false
            false
        }


        navEditExposure.setOnClickListener {
            navEditExposure.isCursorVisible = true
        }

        navEditExposure.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (!navManualExposure) {
                    navManualExposure = true
                    navISO = currentISO
                    navExposure = currentExposure
                    setButtonSeekerbarState(active = true, button = navISOButton, seekbar = navISOSeekbar)
                    setButtonSeekerbarState(active = true, button = navExposureButton, seekbar = navExposureSeekbar)
                    saveSettings()
                }
            }
        }

        navExposureSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && navManualExposure) { // change requested by user, not by our code
                    navExposure = Math.pow(2.0, progress.toFloat()/100.0).toLong().coerceIn(navExposuremin, navExposuremax)
                    currentExposure = navExposure
                    captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    captureRequest.set(CaptureRequest.SENSOR_EXPOSURE_TIME, navExposure)
                    session.setRepeatingRequest(captureRequest.build(), captureCallback, cameraHandler)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                session.abortCaptures()
                if (!navManualExposure) {
                    navManualExposure = true
                    navISO = currentISO
                    setButtonSeekerbarState(active = true, button = navISOButton, seekbar = navISOSeekbar)
                    setButtonSeekerbarState(active = true, button = navExposureButton, seekbar = navExposureSeekbar)
                    saveSettings()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveSettings()
            }
        })

        navEditISO.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() && actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navISO = v.text.toString().toInt().coerceIn(navISOmin, navISOmax)
                saveSettings()
                restartCameraStream()
            }
            navEditISO.isCursorVisible = false
            false
        }

        navEditISO.setOnClickListener {
            navEditISO.isCursorVisible = true
        }

        navEditISO.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                if (!navManualExposure) {
                    navManualExposure = true
                    navISO = currentISO
                    navExposure = currentExposure
                    setButtonSeekerbarState(active = true, button = navISOButton, seekbar = navISOSeekbar)
                    setButtonSeekerbarState(active = true, button = navExposureButton, seekbar = navExposureSeekbar)
                    saveSettings()
                }
            }
        }

        navISOSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && navManualExposure) {
                    navISO = Math.pow(2.0, progress.toFloat()/100.0).toInt().coerceIn(navISOmin, navISOmax)
                    currentISO = navISO
                    captureRequest.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    captureRequest.set(CaptureRequest.SENSOR_SENSITIVITY, navISO)
                    session.setRepeatingRequest(captureRequest.build(), captureCallback, cameraHandler)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                session.abortCaptures()
                if (!navManualExposure) {
                    navManualExposure = true
                    navExposure = currentExposure
                    setButtonSeekerbarState(active = true, button = navISOButton, seekbar = navISOSeekbar)
                    setButtonSeekerbarState(active = true, button = navExposureButton, seekbar = navExposureSeekbar)
                    session.stopRepeating()
                    saveSettings()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveSettings()
            }
        })


        navEditFocus.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() && actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navFocus = v.text.toString().toFloat().coerceIn(navFocusmin, navFocusmax)
                saveSettings()
                restartCameraStream()
            }
            navEditFocus.isCursorVisible = false
            false
        }

        navEditFocus.setOnClickListener {
            navEditFocus.isCursorVisible = true
        }

        navEditFocus.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                navManualFocus = true
                setButtonSeekerbarState(active = true, button = navFocusButton, seekbar = navFocusSeekbar)
                saveSettings()
            }
        }

        navFocusSeekbar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser && navManualFocus) {
                    navFocus = (progress.toFloat() / 100).coerceIn(navFocusmin, navFocusmax)
                    currentFocus = navFocus
                    captureRequest.set(CaptureRequest.LENS_FOCUS_DISTANCE, navFocus)
                    // have to manually change captureRequest since restartCameraStream() makes the stream hiccup
                    captureRequest.set(
                        CaptureRequest.CONTROL_AF_MODE,
                        CaptureRequest.CONTROL_AF_MODE_OFF
                    )
                    session.setRepeatingRequest(captureRequest.build(), captureCallback, cameraHandler)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                session.abortCaptures()
                if (!navManualFocus) {
                    navManualFocus = true
                    setButtonSeekerbarState(active = true, button = navFocusButton, seekbar = navFocusSeekbar)
                    session.stopRepeating()
                    saveSettings()
                }
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                saveSettings()
            }
        })

        navEditMaxFPS.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() && actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navMaxFPS = v.text.toString().toFloat()
                saveSettings()
            }
            navEditMaxFPS.isCursorVisible = false
            false
        }

        navEditMaxFPS.setOnClickListener {
            navEditMaxFPS.isCursorVisible = true
        }

        navEditMaxFrames.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() && actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navMaxFrames = v.text.toString().toInt()
                saveSettings()
            }
            navEditMaxFrames.isCursorVisible = false
            false
        }

        navEditMaxFrames.setOnClickListener {
            navEditMaxFrames.isCursorVisible = true
        }

        navSwitchLockAE.setOnCheckedChangeListener { _, isChecked ->
            navLockAE = isChecked
            saveSettings()
        }

        navSwitchLockAF.setOnCheckedChangeListener { _, isChecked ->
            navLockAF = isChecked
            saveSettings()
            restartCameraStream()
        }

        navSwitchLockOIS.setOnCheckedChangeListener { _, isChecked ->
            navLockOIS = isChecked
            saveSettings()
            restartCameraStream()
        }

        navExposureButton.setOnLongClickListener {
            if (navManualExposure) {
                navManualExposure = false
                setButtonSeekerbarState(active = false, button = navISOButton, seekbar = navISOSeekbar)
                setButtonSeekerbarState(active = false, button = navExposureButton, seekbar = navExposureSeekbar)
                saveSettings()
                restartCameraStream()
                true
            } else {
                navManualExposure = true
                setButtonSeekerbarState(active = true, button = navISOButton, seekbar = navISOSeekbar)
                setButtonSeekerbarState(active = true, button = navExposureButton, seekbar = navExposureSeekbar)
                navISO = currentISO
                navExposure = currentExposure
                saveSettings()
                restartCameraStream()
                false
            }
        }

        // duplicate code because we can't do shutter/speed priority
        navISOButton.setOnLongClickListener {
            if (navManualExposure) {
                navManualExposure = false
                setButtonSeekerbarState(active = false, button = navISOButton, seekbar = navISOSeekbar)
                setButtonSeekerbarState(active = false, button = navExposureButton, seekbar = navExposureSeekbar)
                saveSettings()
                restartCameraStream()
                true
            } else {
                navManualExposure = true
                setButtonSeekerbarState(active = true, button = navISOButton, seekbar = navISOSeekbar)
                setButtonSeekerbarState(active = true, button = navExposureButton, seekbar = navExposureSeekbar)
                navISO = currentISO
                navExposure = currentExposure
                saveSettings()
                restartCameraStream()
                false
            }
        }


        navFocusButton.setOnLongClickListener {
            if (navManualFocus) {
                navManualFocus = false
                setButtonSeekerbarState(active = false, button = navFocusButton, seekbar = navFocusSeekbar)
                saveSettings()
                restartCameraStream()
                true
            } else {
                navManualFocus = true
                navFocus = currentFocus
                setButtonSeekerbarState(active = true, button = navFocusButton, seekbar = navFocusSeekbar)
                saveSettings()
                restartCameraStream()
                false
            }

        }


        navSwitchFullRes.setOnCheckedChangeListener { _, isChecked ->
            navFullResolution = isChecked
            saveSettings()
        }

        data class Camera(
            val id: String,
            val focalLength: Float,
            val sensorArea: Float
        )

        fun createCameraGroups(cameraManager: CameraManager): List<List<String>> {
            val cameras = mutableListOf<Camera>()

            // Collect all back cameras
            for (i in 0..50) { // idk there's probably not more than 50 cameras
                try {
                    val chars = cameraManager.getCameraCharacteristics(i.toString())

                    // Skip if not back-facing
                    if (chars.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue

                    // Get focal length
                    val focalLength = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.get(0) ?: continue

                    // Get sensor size for sorting
                    val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val sensorArea = if (sensor != null) sensor.width * sensor.height else 0f

                    cameras.add(Camera(i.toString(), focalLength, sensorArea))

                } catch (e: Exception) {
                    continue
                }
            }

            // Group by focal length and sort by sensor size
            return cameras
                .groupBy { it.focalLength.roundToInt() }
                .toSortedMap()
                .values
                .map { group ->
                    group.sortedByDescending { it.sensorArea }.map { it.id }
                }
        }

        val cameraGroups = createCameraGroups(cameraManager)

        // Radio Camera Selection
        // hard-coded camera selection nonsense, 2 almost always exists and is the main camera on every phone I've tested
        navRadioCamera.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.nav_camera_main -> {
                    physicalCameraID = cameraGroups.getOrNull(1)?.getOrNull(1) ?: "2"
                    navSelectedCamera = 0
                }

                R.id.nav_camera_uw -> {
                    physicalCameraID = cameraGroups.getOrNull(0)?.getOrNull(0) ?: "2"
                    navSelectedCamera = 1
                }

                R.id.nav_camera_tele -> {
                    physicalCameraID =  cameraGroups.getOrNull(2)?.getOrNull(0) ?: "2"
                    navSelectedCamera = 2
                }

                R.id.nav_camera_2X -> {
                    physicalCameraID =  cameraGroups.getOrNull(1)?.getOrNull(2) ?: "2"
                    navSelectedCamera = 3
                }

                R.id.nav_camera_10X -> {
                    physicalCameraID = cameraGroups.getOrNull(2)?.getOrNull(1) ?: "2"
                    navSelectedCamera = 4
                }

            }
            saveSettings()
            restartCameraStream()
        }

        navResetButton.setOnClickListener {
            // Simulate backpress to restart app
            session.abortCaptures()
            requireActivity().onBackPressedDispatcher.onBackPressed()
        }

        navResetButton.setOnLongClickListener { // hard reset
            session.abortCaptures()
            // Reset the settings
            resetSharedPreferences()

            // Simulate backpress to restart app
            requireActivity().onBackPressedDispatcher.onBackPressed()

            true
        }

        navDrawerButton.setOnClickListener {
            // open/close drawer
            drawerLayout.openDrawer(Gravity.LEFT)
        }

    }

    /** Opens the camera and returns the opened device (as the result of the suspend coroutine) */
    @SuppressLint("MissingPermission")
    private suspend fun openCamera(
        manager: CameraManager,
        cameraId: String,
        handler: Handler? = null
    ): CameraDevice = suspendCancellableCoroutine { cont ->

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) = cont.resume(device)

            override fun onDisconnected(device: CameraDevice) {
                Log.w(TAG, "Camera $cameraId has been disconnected")
                requireActivity().finish()
            }

            override fun onError(device: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                val exc = RuntimeException("Camera $cameraId error: ($error) $msg")
                Log.e(TAG, exc.message, exc)
                if (cont.isActive) cont.resumeWithException(exc)
            }
        }, handler)
    }

    /** Starts a [CameraCaptureSession] and returns the configured session */
    private suspend fun createCaptureSession(
        device: CameraDevice,
        targets: List<Surface>,
        handler: Handler? = null
    ): CameraCaptureSession = suspendCoroutine { cont ->

        val outputConfigsLogical = OutputConfiguration(targets[0]).apply {
            setPhysicalCameraId(physicalCameraID)
        }
        val outputConfigsPhysical = OutputConfiguration(targets[1]).apply {
            setPhysicalCameraId(physicalCameraID)
        }
        val outputConfigsAll = listOf(outputConfigsLogical, outputConfigsPhysical)
        val executor = Executors.newSingleThreadExecutor()

        val sessionConfiguration = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputConfigsAll,
            executor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) = cont.resume(session)
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    val exc = RuntimeException("Camera ${device.id} session configuration failed")
                    Log.e(TAG, exc.message, exc)
                    cont.resumeWithException(exc)
                }
            }
        )
        device.createCaptureSession(sessionConfiguration)
    }

    /** Captures a burst of images and saves them */
    private suspend fun takeBurst(
        imageReader: ImageReader,
        cameraName: String,
        characteristicsP: CameraCharacteristics,
        numRecordedFrames: AtomicInteger
    ) = coroutineScope {

        while (imageReader.acquireNextImage() != null) {
        }
        Log.d(TAG, "Starting capture.")
        val imageQueue = ArrayBlockingQueue<Image>(imageBufferSize)
        val requestHashQueue = ConcurrentLinkedQueue<Int>()
        val requestsInFlight = AtomicInteger(0)
        val dispatcherWorkStealing = Executors.newWorkStealingPool().asCoroutineDispatcher()

        imageReader.setOnImageAvailableListener({ reader ->
            val image = reader.acquireNextImage()
            Log.d(TAG, "Image available in queue: ${image.timestamp}")
            imageQueue.add(image)
        }, imageReaderHandler)

        while (capturingBurst.get()) {
            delay((1000 / navMaxFPS).toLong())
            while (requestsInFlight.get() > (imageBufferSize - 4)) {
                delay(3)
                Log.d(TAG, "Waiting on requests in flight")
            }
            Log.d(TAG, "Request in flight.")
            requestsInFlight.incrementAndGet()

            val captureRequest =
                session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(imageReader.surface)
                    addTarget(fragmentCameraBinding.viewFinder.holder.surface)
                    set(CaptureRequest.CONTROL_AWB_LOCK, true)

                    if (navManualExposure) {
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                        set(CaptureRequest.SENSOR_EXPOSURE_TIME, navExposure)
                        set(CaptureRequest.SENSOR_SENSITIVITY, navISO)
                    } else if (navLockAE) {
                        set(CaptureRequest.CONTROL_AE_LOCK, true)
                    }

                    when {
                        navManualFocus -> {
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            set(CaptureRequest.LENS_FOCUS_DISTANCE, navFocus)
                        }

                        navLockAF -> {
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocus)
                        }

                        else -> set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                        )
                    }

                    if (navLockOIS) {
                        set(
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                        )
                        set(
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF
                        )
                    }
                }

            session.capture(
                captureRequest.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureStarted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        timestamp: Long,
                        frameNumber: Long
                    ) {
                        requestHashQueue.add(request.hashCode())
                        Log.d(TAG, "Queued: ${request.hashCode()}")
                    }

                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        val resultTimestamp = result.get(CaptureResult.SENSOR_TIMESTAMP)
                        val requestHash = request.hashCode()

                        if (numCapturedFrames.get() >= navMaxFrames) {
                            navFramesValue.text = "MAX"
                            capturingBurst.set(false)
                        } else {
                            navFramesValue.text = numCapturedFrames.getAndIncrement().toString()
                        }

                        maxCapturedTimestamp.set(max(maxCapturedTimestamp.get(), resultTimestamp!!))

                        if (!requestHashQueue.contains(requestHash)) {
                            Log.d(TAG, "Capture request missing: $requestHash")
                        } else if (capturingBurst.get()) {
                            Log.d(TAG, "Capture result received: $resultTimestamp")

                            while (requestHashQueue.peek() != requestHash) {
                                Thread.sleep(Random.nextLong(3, 6))
                                if (!requestHashQueue.contains(requestHash)) {
                                    requestsInFlight.decrementAndGet()
                                    return
                                }
                            }

                            while (true) {
                                val image = imageQueue.poll()
                                if (image == null) {
                                    requestHashQueue.poll()
                                    Log.d(TAG, "Capture abandoned")
                                    break
                                } else if (image.timestamp < resultTimestamp!!) {
                                    image.close()
                                    continue
                                } else if (image.timestamp == resultTimestamp) {
                                    Log.d(TAG, "Matching image dequeued: ${image.timestamp}")
                                    requestHashQueue.poll()
                                    lifecycleScope.launch(dispatcherWorkStealing) {
                                        saveResult(
                                            CombinedCaptureResult(
                                                image,
                                                result,
                                                imageReader.imageFormat
                                            ),
                                            numRecordedFrames,
                                            cameraName,
                                            characteristicsP
                                        )
                                        image.close()
                                        requestsInFlight.decrementAndGet()
                                    }
                                    break
                                } else {
                                    Log.d(TAG, "No matching image found.")
                                    requestHashQueue.poll()
                                    image.close()
                                    requestsInFlight.decrementAndGet()
                                    break
                                }
                            }
                        } else {
                            requestHashQueue.poll()
                            Log.d(TAG, "Capture ended, dequeued")
                            requestsInFlight.decrementAndGet()
                        }
                    }
                },
                cameraHandler
            )
        }

        imageReader.setOnImageAvailableListener(null, null)
        requestHashQueue.clear()

        while (requestsInFlight.get() > 0) {
            delay(3)
        }

        while (imageQueue.isNotEmpty()) {
            imageQueue.take().close()
        }

        imageReader.close()
        Log.d(TAG, "Done capturing burst.")
    }
    /** Saves the capture result to a file */
    private fun saveResult(
        result: CombinedCaptureResult,
        numRecordedFrames: AtomicInteger,
        cameraName: String,
        characteristicsP: CameraCharacteristics
    ) {
        if (result.format == ImageFormat.RAW_SENSOR) {
            try {
                val currentFrame = numRecordedFrames.getAndIncrement()
                val outputRaw = createFile("IMG_${cameraName}_${String.format("%03d", currentFrame)}", "raw")
                val outputMetadata = createFile("IMG_${cameraName}_${String.format("%03d", currentFrame)}", "bin")
                val outputPreview = createFile("IMG_${cameraName}_${String.format("%03d", currentFrame)}_preview", "bmp")

                // Save the BMP preview of the RAW image
                saveBMP(result, outputPreview, characteristicsP)
                // Save the downsampled RAW image
                saveRAW(result, outputRaw)

                // Save the metadata to a separate file
                val metadataString = createMetadataString(result.metadata)
                BufferedOutputStream(FileOutputStream(outputMetadata)).use { outStream ->
                    outStream.write(metadataString.toByteArray())
                }

                Log.d(TAG, "RAW image and preview saved: ${outputRaw.absolutePath}, ${outputPreview.absolutePath}")
            } catch (exc: IOException) {
                Log.e(TAG, "Unable to write RAW image or preview to file", exc)
            }
        } else {
            val exc = RuntimeException("Unknown image format: ${result.image.format}")
            Log.e(TAG, exc.message, exc)
        }
    }

    private fun saveBMP(result: CombinedCaptureResult, outputPreview: File, characteristicsP: CameraCharacteristics) {
        val plane = result.image.planes[0]
        val buffer = plane.buffer

        val originalWidth = result.image.width
        val originalHeight = result.image.height

        var targetWidth = 512
        var targetHeight = ((originalHeight * targetWidth) / originalWidth).coerceAtLeast(2)

        // Calculate ratios to downsample the original image
        val widthRatio = (((originalWidth / targetWidth).coerceAtLeast(2) / 2) * 2).coerceAtLeast(2)
        val heightRatio = (((originalHeight / targetHeight).coerceAtLeast(2) / 2) * 2).coerceAtLeast(2)

        targetWidth = originalWidth / widthRatio
        targetHeight = originalHeight / heightRatio

        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val maxPixelValue = characteristicsP.get(CameraCharacteristics.SENSOR_INFO_WHITE_LEVEL) ?: 1023
        val bytesPerPixel = pixelStride.coerceAtLeast(1)

        FileOutputStream(outputPreview).use { outputStream ->
            val rotatedWidth = targetHeight
            val rotatedHeight = targetWidth

            // Write BMP header and DIB header for an 8-bit grayscale image
            val fileSize = 14 + 40 + 256 * 4 + (rotatedWidth * rotatedHeight)
            val bmpHeader = ByteArray(14)
            bmpHeader[0] = 'B'.toByte()
            bmpHeader[1] = 'M'.toByte()
            ByteBuffer.wrap(bmpHeader, 2, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(fileSize)
            ByteBuffer.wrap(bmpHeader, 10, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(14 + 40 + 256 * 4)
            outputStream.write(bmpHeader)

            val dibHeader = ByteArray(40)
            ByteBuffer.wrap(dibHeader, 0, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(40)
            ByteBuffer.wrap(dibHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(rotatedWidth)
            ByteBuffer.wrap(dibHeader, 8, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(-rotatedHeight)
            ByteBuffer.wrap(dibHeader, 12, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(1)
            ByteBuffer.wrap(dibHeader, 14, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(8)
            ByteBuffer.wrap(dibHeader, 20, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0)
            outputStream.write(dibHeader)

            // Write grayscale color palette
            val colorPalette = ByteArray(256 * 4)
            for (i in 0 until 256) {
                colorPalette[i * 4] = i.toByte()
                colorPalette[i * 4 + 1] = i.toByte()
                colorPalette[i * 4 + 2] = i.toByte()
                colorPalette[i * 4 + 3] = 0
            }
            outputStream.write(colorPalette)

            val rowPadding = (4 - (rotatedWidth % 4)) % 4
            val rowBuffer = ByteArray(rotatedWidth + rowPadding)

            for (y in 0 until rotatedHeight) {
                for (x in 0 until rotatedWidth) {
                    // Map rotated coordinates to the original image for sampling
                    val mappedX = (y * widthRatio).toInt()
                    val mappedY = (originalHeight - 1 - x * heightRatio).toInt()

                    val originalX = (mappedX and -2).coerceIn(0, originalWidth - 2)
                    val originalY = (mappedY and -2).coerceIn(0, originalHeight - 2)

                    val pixelIndex = originalY * rowStride + originalX * pixelStride

                    if (pixelIndex + bytesPerPixel <= buffer.limit()) {
                        var pixelValue = 0
                        for (i in 0 until bytesPerPixel) {
                            pixelValue += ((buffer.get(pixelIndex + i).toInt() and 0xFF) shl (i * 8))
                        }
                        // Convert pixel value to 8-bit grayscale and adjust brightness
                        val pixelValue8Bit = ((pixelValue.toFloat() * 5 / maxPixelValue) * 255f).toInt().coerceIn(0, 255).toByte()
                        rowBuffer[x] = pixelValue8Bit
                    } else {
                        rowBuffer[x] = 0
                    }
                }
                for (p in 0 until rowPadding) {
                    rowBuffer[rotatedWidth + p] = 0
                }
                outputStream.write(rowBuffer)
            }
        }
    }

    private fun saveRAW(result: CombinedCaptureResult, outputRaw: File) {
        val plane = result.image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val bytesPerPixel = pixelStride.coerceAtLeast(1)
        val originalWidth = result.image.width
        val originalHeight = result.image.height

        if (navFullResolution) {
            // add header for unpacking
            val characteristicStringBuilder = StringBuilder()
            characteristicStringBuilder.append("<KEY>HEIGHT<ENDKEY><VALUE>${originalHeight}<ENDVALUE>")
            characteristicStringBuilder.append("<KEY>WIDTH<ENDKEY><VALUE>${originalWidth}<ENDVALUE>")
            characteristicStringBuilder.append("<KEY>BYTES_PER_PIXEL<ENDKEY><VALUE>${bytesPerPixel}<ENDVALUE>")
            characteristicStringBuilder.append("<KEY>RESOLUTION<ENDKEY><VALUE>FULL<ENDVALUE>")

            val headerBytes = characteristicStringBuilder.toString().toByteArray(Charsets.UTF_8)

            val rawBytes = ByteArray(buffer.remaining())
            buffer.get(rawBytes)

            BufferedOutputStream(FileOutputStream(outputRaw)).use { outStream ->
                outStream.write(headerBytes)
                outStream.write(rawBytes)
            }
        } else {
            BufferedOutputStream(FileOutputStream(outputRaw)).use { outputStream ->
                val writeBuffer = ByteArray(bytesPerPixel * originalWidth)
                val rowBuffer = ByteArray(rowStride * 2)

                val characteristicStringBuilder = StringBuilder()
                characteristicStringBuilder.append("<KEY>HEIGHT<ENDKEY><VALUE>${originalHeight / 2}<ENDVALUE>")
                characteristicStringBuilder.append("<KEY>WIDTH<ENDKEY><VALUE>${originalWidth / 2}<ENDVALUE>")
                characteristicStringBuilder.append("<KEY>BYTES_PER_PIXEL<ENDKEY><VALUE>${bytesPerPixel}<ENDVALUE>")
                characteristicStringBuilder.append("<KEY>RESOLUTION<ENDKEY><VALUE>HALF<ENDVALUE>")

                val headerBytes = characteristicStringBuilder.toString().toByteArray(Charsets.UTF_8)

                outputStream.write(headerBytes)

                for (y in 0 until originalHeight step 4) {
                    val readIndexRow1 = y * rowStride
                    buffer.position(readIndexRow1)

                    if (buffer.remaining() < rowStride * 2) {
                        break
                    }

                    buffer.get(rowBuffer, 0, rowStride * 2)

                    var writeIndex = 0
                    // write top half of bayer pattern (e.g., RGRGRG...)
                    for (x in 0 until originalWidth step 4) {
                        val offset1 = x * pixelStride

                        System.arraycopy(rowBuffer, offset1, writeBuffer, writeIndex, bytesPerPixel * 2)
                        writeIndex += bytesPerPixel * 2
                    }
                    // write bottom half of bayer pattern (e.g., GBGBGB...)
                    for (x in 0 until originalWidth step 4) {
                        val offset2 = x * pixelStride

                        System.arraycopy(rowBuffer, rowStride + offset2, writeBuffer, writeIndex, bytesPerPixel * 2)
                        writeIndex += bytesPerPixel * 2
                    }
                    // Write the downsampled data for these rows
                    outputStream.write(writeBuffer, 0, writeIndex)
                }
            }
        }
    }




    /** Saves motion sensor data to a file */
    private fun saveMotion() {
        val motionStringBuilder = StringBuilder()
        sensorAccValues.forEach { data ->
            motionStringBuilder.append("<ACC>")
            motionStringBuilder.append("${data[0].toLong()},")
            motionStringBuilder.append(data.drop(1).joinToString(","))
            motionStringBuilder.append("<ENDACC>")
        }
        sensorRotValues.forEach { data ->
            motionStringBuilder.append("<ROT>")
            motionStringBuilder.append("${data[0].toLong()},")
            motionStringBuilder.append(data.drop(1).joinToString(","))
            motionStringBuilder.append("<ENDROT>")
        }

        val outputMotion = createFile("MOTION", "bin")
        FileOutputStream(outputMotion).use { outStream ->
            outStream.write(motionStringBuilder.toString().toByteArray())
        }

        sensorAccValues.clear()
        sensorRotValues.clear()
    }

    /** Saves camera characteristics to a file */
    private fun saveCharacteristics(characteristicsP: CameraCharacteristics) {
        val characteristicStringBuilder = StringBuilder()
        val filterKeys = listOf("StreamCombinations")
        for (key in characteristicsP.keys) {
            if (filterKeys.none { key.name.contains(it, true) }) {
                val value = characteristicsP.get(key)
                val valueString = when (value) {
                    is Array<*> -> value.joinToString()
                    is List<*> -> value.joinToString()
                    is FloatArray -> value.joinToString()
                    is Boolean, is Int, is Double, is Float, is Long, is Byte, is Short, is Char, is String -> value.toString()
                    else -> null
                }
                if (valueString != null) {
                    characteristicStringBuilder.append("<KEY>${key.name}<ENDKEY><VALUE>$valueString<ENDVALUE>")
                }
            }
        }

        val outputCharacteristics = createFile("CHARACTERISTICS", "bin")
        FileOutputStream(outputCharacteristics).use { outStream ->
            outStream.write(characteristicStringBuilder.toString().toByteArray())
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            camera.close()
        } catch (exc: Throwable) {
            Log.e(TAG, "Error closing camera", exc)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()
    }

    /** Data class to hold capture metadata with their associated image */
    data class CombinedCaptureResult(
        val image: Image,
        val metadata: CaptureResult,
        val format: Int
    ) : Closeable {
        override fun close() = image.close()
    }

    /** Creates a metadata string from the capture result */
    private fun createMetadataString(metadata: CaptureResult): String {
        val metadataStringBuilder = StringBuilder()
        val filterKeys = listOf("internal", "lyric", "vendor", "experimental", "com.google")
        for (key in metadata.keys) {
            if (filterKeys.none { key.name.contains(it, true) }) {
                val value = metadata.get(key)
                val valueString = when (value) {
                    is Array<*> -> value.joinToString()
                    is List<*> -> value.joinToString()
                    is FloatArray -> value.joinToString()
                    else -> value.toString()
                }
                metadataStringBuilder.append("<KEY>${key.name}<ENDKEY><VALUE>$valueString<ENDVALUE>")
            }
        }
        return metadataStringBuilder.toString()
    }

    /** Creates a data folder with an optional descriptor */
    private fun createDataFolder(descriptor: String = "") {
        val date = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.US).format(Date())
        val documentFolder =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        saveFolderDir = File(documentFolder, "$date-$descriptor")
        saveFolderDir.mkdirs()
    }

    /** Creates a file with the given descriptor and extension */
    private fun createFile(descriptor: String, extension: String): File {
        return File(saveFolderDir, "$descriptor.$extension")
    }
}

