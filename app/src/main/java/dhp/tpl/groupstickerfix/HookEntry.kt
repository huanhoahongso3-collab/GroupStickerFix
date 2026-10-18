package dhp.tpl.groupstickerfix

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
        if (lpparam.processName != lpparam.packageName) return
        try {
            when (lpparam.packageName) {
                MessengerHook.TARGET -> MessengerHook.install(lpparam, modulePath)
                ZaloHook.TARGET -> ZaloHook.install(lpparam, modulePath)
            }
        } catch (t: Throwable) {
            XposedBridge.log("${Resolver.TAG}: install failed for ${lpparam.packageName}: $t")
        }
    }
}
