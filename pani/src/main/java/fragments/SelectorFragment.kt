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
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.android.camera.utils.GenericListAdapter
import com.android.pani.R
import fragmentargs.SelectorFragmentDirections

class SelectorFragment : Fragment() {

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View = RecyclerView(requireContext())

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view as RecyclerView
        view.apply {
            layoutManager = LinearLayoutManager(requireContext())

            val cameraManager =
                    requireContext().getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraList = enumerateCameras(cameraManager)

            lifecycleScope.launchWhenStarted {
                Navigation.findNavController(requireActivity(), R.id.fragment_container).navigate(
                    SelectorFragmentDirections.actionSelectorToCamera(cameraList.first().cameraId, cameraList.first().format))
            }

//            val layoutId = android.R.layout.simple_list_item_1
//            adapter = GenericListAdapter(cameraList, itemLayoutId = layoutId) { view, item, _ ->
//                view.findViewById<TextView>(android.R.id.text1).text = item.title
//                view.setOnClickListener {
//                    Navigation.findNavController(requireActivity(), R.id.fragment_container)
//                            .navigate(SelectorFragmentDirections.actionSelectorToCamera(
//                                    item.cameraId, item.format))
//                }
//            }
        }

    }

    companion object {

        /** Helper class used as a data holder for each selectable camera format item */
        private data class FormatItem(val title: String, val cameraId: String, val format: Int)

        /** Helper function used to convert a lens orientation enum into a human-readable string */
        private fun lensOrientationString(value: Int) = when(value) {
            CameraCharacteristics.LENS_FACING_BACK -> "Back"
            CameraCharacteristics.LENS_FACING_FRONT -> "Front"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
            else -> "Unknown"
        }

        /** Helper function used to list all compatible cameras and supported pixel formats */
        @SuppressLint("InlinedApi")
        private fun enumerateCameras(cameraManager: CameraManager): List<FormatItem> {
            val availableCameras: MutableList<FormatItem> = mutableListOf()

            val characteristics: CameraCharacteristics = cameraManager.getCameraCharacteristics("2")
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            if (sensorSize != null) {
                val width = sensorSize.width
                val height = sensorSize.height
                Log.d("Camera Dimensions", width.toString())
                Log.d("Camera Dimensions", height.toString())
            }

            // Get list of all compatible cameras
            val cameraIds = cameraManager.cameraIdList.filter {
                val characteristics = cameraManager.getCameraCharacteristics(it)
                val capabilities = characteristics.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                capabilities?.contains(
                        CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false
            }

            // Iterate over the list of cameras and return all the compatible ones
            cameraIds.forEach { id ->
                val characteristics = cameraManager.getCameraCharacteristics(id)
                val orientation = lensOrientationString(
                        characteristics.get(CameraCharacteristics.LENS_FACING)!!)

                // Query the available capabilities and output formats
                val capabilities = characteristics.get(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)!!
                val outputFormats = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!.outputFormats

                // Return back cameras with RAW
                if (capabilities.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) &&
                    outputFormats.contains(ImageFormat.RAW_SENSOR) &&
                    orientation == lensOrientationString(CameraCharacteristics.LENS_FACING_BACK)) {
                    availableCameras.add(FormatItem(
                            "$orientation RAW ($id)", id, ImageFormat.RAW_SENSOR))
                }
            }

            return availableCameras
        }
    }
}
