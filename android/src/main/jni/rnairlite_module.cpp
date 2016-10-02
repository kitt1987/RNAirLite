#include "com_kh_rnairlite_RNAirPatchManager.h"
#include "libbzip2/bzlib.h"
#include "libminibsdiff/bspatch.h"

#include <stdlib.h>
//
// Created by Kitt Hsu on 5/22/16.
//

JNIEXPORT jobject JNICALL Java_com_kh_rnairlite_RNAirPatchManager_decompress(JNIEnv* env, jobject,
                                                                             jobject patch) {
    void* patchData = env->GetDirectBufferAddress(patch);
    jlong patchSize = env->GetDirectBufferCapacity(patch);

    bz_stream* bzs = (bz_stream*)calloc(1, sizeof(bz_stream));
    if (bzs == NULL) {
        return NULL;
    }

    int result = BZ2_bzDecompressInit(bzs, 0, 1);
    if (result != BZ_OK) {
        return NULL;
    }

    bzs->next_in = (char*) patchData;
    bzs->avail_in = patchSize;

    size_t outputSize = patchSize * 130;
    char* output = (char*) malloc(outputSize);
    if (output == NULL) {
        return NULL;
    }

    bzs->next_out = output;
    bzs->avail_out = outputSize;

    while (bzs->avail_in > 0 && result != BZ_STREAM_END) {
        result = BZ2_bzDecompress(bzs);
        if (result != BZ_OK && result != BZ_STREAM_END) {
            free(output);
            free(bzs);
            return NULL;
        }

        if (bzs->avail_out == 0) {
            output = (char*) realloc(output, outputSize * 2);
            bzs->next_out = output + outputSize;
            bzs->avail_out = outputSize;
            outputSize *= 2;
        }
    }

    BZ2_bzDecompressEnd(bzs);
    outputSize -= bzs->avail_out;
    free(bzs);

    return env->NewDirectByteBuffer(output, outputSize);
}

JNIEXPORT jobject JNICALL Java_com_kh_rnairlite_RNAirPatchManager_patch(JNIEnv* env, jobject,
                                                                        jobject raw,
                                                                        jobject patch) {
    void* rawData = env->GetDirectBufferAddress(raw);
    jlong rawSize = env->GetDirectBufferCapacity(raw);
    void* patchData = env->GetDirectBufferAddress(patch);
    jlong patchSize = env->GetDirectBufferCapacity(patch);

    jlong newSize = patchSize;
    u_char* newData = (u_char*) malloc(newSize);
    if (newData == NULL) {
        return NULL;
    }

    int new_bytes = 0;
    while (true) {
        new_bytes = bspatch((u_char*) rawData, rawSize, (u_char*)patchData, patchSize, newData,
                            newSize);
        if (new_bytes == -1) {
            newSize *= 2;
            newData = (u_char*) realloc(newData, newSize);
            continue;
        }

        if (new_bytes == -2 || new_bytes == -3) {
            return NULL;
        }

        break;
    }


    return env->NewDirectByteBuffer(newData, new_bytes);
}

