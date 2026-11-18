package io.github.custom.lyrictranslator

import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.Bundle
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class MainHook : IXposedHookLoadPackage {

    companion object {
        private const val ACTION_TOGGLE_TRANSLATION =
            "io.github.andrealtb.lockscreenlyrics.action.TOGGLE_TRANSLATION"

        @Volatile
        var hasActiveTranslation = false
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != "com.android.systemui" && lpparam.packageName != "android") return

        XposedBridge.log("LyricAutoTranslator: active in ${lpparam.packageName}")

        try {
            XposedHelpers.findAndHookMethod(
                MediaMetadata::class.java,
                "getString",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val key = param.args.getOrNull(0) as? String ?: return
                        if (key != "lyricInfo") return

                        val rawJson = param.result as? String ?: return
                        if (rawJson.isBlank()) return

                        val result = AutoLyricTranslator.enrichLyricInfoJson(rawJson)
                        if (result.second) {
                            param.result = result.first
                            hasActiveTranslation = true
                        } else {
                            hasActiveTranslation = false
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                PlaybackState::class.java,
                "getCustomActions",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!hasActiveTranslation) return
                        @Suppress("UNCHECKED_CAST")
                        val original = (param.result as? List<PlaybackState.CustomAction>) ?: emptyList()
                        if (original.any { it.action == ACTION_TOGGLE_TRANSLATION }) return

                        val customAction = PlaybackState.CustomAction.Builder(
                            ACTION_TOGGLE_TRANSLATION,
                            "Translation",
                            android.R.drawable.ic_menu_sort_alphabetically
                        ).setExtras(Bundle()).build()

                        val updated = ArrayList(original)
                        updated.add(customAction)
                        param.result = updated
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("LyricAutoTranslator hook error: ${t.message}")
        }
    }
}
