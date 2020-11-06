/*
 * Copyright 2020 Google LLC. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.androidcamerard.processor

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import com.example.androidcamerard.R
import com.example.androidcamerard.camera.GraphicOverlay
import com.google.android.gms.tasks.Task
import com.google.android.material.chip.Chip
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.demo.kotlin.objectdetector.ObjectGraphic
import com.google.mlkit.vision.objects.DetectedObject
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.ObjectDetector
import com.google.mlkit.vision.objects.ObjectDetectorOptionsBase
import java.io.IOException

/** A processor to run object detector.  */
class ObjectDetectorProcessor(
    private val context: Context, options: ObjectDetectorOptionsBase,
    private val searchChip: Chip
) :
    VisionProcessorBase<List<DetectedObject>>(context) {

    private val detector: ObjectDetector = ObjectDetection.getClient(options)

    override fun stop() {
        super.stop()
        try {
            detector.close()
        } catch (e: IOException) {
            Log.e(
                TAG,
                "Exception thrown while trying to close object detector!",
                e
            )
        }
    }

    override fun detectInImage(image: InputImage): Task<List<DetectedObject>> {
        return detector.process(image)
    }

    @SuppressLint("SetTextI18n")
    override fun onSuccess(results: List<DetectedObject>, graphicOverlay: GraphicOverlay) {
        for (result in results) {
            graphicOverlay.add(ObjectGraphic(graphicOverlay, result))
        }
        if (results.isEmpty()) {
            searchChip.text = context.resources.getString(R.string.searching)
        }
        else {
            for (result in results) {
                if (result.labels.isNotEmpty()) {
                    for (label in result.labels) searchChip.text =
                        label.text + " - Confidence: " + "%.2f".format(label.confidence * 100)
                }
            }
        }
    }

    override fun onFailure(e: Exception) {
        Log.e(TAG, "Object detection failed!", e)
    }

    companion object {
        private const val TAG = "ObjectDetectorProcessor"
    }
}