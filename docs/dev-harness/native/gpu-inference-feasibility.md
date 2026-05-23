# GPU Inference on Android — Feasibility Evaluation

> Captured during the ASR + polish latency optimization pass (Phase 0–5 ran first
> on CPU; this is the "should we also do GPU?" follow-up.)

## TL;DR

- **Vulkan via llama.cpp's ggml-vulkan backend** is the only path that is
  technically realistic right now. We ship the CMake/Gradle skeleton (default
  OFF) so anyone can experiment by passing `-POpenflowEnableVulkan=true`, but
  shipping it in the default APK requires more groundwork than this PR covers.
- **NPU paths** (Snapdragon QNN/HTP, MediaTek APU, Samsung ENN, MediaPipe LLM
  Inference) are NOT viable for GGUF + llama.cpp today. They'd require
  re-quantising/exporting the models into vendor-specific formats and rewriting
  the inference layer.
- **Expected user-perceived win** if Vulkan is wired up end-to-end:
  - Whisper encoder: 2–4× faster (compute-bound)
  - LLM polish prefill: 2–3× faster
  - LLM polish decode: only 1.2–1.5× faster (memory-bandwidth-bound; GPU shares
    LPDDR with CPU)
  - Whole pipeline: roughly 30–50% off the *post-Phase-5* latency for the
    common short-output polish case.

## Why we didn't ship it in this PR

| Blocker | Detail |
|---|---|
| **LunarG Vulkan SDK is required on the host** | ggml-vulkan's CMake does `find_package(Vulkan COMPONENTS glslc REQUIRED)` and `find_package(SPIRV-Headers REQUIRED)`. NDK r29 ships `glslc` under `shader-tools/<host>/glslc` (so we auto-add it to PATH), but it does NOT ship the `SPIRV-Headers` CMake config or the Vulkan CMake find module that LunarG provides. Every contributor + CI runner would need a ~500 MB SDK install. |
| **No runtime CPU fallback yet** | If `GGML_VULKAN=ON` but the device's Vulkan driver is unusable, `llama_model_load_from_file` with `n_gpu_layers > 0` may fail. Need a try-GPU-then-CPU path with metric tracking. |
| **APK size** | ~5–8 MB extra for compiled SPIR-V shaders + ggml-vulkan code. |
| **First-build time** | ggml has 181 GLSL shaders; clean build adds 5–10 minutes per ABI. CI cache mitigates incremental builds but cold builds hurt. |
| **Driver quality** | Adreno (Qualcomm) generally fine on flagship; mid-range Mali (Cortex-A55 class) has known llama.cpp Vulkan stability issues; PowerVR rare. Needs device matrix testing before we surface a user toggle. |
| **Interaction with Phase 5 KV prefix cache** | `llama_state_seq_get_data` / `set_data` semantics under partial GPU offload need verification — the blob may not be portable between CPU-only and GPU-offloaded contexts. Risk of returning garbage tokens if not handled. |

## What we DID ship

- `app/src/main/cpp/CMakeLists.txt`: `OPENFLOW_ENABLE_VULKAN` option (default
  OFF). When ON, sets `GGML_VULKAN=ON`, auto-prepends NDK's bundled glslc to
  PATH, and prints a STATUS line explaining the LunarG SDK requirement.
- `app/build.gradle.kts`: propagates `-POpenflowEnableVulkan=true` from a
  Gradle property to the CMake argument list.

That's it. **No** Kotlin/Settings UI changes — we intentionally did NOT add a
"Enable GPU inference" toggle, because a toggle that does nothing in the
default APK (which is compiled without GGML_VULKAN) would be misleading.

## Roadmap to a shipped GPU mode

If we want to make this real, here's the order of operations:

1. **CI infra**: bake LunarG Vulkan SDK into the GitHub Actions runner image
   (or vendor SPIRV-Headers into `third_party/`).
2. **Build matrix**: produce both CPU-only and `+vulkan` APK splits. Don't
   force GPU compile-in on all users until step 4.
3. **Runtime fallback path**: at `PolishEngine.ensureLoaded` and
   `WhisperEngine.ensureLoaded`, try `nGpuLayers=99` first; on failure (detected
   via llama.cpp logs or a `llama_backend_dev_count` query), retry with
   `nGpuLayers=0` and remember the result in `SettingsStore` so we don't probe
   on every cold start.
4. **Settings toggle**: surface "GPU 推理（实验）" only when the build supports
   it AND runtime probe succeeded. Default OFF for one release cycle so user
   reports surface driver-specific issues before we flip the default.
5. **Phase 5 + GPU compatibility check**: test KV prefix cache round-trip
   across CPU/GPU; document any restriction.
6. **Device matrix smoke test**: Snapdragon 8 Gen 2/3, Tensor G3, Dimensity
   9000, Mali-G715, Mali-G610 mid-range, Adreno 6xx mid-range. Minimum: verify
   `polishStreaming` produces same text as CPU-only mode (token IDs may
   differ slightly due to ordering — compare semantic similarity).

## Why not NPU

| Vendor | Toolchain | Blocker |
|---|---|---|
| Qualcomm QNN (HTP/NPU) | QNN SDK + AIMET | Qwen GGUF not supported; conversion path requires re-quantizing to INT8 in QNN's format, no upstream automation |
| MediaTek APU (NeuroPilot) | NeuroPilot SDK | Same as above; no Qwen support |
| Samsung ENN | Exynos-only, partner-NDA | N/A |
| Google EdgeTPU / Pixel Tensor | TFLite Delegate | Doesn't accept GGUF; would need full reimplementation |
| MediaPipe LLM Inference | Google's reference impl | Only supports Gemma family; Qwen isn't on the roadmap |

None of these have a GGUF input path, so adopting any of them is equivalent to
choosing a new inference stack — out of scope for a latency tuning pass.

## Related code

- `app/src/main/cpp/CMakeLists.txt` — `OPENFLOW_ENABLE_VULKAN` option
- `app/build.gradle.kts` — `-POpenflowEnableVulkan=true` propagation
- `app/src/main/cpp/jni_whisper.cpp:19` — `cparams.use_gpu = false` (would need
  to become settings-driven once GPU is shipped)
- `app/src/main/cpp/jni_llama.cpp:70` — `mparams.n_gpu_layers = nGpuLayers`
  (already pluggable; `LlamaJni.nativeInit` accepts the param, just hardcoded to
  0 by `PolishEngine` today)
