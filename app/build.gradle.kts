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
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.heifwriter:heifwriter:1.0.0")
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    implementation("org.tensorflow:tensorflow-lite:2.17.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    testImplementation("io.mockk:mockk:1.13.17")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
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
