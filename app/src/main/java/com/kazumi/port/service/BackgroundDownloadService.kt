package com.kazumi.port.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.kazumi.port.R

/**
 * 后台下载前台服务
 *
 * 对应原版 lib/services/download/background_download_service.dart。
 *
 * 职责（与原版一致，下载逻辑本身由 [DownloadManager] 运行）：
 * 1. 显示通知栏进度（进行中 / 暂停）
 * 2. 保持进程存活，防止系统在后台杀死下载进程
 * 3. 通知栏交互（「暂停全部」按钮、点击跳转下载页）
 */
class BackgroundDownloadService : Service() {

    companion object {
        private const val CHANNEL_ID = "kazumi_download_channel"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_PAUSE_ALL = "com.kazumi.port.action.PAUSE_ALL"
        private const val ACTION_STOP = "com.kazumi.port.action.STOP_DOWNLOAD_SERVICE"

        /** 通知栏「暂停全部」回调（由下载页/管理器注册） */
        @Volatile
        var onPauseAll: (() -> Unit)? = null

        @Volatile
        private var running = false

        val isRunning: Boolean get() = running

        fun start(context: Context) {
            val intent = Intent(context, BackgroundDownloadService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BackgroundDownloadService::class.java)
                .setAction(ACTION_STOP)
            context.startService(intent)
        }

        /** 通知栏按钮点击（暂停全部） */
        fun notifyPauseAll(context: Context) {
            val intent = Intent(context, BackgroundDownloadService::class.java)
                .setAction(ACTION_PAUSE_ALL)
            context.startService(intent)
        }

        /** 静态更新通知（供 DownloadController 跨实例调用） */
        fun updateProgress(
            context: Context,
            activeCount: Int,
            totalCount: Int,
            overallProgress: Double,
            speedText: String
        ) {
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (activeCount == 0) {
                val paused = NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_launcher_foreground)
                    .setContentTitle("下载已暂停")
                    .setContentText("共 $totalCount 个任务")
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .build()
                manager.notify(NOTIFICATION_ID, paused)
                return
            }
            val percent = (overallProgress * 100).toInt()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("正在下载 ($activeCount/$totalCount)")
                .setContentText("$percent% · $speedText")
                .setProgress(100, percent, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_ALL -> {
                onPauseAll?.invoke()
                return START_STICKY
            }
            ACTION_STOP -> {
                stopForegroundCompat()
                stopSelf()
                running = false
                return START_NOT_STICKY
            }
        }

        // 启动前台服务并显示初始通知
        val notification = buildNotification(
            title = "正在下载",
            text = "准备中...",
            progress = null,
            paused = false
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    /**
     * 更新通知栏进度。
     * 对应原版 updateProgress()：
     * - activeCount == 0 -> 标题「下载已暂停」
     * - 否则 -> 「正在下载 (active/total)」+ 百分比与速度
     */
    fun updateProgress(
        activeCount: Int,
        totalCount: Int,
        overallProgress: Double,
        speedText: String
    ) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (activeCount == 0) {
            val paused = buildNotification(
                title = "下载已暂停",
                text = "共 $totalCount 个任务",
                progress = null,
                paused = true
            )
            manager.notify(NOTIFICATION_ID, paused)
            return
        }

        val percent = (overallProgress * 100).toInt()
        val notification = buildNotification(
            title = "正在下载 ($activeCount/$totalCount)",
            text = "$percent% · $speedText",
            progress = overallProgress,
            paused = false
        )
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Double?,
        paused: Boolean
    ): Notification {
        // 点击通知跳转主界面（下载页由 MainActivity 导航处理）
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 「暂停全部」按钮
        val pauseIntent = Intent(this, BackgroundDownloadService::class.java)
            .setAction(ACTION_PAUSE_ALL)
        val pausePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "暂停全部", pausePendingIntent)

        if (progress != null) {
            builder.setProgress(100, (progress * 100).toInt(), false)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "下载服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "视频下载后台服务"
        }
        manager.createNotificationChannel(channel)
    }

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }
}