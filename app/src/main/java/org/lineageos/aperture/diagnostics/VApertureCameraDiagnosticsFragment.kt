/*
 * SPDX-FileCopyrightText: 2026 VAperture Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.aperture.diagnostics

import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.lineageos.aperture.R
import org.lineageos.aperture.SettingsActivity
import java.io.IOException

/** Read-only Camera2 diagnostics; does not open a camera capture session. */
class VApertureCameraDiagnosticsFragment : SettingsActivity.SettingsFragment(
    R.xml.va_camera_diagnostics_preferences
) {
    private var report: JSONObject? = null

    private val saveReport = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri == null) return@registerForActivityResult
        val currentReport = report ?: return@registerForActivityResult
        val succeeded = try {
            val stream = requireContext().contentResolver.openOutputStream(uri)
                ?: throw IOException("Cannot open the selected destination")
            stream.bufferedWriter(Charsets.UTF_8).use { it.write(currentReport.toString(2)) }
            true
        } catch (e: Exception) {
            false
        }
        Toast.makeText(
            requireContext(),
            if (succeeded) R.string.va_camera_diagnostics_export_success
            else R.string.va_camera_diagnostics_export_failed,
            Toast.LENGTH_SHORT,
        ).show()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)

        findPreference<Preference>("va_camera_diagnostics_device")?.summary =
            "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) • " +
                "Android ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}"

        findPreference<Preference>("va_camera_diagnostics_export")?.apply {
            isEnabled = false
            setOnPreferenceClickListener {
                saveReport.launch("vaperture_camera_diagnostics.json")
                true
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val collected = withContext(Dispatchers.IO) {
                    VApertureCameraCapabilityCollector.collect(requireContext().applicationContext)
                }
                report = collected.json
                findPreference<Preference>("va_camera_diagnostics_status")?.summary =
                    "${collected.cameras.size} camera(s) inspected"
                findPreference<Preference>("va_camera_diagnostics_export")?.isEnabled = true

                val cameraCategory = findPreference<PreferenceCategory>(
                    "va_camera_diagnostics_cameras"
                ) ?: return@launch
                cameraCategory.removeAll()
                collected.cameras.forEach { camera ->
                    cameraCategory.addPreference(Preference(requireContext()).apply {
                        title = "Camera ${camera.id} • ${camera.facing} • ${camera.hardwareLevel}"
                        summary = "ISO ${camera.isoRange} • Exposure ${camera.exposureRange} • " +
                            "RAW: ${camera.rawSupported} • Vendor keys: ${camera.vendorKeyCount}"
                        isIconSpaceReserved = false
                        setOnPreferenceClickListener {
                            AlertDialog.Builder(requireContext())
                                .setTitle("Camera ${camera.id} — details")
                                .setMessage(camera.details.toString(2))
                                .setPositiveButton(android.R.string.ok, null)
                                .show()
                            true
                        }
                    })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                findPreference<Preference>("va_camera_diagnostics_status")?.summary =
                    "${getString(R.string.va_camera_diagnostics_error)}: " +
                        e.message.orEmpty()
            }
        }
    }
}
