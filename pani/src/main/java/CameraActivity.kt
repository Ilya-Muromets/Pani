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

package info.ilyac.pani

import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment
import info.ilyac.pani.databinding.ActivityCameraBinding
import info.ilyac.pani.fragments.CameraFragment

class CameraActivity : AppCompatActivity() {

    private lateinit var activityCameraBinding: ActivityCameraBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityCameraBinding = ActivityCameraBinding.inflate(layoutInflater)
        setContentView(activityCameraBinding.root)
    }

    override fun onResume() {
        super.onResume()
        // Hide system bars
        activityCameraBinding.fragmentContainer.windowInsetsController?.let {
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            it.hide(WindowInsets.Type.systemBars())
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val cameraFragment = getCurrentCameraFragment()
            if (cameraFragment != null) {
                cameraFragment.startBurstCapture()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            val cameraFragment = getCurrentCameraFragment()
            if (cameraFragment != null) {
                cameraFragment.stopBurstCapture()
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun getCurrentCameraFragment(): CameraFragment? {
        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.fragment_container) as? NavHostFragment
        return navHostFragment?.childFragmentManager?.primaryNavigationFragment as? CameraFragment
    }
}