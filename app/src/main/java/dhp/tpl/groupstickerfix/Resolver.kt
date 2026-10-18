package dhp.tpl.groupstickerfix

import android.os.Build
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.result.MethodData
import org.luckypray.dexkit.wrap.DexMethod
import java.io.File
import java.lang.reflect.Method

/**
 * Locates obfuscated methods with DexKit (structure/string fingerprints, never hardcoded names) so the
 * module keeps working across app versions. Results are cached per APK build in the app's data dir.
 */
object Resolver {
    const val TAG = "GroupStickerFix"
    private var loaded = false

    /** [find] returns exactly [count] methods (in a fixed order) or null if the fingerprints don't match. */
    fun resolve(
        lp: XC_LoadPackage.LoadPackageParam,
        modulePath: String,
        count: Int,
        find: (DexKitBridge) -> List<MethodData>?,
    ): List<Method>? {
        val cl = lp.classLoader
        val apk = File(lp.appInfo.sourceDir)
        val key = "${apk.length()}-${apk.lastModified()}"
        val cache = File(lp.appInfo.dataDir, "groupstickerfix.${lp.packageName}.cache")

        try {
            val lines = cache.takeIf { it.exists() }?.readLines()
            if (lines != null && lines.size == count + 1 && lines[0] == key) {
                return lines.drop(1).map { DexMethod(it).getMethodInstance(cl) }
            }
        } catch (t: Throwable) {
            XposedBridge.log("$TAG: cache unusable: $t")
        }

        if (!loaded) {
            System.load("$modulePath!/lib/${Build.SUPPORTED_ABIS[0]}/libdexkit.so")
            loaded = true
        }
        DexKitBridge.create(apk.absolutePath).use { bridge ->
            val found = find(bridge) ?: return null
            if (found.size != count) return null
            val methods = found.map { it.getMethodInstance(cl) }
            try { cache.writeText(key + "\n" + found.joinToString("\n") { it.descriptor } + "\n") } catch (_: Throwable) {}
            return methods
        }
    }
}
