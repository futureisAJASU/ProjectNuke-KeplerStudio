import java.security.MessageDigest as ShaDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.projectnuke.keplerstudio"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.projectnuke.keplerstudio"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++20", "-O3", "-Wall", "-Wextra")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        noCompress += listOf("tflite", "task", "onnx", "ort", "bin", "gguf")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.12.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.heifwriter:heifwriter:1.0.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("io.mockk:mockk:1.13.17")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}

/**
 * Reproducible model-asset pinning helper.
 *
 * Prints id/path/byte-size/sha-256 for every file under app/src/main/assets/models,
 * in a paste-friendly form so a developer can copy the printed sha256 into
 * ModelAssetManifest.entries and re-run validation.
 *
 *     ./gradlew :app:printModelAssetSummary
 *
 * Only id/path/size/hash are emitted; never image or pixel contents.
 */
tasks.register("printModelAssetSummary") {
    group = "kepler"
    description = "Print id/path/byte-size/sha-256 for each packaged model asset so the developer can pin it in ModelAssetManifest."
    doLast {
        val modelsDir = file("src/main/assets/models")
        if (!modelsDir.isDirectory) {
            logger.lifecycle("# no packaged model assets at ${modelsDir.path}")
            return@doLast
        }
        val md = ShaDigest.getInstance("SHA-256")
        logger.lifecycle("# model asset report — paste sha256 + path into ModelAssetManifest.entries")
        modelsDir.listFiles()?.sortedBy { it.name }?.forEach { modelFile ->
            if (!modelFile.isFile) return@forEach
            md.reset()
            val shaHex = modelFile.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    md.update(buffer, 0, read)
                }
                md.digest().joinToString("") { b -> "%02x".format(b) }
            }
            val size = modelFile.length()
            logger.lifecycle("path=models/${modelFile.name}\tsize=${size}\tsha256=${shaHex}")
        }
    }
}

val hostGoldenExecutable =
    layout.buildDirectory.file(
        "host-native/kepler_host_goldens_v1" +
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else "",
    )

tasks.register<Exec>("compileHostNativeV1Goldens") {
    group = "verification"
    description = "Compile the host harness against the production V1 C++ kernels."
    val nativeSources =
        listOf(
            "src/test/native/native_v1_exact_golden.cpp",
            "src/main/cpp/native_bridge.cpp",
            "src/main/cpp/native_processing_algorithms.cpp",
            "src/main/cpp/native_special_effects.cpp",
            "src/main/cpp/native_flare_guard.cpp",
            "src/main/cpp/native_flare_mask.cpp",
            "src/main/cpp/native_crop_transform.cpp",
            "src/main/cpp/native_selection_blend.cpp",
            "src/main/cpp/native_cancellation.cpp",
        )
    inputs.files(nativeSources.map(::file))
    inputs.dir("src/test/native/stubs")
    inputs.file("src/main/cpp/native_v1_host_test.h")
    outputs.file(hostGoldenExecutable)
    doFirst {
        hostGoldenExecutable.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "g++",
        "-std=c++20",
        "-O2",
        "-DKEPLER_HOST_TEST",
        "-Isrc/test/native/stubs",
        "-Isrc/main/cpp",
        *nativeSources.toTypedArray(),
        "-o",
        hostGoldenExecutable.get().asFile.absolutePath,
    )
}

tasks.register<Exec>("hostNativeV1Goldens") {
    group = "verification"
    description = "Run fixed exact hashes through the production V1 host kernels."
    dependsOn("compileHostNativeV1Goldens")
    inputs.file(hostGoldenExecutable)
    commandLine(hostGoldenExecutable.get().asFile.absolutePath)
}

val hostV2GoldenExecutable =
    layout.buildDirectory.file(
        "host-native/native_v2_exact_golden" +
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else "",
    )

tasks.register<Exec>("compileHostNativeV2Goldens") {
    group = "verification"
    description = "Compile the host harness against the production experimental V2 kernel."
    val nativeSources =
        listOf(
            "src/test/native/native_v2_exact_golden.cpp",
            "src/main/cpp/native_corrections_v2.cpp",
            "src/main/cpp/native_cancellation.cpp",
        )
    inputs.files(nativeSources.map(::file))
    inputs.dir("src/test/native/stubs")
    inputs.file("src/main/cpp/native_corrections_v2.h")
    outputs.file(hostV2GoldenExecutable)
    doFirst {
        hostV2GoldenExecutable.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "g++",
        "-std=c++20",
        "-O2",
        "-DKEPLER_HOST_TEST",
        "-Isrc/test/native/stubs",
        "-Isrc/main/cpp",
        *nativeSources.toTypedArray(),
        "-o",
        hostV2GoldenExecutable.get().asFile.absolutePath,
    )
}

tasks.register<Exec>("hostNativeV2Goldens") {
    group = "verification"
    description = "Run fixed exact and transactional checks through the production V2 kernel."
    dependsOn("compileHostNativeV2Goldens")
    inputs.file(hostV2GoldenExecutable)
    commandLine(hostV2GoldenExecutable.get().asFile.absolutePath)
}

val hostV2BenchmarkExecutable =
    layout.buildDirectory.file(
        "host-native/native_v2_benchmark" +
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else "",
    )

tasks.register<Exec>("compileHostNativeV2Benchmark") {
    group = "verification"
    description = "Compile the production V2 kernel benchmark harness."
    val nativeSources =
        listOf(
            "src/test/native/native_v2_benchmark.cpp",
            "src/main/cpp/native_corrections_v2.cpp",
            "src/main/cpp/native_cancellation.cpp",
        )
    inputs.files(nativeSources.map(::file))
    inputs.dir("src/test/native/stubs")
    inputs.file("src/main/cpp/native_corrections_v2.h")
    outputs.file(hostV2BenchmarkExecutable)
    doFirst {
        hostV2BenchmarkExecutable.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "g++",
        "-std=c++20",
        "-O2",
        "-DKEPLER_HOST_TEST",
        "-Isrc/test/native/stubs",
        "-Isrc/main/cpp",
        *nativeSources.toTypedArray(),
        "-o",
        hostV2BenchmarkExecutable.get().asFile.absolutePath,
    )
}

tasks.register<Exec>("hostNativeV2Benchmark") {
    group = "verification"
    description = "Measure production V2 kernel wall time and defended buffer bytes."
    dependsOn("compileHostNativeV2Benchmark")
    inputs.file(hostV2BenchmarkExecutable)
    commandLine(hostV2BenchmarkExecutable.get().asFile.absolutePath)
}

val hostCancellationExecutable =
    layout.buildDirectory.file(
        "host-native/kepler_host_cancellation_registry" +
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) ".exe" else "",
    )

tasks.register<Exec>("compileHostNativeCancellationRegistryTest") {
    group = "verification"
    description = "Compile and stress the production C++ cancellation registry."
    val nativeSources =
        listOf(
            "src/test/native/native_cancellation_registry_test.cpp",
            "src/main/cpp/native_cancellation.cpp",
        )
    inputs.files(nativeSources.map(::file))
    inputs.dir("src/test/native/stubs")
    inputs.file("src/main/cpp/native_cancellation.h")
    outputs.file(hostCancellationExecutable)
    doFirst {
        hostCancellationExecutable.get().asFile.parentFile.mkdirs()
    }
    commandLine(
        "g++",
        "-std=c++20",
        "-O2",
        "-pthread",
        "-DKEPLER_HOST_TEST",
        "-Isrc/test/native/stubs",
        "-Isrc/main/cpp",
        *nativeSources.toTypedArray(),
        "-o",
        hostCancellationExecutable.get().asFile.absolutePath,
    )
}

tasks.register<Exec>("hostNativeCancellationRegistryTest") {
    group = "verification"
    description = "Run concurrent signal/release stress against the production C++ registry."
    dependsOn("compileHostNativeCancellationRegistryTest")
    inputs.file(hostCancellationExecutable)
    commandLine(hostCancellationExecutable.get().asFile.absolutePath)
}
