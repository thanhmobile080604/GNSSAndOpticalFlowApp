package com.example.gnssandopticalflowapp.screen.dialog

import android.annotation.SuppressLint
import android.location.GnssStatus
import android.os.Bundle
import androidx.fragment.app.FragmentManager
import com.example.gnssandopticalflowapp.base.BaseDialogFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.DialogMap3dInformationBinding
import com.example.gnssandopticalflowapp.model.SatelliteInfo
import java.util.Locale

class Map3DInformationDialog :
    BaseDialogFragment<DialogMap3dInformationBinding>(DialogMap3dInformationBinding::inflate) {

    override fun DialogMap3dInformationBinding.initView() {
        val sat = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            arguments?.getSerializable(KEY_SAT, SatelliteInfo::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getSerializable(KEY_SAT) as? SatelliteInfo
        }
        val totalSats = arguments?.getInt(KEY_TOTAL_SATS) ?: 0

        sat?.let {
            bindSatelliteInformation(
                satellite = it,
                totalSats = totalSats
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun DialogMap3dInformationBinding.bindSatelliteInformation(
        satellite: SatelliteInfo,
        totalSats: Int
    ) {
        val constellation = when (satellite.constellationType) {
            GnssStatus.CONSTELLATION_GPS -> "GPS"
            GnssStatus.CONSTELLATION_SBAS -> "SBAS"
            GnssStatus.CONSTELLATION_GLONASS -> "GLONASS"
            GnssStatus.CONSTELLATION_QZSS -> "QZSS"
            GnssStatus.CONSTELLATION_BEIDOU -> "BeiDou"
            GnssStatus.CONSTELLATION_GALILEO -> "Galileo"
            GnssStatus.CONSTELLATION_IRNSS -> "IRNSS"
            else -> "Unknown"
        }

        val formattedLatitude = String.format(Locale.getDefault(), "%.4f", satellite.latitude)
        val formattedLongitude = String.format(Locale.getDefault(), "%.4f", satellite.longitude)
        val formattedAltitude = String.format(Locale.getDefault(), "%,.0f", satellite.altitude)
        val formattedSpeed = String.format(
            Locale.getDefault(),
            "%,.1f km/s (%,.0f km/h)",
            satellite.speed / 1000.0,
            satellite.speed * 3.6
        )

        tvTotalSats.text = "Total satellites: $totalSats"
        tvSvid.text = "SVID: ${satellite.svid}"
        tvConstellation.text = "Constellation: $constellation"
        tvSignalStrength.text = "Signal strength (Cn0DbHz): ${satellite.cn0DbHz}"
        tvElevation.text = "Elevation: ${satellite.elevationDegrees}°"
        tvAzimuth.text = "Azimuth: ${satellite.azimuthDegrees}°"
        tvCarrierFrequency.text =
            "Carrier frequency: ${
                if (satellite.carrierFrequencyHz > 0) {
                    "${satellite.carrierFrequencyHz} Hz"
                } else {
                    "N/A"
                }
            }"
        tvUsedInFix.text =
            "Usage status: ${if (satellite.usedInFix) "In use" else "Not in use"}"
        tvLatitude.text = "Latitude: $formattedLatitude°"
        tvLongitude.text = "Longitude: $formattedLongitude°"
        tvAltitude.text = "Altitude: $formattedAltitude m"
        tvVelocity.text = "Speed: $formattedSpeed"
    }

    override fun DialogMap3dInformationBinding.initListener() {
        ivCLose.setSingleClick {
            dismissAllowingStateLoss()
        }

        root.setSingleClick {
            dismissAllowingStateLoss()
        }

        bgParent.setSingleClick {
        }
    }

    override fun initObserver() = Unit

    companion object {
        private const val TAG = "Map3DInformationDialog"
        private const val KEY_SAT = "key_sat"
        private const val KEY_TOTAL_SATS = "key_total_sats"

        fun showDialog(
            fragmentManager: FragmentManager,
            sat: SatelliteInfo,
            totalSats: Int
        ) {
            val dialog = Map3DInformationDialog().apply {
                arguments = Bundle().apply {
                    putSerializable(KEY_SAT, sat)
                    putInt(KEY_TOTAL_SATS, totalSats)
                }
            }
            dialog.show(fragmentManager, TAG)
        }
    }
}