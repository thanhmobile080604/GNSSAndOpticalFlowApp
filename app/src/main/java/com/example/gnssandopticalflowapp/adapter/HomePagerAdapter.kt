package com.example.gnssandopticalflowapp.adapter

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.example.gnssandopticalflowapp.screen.fragment.GnssViewerFragment
import com.example.gnssandopticalflowapp.screen.fragment.HomeOpticalFlowFragment

class HomePagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {

    override fun getItemCount(): Int = 2

    @RequiresApi(Build.VERSION_CODES.R)
    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> GnssViewerFragment()
            else -> HomeOpticalFlowFragment()
        }
    }
}

