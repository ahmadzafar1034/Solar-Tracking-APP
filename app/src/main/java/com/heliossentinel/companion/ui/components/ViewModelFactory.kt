package com.heliossentinel.companion.ui.components

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Tiny generic factory so screens can construct their ViewModel with a
 * [com.heliossentinel.companion.data.repository.TrackerRepository] dependency
 * without pulling in a DI framework.
 */
class SimpleViewModelFactory<T : ViewModel>(private val creator: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = creator() as VM
}
