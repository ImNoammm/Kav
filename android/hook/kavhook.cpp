// Kav's engine for an extension's hooks on Kav's own methods (ext/MethodHooks.kt): LSPlant hooks a Java method, Dobby
// patches the native code under it, and ElfImage finds the ART functions LSPlant needs in libart.so. Kav loads this
// only the first time an extension asks to hook.
#include <jni.h>

#include <dobby.h>
#include <lsplant.hpp>

#include "elf/elf_image.h"

namespace {

using vector::native::ElfImage;

const ElfImage &Art() {
    static const ElfImage art("libart.so");
    return art;
}

bool Start(JNIEnv *env) {
    lsplant::InitInfo info{
        .inline_hooker = [](void *target, void *hooker) -> void * {
            void *backup = nullptr;
            return DobbyHook(target, hooker, &backup) == 0 ? backup : nullptr;
        },
        .inline_unhooker = [](void *func) { return DobbyDestroy(func) == 0; },
        .art_symbol_resolver = [](std::string_view symbol) { return Art().getSymbAddress(symbol); },
        .art_symbol_prefix_resolver = [](std::string_view prefix) { return Art().getSymbPrefixFirstAddress(prefix); },
        .generated_class_name = "KavHook_",
        .generated_source_name = "KavHook",
    };
    return lsplant::Init(env, info);
}

jboolean Init(JNIEnv *env, jclass) {
    static const bool started = Start(env);
    return started;
}

jobject Hook(JNIEnv *env, jclass, jobject target, jobject hooker, jobject callback) {
    return lsplant::Hook(env, target, hooker, callback);
}

jboolean Unhook(JNIEnv *env, jclass, jobject target) { return lsplant::UnHook(env, target); }

jboolean Deoptimize(JNIEnv *env, jclass, jobject method) { return lsplant::Deoptimize(env, method); }

}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    jclass engine = env->FindClass("uk/noammm/kav/ext/HookEngine");
    if (engine == nullptr) return JNI_ERR;
    const JNINativeMethod methods[] = {
        {"start", "()Z", reinterpret_cast<void *>(Init)},
        {"hook", "(Ljava/lang/reflect/Member;Ljava/lang/Object;Ljava/lang/reflect/Method;)Ljava/lang/reflect/Method;",
         reinterpret_cast<void *>(Hook)},
        {"unhook", "(Ljava/lang/reflect/Member;)Z", reinterpret_cast<void *>(Unhook)},
        {"deoptimize", "(Ljava/lang/reflect/Member;)Z", reinterpret_cast<void *>(Deoptimize)},
    };
    if (env->RegisterNatives(engine, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
