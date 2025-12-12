import java.net.URL
import java.io.FileOutputStream

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ai.assistance.mnn"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // 支持的 ABI（与主 app 保持一致）
            abiFilters.addAll(listOf("arm64-v8a"))
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fno-emulated-tls")
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_PLATFORM=android-26",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DMNN_BUILD_SHARED_LIBS=ON",
                    "-DMNN_SEP_BUILD=OFF",
                    "-DMNN_BUILD_TOOLS=OFF",
                    "-DMNN_BUILD_DEMO=OFF",
                    "-DMNN_BUILD_CONVERTER=OFF",
                    "-DMNN_USE_LOGCAT=ON",
                    "-DMNN_BUILD_TEST=OFF",
                    "-DMNN_BUILD_BENCHMARK=OFF",
                    "-DMNN_BUILD_QUANTOOLS=OFF",
                    "-DMNN_OPENCL=OFF",
                    "-DMNN_OPENGL=OFF",
                    "-DMNN_VULKAN=OFF",
                    "-DMNN_ARM82=ON",
                    // 启用 LLM 支持
                    "-DMNN_BUILD_LLM=ON",
                    "-DMNN_SUPPORT_TRANSFORMER_FUSE=ON",
                    "-DMNN_LOW_MEMORY=ON",
                    "-DMNN_CPU_WEIGHT_DEQUANT_GEMM=ON"
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }
}

// Sherpa MNN native library download task
// Note: The library can be placed in either:
// - mnn/src/main/jniLibs/arm64-v8a/ (recommended, for library module)
// - app/src/main/jniLibs/arm64-v8a/ (also works, for app module)
tasks.register<Copy>("downloadAndUnzipSherpaMnnLibs") {
    group = "Pre-build"
    description = "Downloads and unzips libsherpa-mnn-jni native library from CDN."

    val nativeLibsUrl = "https://meta.alicdn.com/data/mnn/libs/libsherpa-mnn-jni-16k.zip"
    val zipFileName = "libsherpa-mnn-jni-16k.zip"
    val outputDir = file("src/main/jniLibs/arm64-v8a")
    val downloadedZip = File(project.buildDir, zipFileName)
    val checkFile = File(outputDir, "libsherpa-mnn-jni.so")
    
    // Also check if file exists in app module (for manual placement)
    val appModuleCheckFile = project.rootProject.project(":app").file("src/main/jniLibs/arm64-v8a/libsherpa-mnn-jni.so")

    inputs.property("url", nativeLibsUrl)
    outputs.file(checkFile)

    onlyIf {
        val existsInMnn = checkFile.exists()
        val existsInApp = appModuleCheckFile.exists()
        val exists = existsInMnn || existsInApp
        println("-> Checking if sherpa-mnn native libs exist...")
        println("   In mnn module: ${existsInMnn}")
        println("   In app module: ${existsInApp}")
        if (exists) {
            println("   ✓ Library found, skipping download.")
        }
        return@onlyIf !exists
    }

    doFirst {
        println("-> Executing downloadAndUnzipSherpaMnnLibs task...")
        println("   Downloading from ${nativeLibsUrl}")
        println("   outputDir  ${outputDir}")
        println("   downloadedZip  ${downloadedZip}")

        // Create output directory if it doesn't exist
        outputDir.mkdirs()

        // Ensure build directory exists before downloading
        downloadedZip.parentFile.mkdirs()

        // Download the file using Java URL
        try {
            val url = URL(nativeLibsUrl)
            url.openStream().use { input ->
                FileOutputStream(downloadedZip).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            throw GradleException("Download failed: ${e.message}", e)
        }

        if (!downloadedZip.exists()) {
            throw GradleException("Download failed: ${downloadedZip} not found.")
        }
        println("   Download complete.")
        println("   Unzipping ${downloadedZip.name} to ${outputDir}...")
    }

    from(zipTree(downloadedZip))
    into(outputDir)

    doLast {
        println("   Unzip complete.")
        downloadedZip.delete()
    }
}

// Make preBuild depend on this task
tasks.named("preBuild") {
    dependsOn("downloadAndUnzipSherpaMnnLibs")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}

