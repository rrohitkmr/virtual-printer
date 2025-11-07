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

package com.google.virtualprinter.utils

import android.content.Context
import android.content.SharedPreferences

/**
 * Utility class for managing app preferences
 */
object PreferenceUtils {
    private const val PREFS_NAME = "printer_preferences"
    private const val KEY_PRINTER_NAME = "printer_name"
    private const val DEFAULT_PRINTER_NAME = "Android Virtual Printer"
    
    /**
     * Gets the user-defined printer name or the default name if not set
     */
    fun getCustomPrinterName(context: Context): String {
        val prefs = getPreferences(context)
        return prefs.getString(KEY_PRINTER_NAME, DEFAULT_PRINTER_NAME) ?: DEFAULT_PRINTER_NAME
    }
    
    /**
     * Saves a custom printer name
     */
    fun saveCustomPrinterName(context: Context, name: String) {
        val prefs = getPreferences(context)
        prefs.edit().putString(KEY_PRINTER_NAME, name).apply()
    }
    
    /**
     * Gets the shared preferences instance
     */
    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
} 