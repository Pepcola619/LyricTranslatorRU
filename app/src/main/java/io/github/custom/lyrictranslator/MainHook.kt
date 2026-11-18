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
        if (lpparam.packageName != "android") return

        try {
            XposedHelpers.findAndHookMethod(
                MediaMetadata.Builder::class.java,
                "putString",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val key = param.args.getOrNull(0) as? String ?: return
                        val value = param.args.getOrNull(1) as? String ?: return

                        if (key == "lyricInfo" && value.isNotBlank()) {
                            val result = AutoLyricTranslator.enrichLyricInfoJson(value)
                            param.args.set(1, result.first)
                            hasActiveTranslation = result.second
                        }
                    }
                }
            )

            XposedHelpers.findAndHookMethod(
                PlaybackState.Builder::class.java,
                "build",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!hasActiveTranslation) return
                        val builder = param.thisObject as PlaybackState.Builder
                        val state = XposedHelpers.getObjectField(builder, "mState") as? PlaybackState
                        val actions = state?.customActions ?: emptyList()

                        val alreadyHasBtn = actions.any { it.action == ACTION_TOGGLE_TRANSLATION }
                        if (!alreadyHasBtn) {
                            val customAction = PlaybackState.CustomAction.Builder(
                                ACTION_TOGGLE_TRANSLATION,
                                "Translation",
                                android.R.drawable.ic_menu_sort_alphabetically
                            ).setExtras(Bundle()).build()
                            builder.addCustomAction(customAction)
                        }
                    }
                }
            )
        } catch (t: Throwable) {
            XposedBridge.log("LyricAutoTranslator error: ${t.message}")
        }
    }
}
