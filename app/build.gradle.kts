import com.android.build.api.variant.ApplicationVariant
import com.android.build.api.variant.BuildConfigField
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import mihon.gradle.Config
import mihon.gradle.getCurrentTime
import mihon.gradle.getLatestCommitCount
import mihon.gradle.getLatestCommitSha
import mihon.gradle.getLatestCommitTime
import mihon.gradle.tasks.ReplaceShortcutsPlaceholderTask
import org.gradle.api.tasks.Sync
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(mihonx.plugins.android.application)
    alias(mihonx.plugins.compose)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.metro)
    alias(libs.plugins.aboutLibraries)
    alias(libs.plugins.androidx.baselineProfile)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.apollo)
}

if (Config.includeTelemetry) {
    pluginManager.apply {
        apply(libs.plugins.google.services.get().pluginId)
        apply(libs.plugins.firebase.crashlytics.get().pluginId)
    }
}

val keystorePropertiesFile = layout.settingsDirectory.file("keystore.properties").asFile

val localProperties = Properties().apply {
    val file = layout.settingsDirectory.file("local.properties").asFile
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

val bundledNcnnSdkDir = layout.settingsDirectory.dir("third_party/ncnn-20260113-android-vulkan").asFile

// AGP cannot shrink resources for an App Bundle when ABI split APKs are enabled.
// Bundles already contain ABI-specific native libraries, so disable APK splits only for bundle tasks.
val isBundleBuild = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }

val ncnnSdkDir = providers.gradleProperty("ncnnSdkDir").orNull
    ?: localProperties.getProperty("ncnn.sdk.dir")
    ?: System.getenv("NCNN_SDK_DIR")
    ?: bundledNcnnSdkDir.takeIf { it.exists() }?.absolutePath

val qnnSdkDir = providers.gradleProperty("qnnSdkDir").orNull
    ?: localProperties.getProperty("qnn.sdk.dir")
    ?: System.getenv("QNN_SDK_ROOT")

val qnnDlcDir = providers.gradleProperty("qnnDlcDir").orNull
    ?: localProperties.getProperty("qnn.dlc.dir")
    ?: System.getenv("QNN_DLC_DIR")

val qnnSdkRoot = qnnSdkDir?.takeIf { it.isNotBlank() }?.let { layout.settingsDirectory.file(it).asFile }
val qnnDlcRoot = qnnDlcDir?.takeIf { it.isNotBlank() }?.let { layout.settingsDirectory.file(it).asFile }
val qnnW8a8DlcDir = providers.gradleProperty("qnnW8a8DlcDir").orNull
    ?: localProperties.getProperty("qnn.w8a8.dlc.dir")
    ?: System.getenv("QNN_W8A8_DLC_DIR")
val qnnW8a8DlcRoot = qnnW8a8DlcDir?.let { layout.settingsDirectory.file(it).asFile }
    ?: qnnDlcRoot?.resolve("../dlc-w8a8")
// Keep in sync with QnnModelContexts.supportsW8a8. Other models require wider activations.
val qnnW8a8ModelNames = listOf("realesrgan-animevideov3-x2")
val generatedQnnAssetsDir = layout.buildDirectory.dir("generated/qnnAssets")
val generatedQnnJniLibsDir = layout.buildDirectory.dir("generated/qnnJniLibs")
val qnnModelNames = listOf(
    "realesrgan-animevideov3-x2",
    "realesrgan-general-x4v3-x2",
    "realcugan-se-x2-no-denoise",
    "realcugan-se-x2-denoise1x",
    "realcugan-se-x2-denoise2x",
    "realcugan-se-x2-denoise3x",
    "realcugan-se-x2-conservative",
    "realcugan-pro-x2-no-denoise",
    "realcugan-pro-x2-denoise3x",
    "realcugan-pro-x2-conservative",
    "realcugan-pro-x3-no-denoise",
    "realcugan-pro-x3-denoise3x",
    "realcugan-pro-x3-conservative",
    "span-nomosuni-x2",
    "w2xex-photo-small-x2",
)
val stageQnnModels = qnnDlcRoot?.let { dlcRoot ->
    val modelFiles = qnnModelNames.associateWith { dlcRoot.resolve("$it.dlc") } +
        qnnW8a8ModelNames.associate { model ->
            "$model-w8a8" to requireNotNull(qnnW8a8DlcRoot).resolve("$model-w8a8.dlc")
        }
    tasks.register<Sync>("stageQnnModels") {
        modelFiles.forEach { (_, source) ->
            from(source) { into("qnn-models") }
        }
        into(generatedQnnAssetsDir)
        doFirst {
            val missingFiles = modelFiles.filterValues { !it.isFile }.keys
            check(missingFiles.isEmpty()) {
                "QNN FP16/W8A8 DLC inputs are incomplete: ${missingFiles.joinToString()}"
            }
        }
        doLast {
            val entries = modelFiles.entries.joinToString(",\n") { (model, source) ->
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.readBytes())
                    .joinToString("") { "%02x".format(it) }
                "  \"$model\": \"$digest\""
            }
            destinationDir.resolve("qnn-models/manifest.json").writeText("{\n$entries\n}\n")
        }
    }
}
val mediaTekTfliteDir = providers.gradleProperty("mediatekTfliteDir").orNull
    ?: localProperties.getProperty("mediatek.tflite.dir")
    ?: System.getenv("MEDIATEK_TFLITE_DIR")
val generatedMediaTekAssetsDir = layout.buildDirectory.dir("generated/mediatekAssets")
val stageMediaTekModels = mediaTekTfliteDir?.takeIf { it.isNotBlank() }?.let { path ->
    val root = layout.settingsDirectory.file(path).asFile
    val models = qnnModelNames
    tasks.register<Sync>("stageMediaTekModels") {
        models.forEach { model ->
            from(root.resolve("$model.tflite")) { into("litert-models") }
        }
        into(generatedMediaTekAssetsDir)
        doFirst {
            val missing = models.filterNot { root.resolve("$it.tflite").isFile }
            check(missing.isEmpty()) { "MediaTek FP16 models are incomplete: ${missing.joinToString()}" }
        }
        doLast {
            val entries = models.joinToString(",\n") { model ->
                val hash = MessageDigest.getInstance("SHA-256").digest(root.resolve("$model.tflite").readBytes())
                    .joinToString("") { "%02x".format(it) }
                "  \"$model\": \"$hash\""
            }
            destinationDir.resolve("litert-models/manifest.json").writeText("{\n$entries\n}\n")
        }
    }
}

val stageQnnDlcRuntime = qnnSdkRoot?.let { sdkRoot ->
    // libQnnHtpPrepare.so is supplied by the matching qnn-runtime AAR.
    val libraries = listOf("libQnnModelDlc.so")
    tasks.register<Sync>("stageQnnDlcRuntime") {
        libraries.forEach { from(sdkRoot.resolve("lib/aarch64-android/$it")) }
        into(generatedQnnJniLibsDir.map { it.dir("arm64-v8a") })
        doFirst {
            libraries.forEach { library ->
                check(sdkRoot.resolve("lib/aarch64-android/$library").isFile) {
                    "QNN DLC runtime not found: $library"
                }
            }
        }
    }
}

android {
    namespace = "eu.kanade.tachiyomi"

    defaultConfig {
        applicationId = "app.mihon"

        versionCode = 46
        versionName = "1.3.17-beta.3"
        buildConfigField("int", "UPSTREAM_VERSION_CODE", "38")

        buildConfigField("boolean", "TELEMETRY_INCLUDED", "${Config.includeTelemetry}")
        buildConfigField("boolean", "UPDATER_ENABLED", "${Config.enableUpdater}")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                if (!ncnnSdkDir.isNullOrBlank()) {
                    arguments += "-DNCNN_SDK_DIR=$ncnnSdkDir"
                }
                if (qnnSdkRoot != null) {
                    arguments += "-DQNN_SDK_DIR=${qnnSdkRoot.absolutePath}"
                }
            }
        }
    }

    if (System.getenv("MIHON_GITHUB_RELEASE").toBoolean()) {
        signingConfigs {
            named("debug") {
                storeFile = file(System.getenv("storeFile"))
                storePassword = System.getenv("storePassword")
                keyAlias = System.getenv("keyAlias")
                keyPassword = System.getenv("keyPassword")
            }
        }
    } else if (keystorePropertiesFile.exists()) {
        val keystoreProperties = FileInputStream(keystorePropertiesFile).use { Properties().apply { load(it) } }

        signingConfigs {
            named("debug") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        val debug = getByName("debug") {
            applicationIdSuffix = ".dev"
            isPseudoLocalesEnabled = true
        }
        val release = getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true

            signingConfig = debug.signingConfig

            isProfileable = true

            proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")
        }

        val commonMatchingFallbacks = listOf(release.name)

        create("foss") {
            initWith(release)

            applicationIdSuffix = ".foss"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("nightly") {
            initWith(release)

            applicationIdSuffix = ".debug"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }
        create("benchmark") {
            initWith(release)

            versionNameSuffix = "-benchmark"
            applicationIdSuffix = ".benchmark"

            matchingFallbacks.addAll(commonMatchingFallbacks)
        }

        if (Config.includeTelemetry) {
            configureEach {
                configure<CrashlyticsExtension> {
                    mappingFileUploadEnabled = Config.uploadCrashlyticsMapping
                }
            }
        }
    }

    sourceSets {
        getByName("nightly").res.directories.add("src/debug/res")
        getByName("benchmark").res.directories.add("src/debug/res")
        if (stageMediaTekModels != null) {
            getByName("main").assets.directories.add(generatedMediaTekAssetsDir.get().asFile.path)
        }
        if (stageQnnModels != null) {
            getByName("main").assets.directories.add(generatedQnnAssetsDir.get().asFile.path)
        }
        if (stageQnnDlcRuntime != null) {
            getByName("main").jniLibs.directories.add(generatedQnnJniLibsDir.get().asFile.path)
        }
    }

    splits {
        abi {
            isEnable = !isBundleBuild
            isUniversalApk = !isBundleBuild
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += listOf(
                "**/libQnnDsp.so",
                "**/libQnnDspV66Skel.so",
                "**/libQnnDspV66Stub.so",
                "**/libQnnGpu.so",
                "**/libQnnHtpV68Skel.so",
                "**/libQnnHtpV68Stub.so",
            )
            keepDebugSymbols += listOf(
                "libandroidx.graphics.path",
                "libarchive-jni",
                "libconscrypt_jni",
                "libimagedecoder",
                "libquickjs",
                "libsqlite3x",
            )
                .map { "**/$it.so" }
        }
        resources {
            excludes += setOf(
                "kotlin-tooling-metadata.json",
                "LICENSE.txt",
                "META-INF/**/*.properties",
                "META-INF/**/LICENSE.txt",
                "META-INF/*.properties",
                "META-INF/*.version",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/NOTICE",
                "META-INF/README.md",
            )
        }
    }

    dependenciesInfo {
        includeInApk = Config.includeDependencyInfo
        includeInBundle = Config.includeDependencyInfo
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        aidl = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "28.2.13676358"

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

if (stageMediaTekModels != null) {
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
        dependsOn(stageMediaTekModels)
    }
}

if (stageQnnModels != null) {
    tasks.configureEach {
        if (name.startsWith("merge") && name.endsWith("Assets")) {
            dependsOn(stageQnnModels)
        }
    }
}

if (stageQnnDlcRuntime != null) {
    tasks.configureEach {
        if (name.startsWith("merge") && name.endsWith("JniLibFolders")) {
            dependsOn(stageQnnDlcRuntime)
        }
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.animation.ExperimentalAnimationApi",
            "-opt-in=androidx.compose.animation.graphics.ExperimentalAnimationGraphicsApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-opt-in=coil3.annotation.ExperimentalCoilApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=kotlinx.coroutines.FlowPreview",
            "-opt-in=kotlinx.coroutines.InternalCoroutinesApi",
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
}

baselineProfile {
    baselineProfileOutputDir = "baselineProfiles"
    mergeIntoMain = true
}

dependencies {
    implementation("com.qualcomm.qti:qnn-runtime:2.49.0")
    implementation("com.google.ai.edge.litert:litert:2.3.0")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-mediatek:2.3.0")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation("androidx.test:runner:1.7.0")
    baselineProfile(projects.baselineProfile)

    implementation(projects.i18n)
    implementation(projects.icons.customMaterialSymbols)
    implementation(projects.icons.materialSymbols)
    implementation(projects.icons.simpleIcons)
    implementation(projects.core.archive)
    implementation(projects.core.common)
    implementation(projects.core.metro)
    implementation(projects.coreMetadata)
    implementation(projects.sourceApi)
    implementation(projects.sourceLocal)
    implementation(projects.data)
    implementation(projects.domain)
    implementation(projects.presentationCore)
    implementation(projects.presentationWidget)
    implementation(projects.telemetry)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.animationGraphics)
    debugImplementation(libs.androidx.compose.uiTooling)
    implementation(libs.androidx.compose.uiToolingPreview)
    implementation(libs.androidx.compose.uiUtil)
    implementation(libs.androidx.navigationEvent)

    implementation(libs.androidx.interpolator)

    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    implementation(libs.kotlin.reflect)

    implementation(libs.bundles.kotlinx.coroutines)

    implementation(libs.kotlinx.datetime)

    // AndroidX libraries
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.appCompat)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.constraintLayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.coreSplashScreen)
    implementation(libs.androidx.recyclerView)
    implementation(libs.androidx.viewPager)
    implementation(libs.androidx.profileInstaller)

    implementation(libs.bundles.androidx.lifecycle)

    // Job scheduling
    implementation(libs.androidx.work)

    // RxJava
    implementation(libs.rxJava)

    // Networking
    implementation(libs.bundles.okhttp)
    implementation(libs.okio)
    implementation(libs.conscrypt) // TLS 1.3 support for Android < 10

    // Data serialization (JSON, protobuf, xml)
    implementation(libs.bundles.serialization)

    // HTML parser
    implementation(libs.jsoup)

    // Disk
    implementation(libs.diskLruCache)
    implementation(libs.unifile)

    // Preferences
    implementation(libs.androidx.preference)

    // Dependency injection
    implementation(libs.injekt)
    implementation(libs.metro.runtime)
    implementation(libs.metrox.viewmodel)
    implementation(libs.metrox.viewmodel.compose)

    // Image loading
    implementation(libs.bundles.coil)
    implementation(libs.subsamplingScaleImageView) {
        exclude(module = "image-decoder")
    }
    implementation(libs.image.decoder)

    implementation(libs.webgpuviewer)

    // UI libraries
    implementation(libs.material)
    implementation(libs.flexibleAdapter)
    implementation(libs.photoView)
    implementation(libs.directionalViewPager) {
        exclude(group = "androidx.viewpager", module = "viewpager")
    }
    implementation(libs.composeRichEditor)
    implementation(libs.aboutLibraries.compose)
    implementation(libs.bundles.voyager)
    implementation(libs.composeMaterialMotion)
    implementation(libs.swipe)
    implementation(libs.composeWebview)
    implementation(libs.composeGrid)
    implementation(libs.reorderable)
    implementation(libs.bundles.markdown)
    implementation(libs.materialKolor)

    // Logging
    implementation(libs.logcat)

    // Shizuku
    implementation(libs.bundles.shizuku)

    // String similarity
    implementation(libs.stringSimilarity)

    // GraphQL generation
    implementation(libs.apollo)
    implementation(libs.apollo.adapters)

    // Tests
    testImplementation(libs.bundles.test)
    testRuntimeOnly(libs.junit.platform.launcher)

    // For detecting memory leaks; see https://square.github.io/leakcanary/
    // debugImplementation(libs.leakCanary.android)
    implementation(libs.leakCanary.plumber)

    testImplementation(libs.kotlinx.coroutines.test)
}

apollo {
    val schemaBasePath = "src/main/graphql/mihon/graphql"
    service("anilist") {
        packageName.set("mihon.graphql.anilist")
        val srcDir = "$schemaBasePath/anilist"
        srcDir(file(srcDir))

        introspection {
            endpointUrl.set("https://graphql.anilist.co")
            schemaFile.set(file("$srcDir/anilist.graphqls"))
        }

        // ISO 3166-1 alpha-2 country code
        mapScalarToKotlinString("CountryCode")
    }

    service("kitsu") {
        packageName.set("mihon.graphql.kitsu")
        val srcDir = "$schemaBasePath/kitsu"
        srcDir(file(srcDir))
        android.defaultConfig.versionCode

        introspection {
            // Kitsu doesn't like requests without a UA
            headers.put(
                "User-Agent",
                "Mihon v${android.defaultConfig.versionName} (${android.defaultConfig.applicationId})",
            )
            endpointUrl.set("https://kitsu.app/api/graphql")
            schemaFile.set(file("$srcDir/kitsu.graphqls"))
        }

        // A date, expressed as an ISO8601 string
        mapScalarToKotlinString("Date")
        // An ISO 8601-encoded datetime
        mapScalar("ISO8601DateTime", "kotlin.time.Instant", "com.apollographql.adapter.core.KotlinInstantAdapter")
        // A loose key-value map in GraphQL
        mapScalar(
            "Map",
            "kotlin.collections.Map<String, Any?>",
            "mihon.graphql.kitsu.adapter.KitsuMapAdapter",
        )
    }

    service("shikimori") {
        packageName.set("mihon.graphql.shikimori")
        val srcDir = "$schemaBasePath/shikimori"
        srcDir(file(srcDir))

        introspection {
            endpointUrl.set("https://shikimori.io/api/graphql")
            schemaFile.set(file("$srcDir/shikimori.graphqls"))
        }

        // An ISO 8601-encoded date
        mapScalarToKotlinString("ISO8601Date")
    }

    service("suwayomi") {
        packageName.set("mihon.graphql.suwayomi")
        val srcDir = "$schemaBasePath/suwayomi"
        srcDir(file(srcDir))

        introspection {
            endpointUrl.set("http://localhost:4567/api/graphql")
            schemaFile.set(file("$srcDir/suwayomi.graphqls"))
        }
    }
}

val latestCommitCount = getLatestCommitCount()
val latestCommitSha = getLatestCommitSha()
val latestCommitTime = getLatestCommitTime()
val currentTime = getCurrentTime()

fun ApplicationVariant.buildConfigField(type: String, name: String, value: Provider<String>) {
    buildConfigFields?.put(name, value.map { BuildConfigField(type, it, null) })
}

androidComponents {
    onVariants { variant ->
        val isUnstableBuild = variant.buildType == "debug" || variant.buildType == "nightly"
        val buildTime = if (isUnstableBuild) currentTime else latestCommitTime

        variant.buildConfigField("String", "COMMIT_COUNT", latestCommitCount.map { "\"$it\"" })
        variant.buildConfigField("String", "COMMIT_SHA", latestCommitSha.map { "\"$it\"" })
        variant.buildConfigField("String", "BUILD_TIME", buildTime.map { "\"$it\"" })

        if (isUnstableBuild) {
            variant.outputs.forEach { output ->
                val versionName = output.versionName.get()
                output.versionName.set(latestCommitCount.map { "$versionName-$it" })
            }
        }
    }

    onVariants { variant ->
        val resSource = variant.sources.res ?: return@onVariants

        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val replaceShortcutsPlaceholderTask = tasks.register<ReplaceShortcutsPlaceholderTask>(
            "replace${variantName}ShortcutPlaceholder",
        ) {
            applicationId.set(variant.applicationId)
            shortcutsFile.set(projectDir.resolve("src/main/shortcuts.xml"))
        }
        resSource.addGeneratedSourceDirectory(replaceShortcutsPlaceholderTask) { it.outputDir }
    }

    onVariants(selector().withFlavor("default" to "standard")) {
        // Only excluding in standard flavor because this breaks
        // Layout Inspector's Compose tree
        it.packaging.resources.excludes.add("META-INF/*.version")
    }
}
