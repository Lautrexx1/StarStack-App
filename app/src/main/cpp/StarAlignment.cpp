#include "StarAlignment.h"
#include <cmath>
#include <random>
#include <algorithm>
#include <numeric>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "StarAlignment"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace starstack {

// ---------------------------------------------------------------------------
// Internal constants
// ---------------------------------------------------------------------------
static constexpr int    kMinStarsForAlignment   = 4;
static constexpr int    kMaxStarsToKeep          = 200;
static constexpr int    kNonMaxSuppressionRadius = 5;
static constexpr int    kBlockSize               = 64;
static constexpr int    kRANSACIterations        = 500;
static constexpr float  kRANSACInlierThreshold   = 3.0f; // pixels
static constexpr int    kMaxTriangleStars        = 40;

// ---------------------------------------------------------------------------
// Helper: compute local mean and stddev for a block
// ---------------------------------------------------------------------------
static void computeBlockStats(const uint8_t* gray, int width, int height,
                               int bx, int by, int bw, int bh,
                               float& outMean, float& outStdDev) {
    double sum = 0.0;
    double sumSq = 0.0;
    int count = 0;

    int yEnd = std::min(by + bh, height);
    int xEnd = std::min(bx + bw, width);

    for (int y = by; y < yEnd; ++y) {
        const uint8_t* row = gray + y * width + bx;
        for (int x = bx; x < xEnd; ++x) {
            float v = static_cast<float>(*row++);
            sum += v;
            sumSq += v * v;
            count++;
        }
    }

    if (count == 0) {
        outMean = 0.0f;
        outStdDev = 0.0f;
        return;
    }

    outMean = static_cast<float>(sum / count);
    float variance = static_cast<float>((sumSq / count) - (outMean * outMean));
    outStdDev = (variance > 0.0f) ? std::sqrt(variance) : 0.0f;
}

// ---------------------------------------------------------------------------
// Constructor / Destructor
// ---------------------------------------------------------------------------
StarAlignment::StarAlignment() {}
StarAlignment::~StarAlignment() {}

// ---------------------------------------------------------------------------
// detectStars: adaptive threshold peak-finding with non-maximum suppression
// ---------------------------------------------------------------------------
bool StarAlignment::detectStars(const uint8_t* grayBuffer, int width, int height,
                                 std::vector<Star>& outStars, float thresholdSigma) {
    if (!grayBuffer || width <= 0 || height <= 0) {
        return false;
    }
    outStars.clear();

    // Margin to avoid edge artifacts
    const int margin = kNonMaxSuppressionRadius + 2;

    // Divide image into blocks, compute local threshold per block
    int blocksX = (width + kBlockSize - 1) / kBlockSize;
    int blocksY = (height + kBlockSize - 1) / kBlockSize;

    // Precompute block stats
    std::vector<float> blockMean(blocksX * blocksY);
    std::vector<float> blockStdDev(blocksX * blocksY);

    for (int by = 0; by < blocksY; ++by) {
        for (int bx = 0; bx < blocksX; ++bx) {
            computeBlockStats(grayBuffer, width, height,
                              bx * kBlockSize, by * kBlockSize, kBlockSize, kBlockSize,
                              blockMean[by * blocksX + bx], blockStdDev[by * blocksX + bx]);
        }
    }

    // Pass 1: Find candidate pixels exceeding local adaptive threshold
    struct Candidate {
        int x, y;
        float value;
    };
    std::vector<Candidate> candidates;
    candidates.reserve(2048);

    for (int y = margin; y < height - margin; ++y) {
        int by = y / kBlockSize;
        for (int x = margin; x < width - margin; ++x) {
            int bx = x / kBlockSize;
            int blockIdx = by * blocksX + bx;

            float pixVal = static_cast<float>(grayBuffer[y * width + x]);
            float threshold = blockMean[blockIdx] + thresholdSigma * blockStdDev[blockIdx];

            // Minimum absolute brightness to filter noise in very dark blocks
            threshold = std::max(threshold, 20.0f);

            if (pixVal > threshold) {
                candidates.push_back({x, y, pixVal});
            }
        }
    }

    // Pass 2: Non-maximum suppression — keep only local maxima
    // Sort candidates by brightness descending
    std::sort(candidates.begin(), candidates.end(),
              [](const Candidate& a, const Candidate& b) { return a.value > b.value; });

    // Suppression grid
    std::vector<bool> suppressed(width * height, false);

    for (const auto& cand : candidates) {
        if (suppressed[cand.y * width + cand.x]) continue;

        // Check if this pixel is the local maximum in its neighborhood
        bool isMax = true;
        for (int dy = -kNonMaxSuppressionRadius; dy <= kNonMaxSuppressionRadius && isMax; ++dy) {
            for (int dx = -kNonMaxSuppressionRadius; dx <= kNonMaxSuppressionRadius && isMax; ++dx) {
                if (dx == 0 && dy == 0) continue;
                int nx = cand.x + dx;
                int ny = cand.y + dy;
                if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                    if (static_cast<float>(grayBuffer[ny * width + nx]) > cand.value) {
                        isMax = false;
                    }
                }
            }
        }

        if (!isMax) continue;

        Star star;
        star.rawX = static_cast<float>(cand.x);
        star.rawY = static_cast<float>(cand.y);
        star.x = star.rawX;
        star.y = star.rawY;
        star.brightness = cand.value;
        star.size = 1.0f;
        outStars.push_back(star);

        // Suppress neighborhood
        for (int dy = -kNonMaxSuppressionRadius; dy <= kNonMaxSuppressionRadius; ++dy) {
            for (int dx = -kNonMaxSuppressionRadius; dx <= kNonMaxSuppressionRadius; ++dx) {
                int nx = cand.x + dx;
                int ny = cand.y + dy;
                if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                    suppressed[ny * width + nx] = true;
                }
            }
        }

        if (outStars.size() >= kMaxStarsToKeep) break;
    }

    // Refine centroids for all detected stars
    for (auto& star : outStars) {
        computeSubpixelCentroid(grayBuffer, width, height, star, 4);
    }

    LOGI("detectStars: Found %zu stars (image %dx%d, sigma=%.1f)",
         outStars.size(), width, height, thresholdSigma);
    return outStars.size() >= static_cast<size_t>(kMinStarsForAlignment);
}

// ---------------------------------------------------------------------------
// computeSubpixelCentroid: intensity-weighted center of mass in a window
// ---------------------------------------------------------------------------
void StarAlignment::computeSubpixelCentroid(const uint8_t* grayBuffer, int width, int height,
                                             Star& star, int searchRadius) {
    if (!grayBuffer || width <= 0 || height <= 0) return;

    int cx = static_cast<int>(star.rawX);
    int cy = static_cast<int>(star.rawY);

    // Compute background level from the edges of the search window
    float background = 0.0f;
    int bgCount = 0;
    int r2 = searchRadius + 1;
    for (int dy = -r2; dy <= r2; ++dy) {
        for (int dx = -r2; dx <= r2; ++dx) {
            if (std::abs(dx) < searchRadius && std::abs(dy) < searchRadius) continue;
            int nx = cx + dx;
            int ny = cy + dy;
            if (nx >= 0 && nx < width && ny >= 0 && ny < height) {
                background += static_cast<float>(grayBuffer[ny * width + nx]);
                bgCount++;
            }
        }
    }
    if (bgCount > 0) background /= bgCount;

    // Intensity-weighted center of mass
    double sumX = 0.0, sumY = 0.0, sumW = 0.0;
    float totalFlux = 0.0f;

    for (int dy = -searchRadius; dy <= searchRadius; ++dy) {
        for (int dx = -searchRadius; dx <= searchRadius; ++dx) {
            int nx = cx + dx;
            int ny = cy + dy;
            if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;

            float w = std::max(0.0f, static_cast<float>(grayBuffer[ny * width + nx]) - background);
            sumX += nx * w;
            sumY += ny * w;
            sumW += w;
            totalFlux += w;
        }
    }

    if (sumW > 0.0) {
        star.x = static_cast<float>(sumX / sumW);
        star.y = static_cast<float>(sumY / sumW);
    }
    star.brightness = totalFlux;
}

// ---------------------------------------------------------------------------
// Internal: Invariant triangle descriptor for star matching
// ---------------------------------------------------------------------------
struct TriangleDescriptor {
    int idxA, idxB, idxC; // Indices into source star list
    float ratioAB_AC;     // Ratio of side AB / AC (sorted ascending)
    float ratioBC_AC;     // Ratio of side BC / AC
    float angle;          // Angle at vertex A
};

static float starDist(const Star& a, const Star& b) {
    float dx = a.x - b.x;
    float dy = a.y - b.y;
    return std::sqrt(dx * dx + dy * dy);
}

static void buildTriangles(const std::vector<Star>& stars, int maxStars,
                           std::vector<TriangleDescriptor>& out) {
    int n = std::min(static_cast<int>(stars.size()), maxStars);
    out.clear();
    out.reserve(n * (n - 1) * (n - 2) / 6);

    for (int i = 0; i < n; ++i) {
        for (int j = i + 1; j < n; ++j) {
            for (int k = j + 1; k < n; ++k) {
                float dAB = starDist(stars[i], stars[j]);
                float dAC = starDist(stars[i], stars[k]);
                float dBC = starDist(stars[j], stars[k]);

                // Sort sides: s0 <= s1 <= s2
                float sides[3] = {dAB, dAC, dBC};
                int indices[3] = {0, 1, 2}; // Map back to vertices
                // Simple sort of 3
                if (sides[0] > sides[1]) { std::swap(sides[0], sides[1]); std::swap(indices[0], indices[1]); }
                if (sides[1] > sides[2]) { std::swap(sides[1], sides[2]); std::swap(indices[1], indices[2]); }
                if (sides[0] > sides[1]) { std::swap(sides[0], sides[1]); std::swap(indices[0], indices[1]); }

                if (sides[2] < 5.0f) continue; // Skip degenerate tiny triangles

                TriangleDescriptor td;
                td.idxA = i; td.idxB = j; td.idxC = k;
                td.ratioAB_AC = sides[0] / sides[2];
                td.ratioBC_AC = sides[1] / sides[2];
                // Angle at the vertex opposite the longest side (using law of cosines)
                float cosAngle = (sides[0] * sides[0] + sides[1] * sides[1] - sides[2] * sides[2])
                                 / (2.0f * sides[0] * sides[1] + 1e-9f);
                td.angle = std::acos(std::clamp(cosAngle, -1.0f, 1.0f));
                out.push_back(td);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Internal: Solve similarity transform from matched point pairs
// Given ref -> target pairs, compute rotation + translation + uniform scale
// Model: target = s*R*ref + t
// Using least squares on >= 2 pairs
// ---------------------------------------------------------------------------
static bool solveSimilarityTransform(const std::vector<Star>& refStars,
                                      const std::vector<Star>& tgtStars,
                                      const std::vector<std::pair<int, int>>& matches,
                                      const std::vector<int>& subset,
                                      AlignmentTransform& outTransform) {
    // Need at least 2 point pairs for similarity transform
    if (subset.size() < 2) return false;

    // Build the linear system
    // For each pair (ref_i, tgt_i):
    //   tgt_x = a*ref_x - b*ref_y + tx
    //   tgt_y = b*ref_x + a*ref_y + ty
    // where a = s*cos(theta), b = s*sin(theta)
    //
    // Linear system Ax = B where x = [a, b, tx, ty]

    int n = static_cast<int>(subset.size());
    // Using normal equations: A^T A x = A^T B
    // A is 2n x 4, but we accumulate directly

    double ATA[4][4] = {};
    double ATB[4] = {};

    for (int i = 0; i < n; ++i) {
        int refIdx = matches[subset[i]].first;
        int tgtIdx = matches[subset[i]].second;

        double rx = refStars[refIdx].x;
        double ry = refStars[refIdx].y;
        double tx = tgtStars[tgtIdx].x;
        double ty = tgtStars[tgtIdx].y;

        // Row 1: tx_hat = a*rx - b*ry + tx_param
        // Row 2: ty_hat = b*rx + a*ry + ty_param
        // Row 1 coefficients: [rx, -ry, 1, 0]
        // Row 2 coefficients: [ry,  rx, 0, 1]

        double r1[4] = {rx, -ry, 1.0, 0.0};
        double r2[4] = {ry,  rx, 0.0, 1.0};

        for (int j = 0; j < 4; ++j) {
            for (int k = 0; k < 4; ++k) {
                ATA[j][k] += r1[j] * r1[k] + r2[j] * r2[k];
            }
            ATB[j] += r1[j] * tx + r2[j] * ty;
        }
    }

    // Solve 4x4 system using Gauss-Jordan elimination
    double augmented[4][5];
    for (int i = 0; i < 4; ++i) {
        for (int j = 0; j < 4; ++j) augmented[i][j] = ATA[i][j];
        augmented[i][4] = ATB[i];
    }

    for (int col = 0; col < 4; ++col) {
        // Partial pivoting
        int maxRow = col;
        for (int row = col + 1; row < 4; ++row) {
            if (std::abs(augmented[row][col]) > std::abs(augmented[maxRow][col])) {
                maxRow = row;
            }
        }
        std::swap(augmented[col], augmented[maxRow]);

        if (std::abs(augmented[col][col]) < 1e-12) return false; // Singular

        double pivot = augmented[col][col];
        for (int j = col; j < 5; ++j) augmented[col][j] /= pivot;

        for (int row = 0; row < 4; ++row) {
            if (row == col) continue;
            double factor = augmented[row][col];
            for (int j = col; j < 5; ++j) {
                augmented[row][j] -= factor * augmented[col][j];
            }
        }
    }

    double a  = augmented[0][4];
    double b  = augmented[1][4];
    double tx_param = augmented[2][4];
    double ty_param = augmented[3][4];

    // Validate: scale should be close to 1.0 for astrophotography (no optical zoom change)
    double scale = std::sqrt(a * a + b * b);
    if (scale < 0.8 || scale > 1.2) return false; // Reject unreasonable transforms

    outTransform.m00 = static_cast<float>(a);
    outTransform.m01 = static_cast<float>(-b);
    outTransform.tx  = static_cast<float>(tx_param);
    outTransform.m10 = static_cast<float>(b);
    outTransform.m11 = static_cast<float>(a);
    outTransform.ty  = static_cast<float>(ty_param);

    return true;
}

// ---------------------------------------------------------------------------
// solveAlignment: Triangle matching + RANSAC similarity transform
// ---------------------------------------------------------------------------
bool StarAlignment::solveAlignment(const std::vector<Star>& refStars,
                                    const std::vector<Star>& targetStars,
                                    AlignmentTransform& outTransform) {
    if (refStars.size() < static_cast<size_t>(kMinStarsForAlignment) ||
        targetStars.size() < static_cast<size_t>(kMinStarsForAlignment)) {
        LOGW("solveAlignment: Not enough stars (ref=%zu, tgt=%zu)", refStars.size(), targetStars.size());
        return false;
    }

    // Step 1: Build invariant triangle descriptors for both frames
    std::vector<TriangleDescriptor> refTriangles, tgtTriangles;
    buildTriangles(refStars, kMaxTriangleStars, refTriangles);
    buildTriangles(targetStars, kMaxTriangleStars, tgtTriangles);

    if (refTriangles.empty() || tgtTriangles.empty()) {
        LOGW("solveAlignment: No triangles could be formed");
        return false;
    }

    // Step 2: Match triangles by comparing side ratios
    const float kTriMatchTol = 0.05f; // Tolerance for ratio matching
    std::vector<std::pair<int, int>> pointMatches; // ref star idx -> target star idx

    // Use a set to avoid duplicate matches
    std::vector<std::vector<int>> voteMatrix(refStars.size(), std::vector<int>(targetStars.size(), 0));

    for (const auto& rt : refTriangles) {
        for (const auto& tt : tgtTriangles) {
            if (std::abs(rt.ratioAB_AC - tt.ratioAB_AC) < kTriMatchTol &&
                std::abs(rt.ratioBC_AC - tt.ratioBC_AC) < kTriMatchTol &&
                std::abs(rt.angle - tt.angle) < 0.1f) {
                // This triangle pair matches — vote for vertex correspondences
                // We don't know the vertex ordering, so vote for all permutations
                int refVerts[3] = {rt.idxA, rt.idxB, rt.idxC};
                int tgtVerts[3] = {tt.idxA, tt.idxB, tt.idxC};

                // Vote for identity permutation and rotations
                for (int perm = 0; perm < 3; ++perm) {
                    voteMatrix[refVerts[0]][tgtVerts[(0 + perm) % 3]]++;
                    voteMatrix[refVerts[1]][tgtVerts[(1 + perm) % 3]]++;
                    voteMatrix[refVerts[2]][tgtVerts[(2 + perm) % 3]]++;
                }
            }
        }
    }

    // Extract best matches from vote matrix (greedy assignment)
    std::vector<bool> refUsed(refStars.size(), false);
    std::vector<bool> tgtUsed(targetStars.size(), false);

    struct VoteEntry {
        int refIdx, tgtIdx, votes;
    };
    std::vector<VoteEntry> allVotes;
    for (size_t i = 0; i < refStars.size(); ++i) {
        for (size_t j = 0; j < targetStars.size(); ++j) {
            if (voteMatrix[i][j] > 0) {
                allVotes.push_back({static_cast<int>(i), static_cast<int>(j), voteMatrix[i][j]});
            }
        }
    }
    std::sort(allVotes.begin(), allVotes.end(),
              [](const VoteEntry& a, const VoteEntry& b) { return a.votes > b.votes; });

    for (const auto& v : allVotes) {
        if (refUsed[v.refIdx] || tgtUsed[v.tgtIdx]) continue;
        if (v.votes < 2) break; // Minimum confidence
        pointMatches.push_back({v.refIdx, v.tgtIdx});
        refUsed[v.refIdx] = true;
        tgtUsed[v.tgtIdx] = true;
    }

    LOGI("solveAlignment: Found %zu point correspondences from triangle voting", pointMatches.size());

    if (pointMatches.size() < 2) {
        LOGW("solveAlignment: Insufficient matches for transform estimation");
        return false;
    }

    // Step 3: RANSAC to find the best similarity transform
    std::mt19937 rng(42); // Deterministic seed for reproducibility
    int bestInliers = 0;
    AlignmentTransform bestTransform;

    int nMatches = static_cast<int>(pointMatches.size());
    int iterations = std::min(kRANSACIterations, nMatches * nMatches);

    for (int iter = 0; iter < iterations; ++iter) {
        // Sample 2 random matches
        std::vector<int> sample(2);
        sample[0] = rng() % nMatches;
        do { sample[1] = rng() % nMatches; } while (sample[1] == sample[0]);

        AlignmentTransform candidate;
        if (!solveSimilarityTransform(refStars, targetStars, pointMatches, sample, candidate)) {
            continue;
        }

        // Count inliers
        int inliers = 0;
        for (int i = 0; i < nMatches; ++i) {
            int ri = pointMatches[i].first;
            int ti = pointMatches[i].second;

            float predX, predY;
            transformPoint(candidate, refStars[ri].x, refStars[ri].y, predX, predY);

            float errX = predX - targetStars[ti].x;
            float errY = predY - targetStars[ti].y;
            float err = std::sqrt(errX * errX + errY * errY);

            if (err < kRANSACInlierThreshold) {
                inliers++;
            }
        }

        if (inliers > bestInliers) {
            bestInliers = inliers;
            bestTransform = candidate;
        }

        // Early termination if we have very high consensus
        if (bestInliers >= nMatches * 0.9f) break;
    }

    if (bestInliers < 2) {
        LOGW("solveAlignment: RANSAC failed, best inlier count = %d", bestInliers);
        return false;
    }

    // Step 4: Refine transform using all inliers
    std::vector<int> inlierIndices;
    for (int i = 0; i < nMatches; ++i) {
        int ri = pointMatches[i].first;
        int ti = pointMatches[i].second;

        float predX, predY;
        transformPoint(bestTransform, refStars[ri].x, refStars[ri].y, predX, predY);

        float errX = predX - targetStars[ti].x;
        float errY = predY - targetStars[ti].y;
        float err = std::sqrt(errX * errX + errY * errY);

        if (err < kRANSACInlierThreshold) {
            inlierIndices.push_back(i);
        }
    }

    if (inlierIndices.size() >= 2) {
        solveSimilarityTransform(refStars, targetStars, pointMatches, inlierIndices, bestTransform);
    }

    outTransform = bestTransform;

    // Log transform info
    float scale = std::sqrt(outTransform.m00 * outTransform.m00 + outTransform.m10 * outTransform.m10);
    float rotDeg = std::atan2(outTransform.m10, outTransform.m00) * 180.0f / 3.14159265f;
    LOGI("solveAlignment: Success! Inliers=%d/%d, Scale=%.4f, Rot=%.3f°, Tx=%.2f, Ty=%.2f",
         bestInliers, nMatches, scale, rotDeg, outTransform.tx, outTransform.ty);

    return true;
}

} // namespace starstack
