package com.kazumi.port.ui.components

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** 主线程 Handler，保证 Toast 一定在主线程弹出 */
private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

/** 简易 Toast 封装（对应原版 KazumiDialog.showToast） */
@Composable
fun rememberToast(): (String) -> Unit {
    val context = LocalContext.current.applicationContext
    return { message ->
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        } else {
            mainHandler.post {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}