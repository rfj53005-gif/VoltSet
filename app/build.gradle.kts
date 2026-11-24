plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "io.voltset.sm8850"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.voltset.sm8850"
        minSdk = 30
        targetSdk = 36
        versionCode = providers.gradleProperty("releaseVersionCode").orElse("8").get().toInt()
        versionName = providers.gradleProperty("releaseVersionName").orElse("0.3.5").get()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    buildFeatures {
        compose = true
    }
}

val generatedAssets = layout.buildDirectory.dir("generated/voltset-assets")
val kernelPrebuilts = rootProject.file("kernel/prebuilt")
val scmiModules = listOf(
    "sm8750/qcom-scmi-ro.ko",
    "sm8850/android16-5-4k/qcom-scmi-ro.ko",
    "sm8850/android16-6-4k/qcom-scmi-ro.ko",
)

val syncKernelModules by tasks.registering(Sync::class) {
    from(kernelPrebuilts) {
        include(scmiModules)
    }
    into(generatedAssets.map { it.dir("backend/modules") })
    doFirst {
        scmiModules.forEach { path ->
            val module = kernelPrebuilts.resolve(path)
            check(module.isFile) { "Missing kernel module: $module" }
        }
    }
}

val nativeHelpersDir = rootProject.file("native/vsetns/prebuilt")
val nativeHelperFile = nativeHelpersDir.resolve("arm64-v8a/vsetns")

val syncNativeHelpers by tasks.registering(Sync::class) {
    from(nativeHelperFile)
    into(generatedAssets.map { it.dir("backend/bin") })
    doFirst {
        check(nativeHelperFile.isFile) { "Missing native helper: $nativeHelperFile" }
    }
}

android.sourceSets["main"].assets.directories.add(generatedAssets.get().asFile.path)
tasks.named("preBuild") {
    dependsOn(syncKernelModules, syncNativeHelpers)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    testImplementation("junit:junit:4.13.2")
}
