package uk.noammm.kav.ext

import kav.kavapp.api.Kav

// Raw hooks on Kav's own methods (Kav.hook, capability "hooks").
object MethodHooks {
    const val CAPABILITY = "hooks"

    fun hook(identity: String, l: Native.Loaded, method: java.lang.reflect.Member, before: Kav.MethodHook?, after: Kav.MethodHook?): Kav.Unhook =
        throw UnsupportedOperationException("hooks aren't in this build yet")
}
