#include <bgfx_compute.sh>

uniform vec4 DisplayResolution;
uniform vec4 RenderResolution;
uniform vec4 RecipDisplayResolution;
uniform vec4 RenderResolutionDivDisplayResolution;
uniform vec4 DisplayResolutionDivRenderResolution;
uniform vec4 SubPixelJitter;

uniform mat4 PreviousViewProjectionMatrixUniform;
uniform mat4 CurrentViewProjectionMatrixUniform;
uniform vec4 CurrentWorldOrigin;
uniform vec4 PreviousWorldOrigin;

SAMPLER2D(s_InputFinalColor, 0);
SAMPLER2D(s_InputTAAHistory, 1);
SAMPLER2D(s_InputBufferMotionVectors, 2);
IMAGE2D_WR(s_OutputBuffer, rgba16f, 3);

vec2 computeMotionVectorForEnvironment(vec3 pixelWorldPos, mat4 currentViewProj, mat4 previousViewProj, vec3 currentWorldOrigin, vec3 previousWorldOrigin) {
    vec4 clipPos = mul(currentViewProj, vec4(pixelWorldPos, 0.0));
    vec3 previousPixelWorldPosition = pixelWorldPos + currentWorldOrigin - previousWorldOrigin;
    vec4 prevClipPos = mul(previousViewProj, vec4(previousPixelWorldPosition, 0.0));
    if (clipPos.w <= 0.0 || prevClipPos.w <= 0.0) {
        return vec2(0.0, 0.0);
    }
    vec2 ndcPos = clipPos.xy / clipPos.ww;
    vec2 prevNdcPos = prevClipPos.xy / prevClipPos.ww;
    vec2 currentToPreviousNdcXy = prevNdcPos - ndcPos;
    return currentToPreviousNdcXy * vec2(0.5f, -0.5f);
}

vec3 bicubicSampleCatmullRom(sampler2D tex, vec2 samplePos, vec2 recipTextureResolution) {
    vec2 tc = floor(samplePos - 0.5) + 0.5;
    vec2 f = clamp(samplePos - tc, 0.0, 1.0);
    vec2 f2 = f * f;
    vec2 f3 = f2 * f;
    vec2 w0 = f2 - 0.5 * (f3 + f);
    vec2 w1 = 1.5 * f3 - 2.5 * f2 + 1.0;
    vec2 w3 = 0.5 * (f3 - f2);
    vec2 w2 = 1.0 - w0 - w1 - w3;
    vec2 w12 = w1 + w2;
    vec2 tc0 = (tc - 1.0) * recipTextureResolution;
    vec2 tc12 = (tc + w2 / w12) * recipTextureResolution;
    vec2 tc3 = (tc + 2.0) * recipTextureResolution;
    vec3 result =
        texture2DLod(tex, vec2(tc0.x, tc0.y), 0.0).rgb * (w0.x * w0.y) +
        texture2DLod(tex, vec2(tc0.x, tc12.y), 0.0).rgb * (w0.x * w12.y) +
        texture2DLod(tex, vec2(tc0.x, tc3.y), 0.0).rgb * (w0.x * w3.y) +
        texture2DLod(tex, vec2(tc12.x, tc0.y), 0.0).rgb * (w12.x * w0.y) +
        texture2DLod(tex, vec2(tc12.x, tc12.y), 0.0).rgb * (w12.x * w12.y) +
        texture2DLod(tex, vec2(tc12.x, tc3.y), 0.0).rgb * (w12.x * w3.y) +
        texture2DLod(tex, vec2(tc3.x, tc0.y), 0.0).rgb * (w3.x * w0.y) +
        texture2DLod(tex, vec2(tc3.x, tc12.y), 0.0).rgb * (w3.x * w12.y) +
        texture2DLod(tex, vec2(tc3.x, tc3.y), 0.0).rgb * (w3.x * w3.y);
    return max(vec3(0.0, 0.0, 0.0), result);
}

float sampleWeight(vec2 delta, float scale) {
    float x = scale * dot(delta, delta);
    return clamp(1.0 - x, 0.05, 1.0);
}

void TAAU(uvec3 GlobalInvocationID) {
    uint x = GlobalInvocationID.x;
    uint y = GlobalInvocationID.y;
    vec2 nearestRenderPos = vec2(float(x) + 0.5f, float(y) + 0.5f) * RenderResolutionDivDisplayResolution.x - SubPixelJitter.xy - 0.5f;
    ivec2 intRenderPos = ivec2(round(nearestRenderPos.x), round(nearestRenderPos.y));
    vec4 currentColor = texelFetch(s_InputFinalColor, intRenderPos, 0).rgba;
    vec2 motionPixels = texelFetch(s_InputBufferMotionVectors, intRenderPos, 0).ba;
#if !BGFX_SHADER_LANGUAGE_GLSL
    motionPixels.y *= -1.0;
#endif
    vec3 c1 = currentColor.rgb;
    vec3 c2 = currentColor.rgb * currentColor.rgb;
    for (int i = -1; i <= 1; i++) {
        for (int j = -1; j <= 1; j++) {
            if (i == 0 && j == 0)
                continue;
            ivec2 p = intRenderPos + ivec2(i, j);
            vec3 c = texelFetch(s_InputFinalColor, p, 0).rgb;
            vec2 mv = texelFetch(s_InputBufferMotionVectors, p, 0).ba;
            c1 = c1 + c;
            c2 = c2 + c * c;
        }
    }
    motionPixels *= RenderResolution.xy;
    c1 = c1 / 9.0f;
    c2 = c2 / 9.0f;
    vec3 extent = sqrt(max(vec3(0.0, 0.0, 0.0), c2 - c1 * c1));
    float motionWeight = smoothstep(0.0, 1.0f, sqrt(dot(motionPixels, motionPixels)));
    float bias = mix(4.0f, 1.0f, motionWeight);
    vec3 minValidColor = c1 - extent * bias;
    vec3 maxValidColor = c1 + extent * bias;
    vec2 posPreviousPixels = vec2(float(x) + 0.5f, float(y) + 0.5f) - motionPixels * DisplayResolutionDivRenderResolution.x;
    posPreviousPixels = clamp(posPreviousPixels, vec2(0, 0), DisplayResolution.xy - 1.0f);
    vec3 prevColor = bicubicSampleCatmullRom(s_InputTAAHistory, posPreviousPixels, RecipDisplayResolution.xy);
    prevColor = min(maxValidColor, max(minValidColor, prevColor));
    float pixelWeight = max(motionWeight, sampleWeight(nearestRenderPos - vec2(intRenderPos), DisplayResolutionDivRenderResolution.x)) * 0.1f;
    vec3 finalColor = mix(prevColor, currentColor.rgb, pixelWeight);
    imageStore(s_OutputBuffer, ivec2(x, y), vec4(finalColor, 0.0));
}

NUM_THREADS(16, 16, 1)
void main() {
    TAAU(gl_GlobalInvocationID);
}
