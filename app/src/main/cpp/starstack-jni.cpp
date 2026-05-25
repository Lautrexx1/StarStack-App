#include <jni.h>
#include <android/log.h>
#include <memory>
#include <vector>
#include "StackingCore.h"

#define LOG_TAG "StarStackNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_starstack_app_processing_StackingEngine_nCreateSession(
        JNIEnv* env, jobject thiz, jint width, jint height, jint stackModeVal, jboolean enableAlignment) {
    LOGI("nCreateSession: Width=%d, Height=%d, Mode=%d, Alignment=%d", width, height, stackModeVal, enableAlignment);
    
    starstack::StackingConfig config;
    config.width = width;
    config.height = height;
    config.enableAlignment = enableAlignment;
    
    switch (stackModeVal) {
        case 1:
            config.mode = starstack::StackMode::MEDIAN;
            break;
        case 2:
            config.mode = starstack::StackMode::SIGMA_CLIPPING;
            break;
        case 0:
        default:
            config.mode = starstack::StackMode::MEAN;
            break;
    }

    auto* session = new starstack::StackingCore(config);
    return reinterpret_cast<jlong>(session);
}

JNIEXPORT jboolean JNICALL
Java_com_starstack_app_processing_StackingEngine_nSetDarkFrame(
        JNIEnv* env, jobject thiz, jlong sessionHandle, jshortArray darkData) {
    auto* session = reinterpret_cast<starstack::StackingCore*>(sessionHandle);
    if (!session || !darkData) {
        LOGE("nSetDarkFrame: Invalid session handle or null array pointer");
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(darkData);
    jshort* body = env->GetShortArrayElements(darkData, nullptr);
    if (!body) {
        return JNI_FALSE;
    }

    // Cast signed short pointer to unsigned 16-bit uint16_t pointer
    bool success = session->setDarkFrame(reinterpret_cast<const uint16_t*>(body), static_cast<size_t>(len));

    env->ReleaseShortArrayElements(darkData, body, JNI_ABORT);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_starstack_app_processing_StackingEngine_nSetFlatFrame(
        JNIEnv* env, jobject thiz, jlong sessionHandle, jfloatArray flatData) {
    auto* session = reinterpret_cast<starstack::StackingCore*>(sessionHandle);
    if (!session || !flatData) {
        LOGE("nSetFlatFrame: Invalid session handle or null array pointer");
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(flatData);
    jfloat* body = env->GetFloatArrayElements(flatData, nullptr);
    if (!body) {
        return JNI_FALSE;
    }

    bool success = session->setFlatFrame(body, static_cast<size_t>(len));

    env->ReleaseFloatArrayElements(flatData, body, JNI_ABORT);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_starstack_app_processing_StackingEngine_nAddFrame(
        JNIEnv* env, jobject thiz, jlong sessionHandle, jbyteArray frameData) {
    auto* session = reinterpret_cast<starstack::StackingCore*>(sessionHandle);
    if (!session || !frameData) {
        LOGE("nAddFrame: Invalid session handle or null frame data");
        return JNI_FALSE;
    }

    jsize len = env->GetArrayLength(frameData);
    jbyte* body = env->GetByteArrayElements(frameData, nullptr);
    if (!body) {
        return JNI_FALSE;
    }

    bool success = session->addFrame(reinterpret_cast<const uint8_t*>(body), static_cast<size_t>(len));

    env->ReleaseByteArrayElements(frameData, body, JNI_ABORT);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_starstack_app_processing_StackingEngine_nAddFrameBuffer(
        JNIEnv* env, jobject thiz, jlong sessionHandle, jobject byteBuffer) {
    auto* session = reinterpret_cast<starstack::StackingCore*>(sessionHandle);
    if (!session || !byteBuffer) {
        LOGE("nAddFrameBuffer: Invalid session handle or null ByteBuffer");
        return JNI_FALSE;
    }

    void* bufferAddress = env->GetDirectBufferAddress(byteBuffer);
    jlong bufferCapacity = env->GetDirectBufferCapacity(byteBuffer);
    if (!bufferAddress || bufferCapacity <= 0) {
        LOGE("nAddFrameBuffer: Failed to retrieve direct buffer address or capacity");
        return JNI_FALSE;
    }

    bool success = session->addFrame(reinterpret_cast<const uint8_t*>(bufferAddress), static_cast<size_t>(bufferCapacity));
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_starstack_app_processing_StackingEngine_nGetProgressivePreview(
        JNIEnv* env, jobject thiz, jlong sessionHandle, jbyteArray previewBuffer, jint previewWidth, jint previewHeight) {
    auto* session = reinterpret_cast<starstack::StackingCore*>(sessionHandle);
    if (!session || !previewBuffer) {
        LOGE("nGetProgressivePreview: Invalid session handle or null previewBuffer");
        return JNI_FALSE;
    }

    jbyte* body = env->GetByteArrayElements(previewBuffer, nullptr);
    if (!body) {
        return JNI_FALSE;
    }

    bool success = session->getProgressivePreview(reinterpret_cast<uint8_t*>(body), previewWidth, previewHeight);

    if (success) {
        // Commit changes to Java memory
        env->ReleaseByteArrayElements(previewBuffer, body, 0);
    } else {
        // Abandon changes to avoid copying back
        env->ReleaseByteArrayElements(previewBuffer, body, JNI_ABORT);
    }
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jshortArray JNICALL
Java_com_starstack_app_processing_StackingEngine_nFinalizeStack(
        JNIEnv* env, jobject thiz, jlong sessionHandle) {
    auto* session = reinterpret_cast<starstack::StackingCore*>(sessionHandle);
    if (!session) {
        LOGE("nFinalizeStack: Invalid session handle");
        return nullptr;
    }

    int width = session->getWidth();
    int height = session->getHeight();
    jsize arraySize = static_cast<jsize>(width) * height * 3; // 16-bit RGB output
    
    jshortArray outArray = env->NewShortArray(arraySize);
    if (!outArray) {
        LOGE("nFinalizeStack: Failed to allocate JNI short array");
        return nullptr;
    }

    jshort* body = env->GetShortArrayElements(outArray, nullptr);
    if (!body) {
        return nullptr;
    }

    bool success = session->finalizeStack(reinterpret_cast<uint16_t*>(body), static_cast<size_t>(arraySize));
    
    if (success) {
        env->ReleaseShortArrayElements(outArray, body, 0); // Copy back to Java
        return outArray;
    } else {
        env->ReleaseShortArrayElements(outArray, body, JNI_ABORT); // Abandon changes
        LOGE("nFinalizeStack: StackingCore finalizeStack failed");
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_starstack_app_processing_StackingEngine_nReleaseSession(
        JNIEnv* env, jobject thiz, jlong sessionHandle) {
    auto* session = reinterpret_cast<starstack::StackingCore*>(sessionHandle);
    if (session) {
        LOGI("nReleaseSession: Releasing session at pointer %p", session);
        delete session;
    }
}

}
