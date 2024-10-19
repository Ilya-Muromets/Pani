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

package com.android.pani.fragments

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
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
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ExifInterface
import android.media.Image
import android.media.ImageReader
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.text.Html
import android.util.Log
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
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import com.android.pani.R
import com.android.pani.databinding.FragmentCameraBinding
import fragmentargs.CameraFragmentArgs
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.math.max
import kotlin.random.Random


class CameraFragment : Fragment() {

    private val TAG = CameraFragment::class.java.simpleName
    lateinit var saveFolderDir: File
    private val imageBufferSize = 42 // Max number of images in imageQueue

    // Navigation/UI variables
    var navSelectedCamera = 0 // 0 - Wide, 1 - Ultrawide, 2 - Tele, 3 - 2X, 4 - 10X
    var navISO = 1000
    var navExposure: Long = 10000
    var navFocal = 1.0F
    var navLockAF = true
    var navLockAE = true
    var navLockOIS = false
    var navManualFocus = false
    var navManualExposure = false
    var navFilename = "0"
    var navMaxFPS = 22F
    var navMaxFrames = 999

    lateinit var navInfoTextView: TextView
    lateinit var navFramesValue: TextView

    var currentISO = 42
    var currentFrameDuration: Long = 42
    var currentExposure: Long = 42
    var currentFocal = 1.0F

    var sensorAccValues = mutableListOf<List<Float>>()
    var sensorRotValues = mutableListOf<List<Float>>()

    var capturingBurst = AtomicBoolean(false)
    var numCapturedFrames = AtomicInteger(0)
    var maxCapturedTimestamp = AtomicLong(0)

    private fun saveSettings() {
        val sharedPref = requireActivity().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        with(sharedPref.edit()) {
            putInt("navSelectedCamera", navSelectedCamera)
            putInt("navISO", navISO)
            putLong("navExposure", navExposure)
            putFloat("navFocal", navFocal)
            putBoolean("navLockAF", navLockAF)
            putBoolean("navLockAE", navLockAE)
            putBoolean("navLockOIS", navLockOIS)
            putBoolean("navManualFocus", navManualFocus)
            putBoolean("navManualExposure", navManualExposure)
            putString("navFilename", navFilename)
            putFloat("navMaxFPS", navMaxFPS)
            putInt("navMaxFrames", navMaxFrames)
            putString("physicalCameraID", physicalCameraID)
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
            ) {
            }

            override fun surfaceCreated(holder: SurfaceHolder) {
                view.post { initializeCamera() }
            }
        })
    }

    private fun restartCameraStream() {
        lifecycleScope.launch(Dispatchers.Main) { startCameraStream() }
    }

    @SuppressLint("ClickableViewAccessibility")
    private suspend fun startCameraStream() {
        val characteristicsP = cameraManager.getCameraCharacteristics(physicalCameraID)
        val size = characteristicsP.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
            .getOutputSizes(args.pixelFormat).maxByOrNull { it.height * it.width }!!
        imageReader = ImageReader.newInstance(
            size.width, size.height, args.pixelFormat, imageBufferSize,
            HardwareBuffer.USAGE_CPU_READ_OFTEN or HardwareBuffer.USAGE_CPU_WRITE_RARELY
        )
        val targets = listOf(fragmentCameraBinding.viewFinder.holder.surface, imageReader.surface)
        session = createCaptureSession(camera, targets, cameraHandler)
        val captureRequest =
            camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
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
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, navFocal)
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

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                currentISO = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: currentISO
                currentExposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: currentExposure
                currentFrameDuration =
                    result.get(CaptureResult.SENSOR_FRAME_DURATION) ?: currentFrameDuration
                currentFocal = result.get(CaptureResult.LENS_FOCUS_DISTANCE) ?: currentFocal

                val inverseExposure = (1e9 / currentExposure).toInt()
                val roundedFocal = String.format("%.2f", currentFocal)
                val text =
                    "<font color=#808080>ISO:</font> $currentISO <font color=#808080>Exposure:</font>1/$inverseExposure <font color=#808080>Focus:</font> $roundedFocal"

                requireActivity().runOnUiThread {
                    navInfoTextView.text = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY)
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

        fragmentCameraBinding.captureButton.setOnTouchListener { view, motionEvent ->
            when (motionEvent.action) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    numCapturedFrames.set(0)
                    capturingBurst.set(true)
                    createDataFolder(navFilename)
                    fragmentCameraBinding.overlay.background =
                        ContextCompat.getDrawable(requireContext(), R.drawable.border)
                    session.stopRepeating()
                    val burstDispatcher =
                        Executors.newSingleThreadExecutor().asCoroutineDispatcher()
                    val cameraName = arrayOf("MAIN", "UW", "TELE", "2X", "5X")[navSelectedCamera]
                    lifecycleScope.launch(burstDispatcher) {
                        takeBurst(imageReader, cameraName, characteristicsP, AtomicInteger(0))
                    }
                }

                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    capturingBurst.set(false)
                    saveMotion()
                    saveCharacteristics(characteristicsP)
                    fragmentCameraBinding.overlay.background = null
                    session.setRepeatingRequest(
                        captureRequest.build(),
                        captureCallback,
                        cameraHandler
                    )
                }
            }
            true
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeCamera() = lifecycleScope.launch(Dispatchers.Main) {
        // load settings
        val sharedPref = requireActivity().getSharedPreferences("prefs", Context.MODE_PRIVATE)
        navSelectedCamera = sharedPref.getInt("navSelectedCamera", navSelectedCamera)
        navISO = sharedPref.getInt("navISO", navISO)
        navExposure = sharedPref.getLong("navExposure", navExposure)
        navFocal = sharedPref.getFloat("navFocal", navFocal)
        navLockAF = sharedPref.getBoolean("navLockAF", navLockAF)
        navLockAE = sharedPref.getBoolean("navLockAE", navLockAE)
        navLockOIS = sharedPref.getBoolean("navLockOIS", navLockOIS)
        navManualFocus = sharedPref.getBoolean("navManualFocus", navManualFocus)
        navManualExposure = sharedPref.getBoolean("navManualExposure", navManualExposure)
//        navTrash = sharedPref.getBoolean("navTrash", navTrash)
        navFilename = sharedPref.getString("navFilename", navFilename) ?: navFilename
        navMaxFPS = sharedPref.getFloat("navMaxFPS", navMaxFPS)
        navMaxFrames = sharedPref.getInt("navMaxFrames", navMaxFrames)
        physicalCameraID = sharedPref.getString("physicalCameraID", physicalCameraID).toString()

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
//                            Log.d(TAG, "Acceleration: " +
//                                    event.values[0].toString().slice(0..4) + " " +
//                                    event.values[1].toString().slice(0..4) + " " +
//                                    event.values[2].toString().slice(0..4))
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

        val navEditFilename = requireActivity().findViewById<EditText>(R.id.filename_input)
        val navEditExposure = requireActivity().findViewById<EditText>(R.id.nav_exposure)
        val navEditISO = requireActivity().findViewById<EditText>(R.id.nav_iso)
        val navEditFocal = requireActivity().findViewById<EditText>(R.id.nav_focal)
        val navEditMaxFPS = requireActivity().findViewById<EditText>(R.id.nav_max_fps)
        val navEditMaxFrames = requireActivity().findViewById<EditText>(R.id.nav_max_frames)
        val navSwitchLockAE = requireActivity().findViewById<Switch>(R.id.nav_lock_AE)
        val navSwitchLockAF = requireActivity().findViewById<Switch>(R.id.nav_lock_AF)
        val navSwitchLockOIS = requireActivity().findViewById<Switch>(R.id.nav_lock_OIS)
        val navSwitchManualE = requireActivity().findViewById<Switch>(R.id.nav_manual_exposure)
        val navSwitchManualF = requireActivity().findViewById<Switch>(R.id.nav_manual_focus)
//        val navSwitchTrash = requireActivity().findViewById<Switch>(R.id.nav_trash)
        val navRadioCamera = requireActivity().findViewById<RadioGroup>(R.id.radio_group)
        val navResetButton = requireActivity().findViewById<Button>(R.id.nav_reset)

        // set UI values to those loaded from preferences

        navEditExposure.setText(navExposure.toString())
        navEditISO.setText(navISO.toString())
        navEditFocal.setText(navFocal.toString())
        navEditMaxFPS.setText(navMaxFPS.toString())
        navEditMaxFrames.setText(navMaxFrames.toString())

        navSwitchLockAE.isChecked = navLockAE
        navSwitchLockAF.isChecked = navLockAF
        navSwitchLockOIS.isChecked = navLockOIS
        navSwitchManualE.isChecked = navManualExposure
        navSwitchManualF.isChecked = navManualFocus
//        navSwitchTrash.isChecked = navTrash

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

        navInfoTextView = requireActivity().findViewById(R.id.text_info)
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
                navExposure = (1 / v.text.toString().toFloat() * 1e9).toLong() // to nanoseconds
                saveSettings()
                restartCameraStream()
            }
            navEditExposure.isCursorVisible = false
            false
        }

        navEditExposure.setOnClickListener {
            navEditExposure.isCursorVisible = true
        }

        navEditISO.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() && actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navISO = v.text.toString().toInt()
                saveSettings()
                restartCameraStream()
            }
            navEditISO.isCursorVisible = false
            false
        }

        navEditISO.setOnClickListener {
            navEditISO.isCursorVisible = true
        }

        navEditFocal.setOnEditorActionListener { v, actionId, event ->
            if (v.text.isNotEmpty() && actionId == EditorInfo.IME_ACTION_DONE || event?.action == KeyEvent.ACTION_DOWN && event.keyCode == KeyEvent.KEYCODE_ENTER) {
                navFocal = v.text.toString().toFloat()
                saveSettings()
                restartCameraStream()
            }
            navEditFocal.isCursorVisible = false
            false
        }

        navEditFocal.setOnClickListener {
            navEditFocal.isCursorVisible = true
        }

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

        navSwitchManualE.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                navManualExposure = true
                navISO = currentISO
                navExposure = currentExposure
                navEditISO.setText(currentISO.toString())
                navEditExposure.setText((1 / (currentExposure / 1e9)).toInt().toString())
                saveSettings()
            } else {
                navManualExposure = false
                saveSettings()
            }
            restartCameraStream()
        }

        navSwitchManualF.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                navManualFocus = true
                navFocal = currentFocal
                navEditFocal.setText(currentFocal.toString())
                saveSettings()
            } else {
                navManualFocus = false
                saveSettings()
            }
            restartCameraStream()
        }

//        navSwitchTrash.setOnCheckedChangeListener { _, isChecked ->
//            navTrash = isChecked
//            saveSettings()
//        }

        navRadioCamera.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.nav_camera_main -> {
                    physicalCameraID = "2"
                    navSelectedCamera = 0
                }

                R.id.nav_camera_uw -> {
                    physicalCameraID = "3"
                    navSelectedCamera = 1
                }

                R.id.nav_camera_tele -> {
                    physicalCameraID = "4"
                    navSelectedCamera = 2
                }

                R.id.nav_camera_2X -> {
                    physicalCameraID = "5"
                    navSelectedCamera = 3
                }

                R.id.nav_camera_10X -> {
                    physicalCameraID = "6"
                    navSelectedCamera = 4
                }

            }
            saveSettings()
            restartCameraStream()
        }

        navResetButton.setOnLongClickListener {
            // Reset the settings
            resetSharedPreferences()

            // Simulate backpress to restart app
            requireActivity().onBackPressedDispatcher.onBackPressed()

            true
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
                            set(CaptureRequest.LENS_FOCUS_DISTANCE, navFocal)
                        }

                        navLockAF -> {
                            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                            set(CaptureRequest.LENS_FOCUS_DISTANCE, currentFocal)
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
            val metadataString = createMetadataString(result.metadata)
            val dngCreator = DngCreator(characteristicsP, result.metadata).apply {
                setOrientation(ExifInterface.ORIENTATION_ROTATE_90)
            }
            try {
                val currentFrame = numRecordedFrames.getAndIncrement()
                val outputImage =
                    createFile("IMG_${cameraName}_${String.format("%03d", currentFrame)}", "dng")
                val outputMetadata =
                    createFile("IMG_${cameraName}_${String.format("%03d", currentFrame)}", "bin")

                BufferedOutputStream(FileOutputStream(outputImage)).use {
                    dngCreator.writeImage(it, result.image)
                }
                BufferedOutputStream(FileOutputStream(outputMetadata)).use { outStream ->
                    outStream.write(metadataString.toByteArray())
                }
                Log.d(TAG, "Image saved: ${outputImage.absolutePath}")
            } catch (exc: IOException) {
                Log.e(TAG, "Unable to write DNG image to file", exc)
            }
        } else {
            val exc = RuntimeException("Unknown image format: ${result.image.format}")
            Log.e(TAG, exc.message, exc)
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

