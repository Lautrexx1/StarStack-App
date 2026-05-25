#include "StackingCore.h"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <numeric>
#include <android/log.h>

#define LOG_TAG "StackingCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace starstack {

// ---------------------------------------------------------------------------
// Constructor
// ---------------------------------------------------------------------------
StackingCore::StackingCore(const StackingConfig& config)
    : config_(config), aligner_(std::make_unique<StarAlignment>()) {

    size_t pixelCount = static_cast<size_t>(config.width) * config.height;
    if (pixelCount > 0) {
        accumulatorR_.resize(pixelCount, 0.0f);
        accumulatorG_.resize(pixelCount, 0.0f);
        accumulatorB_.resize(pixelCount, 0.0f);

        if (config.mode == StackMode::SIGMA_CLIPPING) {
            sumSqBufferR_.resize(pixelCount, 0.0f);
            sumSqBufferG_.resize(pixelCount, 0.0f);
            sumSqBufferB_.resize(pixelCount, 0.0f);
        }

        // For median stacking, we store per-pixel lists up to a reasonable limit
        if (config.mode == StackMode::MEDIAN) {
            medianBufferR_.resize(pixelCount);
            medianBufferG_.resize(pixelCount);
            medianBufferB_.resize(pixelCount);
        }
    }
    LOGI("StackingCore created: %dx%d, mode=%d, alignment=%d",
         config.width, config.height, static_cast<int>(config.mode), config.enableAlignment);
}

StackingCore::~StackingCore() {
    LOGI("StackingCore destroyed, stacked %d frames", stackedFramesCount_);
}

// ---------------------------------------------------------------------------
// setDarkFrame: Store per-channel dark calibration data
// ---------------------------------------------------------------------------
bool StackingCore::setDarkFrame(const uint16_t* darkBuffer, size_t length) {
    std::lock_guard<std::mutex> lock(sessionMutex_);
    size_t pixelCount = static_cast<size_t>(config_.width) * config_.height;
    if (!darkBuffer || length != pixelCount) {
        LOGE("setDarkFrame: Invalid buffer (expected %zu, got %zu)", pixelCount, length);
        return false;
    }

    darkBuffer_.resize(length);
    for (size_t i = 0; i < length; ++i) {
        darkBuffer_[i] = static_cast<float>(darkBuffer[i]);
    }
    config_.enableDarkSubtraction = true;
    LOGI("setDarkFrame: Dark frame loaded (%zu pixels)", length);
    return true;
}

// ---------------------------------------------------------------------------
// setFlatFrame: Store normalized flat field correction data
// ---------------------------------------------------------------------------
bool StackingCore::setFlatFrame(const float* flatBuffer, size_t length) {
    std::lock_guard<std::mutex> lock(sessionMutex_);
    size_t pixelCount = static_cast<size_t>(config_.width) * config_.height;
    if (!flatBuffer || length != pixelCount) {
        LOGE("setFlatFrame: Invalid buffer (expected %zu, got %zu)", pixelCount, length);
        return false;
    }

    flatBuffer_.assign(flatBuffer, flatBuffer + length);

    // Normalize flat to its mean to preserve overall brightness
    double sum = 0.0;
    for (size_t i = 0; i < length; ++i) sum += flatBuffer_[i];
    float mean = static_cast<float>(sum / length);
    if (mean > 1e-6f) {
        for (size_t i = 0; i < length; ++i) {
            flatBuffer_[i] /= mean;
        }
    }

    config_.enableFlatDivision = true;
    LOGI("setFlatFrame: Flat frame loaded and normalized (%zu pixels, mean=%.3f)", length, mean);
    return true;
}

// ---------------------------------------------------------------------------
// addFrame: Main processing pipeline for a single frame
// ---------------------------------------------------------------------------
bool StackingCore::addFrame(const uint8_t* rawFrame, size_t length) {
    std::lock_guard<std::mutex> lock(sessionMutex_);
    if (!rawFrame || length == 0) {
        LOGE("addFrame: Null or empty frame data");
        return false;
    }

    size_t pixelCount = static_cast<size_t>(config_.width) * config_.height;
    size_t expectedLen = pixelCount * 3; // Interleaved RGB

    if (length < expectedLen) {
        LOGW("addFrame: Buffer size %zu < expected %zu, padding with zeros", length, expectedLen);
    }

    // Step 1: Parse interleaved RGB into separate float channel buffers
    std::vector<float> frameR(pixelCount);
    std::vector<float> frameG(pixelCount);
    std::vector<float> frameB(pixelCount);

    for (size_t i = 0; i < pixelCount; ++i) {
        size_t idx = i * 3;
        if (idx + 2 < length) {
            frameR[i] = static_cast<float>(rawFrame[idx]);
            frameG[i] = static_cast<float>(rawFrame[idx + 1]);
            frameB[i] = static_cast<float>(rawFrame[idx + 2]);
        }
    }

    // Step 2: Dark frame subtraction (remove sensor thermal noise pattern)
    if (config_.enableDarkSubtraction && darkBuffer_.size() == pixelCount) {
        for (size_t i = 0; i < pixelCount; ++i) {
            frameR[i] = std::max(0.0f, frameR[i] - darkBuffer_[i]);
            frameG[i] = std::max(0.0f, frameG[i] - darkBuffer_[i]);
            frameB[i] = std::max(0.0f, frameB[i] - darkBuffer_[i]);
        }
    }

    // Step 3: Flat field correction (remove vignetting and dust shadows)
    if (config_.enableFlatDivision && flatBuffer_.size() == pixelCount) {
        for (size_t i = 0; i < pixelCount; ++i) {
            float flatVal = flatBuffer_[i];
            if (flatVal > 0.01f) { // Protect against division by near-zero
                frameR[i] /= flatVal;
                frameG[i] /= flatVal;
                frameB[i] /= flatVal;
            }
        }
    }

    // Step 4: Star detection for alignment
    std::vector<uint8_t> grayFrame(pixelCount);
    for (size_t i = 0; i < pixelCount; ++i) {
        grayFrame[i] = static_cast<uint8_t>(std::clamp(
            0.299f * frameR[i] + 0.587f * frameG[i] + 0.114f * frameB[i], 0.0f, 255.0f));
    }

    if (config_.enableAlignment) {
        std::vector<Star> currentStars;
        bool detected = aligner_->detectStars(grayFrame.data(), config_.width, config_.height, currentStars);

        if (!hasReferenceFrame_) {
            // First frame becomes the reference
            if (detected) {
                referenceStars_ = currentStars;
            }
            hasReferenceFrame_ = true;
            blendFrame(frameR, frameG, frameB);
            stackedFramesCount_++;

            // Compute initial sharpness estimate for reference frame quality
            sharpnessReference_ = estimateSharpness(grayFrame.data(), config_.width, config_.height);
            LOGI("addFrame: Reference frame set (%zu stars, sharpness=%.1f)",
                 referenceStars_.size(), sharpnessReference_);
            return true;
        }

        // Align to reference frame
        if (detected && !currentStars.empty()) {
            AlignmentTransform transform;
            bool aligned = aligner_->solveAlignment(referenceStars_, currentStars, transform);

            if (aligned) {
                // Warp the frame using bilinear interpolation
                std::vector<float> warpedR(pixelCount, 0.0f);
                std::vector<float> warpedG(pixelCount, 0.0f);
                std::vector<float> warpedB(pixelCount, 0.0f);

                warpFrame(frameR, frameG, frameB, transform, warpedR, warpedG, warpedB);
                blendFrame(warpedR, warpedG, warpedB);
                stackedFramesCount_++;
                LOGI("addFrame: Frame %d aligned and stacked", stackedFramesCount_);
                return true;
            } else {
                LOGW("addFrame: Alignment failed for frame, stacking without alignment");
                blendFrame(frameR, frameG, frameB);
                stackedFramesCount_++;
                return true;
            }
        } else {
            LOGW("addFrame: No stars detected, stacking without alignment");
            blendFrame(frameR, frameG, frameB);
            stackedFramesCount_++;
            return true;
        }
    } else {
        // Direct accumulation without alignment
        blendFrame(frameR, frameG, frameB);
        stackedFramesCount_++;
        return true;
    }
}

// ---------------------------------------------------------------------------
// warpFrame: Apply affine transform with bilinear interpolation
// Maps reference coordinates -> target frame coordinates
// ---------------------------------------------------------------------------
void StackingCore::warpFrame(const std::vector<float>& srcR,
                              const std::vector<float>& srcG,
                              const std::vector<float>& srcB,
                              const AlignmentTransform& transform,
                              std::vector<float>& dstR,
                              std::vector<float>& dstG,
                              std::vector<float>& dstB) {
    int w = config_.width;
    int h = config_.height;

    // We need the inverse transform: for each output pixel, find source pixel
    // output = T * input  =>  input = T^-1 * output
    // For similarity: [a, -b, tx; b, a, ty]
    // Inverse: det = a^2 + b^2
    float a = transform.m00;
    float b = transform.m10;
    float det = a * a + b * b;

    if (det < 1e-10f) {
        // Degenerate transform, just copy
        dstR = srcR; dstG = srcG; dstB = srcB;
        return;
    }

    float invDet = 1.0f / det;
    // Inverse similarity: [a/det, b/det, -(a*tx+b*ty)/det; -b/det, a/det, (b*tx-a*ty)/det]
    float ia = a * invDet;
    float ib = b * invDet;
    float itx = -(a * transform.tx + b * transform.ty) * invDet;
    float ity = (b * transform.tx - a * transform.ty) * invDet;

    for (int y = 0; y < h; ++y) {
        for (int x = 0; x < w; ++x) {
            // Inverse transform: source coords
            float sx = ia * x + ib * y + itx;
            float sy = -ib * x + ia * y + ity;

            // Bilinear interpolation
            int sx0 = static_cast<int>(std::floor(sx));
            int sy0 = static_cast<int>(std::floor(sy));
            int sx1 = sx0 + 1;
            int sy1 = sy0 + 1;

            size_t dstIdx = static_cast<size_t>(y) * w + x;

            if (sx0 < 0 || sy0 < 0 || sx1 >= w || sy1 >= h) {
                // Out of bounds — mark as black (will be cropped later)
                dstR[dstIdx] = 0.0f;
                dstG[dstIdx] = 0.0f;
                dstB[dstIdx] = 0.0f;
                continue;
            }

            float fx = sx - sx0;
            float fy = sy - sy0;
            float w00 = (1.0f - fx) * (1.0f - fy);
            float w10 = fx * (1.0f - fy);
            float w01 = (1.0f - fx) * fy;
            float w11 = fx * fy;

            size_t i00 = static_cast<size_t>(sy0) * w + sx0;
            size_t i10 = static_cast<size_t>(sy0) * w + sx1;
            size_t i01 = static_cast<size_t>(sy1) * w + sx0;
            size_t i11 = static_cast<size_t>(sy1) * w + sx1;

            dstR[dstIdx] = w00 * srcR[i00] + w10 * srcR[i10] + w01 * srcR[i01] + w11 * srcR[i11];
            dstG[dstIdx] = w00 * srcG[i00] + w10 * srcG[i10] + w01 * srcG[i01] + w11 * srcG[i11];
            dstB[dstIdx] = w00 * srcB[i00] + w10 * srcB[i10] + w01 * srcB[i01] + w11 * srcB[i11];
        }
    }
}

// ---------------------------------------------------------------------------
// estimateSharpness: Laplacian variance for auto frame quality scoring
// ---------------------------------------------------------------------------
float StackingCore::estimateSharpness(const uint8_t* gray, int width, int height) {
    if (!gray || width < 3 || height < 3) return 0.0f;

    double sumLap = 0.0;
    double sumLapSq = 0.0;
    int count = 0;

    for (int y = 1; y < height - 1; ++y) {
        for (int x = 1; x < width - 1; ++x) {
            // Laplacian: 4*center - up - down - left - right
            int lap = 4 * gray[y * width + x]
                      - gray[(y - 1) * width + x]
                      - gray[(y + 1) * width + x]
                      - gray[y * width + (x - 1)]
                      - gray[y * width + (x + 1)];
            sumLap += lap;
            sumLapSq += static_cast<double>(lap) * lap;
            count++;
        }
    }

    if (count == 0) return 0.0f;
    double mean = sumLap / count;
    double variance = (sumLapSq / count) - (mean * mean);
    return static_cast<float>(variance);
}

// ---------------------------------------------------------------------------
// blendFrame: Accumulate frame data into the running stack
// ---------------------------------------------------------------------------
void StackingCore::blendFrame(const std::vector<float>& alignedR,
                               const std::vector<float>& alignedG,
                               const std::vector<float>& alignedB) {
    size_t pixelCount = accumulatorR_.size();

    if (config_.mode == StackMode::MEAN) {
        // Simple summation (divide by count during finalization)
        for (size_t i = 0; i < pixelCount; ++i) {
            accumulatorR_[i] += alignedR[i];
            accumulatorG_[i] += alignedG[i];
            accumulatorB_[i] += alignedB[i];
        }
    } else if (config_.mode == StackMode::SIGMA_CLIPPING) {
        // Running sum and sum-of-squares for Welford's online variance
        for (size_t i = 0; i < pixelCount; ++i) {
            accumulatorR_[i] += alignedR[i];
            accumulatorG_[i] += alignedG[i];
            accumulatorB_[i] += alignedB[i];

            sumSqBufferR_[i] += alignedR[i] * alignedR[i];
            sumSqBufferG_[i] += alignedG[i] * alignedG[i];
            sumSqBufferB_[i] += alignedB[i] * alignedB[i];
        }
    } else if (config_.mode == StackMode::MEDIAN) {
        // Store per-pixel channel values for exact median computation
        // Memory-limited: cap at 50 frames to prevent excessive RAM usage
        if (stackedFramesCount_ < 50) {
            for (size_t i = 0; i < pixelCount; ++i) {
                medianBufferR_[i].push_back(alignedR[i]);
                medianBufferG_[i].push_back(alignedG[i]);
                medianBufferB_[i].push_back(alignedB[i]);
            }
        } else {
            // Fallback to mean accumulation if too many frames
            for (size_t i = 0; i < pixelCount; ++i) {
                accumulatorR_[i] += alignedR[i];
                accumulatorG_[i] += alignedG[i];
                accumulatorB_[i] += alignedB[i];
            }
        }
    }
}

// ---------------------------------------------------------------------------
// getProgressivePreview: Downsampled 8-bit RGBA preview for UI display
// ---------------------------------------------------------------------------
bool StackingCore::getProgressivePreview(uint8_t* outPreviewBuffer, int previewWidth, int previewHeight) {
    std::lock_guard<std::mutex> lock(sessionMutex_);
    if (!outPreviewBuffer || previewWidth <= 0 || previewHeight <= 0 || stackedFramesCount_ == 0) {
        return false;
    }

    float scale = 1.0f / stackedFramesCount_;

    for (int y = 0; y < previewHeight; ++y) {
        int sourceY = std::min((y * config_.height) / previewHeight, config_.height - 1);
        for (int x = 0; x < previewWidth; ++x) {
            int sourceX = std::min((x * config_.width) / previewWidth, config_.width - 1);
            size_t sourceIdx = static_cast<size_t>(sourceY) * config_.width + sourceX;
            size_t destIdx = (static_cast<size_t>(y) * previewWidth + x) * 4;

            float r, g, b;

            if (config_.mode == StackMode::MEDIAN && !medianBufferR_.empty() &&
                !medianBufferR_[sourceIdx].empty()) {
                // Compute running median for preview
                auto sortedR = medianBufferR_[sourceIdx];
                auto sortedG = medianBufferG_[sourceIdx];
                auto sortedB = medianBufferB_[sourceIdx];
                std::nth_element(sortedR.begin(), sortedR.begin() + sortedR.size() / 2, sortedR.end());
                std::nth_element(sortedG.begin(), sortedG.begin() + sortedG.size() / 2, sortedG.end());
                std::nth_element(sortedB.begin(), sortedB.begin() + sortedB.size() / 2, sortedB.end());
                r = sortedR[sortedR.size() / 2];
                g = sortedG[sortedG.size() / 2];
                b = sortedB[sortedB.size() / 2];
            } else {
                r = accumulatorR_[sourceIdx] * scale;
                g = accumulatorG_[sourceIdx] * scale;
                b = accumulatorB_[sourceIdx] * scale;
            }

            // Apply a mild gamma stretch for preview visibility (gamma 0.45 ≈ sRGB)
            r = std::pow(std::clamp(r / 255.0f, 0.0f, 1.0f), 0.45f) * 255.0f;
            g = std::pow(std::clamp(g / 255.0f, 0.0f, 1.0f), 0.45f) * 255.0f;
            b = std::pow(std::clamp(b / 255.0f, 0.0f, 1.0f), 0.45f) * 255.0f;

            outPreviewBuffer[destIdx + 0] = static_cast<uint8_t>(std::clamp(r, 0.0f, 255.0f));
            outPreviewBuffer[destIdx + 1] = static_cast<uint8_t>(std::clamp(g, 0.0f, 255.0f));
            outPreviewBuffer[destIdx + 2] = static_cast<uint8_t>(std::clamp(b, 0.0f, 255.0f));
            outPreviewBuffer[destIdx + 3] = 255;
        }
    }
    return true;
}

// ---------------------------------------------------------------------------
// finalizeStack: Produce the final 16-bit RGB output
// ---------------------------------------------------------------------------
bool StackingCore::finalizeStack(uint16_t* outFinalBuffer, size_t length) {
    std::lock_guard<std::mutex> lock(sessionMutex_);
    size_t pixelCount = accumulatorR_.size();
    if (!outFinalBuffer || length < pixelCount * 3 || stackedFramesCount_ == 0) {
        LOGE("finalizeStack: Invalid parameters (length=%zu, expected>=%zu, frames=%d)",
             length, pixelCount * 3, stackedFramesCount_);
        return false;
    }

    LOGI("finalizeStack: Computing final result from %d frames", stackedFramesCount_);

    if (config_.mode == StackMode::MEAN) {
        float scale = 1.0f / stackedFramesCount_;
        for (size_t i = 0; i < pixelCount; ++i) {
            float r = accumulatorR_[i] * scale;
            float g = accumulatorG_[i] * scale;
            float b = accumulatorB_[i] * scale;

            // Scale 8-bit accumulated mean to 16-bit output range
            outFinalBuffer[i * 3 + 0] = static_cast<uint16_t>(std::clamp(r * 257.0f, 0.0f, 65535.0f));
            outFinalBuffer[i * 3 + 1] = static_cast<uint16_t>(std::clamp(g * 257.0f, 0.0f, 65535.0f));
            outFinalBuffer[i * 3 + 2] = static_cast<uint16_t>(std::clamp(b * 257.0f, 0.0f, 65535.0f));
        }
    } else if (config_.mode == StackMode::SIGMA_CLIPPING) {
        // Sigma-clipped mean: reject pixels outside sigma*stddev from mean
        float n = static_cast<float>(stackedFramesCount_);
        float sigma = config_.sigmaThreshold;

        for (size_t i = 0; i < pixelCount; ++i) {
            float meanR = accumulatorR_[i] / n;
            float meanG = accumulatorG_[i] / n;
            float meanB = accumulatorB_[i] / n;

            float varR = (sumSqBufferR_[i] / n) - (meanR * meanR);
            float varG = (sumSqBufferG_[i] / n) - (meanG * meanG);
            float varB = (sumSqBufferB_[i] / n) - (meanB * meanB);

            float stdR = std::sqrt(std::max(0.0f, varR));
            float stdG = std::sqrt(std::max(0.0f, varG));
            float stdB = std::sqrt(std::max(0.0f, varB));

            // For online sigma clipping, we output the mean but note:
            // True sigma clipping requires re-iterating over original data.
            // As a mobile-optimized approximation, we use the statistics to 
            // weight the output, reducing the impact of outliers.
            // If stddev is very high relative to mean, dampen the output.
            float weightR = (stdR > 0.01f) ? std::min(1.0f, sigma * meanR / (stdR + 1e-6f)) : 1.0f;
            float weightG = (stdG > 0.01f) ? std::min(1.0f, sigma * meanG / (stdG + 1e-6f)) : 1.0f;
            float weightB = (stdB > 0.01f) ? std::min(1.0f, sigma * meanB / (stdB + 1e-6f)) : 1.0f;

            float r = meanR * weightR;
            float g = meanG * weightG;
            float b = meanB * weightB;

            outFinalBuffer[i * 3 + 0] = static_cast<uint16_t>(std::clamp(r * 257.0f, 0.0f, 65535.0f));
            outFinalBuffer[i * 3 + 1] = static_cast<uint16_t>(std::clamp(g * 257.0f, 0.0f, 65535.0f));
            outFinalBuffer[i * 3 + 2] = static_cast<uint16_t>(std::clamp(b * 257.0f, 0.0f, 65535.0f));
        }
    } else if (config_.mode == StackMode::MEDIAN) {
        // Compute exact median from stored per-pixel buffers
        for (size_t i = 0; i < pixelCount; ++i) {
            float r, g, b;

            if (!medianBufferR_.empty() && !medianBufferR_[i].empty()) {
                auto& vr = medianBufferR_[i];
                auto& vg = medianBufferG_[i];
                auto& vb = medianBufferB_[i];

                std::nth_element(vr.begin(), vr.begin() + vr.size() / 2, vr.end());
                std::nth_element(vg.begin(), vg.begin() + vg.size() / 2, vg.end());
                std::nth_element(vb.begin(), vb.begin() + vb.size() / 2, vb.end());

                r = vr[vr.size() / 2];
                g = vg[vg.size() / 2];
                b = vb[vb.size() / 2];
            } else {
                // Fallback to mean
                float scale = 1.0f / std::max(1, stackedFramesCount_);
                r = accumulatorR_[i] * scale;
                g = accumulatorG_[i] * scale;
                b = accumulatorB_[i] * scale;
            }

            outFinalBuffer[i * 3 + 0] = static_cast<uint16_t>(std::clamp(r * 257.0f, 0.0f, 65535.0f));
            outFinalBuffer[i * 3 + 1] = static_cast<uint16_t>(std::clamp(g * 257.0f, 0.0f, 65535.0f));
            outFinalBuffer[i * 3 + 2] = static_cast<uint16_t>(std::clamp(b * 257.0f, 0.0f, 65535.0f));
        }

        // Free median buffers to reclaim memory
        medianBufferR_.clear();
        medianBufferR_.shrink_to_fit();
        medianBufferG_.clear();
        medianBufferG_.shrink_to_fit();
        medianBufferB_.clear();
        medianBufferB_.shrink_to_fit();
    }

    LOGI("finalizeStack: Output written (%zu pixels, 16-bit RGB)", pixelCount);
    return true;
}

int StackingCore::getStackedFrameCount() const {
    std::lock_guard<std::mutex> lock(sessionMutex_);
    return stackedFramesCount_;
}

int StackingCore::getWidth() const {
    return config_.width;
}

int StackingCore::getHeight() const {
    return config_.height;
}

} // namespace starstack
