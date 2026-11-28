plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.alraad.core.adblocker"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets["main"].jniLibs.srcDir(layout.buildDirectory.dir("generated/nativeLibs"))

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

tasks.withType<Test>().configureEach {
    // Host build of the engine (cargo build --release in native/) enables the native-load smoke test.
    val hostLib = layout.projectDirectory.dir("native/target/release").asFile
    if (hostLib.isDirectory) systemProperty("java.library.path", hostLib.absolutePath)
}

dependencies {
    api(projects.core.common)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.hilt.android)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.hilt.compiler)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.robolectric.android.all)
    testImplementation(libs.mockito.core)
    testImplementation(libs.androidx.test.core)
}

// Builds libalraad_adblock.so (Brave adblock-rust via core/adblocker/native) for each Android ABI.
// Needs cargo with the Android Rust targets and an NDK (ANDROID_NDK_HOME or ANDROID_NDK_ROOT). Without them the
// task is skipped with a warning and the app runs with the rule engine reported as unavailable.
// -PnativeAbis=arm64-v8a limits the build to the listed ABIs (comma separated).
val nativeAbis =
    mapOf(
        "arm64-v8a" to ("aarch64-linux-android" to "aarch64-linux-android24-clang"),
        "armeabi-v7a" to ("armv7-linux-androideabi" to "armv7a-linux-androideabi24-clang"),
        "x86_64" to ("x86_64-linux-android" to "x86_64-linux-android24-clang"),
    )
val selectedAbis = (findProperty("nativeAbis") as String?)?.split(",")?.map { it.trim() } ?: nativeAbis.keys.toList()

val buildAdblockNative =
    tasks.register("buildAdblockNative") {
        val nativeDir = layout.projectDirectory.dir("native")
        val outDir = layout.buildDirectory.dir("generated/nativeLibs")
        val abiTargets = nativeAbis
        val abis = selectedAbis
        inputs.dir(nativeDir.dir("src"))
        inputs.files(nativeDir.file("Cargo.toml"), nativeDir.file("Cargo.lock"))
        outputs.dir(outDir)
        doLast {
            val ndk = System.getenv("ANDROID_NDK_HOME") ?: System.getenv("ANDROID_NDK_ROOT")
            val hasCargo =
                runCatching { ProcessBuilder("cargo", "--version").redirectErrorStream(true).start().waitFor() == 0 }.getOrDefault(false)
            if (ndk == null || !hasCargo) {
                logger.warn(
                    "buildAdblockNative skipped (cargo found: $hasCargo, NDK found: ${ndk != null}); rule engine will be unavailable",
                )
                return@doLast
            }
            val bin = File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin")
            for (abi in abis) {
                val (triple, clang) = abiTargets.getValue(abi)
                val envName = triple.replace('-', '_')
                val builder = ProcessBuilder("cargo", "build", "--release", "--locked", "--target", triple)
                builder.directory(nativeDir.asFile).inheritIO()
                builder.environment()["CC_$envName"] = File(bin, clang).path
                builder.environment()["CARGO_TARGET_${envName.uppercase()}_LINKER"] = File(bin, clang).path
                check(builder.start().waitFor() == 0) { "cargo build failed for $abi" }
                val target = outDir.get().dir(abi).asFile.apply { mkdirs() }
                File(nativeDir.asFile, "target/$triple/release/libalraad_adblock.so").copyTo(File(target, "libalraad_adblock.so"), true)
            }
        }
    }

tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(buildAdblockNative) }
