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

package info.ilyac.pani.utils

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView
import kotlin.math.roundToInt

/**
 * A [SurfaceView] that can be adjusted to a specified aspect ratio and
 * performs center-crop transformation of input frames.
 */
class AutoFitSurfaceView @JvmOverloads constructor(
        context: Context,
        attrs: AttributeSet? = null,
        defStyle: Int = 0
) : SurfaceView(context, attrs, defStyle) {

    private var aspectRatio = 0f

    /**
     * Sets the aspect ratio for this view. The size of the view will be
     * measured based on the ratio calculated from the parameters.
     *
     * @param width  Camera resolution horizontal size
     * @param height Camera resolution vertical size
     */
    fun setAspectRatio(width: Int, height: Int) {
        require(width > 0 && height > 0) { "Size cannot be negative" }
        aspectRatio = width.toFloat() / height.toFloat()
        holder.setFixedSize(width, height)
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
//        val width = MeasureSpec.getSize(widthMeasureSpec)
//        val height = MeasureSpec.getSize(heightMeasureSpec)
//        if (aspectRatio == 0f) {
//            setMeasuredDimension(width, height)
//        } else {
//
//            // Performs center-crop transformation of the camera frames
//            val newWidth: Int
//            val newHeight: Int
//            val actualRatio = if (width > height) aspectRatio else 1f / aspectRatio
//
//            newHeight = height
//            newWidth = (height * actualRatio).roundToInt()
//
////            if (width < height * actualRatio) {
////                newHeight = height
////                newWidth = (height * actualRatio).roundToInt()
////            } else {
////                newWidth = width
////                newHeight = (width / actualRatio).roundToInt()
////            }
//
//            Log.d(TAG, "Measured dimensions set: $newWidth x $newHeight")
////            setMeasuredDimension(1920, 1600)
//        }
    }

    companion object {
        private val TAG = AutoFitSurfaceView::class.java.simpleName
    }
}
