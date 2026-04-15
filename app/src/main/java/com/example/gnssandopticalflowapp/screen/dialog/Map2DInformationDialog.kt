package com.example.gnssandopticalflowapp.screen.dialog

import android.annotation.SuppressLint
import android.location.Location
import androidx.fragment.app.FragmentManager
import com.example.gnssandopticalflowapp.base.BaseDialogFragment
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.databinding.DialogMap2dInformationBinding

@SuppressLint("SetTextI18n")
class Map2DInformationDialog :
    BaseDialogFragment<DialogMap2dInformationBinding>(DialogMap2dInformationBinding::inflate) {

    private var latitude: Double = 0.0
    private var longitude: Double = 0.0
    private var speed: Float = 0f
    private var time: String = ""

    override fun DialogMap2dInformationBinding.initView() {
        tvLatitude.text = "Latitude: $latitude"
        tvLongitude.text = "Longitude: $longitude"
        tvTime.text = "Time: $time"
        tvVelocity.text = "Speed: $speed m/s"
    }

    override fun DialogMap2dInformationBinding.initListener() {
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

    override fun initObserver() {
        mainViewModel.currentLocation.observe(viewLifecycleOwner) { loc ->
            loc?.let {
                binding.tvLatitude.text = "Latitude: ${it.latitude}"
                binding.tvLongitude.text = "Longitude: ${it.longitude}"
                binding.tvVelocity.text = "Speed: ${it.speed} m/s"
            }
        }

        mainViewModel.currentTime.observe(viewLifecycleOwner) { currentTime ->
            binding.tvTime.text = "Time: $currentTime"
        }
    }

    companion object {
        private const val TAG = "Map2DInformationDialog"

        fun showDialog(
            fragmentManager: FragmentManager,
            loc: Location,
            time: String
        ) {
            val dialog = Map2DInformationDialog().apply {
                latitude = loc.latitude
                longitude = loc.longitude
                speed = loc.speed
                this.time = time
            }
            dialog.show(fragmentManager, TAG)
        }
    }
}