/*
 * Copyright 2025 The Virtual Printer Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.virtualprinter

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ViewModel for managing printer UI state
 * 
 * This ViewModel replaces the static companion object pattern in MainActivity
 * to prevent memory leaks and ensure proper lifecycle management.
 */
class PrinterViewModel : ViewModel() {
    
    /**
     * Trigger for refreshing the file list
     * Incremented whenever a new print job is received
     */
    private val _refreshTrigger = MutableStateFlow(0)
    val refreshTrigger: StateFlow<Int> = _refreshTrigger.asStateFlow()
    
    /**
     * Trigger a refresh of the print jobs list
     * Called when a new print job broadcast is received
     */
    fun triggerRefresh() {
        _refreshTrigger.value++
    }
    
    override fun onCleared() {
        super.onCleared()
        // Clean up any resources if needed
    }
}

