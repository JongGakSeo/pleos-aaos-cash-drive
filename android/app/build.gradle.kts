plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.cashdrive.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.cashdrive.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "signal"
    productFlavors {
        create("demo") {
            dimension = "signal"; applicationIdSuffix = ".demo"; buildConfigField("boolean", "DEMO_SIGNALS", "true")
            // The portable presentation build supports both current 64-bit targets.
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
        create("emu") {
            dimension = "signal"; applicationIdSuffix = ".emu"; buildConfigField("boolean", "DEMO_SIGNALS", "false")
            ndk { abiFilters += "x86_64" }
        }
        create("vehicle") {
            dimension = "signal"; buildConfigField("boolean", "DEMO_SIGNALS", "false")
            ndk { abiFilters += "arm64-v8a" }
        }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    useLibrary("android.car")
    androidResources { noCompress += "pck" }
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        if (variant.productFlavors.any { it.second != "vehicle" }) variant.enable = false
    }
}

dependencies {
    implementation(project(":core"))
    // Maven's published AAR is templateRelease; debugImplementation limits app packaging scope.
    debugImplementation("org.godotengine:godot:4.7.2.stable")
    debugImplementation("androidx.fragment:fragment:1.8.6")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val exportGodotPack by tasks.registering(Exec::class) {
    val projectDir = rootProject.file("../godot")
    val pack = layout.buildDirectory.file("generated/godotAssets/cash_drive.pck")
    inputs.files(fileTree(projectDir) {
        include("**/*.gd", "**/*.tscn", "**/*.glb", "**/*.import", "**/*.png", "project.godot", "export_presets.cfg")
        exclude(".godot/**", "tests/**", "tools/**")
    })
    outputs.file(pack)
    val toolsDir = rootProject.file(providers.environmentVariable("CASHDRIVE_TOOLS_ROOT").orElse("../.tools").get())
    val godotExecutable = toolsDir.resolve("godot/Godot.app/Contents/MacOS/Godot").absolutePath
    doFirst {
        pack.get().asFile.parentFile.mkdirs()
        // Cold worktrees need imported GLB/texture resources before the Android export.
        project.exec {
            commandLine(godotExecutable, "--headless", "--editor", "--import", "--path", projectDir.absolutePath)
        }
    }
    commandLine(godotExecutable,
        "--headless", "--path", projectDir.absolutePath, "--export-pack", "Android PCK", pack.get().asFile.absolutePath)
}
android.sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("generated/godotAssets"))
tasks.configureEach {
    // AGP 8.7.3 model writers and lint analysis/report tasks read the generated assets.
    if (name.matches(Regex("merge.*DebugAssets")) ||
        (name.contains("Debug") && name.contains("lint", ignoreCase = true))) dependsOn(exportGodotPack)
}
