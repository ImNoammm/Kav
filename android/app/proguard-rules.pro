# Native extensions link against Kav's stable API by name (src/main/java/kav/kavapp/api): never renamed or removed.
-keep interface kav.kavapp.api.** { *; }
-keep class kav.kavapp.api.** { *; }

# An extension's hooks (Kav.hook) find Kav's own classes and methods by name: kept as written, while unused code and
# resources are still removed. R8 still inlines small methods, so a hook on one may not be reached where it was inlined;
# Kav.deoptimize on its caller fixes that.
-keepnames class uk.noammm.kav.** { *; }
# The hook engine's callback and its native methods (ext/MethodHooks.kt, android/hook/kavhook.cpp).
-keep class uk.noammm.kav.ext.MethodHooks$Dispatch { public java.lang.Object callback(java.lang.Object[]); }
-keep class uk.noammm.kav.ext.HookEngine { *; }
