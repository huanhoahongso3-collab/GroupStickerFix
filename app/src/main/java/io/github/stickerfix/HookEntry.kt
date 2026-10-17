package io.github.stickerfix

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage

class HookEntry : IXposedHookZygoteInit, IXposedHookLoadPackage {
    private var modulePath = ""

    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam) {
        modulePath = startupParam.modulePath
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != StickerHook.TARGET || lpparam.processName != lpparam.packageName) return
        try {
            StickerHook.install(lpparam, modulePath)
        } catch (t: Throwable) {
            XposedBridge.log("${StickerHook.TAG}: install failed: $t")
        }
    }
}
