#ifndef STARSTACK_STAR_ALIGNMENT_H
#define STARSTACK_STAR_ALIGNMENT_H

#include <vector>
#include <cstdint>

namespace starstack {

// Structure representing a detected star candidate
struct Star {
    float x;             // x-coordinate (refined sub-pixel centroid)
    float y;             // y-coordinate (refined sub-pixel centroid)
    float rawX;          // Original pixel x-coordinate
    float rawY;          // Original pixel y-coordinate
    float brightness;    // Peak intensity / sum of intensities
    float size;          // Approximate diameter/fwhm
};

// Represents a 2D affine transformation (rotation, scale, translation)
struct AlignmentTransform {
    float m00 = 1.0f; float m01 = 0.0f; float tx = 0.0f; // Row 0: x' = m00*x + m01*y + tx
    float m10 = 0.0f; float m11 = 1.0f; float ty = 0.0f; // Row 1: y' = m10*x + m11*y + ty
};

class StarAlignment {
public:
    StarAlignment();
    ~StarAlignment();

    /**
     * Detects star candidates using an adaptive threshold peak-finding algorithm.
     * @param grayBuffer Pointer to the input grayscale image data (8-bit or 16-bit).
     * @param width Image width in pixels.
     * @param height Image height in pixels.
     * @param outStars Vector to be populated with detected star candidates.
     * @param thresholdSigma Number of standard deviations above local mean to threshold.
     */
    bool detectStars(const uint8_t* grayBuffer, int width, int height, 
                     std::vector<Star>& outStars, float thresholdSigma = 3.0f);

    /**
     * Refines the centroid coordinates of a star candidate to sub-pixel accuracy 
     * using an intensity-weighted center-of-mass (centroid) algorithm.
     * @param grayBuffer Pointer to the input grayscale image data.
     * @param width Image width.
     * @param height Image height.
     * @param star The star structure to update with sub-pixel coordinates.
     * @param searchRadius Neighborhood radius in pixels for centroid calculation.
     */
    void computeSubpixelCentroid(const uint8_t* grayBuffer, int width, int height, 
                                 Star& star, int searchRadius = 4);

    /**
     * Solves for the 2D alignment transformation mapping a target frame to a reference frame
     * using a RANSAC-based triangle or point matching algorithm.
     * @param refStars Reference frame stars.
     * @param targetStars Target frame stars.
     * @param outTransform The resulting alignment transform.
     * @return True if a valid alignment model is found with sufficient consensus.
     */
    bool solveAlignment(const std::vector<Star>& refStars, 
                        const std::vector<Star>& targetStars, 
                        AlignmentTransform& outTransform);

    /**
     * Transforms a point (x, y) using the calculated alignment transform.
     */
    static void transformPoint(const AlignmentTransform& transform, float srcX, float srcY, float& dstX, float& dstY) {
        dstX = transform.m00 * srcX + transform.m01 * srcY + transform.tx;
        dstY = transform.m10 * srcX + transform.m11 * srcY + transform.ty;
    }
};

} // namespace starstack

#endif // STARSTACK_STAR_ALIGNMENT_H
