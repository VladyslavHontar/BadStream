#include <jni.h>
#include <string>
#include <vector>
#include "stream_session.h"
#include "tcp_transport.h"
#include "mp4_writer.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_plohoystream_MainActivity_stringFromJNI(
        JNIEnv* env,
        jobject /* this */) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}

using namespace ps;

namespace {
std::vector<uint8_t> ToBytes(JNIEnv* env, jbyteArray arr) {
    if (!arr) return {};
    jsize n = env->GetArrayLength(arr);
    std::vector<uint8_t> out(static_cast<size_t>(n));
    if (n > 0) env->GetByteArrayRegion(arr, 0, n, reinterpret_cast<jbyte*>(out.data()));
    return out;
}
std::string ToStr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}
StreamSession* Self(jlong h) { return reinterpret_cast<StreamSession*>(h); }
FragmentedMp4Writer* Rec(jlong h) { return reinterpret_cast<FragmentedMp4Writer*>(h); }
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeCreate(
        JNIEnv* env, jobject, jstring host, jstring app, jstring key, jstring tcUrl,
        jint port, jint width, jint height, jint fps, jint sampleRate, jint codec) {
    StreamParams p;
    p.host = ToStr(env, host); p.app = ToStr(env, app);
    p.streamKey = ToStr(env, key); p.tcUrl = ToStr(env, tcUrl);
    p.port = static_cast<uint16_t>(port);
    p.width = width; p.height = height; p.fps = static_cast<double>(fps);
    p.sampleRate = sampleRate;
    auto* s = new StreamSession(p, [] { return std::unique_ptr<Transport>(new TcpTransport()); },
                                static_cast<Codec>(codec));
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT jint JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeNegotiatedCodec(JNIEnv*, jobject, jlong h) {
    return h ? (jint)Self(h)->negotiatedCodec() : 0;
}

JNIEXPORT jlong JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeBytesSent(JNIEnv*, jobject, jlong h) {
    return h ? static_cast<jlong>(Self(h)->bytesSent()) : 0;
}

JNIEXPORT jint JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeQueueDepth(JNIEnv*, jobject, jlong h) {
    return h ? static_cast<jint>(Self(h)->queueDepth()) : 0;
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeStart(JNIEnv*, jobject, jlong h) {
    if (h) Self(h)->Start();
}

JNIEXPORT jint JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeState(JNIEnv*, jobject, jlong h) {
    return h ? static_cast<jint>(Self(h)->state()) : 0; // 0=Idle,1=Connecting,2=Live,3=Dropped,4=Rejected
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeSendVideoConfig(
        JNIEnv* env, jobject, jlong h, jbyteArray csd) {
    if (h) Self(h)->SendVideoConfig(ToBytes(env, csd));
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeSendVideo(
        JNIEnv* env, jobject, jlong h, jbyteArray annexb, jboolean key, jlong pts, jlong dts) {
    if (h) Self(h)->SendVideo(ToBytes(env, annexb), key,
                              static_cast<uint32_t>(pts), static_cast<uint32_t>(dts));
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeSendAudioConfig(
        JNIEnv*, jobject, jlong h, jint sampleRate, jint channels) {
    if (h) Self(h)->SendAudioConfig(sampleRate, channels);
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeSendAudio(
        JNIEnv* env, jobject, jlong h, jbyteArray aac, jlong pts) {
    if (h) Self(h)->SendAudio(ToBytes(env, aac), static_cast<uint32_t>(pts));
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeStop(JNIEnv*, jobject, jlong h) {
    if (h) Self(h)->Stop();
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRtmpStreamer_nativeDestroy(JNIEnv*, jobject, jlong h) {
    delete Self(h);
}

// --- NativeRecorder (local fragmented-MP4 recording) ---

JNIEXPORT jlong JNICALL
Java_com_example_plohoystream_stream_NativeRecorder_nativeCreate(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new FragmentedMp4Writer());
}

JNIEXPORT jboolean JNICALL
Java_com_example_plohoystream_stream_NativeRecorder_nativeStart(
        JNIEnv* env, jobject, jlong h, jstring path, jint codec, jint width, jint height,
        jint fps, jint sampleRate, jint channels) {
    if (!h) return JNI_FALSE;
    bool ok = Rec(h)->Start(ToStr(env, path), codec, width, height, fps, sampleRate, channels);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRecorder_nativeWriteVideoConfig(
        JNIEnv* env, jobject, jlong h, jbyteArray csd) {
    if (h) Rec(h)->WriteVideoConfig(ToBytes(env, csd));
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRecorder_nativeWriteVideo(
        JNIEnv* env, jobject, jlong h, jbyteArray annexb, jboolean key, jlong pts) {
    if (h) Rec(h)->WriteVideo(ToBytes(env, annexb), key, static_cast<uint32_t>(pts));
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRecorder_nativeWriteAudioConfig(
        JNIEnv*, jobject, jlong h, jint sampleRate, jint channels) {
    if (h) Rec(h)->WriteAudioConfig(sampleRate, channels);
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRecorder_nativeWriteAudio(
        JNIEnv* env, jobject, jlong h, jbyteArray aac, jlong pts) {
    if (h) Rec(h)->WriteAudio(ToBytes(env, aac), static_cast<uint32_t>(pts));
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRecorder_nativeStop(JNIEnv*, jobject, jlong h) {
    if (h) Rec(h)->Stop();
}

JNIEXPORT void JNICALL
Java_com_example_plohoystream_stream_NativeRecorder_nativeDestroy(JNIEnv*, jobject, jlong h) {
    delete Rec(h);
}

} // extern "C"