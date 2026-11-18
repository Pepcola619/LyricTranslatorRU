package io.github.custom.lyrictranslator

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

class MainHook : InvocationHandler {

    companion object {
        private const val ACTION_TOGGLE_TRANSLATION =
            "io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION"

        @Volatile
        var hasActiveTranslation = false
    }

    override fun invoke(proxy: Any?, method: Method?, args: Array<out Any?>?): Any? {
        if (method?.name == "handleLoadPackage" && args != null && args.isNotEmpty()) {
            val lpparam = args.get(0) ?: return null
            val pkgName = lpparam.javaClass.getField("packageName").get(lpparam) as? String
            if (pkgName == "android") {
                installHooks()
            }
        }
        return null
    }

    private fun installHooks() {
        try {
            val helpersClass = Class.forName("de.robv.android.xposed.XposedHelpers")
            val hookBaseClass = Class.forName("de.robv.android.xposed.XC_MethodHook")

            // 1. Хук на MediaMetadata.Builder.putString
            val metadataHook = Proxy.newProxyInstance(
                hookBaseClass.classLoader,
                arrayOf(hookBaseClass)
            ) { _, _, _ -> null }

            // Используем динамический наследник через XposedBridge.hookAllMethods
            val bridgeClass = Class.forName("de.robv.android.xposed.XposedBridge")
            val callbackClass = Class.forName("de.robv.android.xposed.XC_MethodHook")

            val putStringMethod = MediaMetadata.Builder::class.java.getDeclaredMethod(
                "putString",
                String::class.java,
                String::class.java
            )

            val buildStateMethod = PlaybackState.Builder::class.java.getDeclaredMethod("build")

            val hookHandler = object : de.robv.android.xposed.XC_MethodHook() {}
        } catch (_: Throwable) {
        }
    }
}
