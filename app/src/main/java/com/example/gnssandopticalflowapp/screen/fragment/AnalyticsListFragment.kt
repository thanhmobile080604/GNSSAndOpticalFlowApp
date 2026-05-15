package com.example.gnssandopticalflowapp.screen.fragment

import androidx.recyclerview.widget.LinearLayoutManager
import com.example.gnssandopticalflowapp.R
import com.example.gnssandopticalflowapp.adapter.AnalyticsSessionAdapter
import com.example.gnssandopticalflowapp.base.BaseFragment
import com.example.gnssandopticalflowapp.common.hide
import com.example.gnssandopticalflowapp.common.safeContext
import com.example.gnssandopticalflowapp.common.setSingleClick
import com.example.gnssandopticalflowapp.common.show
import com.example.gnssandopticalflowapp.databinding.FragmentAnalyticsListBinding
import com.example.gnssandopticalflowapp.model.AnalyticsSessionSummary
import com.example.gnssandopticalflowapp.util.AnalyticsStorageUtil

class AnalyticsListFragment :
    BaseFragment<FragmentAnalyticsListBinding>(FragmentAnalyticsListBinding::inflate) {

    private lateinit var adapter: AnalyticsSessionAdapter

    override fun FragmentAnalyticsListBinding.initView() {
        adapter = AnalyticsSessionAdapter(::openSession)
        rcvAnalytics.layoutManager = LinearLayoutManager(safeContext())
        rcvAnalytics.adapter = adapter
        loadSessions()
    }

    override fun FragmentAnalyticsListBinding.initListener() {
        ivBack.setSingleClick {
            onBack()
        }
    }

    override fun initObserver() {
        mainViewModel.analyticsLibraryUpdated.observe(viewLifecycleOwner) {
            loadSessions()
        }
    }

    private fun loadSessions() {
        val sessions = AnalyticsStorageUtil.getSessionSummaries(safeContext())
        adapter.setData(sessions)
        if (sessions.isEmpty()) {
            binding.tvEmpty.show()
        } else {
            binding.tvEmpty.hide()
        }
    }

    private fun openSession(session: AnalyticsSessionSummary) {
        mainViewModel.selectedAnalyticsSessionId.value = session.id
        navigateTo(R.id.analyticsViewFragment)
    }
}
