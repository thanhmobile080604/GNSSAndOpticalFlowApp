package com.example.gnssandopticalflowapp.screen.fragment

import android.widget.Toast
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

    private enum class Mode {
        NORMAL, EDIT
    }

    private var currentMode = Mode.NORMAL

    override fun FragmentAnalyticsListBinding.initView() {
        adapter = AnalyticsSessionAdapter(::openSession) {
            updateToolbarState()
        }
        rcvAnalytics.layoutManager = LinearLayoutManager(safeContext())
        rcvAnalytics.adapter = adapter
        (rcvAnalytics.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        loadSessions()
        updateToolbarState()
    }

    override fun FragmentAnalyticsListBinding.initListener() {
        ivBack.setSingleClick {
            if (currentMode == Mode.EDIT) {
                exitEditMode()
            } else {
                onBack()
            }
        }

        tvEdit.setSingleClick {
            enterEditMode()
        }

        tvCancel.setSingleClick {
            exitEditMode()
        }

        ivTrash.setSingleClick {
            deleteSelectedSessions()
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

    private fun enterEditMode() {
        currentMode = Mode.EDIT
        adapter.setEditMode(true)
        updateToolbarState()
    }

    private fun exitEditMode() {
        currentMode = Mode.NORMAL
        adapter.setEditMode(false)
        updateToolbarState()
    }

    private fun deleteSelectedSessions() {
        val selectedSessions = adapter.getSelectedSessions()
        if (selectedSessions.isEmpty()) {
            Toast.makeText(safeContext(), "Select files to delete", Toast.LENGTH_SHORT).show()
            return
        }

        val deletedCount = AnalyticsStorageUtil.deleteSessions(safeContext(), selectedSessions)
        adapter.removeSessions(selectedSessions)
        Toast.makeText(safeContext(), "Deleted $deletedCount item(s)", Toast.LENGTH_SHORT).show()

        if (adapter.itemCount == 0) {
            exitEditMode()
            binding.tvEmpty.show()
        } else {
            updateToolbarState()
        }
    }

    private fun updateToolbarState() {
        when (currentMode) {
            Mode.NORMAL -> updateNormalToolbar()
            Mode.EDIT -> updateEditToolbar()
        }
    }

    private fun updateNormalToolbar() = with(binding) {
        ivBack.show()
        tvCancel.hide()
        ivTrash.hide()
        
        if (adapter.itemCount > 0) {
            tvEdit.show()
        } else {
            tvEdit.hide()
        }
    }

    private fun updateEditToolbar() = with(binding) {
        val hasSelection = adapter.getSelectedSessions().isNotEmpty()
        val actionAlpha = if (hasSelection) 1f else 0.45f

        ivBack.hide()
        tvCancel.show()
        tvEdit.hide()
        ivTrash.show()
        ivTrash.alpha = actionAlpha
    }
}
