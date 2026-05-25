#ifndef STARSTACK_STACKING_CORE_H
#define STARSTACK_STACKING_CORE_H

#include <vector>
#include <memory>
#include <mutex>
#include "StarAlignment.h"

namespace starstack {

enum class StackMode {
    MEAN = 0,
    MEDIAN = 1,
    SIGMA_CLIPPING = 2
};

struct StackingConfig {
    int width = 0;
    int height = 0;
    StackMode mode = StackMode::MEAN;
    bool enableAlignment = true;
    bool enableDarkSubtraction = false;
    bool enableFlatDivision = false;
    float sigmaThreshold = 3.0f; // For sigma clipping
};

class StackingCore {
public:
    StackingCore(const StackingConfig& config);
    ~StackingCore();

    /**
     * Sets the dark frame to be used for calibration subtraction.
     * @param darkBuffer Pointer to 16-bit dark frame buffer (grayscale).
     * @param length Number of elements in the buffer (should match width * height).
     */
    bool setDarkFrame(const uint16_t* darkBuffer, size_t length);

    /**
     * Sets the flat frame to be used for vignette correction division.
     * The flat frame is internally normalized to its own mean to preserve brightness.
     * @param flatBuffer Pointer to normalized float flat frame buffer.
     * @param length Number of elements in the buffer.
     */
    bool setFlatFrame(const float* flatBuffer, size_t length);

    /**
     * Processes and stacks a single incoming frame.
     * Pipeline: Parse RGB -> Dark subtraction -> Flat correction -> Star detection ->
     * Alignment (RANSAC) -> Warp (bilinear) -> Blend into accumulator.
     * @param rawFrame Pointer to interleaved 8-bit RGB data.
     * @param length Size of the frame buffer in bytes.
     * @return True if the frame was successfully processed and accumulated.
     */
    bool addFrame(const uint8_t* rawFrame, size_t length);

    /**
     * Generates a progressive 8-bit RGBA preview of the current stack.
     * Applies gamma correction for visibility. Output is downsampled to target size.
     * @param outPreviewBuffer Pointer to output RGBA buffer.
     * @param previewWidth Target width of the preview.
     * @param previewHeight Target height of the preview.
     */
    bool getProgressivePreview(uint8_t* outPreviewBuffer, int previewWidth, int previewHeight);

    /**
     * Finalizes the stacking process and outputs the final 16-bit RGB result.
     * For median mode, computes exact medians and frees per-pixel storage.
     * @param outFinalBuffer Target buffer for 16-bit RGB output.
     * @param length Size of the output buffer in elements (must be >= width*height*3).
     */
    bool finalizeStack(uint16_t* outFinalBuffer, size_t length);

    /** Gets the number of frames successfully accumulated in this session. */
    int getStackedFrameCount() const;

    int getWidth() const;
    int getHeight() const;

private:
    StackingConfig config_;
    int stackedFramesCount_ = 0;
    mutable std::mutex sessionMutex_;

    // Calibration data
    std::vector<float> darkBuffer_;
    std::vector<float> flatBuffer_;

    // Cumulative accumulator buffers (3-channel float for high dynamic range)
    std::vector<float> accumulatorR_;
    std::vector<float> accumulatorG_;
    std::vector<float> accumulatorB_;

    // For sigma-clipping: running sum of squares for online variance
    std::vector<float> sumSqBufferR_;
    std::vector<float> sumSqBufferG_;
    std::vector<float> sumSqBufferB_;

    // For median stacking: per-pixel value lists (memory-capped at 50 frames)
    std::vector<std::vector<float>> medianBufferR_;
    std::vector<std::vector<float>> medianBufferG_;
    std::vector<std::vector<float>> medianBufferB_;

    // Reference frame star list for alignment
    std::vector<Star> referenceStars_;
    bool hasReferenceFrame_ = false;
    float sharpnessReference_ = 0.0f;

    std::unique_ptr<StarAlignment> aligner_;

    // Internal helpers
    void blendFrame(const std::vector<float>& alignedR,
                    const std::vector<float>& alignedG,
                    const std::vector<float>& alignedB);

    void warpFrame(const std::vector<float>& srcR,
                   const std::vector<float>& srcG,
                   const std::vector<float>& srcB,
                   const AlignmentTransform& transform,
                   std::vector<float>& dstR,
                   std::vector<float>& dstG,
                   std::vector<float>& dstB);

    static float estimateSharpness(const uint8_t* gray, int width, int height);
};

} // namespace starstack

#endif // STARSTACK_STACKING_CORE_H
