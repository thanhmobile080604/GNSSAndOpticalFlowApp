package com.example.gnssandopticalflowapp.screen.dialog

import android.location.Location
import android.os.Bundle
import androidx.fragment.app.FragmentManager
import com.example.gnssandopticalflowapp.base.BaseDialogFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.DialogMap2dInformationBinding

class Map2DInformationDialog() :
    BaseDialogFragment<DialogMap2dInformationBinding>(DialogMap2dInformationBinding::inflate) {

    override fun DialogMap2dInformationBinding.initView() {
        val lat = arguments?.getDouble(KEY_LAT) ?: 0.0
        val lon = arguments?.getDouble(KEY_LON) ?: 0.0
        val speed = arguments?.getFloat(KEY_SPEED) ?: 0f
        val time = arguments?.getString(KEY_TIME) ?: ""

        tvLatitude.text = "Latitude: $lat"
        tvLongitude.text = "Longitude: $lon"
        tvTime.text = "Time: $time"
        tvVelocity.text = "Speed: $speed m/s"
    }

    override fun DialogMap2dInformationBinding.initListener() {
        ivCLose.setSingleClick {
            dismiss()
        }

        root.setSingleClick {
            dismiss()
        }

        bgParent.setSingleClick {

        }
    }

    override fun initObserver() {
        mainViewModel.currentLocation.observe(viewLifecycleOwner) { loc ->
            loc?.let {
                binding.tvLatitude.text = "Latitude: ${it.latitude}"
                binding.tvLongitude.text = "Longitude: ${it.longitude}"
                binding.tvVelocity.text = "Speed: ${it.speed} m/s"
            }
        }

        mainViewModel.currentTime.observe(viewLifecycleOwner) { time ->
            binding.tvTime.text = "Time: $time"
        }
    }

    companion object {
        private const val TAG = "Map2DInformationDialog"
        private const val KEY_LAT = "key_lat"
        private const val KEY_LON = "key_lon"
        private const val KEY_SPEED = "key_speed"
        private const val KEY_TIME = "key_time"

        fun showDialog(
            fragmentManager: FragmentManager,
            loc: Location,
            time: String
        ) {
            val dialog = Map2DInformationDialog().apply {
                arguments = Bundle().apply {
                    putDouble(KEY_LAT, loc.latitude)
                    putDouble(KEY_LON, loc.longitude)
                    putFloat(KEY_SPEED, loc.speed)
                    putString(KEY_TIME, time)
                }
            }
            dialog.show(fragmentManager, TAG)
        }
    }
}