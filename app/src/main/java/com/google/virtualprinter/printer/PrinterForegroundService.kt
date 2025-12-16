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

package com.google.virtualprinter.printer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.virtualprinter.MainActivity
import com.google.virtualprinter.R


class PrinterForegroundService : Service() {
    private val TAG = "PrinterForegroundService"
    private val CHANNEL_ID = "PrinterServiceChannel"
    private val NOTIFICATION_ID = 1

    lateinit var printerService: PrinterService

    // Binder instance
    private val binder = LocalBinder()

    // Service started flag (for idempotency)
    private var isServiceStarted = false

    // Expose a local Binder that returns the service instance
    inner class LocalBinder : Binder() {
        fun getService(): PrinterForegroundService = this@PrinterForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate() called")
        instance = this

        // Initialize the printer service
        printerService = PrinterService(this)

        // Create notification channel
        createNotificationChannel()

        // Start as foreground service immediately
        startForeground(NOTIFICATION_ID, createNotification())

    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() called with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                Log.d(TAG, "Stop action received from notification")
                stopServiceGracefully()
                return START_NOT_STICKY
            }

            else -> {
                if (!isServiceStarted) {
                    startPrinterServiceInternal()
                }
            }
        }

        return START_STICKY // Restart service if killed by system
    }

    private fun startPrinterServiceInternal() {
        if (isServiceStarted) {
            return
        }

        isServiceStarted = true
        updateNotification(getString(R.string.app_name), getString(R.string.starting_printer_service))

        try {
            // Start the printer service
            printerService.startPrinterService(
                onSuccess = {
                    updateNotification(getString(R.string.printer_active), getString(R.string.ready_to_receive))
                },
                onError = { error ->
                    updateNotification(getString(R.string.printer_error), error)
                }
            )
        } catch (e: Exception) {
            updateNotification(getString(R.string.printer_error), e.message ?: getString(R.string.unknown_error))
        }
    }
    override fun onBind(intent: Intent?): IBinder {
        startPrinterServiceInternal()
        return binder
    }

    override fun onDestroy() {
        instance = null
        Log.d(TAG, "onDestroy() called - cleaning up resources")
        cleanupResources()
        super.onDestroy()
    }

    override fun onTaskRemoved(intent: Intent?) {
        Log.d(TAG, "onTaskRemoved() called - app swiped away from recent apps")
        // Cleanup mDNS records when app is swiped away
        cleanupResources()

        // Stop the service
        stopSelf()
        super.onTaskRemoved(intent)
    }

    fun stopPrinterServer() {
        if (!isServiceStarted) return
        try {
            printerService.stopPrinterService()
        } catch (e: Exception) {
        }
        isServiceStarted = false
        updateNotification(getString(R.string.printer_error), getString(R.string.stopped))
    }

    private fun cleanupResources() {
        try {
            stopPrinterServer()
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }

    private fun stopServiceGracefully() {
        Log.d(TAG, "Stopping service gracefully")

        try {
            // Cleanup resources first
            cleanupResources()

            // Stop foreground state
            stopForeground(STOP_FOREGROUND_REMOVE)

            // Stop the service
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping service gracefully", e)
            stopSelf()
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Printer Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Virtual Printer Service Status"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(
        title: String = getString(R.string.app_name),
        content: String = getString(R.string.starting_printer_service)
    ): Notification {

        val printerName = printerService.getPrinterName()
        val printerStatus = when (printerService.getServiceStatus()) {
            PrinterService.ServiceStatus.RUNNING -> getString(R.string.status_running)
            PrinterService.ServiceStatus.ERROR_SIMULATION -> getString(R.string.status_error_mode)
            PrinterService.ServiceStatus.STARTING -> getString(R.string.status_starting)
            PrinterService.ServiceStatus.STOPPED -> getString(R.string.status_stopped)
            else -> getString(R.string.status_unknown)
        }
        // Intent to open the app when notification is tapped
        val notificationIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Stop action
        val stopIntent = Intent(this, PrinterForegroundService::class.java).apply {
            action = ACTION_STOP_SERVICE
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(printerName)
            .setContentText(printerStatus)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.stop),
                stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setAutoCancel(false)
            .build()
    }

    private fun updateNotification(title: String, content: String) {
        try {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification(title, content))
        } catch (e: Exception) {
            Log.e(TAG, "Error updating notification", e)
        }
    }

    fun getServiceStatus(): PrinterService.ServiceStatus {
        return printerService.getServiceStatus()
    }

    fun getPrinterServiceInstance(): PrinterService = printerService

    companion object {
        private var instance: PrinterForegroundService? = null

        fun getInstance(): PrinterForegroundService? = instance

        const val ACTION_STOP_SERVICE = "com.google.virtualprinter.STOP_SERVICE"

        fun startService(context: Context) {
            val intent = Intent(context, PrinterForegroundService::class.java)
            try {
                context.startForegroundService(intent)
                Log.d("PrinterForegroundService", "Service start requested")
            } catch (e: Exception) {
                Log.e("PrinterForegroundService", "Error starting service", e)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, PrinterForegroundService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            try {
                context.startForegroundService(intent)
                Log.d("PrinterForegroundService", "Service start requested")
            } catch (e: Exception) {
                Log.e("PrinterForegroundService", "Error starting service", e)
            }
        }
    }
}