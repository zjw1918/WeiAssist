// SPAKE2 for adb's Wireless debugging pairing, as adbd does it (packages/modules/adb pairing_auth.cpp):
// the client is "alice", names include their trailing NUL, the password is the pairing code followed by
// 64 bytes exported from the TLS session. Everything else (TLS, HKDF, AES-GCM) is in Kotlin (AdbPairing).
#include <jni.h>
#include <openssl/curve25519.h>
#include <cstring>

namespace {
const uint8_t kClientName[] = "adb pair client";
const uint8_t kServerName[] = "adb pair server";

struct Ctx {
    SPAKE2_CTX* spake;
    uint8_t msg[SPAKE2_MAX_MSG_SIZE];
    size_t msg_len;
};
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_lanqi_weiassist_shell_adb_Spake2_nativeNew(JNIEnv* env, jclass, jbyteArray password) {
    auto* ctx = new Ctx{};
    ctx->spake = SPAKE2_CTX_new(spake2_role_alice, kClientName, sizeof(kClientName), kServerName, sizeof(kServerName));
    if (!ctx->spake) { delete ctx; return 0; }
    jsize n = env->GetArrayLength(password);
    jbyte* pw = env->GetByteArrayElements(password, nullptr);
    int ok = SPAKE2_generate_msg(ctx->spake, ctx->msg, &ctx->msg_len, sizeof(ctx->msg), reinterpret_cast<uint8_t*>(pw), n);
    env->ReleaseByteArrayElements(password, pw, JNI_ABORT);
    if (ok != 1) { SPAKE2_CTX_free(ctx->spake); delete ctx; return 0; }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jbyteArray JNICALL Java_com_lanqi_weiassist_shell_adb_Spake2_nativeMsg(JNIEnv* env, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<Ctx*>(handle);
    jbyteArray out = env->NewByteArray(ctx->msg_len);
    env->SetByteArrayRegion(out, 0, ctx->msg_len, reinterpret_cast<jbyte*>(ctx->msg));
    return out;
}

/** The shared key from the server's message, or null if it doesn't fit (wrong code). */
JNIEXPORT jbyteArray JNICALL Java_com_lanqi_weiassist_shell_adb_Spake2_nativeProcess(JNIEnv* env, jclass, jlong handle, jbyteArray their) {
    auto* ctx = reinterpret_cast<Ctx*>(handle);
    uint8_t key[SPAKE2_MAX_KEY_SIZE];
    size_t key_len = 0;
    jsize n = env->GetArrayLength(their);
    jbyte* msg = env->GetByteArrayElements(their, nullptr);
    int ok = SPAKE2_process_msg(ctx->spake, key, &key_len, sizeof(key), reinterpret_cast<uint8_t*>(msg), n);
    env->ReleaseByteArrayElements(their, msg, JNI_ABORT);
    if (ok != 1) return nullptr;
    jbyteArray out = env->NewByteArray(key_len);
    env->SetByteArrayRegion(out, 0, key_len, reinterpret_cast<jbyte*>(key));
    return out;
}

JNIEXPORT void JNICALL Java_com_lanqi_weiassist_shell_adb_Spake2_nativeFree(JNIEnv*, jclass, jlong handle) {
    auto* ctx = reinterpret_cast<Ctx*>(handle);
    if (!ctx) return;
    SPAKE2_CTX_free(ctx->spake);
    delete ctx;
}
}
