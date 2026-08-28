package eu.kanade.tachiyomi.ui.reader.spatial

import android.content.Context
import android.util.Log
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class DepthSpatialPipeline(
    private val context: Context,
    private val cache: SpatialSceneCache = SpatialSceneCache(context),
    private val model: DepthSpatialModel = DepthSpatialModel(context),
) {
    sealed interface Result {
        data class Ready(val file: File, val fromCache: Boolean) : Result
        data object ModelMissing : Result
        data object RuntimeUnavailable : Result
        data class Failed(val cause: Throwable) : Result
    }

    suspend fun create(
        page: ReaderPage,
        onCompilationStarted: suspend (htpArchitecture: Int?) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        cache.cachedScene(page)?.let { return@withContext Result.Ready(it, fromCache = true) }
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        if (!DepthQnnBridge.isRuntimeAvailable(nativeLibraryDir)) return@withContext Result.RuntimeUnavailable
        if (!model.isReady) return@withContext Result.ModelMissing

        runCatching {
            val source = page.enhancementStream ?: page.stream
                ?: error("The current page has no readable image stream")
            val prepared = source().use(DepthImagePreprocessor::prepare)
            val contextFile = model.contextFile().apply { parentFile?.mkdirs() }
            if (!contextFile.isFile) {
                withContext(Dispatchers.Main.immediate) {
                    onCompilationStarted(model.htpArchitecture)
                }
            }
            val depthInputs = buildList {
                add(prepared.input)
                prepared.detailTiles.forEach { add(it.input) }
            }
            val depthResults = DepthQnnBridge.inferDepthBatch(
                inputs = depthInputs,
                modelPath = model.dlcFile.absolutePath,
                contextPath = contextFile.absolutePath,
                nativeLibraryDir = nativeLibraryDir,
            ) ?: error(DepthQnnBridge.lastError().ifBlank { "Depth Anything V3 QNN inference failed" })
            val scene = SpatialDepthSceneBuilder.build(
                prepared = prepared,
                depth = depthResults.first(),
                detailDepths = depthResults.drop(1),
            )
            cache.prepare(page)
            val outputFile = cache.sceneFile(page)
            val temporaryOutput = File(outputFile.parentFile, "${outputFile.name}.partial")
            temporaryOutput.delete()
            SpatialDepthSceneIO.write(temporaryOutput, scene)
            outputFile.delete()
            check(temporaryOutput.renameTo(outputFile)) { "Unable to commit the spatial scene cache" }
            Result.Ready(outputFile, fromCache = false)
        }.getOrElse { error ->
            Log.e(
                TAG,
                "Spatial scene failed for chapter=${page.chapter.chapter.id}, page=${page.index}, " +
                    "variant=${page.enhancementKeySuffix.ifBlank { "full" }}",
                error,
            )
            Result.Failed(error)
        }
    }

    private companion object {
        const val TAG = "DepthSpatialPipeline"
    }
}

internal object DepthQnnBridge {
    private const val INPUT_ELEMENTS = 518 * 518 * 3
    private const val OUTPUT_ELEMENTS = 518 * 518
    private val loaded = runCatching {
        System.loadLibrary("waifu2x-jni")
        true
    }.getOrDefault(false)

    fun isRuntimeAvailable(nativeLibraryDir: String): Boolean =
        loaded && runCatching { nativeIsRuntimeAvailable(nativeLibraryDir) }.getOrDefault(false)

    fun inferDepth(
        input: FloatArray,
        modelPath: String,
        contextPath: String,
        nativeLibraryDir: String,
    ): FloatArray? = if (loaded) {
        nativeInferDepth(input, modelPath, contextPath, nativeLibraryDir)
    } else {
        null
    }

    fun inferDepthBatch(
        inputs: List<FloatArray>,
        modelPath: String,
        contextPath: String,
        nativeLibraryDir: String,
    ): List<FloatArray>? {
        if (!loaded || inputs.isEmpty() || inputs.size > 4 || inputs.any { it.size != INPUT_ELEMENTS }) {
            return null
        }
        val combinedInputs = FloatArray(inputs.size * INPUT_ELEMENTS)
        inputs.forEachIndexed { index, input ->
            input.copyInto(combinedInputs, destinationOffset = index * INPUT_ELEMENTS)
        }
        val combinedOutputs = nativeInferDepthBatch(
            inputs = combinedInputs,
            batchCount = inputs.size,
            modelPath = modelPath,
            contextPath = contextPath,
            nativeLibraryDir = nativeLibraryDir,
        ) ?: return null
        if (combinedOutputs.size != inputs.size * OUTPUT_ELEMENTS) return null
        return List(inputs.size) { index ->
            combinedOutputs.copyOfRange(index * OUTPUT_ELEMENTS, (index + 1) * OUTPUT_ELEMENTS)
        }
    }

    fun lastError(): String = if (loaded) runCatching { nativeLastError() }.getOrDefault("") else ""

    private external fun nativeIsRuntimeAvailable(nativeLibraryDir: String): Boolean
    private external fun nativeInferDepth(
        input: FloatArray,
        modelPath: String,
        contextPath: String,
        nativeLibraryDir: String,
    ): FloatArray?
    private external fun nativeInferDepthBatch(
        inputs: FloatArray,
        batchCount: Int,
        modelPath: String,
        contextPath: String,
        nativeLibraryDir: String,
    ): FloatArray?
    private external fun nativeLastError(): String
}
