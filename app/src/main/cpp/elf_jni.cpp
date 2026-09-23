#include "elf_patch.hpp"
#include <jni.h>
#include <stdexcept>

namespace {
std::string string_value(JNIEnv* env, jstring value) {
    if (value == nullptr) throw std::runtime_error("Missing path");
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) throw std::runtime_error("Cannot read path");
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
void fail(JNIEnv* env, const char* message) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/io/IOException"), message);
}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_re_zyg_fri_manager_ElfPatcher_inspectNative(JNIEnv* env, jclass, jstring file) {
    try {
        auto info = zfm::inspect_elf(string_value(env, file));
        jobjectArray result = env->NewObjectArray(static_cast<jsize>(info.needed.size() + 1), env->FindClass("java/lang/String"), nullptr);
        if (!result) return nullptr;
        jstring abi = env->NewStringUTF(info.abi.c_str());
        env->SetObjectArrayElement(result, 0, abi);
        env->DeleteLocalRef(abi);
        for (size_t i = 0; i < info.needed.size(); i++) {
            // Library names are ELF byte strings. Avoid passing invalid modified UTF-8 to JNI.
            std::u16string name;
            for (unsigned char c : info.needed[i]) name.push_back(c);
            jstring value = env->NewString(reinterpret_cast<const jchar*>(name.data()), static_cast<jsize>(name.size()));
            if (!value) return nullptr;
            env->SetObjectArrayElement(result, static_cast<jsize>(i + 1), value);
            env->DeleteLocalRef(value);
        }
        return result;
    } catch (const std::exception& error) { fail(env, error.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_re_zyg_fri_manager_ElfPatcher_patchNative(JNIEnv* env, jclass, jstring input, jstring output, jstring name) {
    try { zfm::patch_elf(string_value(env, input), string_value(env, output), string_value(env, name)); }
    catch (const std::exception& error) { fail(env, error.what()); }
}
