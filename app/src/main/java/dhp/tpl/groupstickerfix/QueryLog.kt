package dhp.tpl.groupstickerfix

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/** Diagnostics: logs MediaStore queries so the filter that hides images can be seen in the module log. */
object QueryLog {
    private val seen = HashSet<String>()

    fun install(tag: String) {
        XposedHelpers.findAndHookMethod(
            ContentResolver::class.java, "query",
            Uri::class.java, Array<String>::class.java, Bundle::class.java, CancellationSignal::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val uri = param.args[0] as? Uri ?: return
                        if (uri.authority != "media") return
                        val b = param.args[2] as? Bundle
                        val sel = b?.getString(ContentResolver.QUERY_ARG_SQL_SELECTION)
                        val args = b?.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS)?.joinToString("|")
                        val line = "$tag query $uri sel=$sel args=$args"
                        if (synchronized(seen) { seen.add(line) }) XposedBridge.log("${Resolver.TAG}: $line")
                    } catch (_: Throwable) {}
                }
            },
        )
    }
}
