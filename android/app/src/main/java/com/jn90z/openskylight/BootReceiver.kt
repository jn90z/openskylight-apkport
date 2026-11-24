package com.jn90z.openskylight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!context.getSharedPreferences("android-platform", Context.MODE_PRIVATE).getBoolean("launchAtBoot", false)) return
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val defaultHome = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        if (defaultHome?.activityInfo?.packageName == context.packageName) {
            context.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else if (android.os.Build.VERSION.SDK_INT <= 28) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
