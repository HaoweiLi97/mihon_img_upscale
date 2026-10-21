import java.util.Properties

plugins { id("com.android.application") version "9.4.1" }

val local = Properties().apply {
    file("../../local.properties").inputStream().use(::load)
}
val qnnSdk = providers.gradleProperty("qnnSdkDir").orNull ?: local.getProperty("qnn.sdk.dir")
    ?: error("Set qnnSdkDir or qnn.sdk.dir in the repository local.properties")

android {
    namespace = "dev.mihon.npubench"
    compileSdk = 36
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.mihon.npubench"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake { arguments += "-DQNN_SDK_DIR=${qnnSdk.replace('\\', '/')}" }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    packaging { jniLibs.useLegacyPackaging = true }
    androidResources { noCompress += "dlc" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

val stageQnnRuntime = tasks.register<Sync>("stageQnnRuntime") {
    val sdk = file(qnnSdk)
    into(layout.buildDirectory.dir("qnnRuntime/arm64-v8a"))
    from(sdk.resolve("lib/aarch64-android")) {
        include("libQnnHtp.so", "libQnnHtpPrepare.so", "libQnnSystem.so", "libQnnModelDlc.so")
        include("libQnnHtpV69Stub.so", "libQnnHtpV73Stub.so", "libQnnHtpV75Stub.so", "libQnnHtpV79Stub.so", "libQnnHtpV81Stub.so")
    }
    listOf(69, 73, 75, 79, 81).forEach { arch ->
        from(sdk.resolve("lib/hexagon-v$arch/unsigned")) { include("libQnnHtpV${arch}Skel.so") }
    }
}
android.sourceSets.getByName("main").jniLibs.directories.add(layout.buildDirectory.dir("qnnRuntime").get().asFile.path)
tasks.named("preBuild") {
    dependsOn(stageQnnRuntime)
    doFirst {
        check(file("src/main/assets/realcugan-pro-x2-denoise3x-fp16.dlc").isFile) {
            "Generate the unprepared FP16 DLC first (see README.md)."
        }
    }
}
