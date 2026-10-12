/*
 * SPDX-FileCopyrightText: 2026 VAperture Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture.diagnostics

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Reads metadata exposed to this app through public Camera2 APIs. */
internal object VApertureCameraCapabilityCollector {
    data class CameraEntry(
        val id: String,
        val facing: String,
        val hardwareLevel: String,
        val isoRange: String,
        val exposureRange: String,
        val rawSupported: Boolean,
        val vendorKeyCount: Int,
        val details: JSONObject,
    )

    data class Report(
        val json: JSONObject,
        val cameras: List<CameraEntry>,
    )

    fun collect(context: Context): Report {
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val device = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("board", Build.BOARD)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdk", Build.VERSION.SDK_INT)
        }
        val cameraArray = JSONArray()
        val entries = mutableListOf<CameraEntry>()
        val report = JSONObject().apply {
            put("schemaVersion", 1)
            put("generator", "VAperture Camera Diagnostics")
            put("generatedAtUtc", utcTimestamp())
            put("device", device)
            put("cameras", cameraArray)
        }

        for (cameraId in cameraManager.cameraIdList) {
            try {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = facingName(characteristics.get(CameraCharacteristics.LENS_FACING))
                val level = hardwareLevelName(
                    characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                )
                val iso = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                val exposure = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                val minFocus = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
                val capabilities = characteristics.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES
                ) ?: intArrayOf()
                val rawSupported = capabilities.contains(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW
                )

                val characteristicVendorKeys = characteristics.keys.map { it.name }
                    .filter(::isVendorKey).sorted()
                val requestVendorKeys = characteristics.availableCaptureRequestKeys.map { it.name }
                    .filter(::isVendorKey).sorted()
                val resultVendorKeys = characteristics.availableCaptureResultKeys.map { it.name }
                    .filter(::isVendorKey).sorted()
                val sessionVendorKeys = characteristics.availableSessionKeys.orEmpty().map { it.name }
                    .filter(::isVendorKey).sorted()
                val uniqueVendorKeys = (
                    characteristicVendorKeys + requestVendorKeys +
                        resultVendorKeys + sessionVendorKeys
                    ).distinct().sorted()
                val vendorKeys = JSONObject().apply {
                    put("characteristics", characteristicVendorKeys.toJsonArray())
                    put("captureRequests", requestVendorKeys.toJsonArray())
                    put("captureResults", resultVendorKeys.toJsonArray())
                    put("session", sessionVendorKeys.toJsonArray())
                    put("uniqueNames", uniqueVendorKeys.toJsonArray())
                }

                val streamMap = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
                )
                val outputSizes = JSONObject().apply {
                    listOf(
                        "JPEG" to ImageFormat.JPEG,
                        "YUV_420_888" to ImageFormat.YUV_420_888,
                        "RAW_SENSOR" to ImageFormat.RAW_SENSOR,
                        "RAW10" to ImageFormat.RAW10,
                        "PRIVATE" to ImageFormat.PRIVATE,
                    ).forEach { (name, format) ->
                        val sizes = runCatching { streamMap?.getOutputSizes(format) }
                            .getOrNull().orEmpty()
                        put(name, JSONArray().apply {
                            sizes.forEach { size ->
                                put(JSONObject().apply {
                                    put("width", size.width)
                                    put("height", size.height)
                                })
                            }
                        })
                    }
                }

                val details = JSONObject().apply {
                    put("id", cameraId)
                    put("lensFacing", facing)
                    put("hardwareLevel", level)
                    put("logicalMultiCamera", capabilities.contains(
                        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA
                    ))
                    put("physicalCameraIds", characteristics.physicalCameraIds.sorted().toJsonArray())
                    put("capabilities", capabilities.map(::capabilityName).toJsonArray())
                    put("isoRange", iso?.let {
                        JSONObject().put("min", it.lower).put("max", it.upper)
                    } ?: JSONObject.NULL)
                    put("exposureTimeRangeNs", exposure?.let {
                        JSONObject().put("min", it.lower).put("max", it.upper)
                    } ?: JSONObject.NULL)
                    put("minimumFocusDistanceDiopters", minFocus ?: JSONObject.NULL)
                    put("rawSupported", rawSupported)
                    put("outputSizes", outputSizes)
                    put("vendorKeys", vendorKeys)
                }
                cameraArray.put(details)
                entries += CameraEntry(
                    id = cameraId,
                    facing = facing,
                    hardwareLevel = level,
                    isoRange = iso?.let { "${it.lower}–${it.upper}" } ?: "Not reported",
                    exposureRange = exposure?.let {
                        "${it.lower / 1_000_000_000.0}–${it.upper / 1_000_000_000.0}s"
                    } ?: "Not reported",
                    rawSupported = rawSupported,
                    vendorKeyCount = uniqueVendorKeys.size,
                    details = details,
                )
            } catch (e: Exception) {
                // Continue scanning other cameras even if one HAL query fails.
                cameraArray.put(JSONObject().apply {
                    put("id", cameraId)
                    put("error", "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                })
            }
        }

        report.put("cameraCount", cameraArray.length())
        return Report(report, entries)
    }

    private fun utcTimestamp(): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())

    private fun isVendorKey(name: String): Boolean = !name.startsWith("android.")

    private fun List<String>.toJsonArray(): JSONArray = JSONArray().apply {
        this@toJsonArray.forEach { put(it) }
    }

    private fun facingName(value: Int?): String = when (value) {
        CameraCharacteristics.LENS_FACING_BACK -> "BACK"
        CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun hardwareLevelName(value: Int?): String = when (value) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        else -> "UNKNOWN"
    }

    private fun capabilityName(value: Int): String = when (value) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA -> "LOGICAL_MULTI_CAMERA"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR -> "ULTRA_HIGH_RESOLUTION_SENSOR"
        else -> "CAPABILITY_$value"
    }
}
