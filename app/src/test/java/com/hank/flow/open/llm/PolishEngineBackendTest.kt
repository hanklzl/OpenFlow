package com.hank.flow.open.llm

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PolishEngineBackendTest {

    @Test
    fun ensureLoadedUsesRequestedBackend() = runBlocking {
        val bridge = FakeLlamaBridge(initResults = ArrayDeque(listOf(7L)))
        val engine = PolishEngine(
            modelPath = "model.gguf",
            backend = InferenceBackend.OpenCl,
            nGpuLayers = -1,
            bridge = bridge,
        )

        assertTrue(engine.ensureLoaded())

        assertEquals(
            listOf(InitCall("model.gguf", ctxSize = 1024, nGpuLayers = -1, backendName = "opencl")),
            bridge.initCalls,
        )
    }

    @Test
    fun ensureLoadedFallsBackToCpuWhenAcceleratedBackendReturnsZero() = runBlocking {
        val bridge = FakeLlamaBridge(initResults = ArrayDeque(listOf(0L, 11L)))
        val engine = PolishEngine(
            modelPath = "model.gguf",
            backend = InferenceBackend.Vulkan,
            nGpuLayers = -1,
            bridge = bridge,
        )

        assertTrue(engine.ensureLoaded())

        assertEquals(
            listOf(
                InitCall("model.gguf", ctxSize = 1024, nGpuLayers = -1, backendName = "vulkan"),
                InitCall("model.gguf", ctxSize = 1024, nGpuLayers = 0, backendName = null),
            ),
            bridge.initCalls,
        )
    }

    @Test
    fun ensureLoadedFallsBackToCpuWhenAcceleratedBackendThrows() = runBlocking {
        val bridge = FakeLlamaBridge(
            initResults = ArrayDeque(listOf(11L)),
            throwOnInitCall = 1,
        )
        val engine = PolishEngine(
            modelPath = "model.gguf",
            backend = InferenceBackend.OpenCl,
            nGpuLayers = -1,
            bridge = bridge,
        )

        assertTrue(engine.ensureLoaded())

        assertEquals(
            listOf(
                InitCall("model.gguf", ctxSize = 1024, nGpuLayers = -1, backendName = "opencl"),
                InitCall("model.gguf", ctxSize = 1024, nGpuLayers = 0, backendName = null),
            ),
            bridge.initCalls,
        )
    }

    @Test
    fun ensureLoadedReturnsFalseWhenJniLibraryIsUnavailable() = runBlocking {
        val bridge = FakeLlamaBridge(loadedValue = false)
        val engine = PolishEngine(
            modelPath = "model.gguf",
            backend = InferenceBackend.Vulkan,
            nGpuLayers = -1,
            bridge = bridge,
        )

        assertFalse(engine.ensureLoaded())
        assertTrue(bridge.initCalls.isEmpty())
    }

    private data class InitCall(
        val modelPath: String,
        val ctxSize: Int,
        val nGpuLayers: Int,
        val backendName: String?,
    )

    private class FakeLlamaBridge(
        private val initResults: ArrayDeque<Long> = ArrayDeque(listOf(1L)),
        private val loadedValue: Boolean = true,
        private val throwOnInitCall: Int? = null,
    ) : LlamaBridge {
        val initCalls = mutableListOf<InitCall>()

        override val loaded: Boolean
            get() = loadedValue

        override fun init(modelPath: String, ctxSize: Int, nGpuLayers: Int, backendName: String?): Long {
            initCalls += InitCall(modelPath, ctxSize, nGpuLayers, backendName)
            if (initCalls.size == throwOnInitCall) error("init failed")
            return initResults.removeFirstOrNull() ?: 0L
        }

        override fun generate(
            handle: Long,
            prompt: String,
            maxNewTokens: Int,
            temperature: Float,
            topP: Float,
        ): String = "润色后"

        override fun generateStreaming(
            handle: Long,
            prompt: String,
            maxNewTokens: Int,
            temperature: Float,
            topP: Float,
            sink: LlamaJni.TokenSink,
        ): String = "润色后"

        override fun prewarmPrefix(handle: Long, prefixText: String): Long = 101L

        override fun polishStreamingWithPrefix(
            handle: Long,
            prefixHandle: Long,
            userText: String,
            suffix: String,
            maxNewTokens: Int,
            temperature: Float,
            topP: Float,
            sink: LlamaJni.TokenSink,
        ): String = "润色后"

        override fun freePrefix(prefixHandle: Long) = Unit

        override fun free(handle: Long) = Unit
    }
}
