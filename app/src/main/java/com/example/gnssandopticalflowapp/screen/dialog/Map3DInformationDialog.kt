package com.example.gnssandopticalflowapp.screen.dialog

import android.annotation.SuppressLint
import android.location.GnssStatus
import androidx.fragment.app.FragmentManager
import com.example.gnssandopticalflowapp.base.BaseDialogFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.DialogMap3dInformationBinding
import com.example.gnssandopticalflowapp.model.SatelliteInfo
import java.util.Locale

class Map3DInformationDialog :
    BaseDialogFragment<DialogMap3dInformationBinding>(DialogMap3dInformationBinding::inflate) {

    private var satelliteInfo: SatelliteInfo? = null
    private var totalSatellites: Int = 0

    override fun DialogMap3dInformationBinding.initView() {
        satelliteInfo?.let { satellite ->
            bindSatelliteInformation(
                satellite = satellite,
                totalSats = totalSatellites
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
            if (satellite.carrierFrequencyHz > 0) {
                "Carrier frequency: ${satellite.carrierFrequencyHz} Hz"
            } else {
                "Carrier frequency: N/A"
            }
        tvUsedInFix.text =
            "Usage status: ${if (satellite.usedInFix) "In use" else "Not in use"} | Source: ${satellite.positionSource}"
        tvLatitude.text = "Latitude: $formattedLatitude°"
        tvLongitude.text = "Longitude: $formattedLongitude°"
        tvAltitude.text = "Altitude: $formattedAltitude m"
        tvVelocity.text = satellite.ephemerisSource?.let {
            "Speed: $formattedSpeed | Ephemeris: $it"
        } ?: "Speed: $formattedSpeed"
    }

    override fun DialogMap3dInformationBinding.initListener() {
        ivCLose.setSingleClick {
            dismissAllowingStateLoss()
        }

        root.setSingleClick {
            dismissAllowingStateLoss()
        }

        bgParent.setSingleClick {
            // block click
        }
    }

    override fun initObserver() = Unit

    companion object {
        private const val TAG = "Map3DInformationDialog"

        fun showDialog(
            fragmentManager: FragmentManager,
            sat: SatelliteInfo,
            totalSats: Int
        ) {
            val dialog = Map3DInformationDialog().apply {
                satelliteInfo = sat
                totalSatellites = totalSats
            }
            dialog.show(fragmentManager, TAG)
        }
    }
}
