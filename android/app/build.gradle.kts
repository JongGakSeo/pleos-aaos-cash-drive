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
        create("demo") { dimension = "signal"; applicationIdSuffix = ".demo"; buildConfigField("boolean", "DEMO_SIGNALS", "true") }
        create("emu") { dimension = "signal"; applicationIdSuffix = ".emu"; buildConfigField("boolean", "DEMO_SIGNALS", "false") }
        create("vehicle") { dimension = "signal"; buildConfigField("boolean", "DEMO_SIGNALS", "false") }
    }

    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    useLibrary("android.car")
    androidResources { noCompress += "pck" }
    sourceSets.getByName("debug").assets.srcDir(layout.buildDirectory.dir("generated/godotAssets"))
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variant ->
        if (variant.productFlavors.any { it.second != "vehicle" }) variant.enable = false
    }
}

dependencies {
    implementation(project(":core"))
    debugImplementation("org.godotengine:godot:4.7.2.stable")
    debugImplementation("androidx.fragment:fragment:1.8.6")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val exportGodotPack by tasks.registering(Exec::class) {
    val projectDir = rootProject.file("../godot")
    val pack = layout.buildDirectory.file("generated/godotAssets/cash_drive.pck")
    inputs.files(fileTree(projectDir) {
        include("**/*.gd", "**/*.tscn", "project.godot", "export_presets.cfg")
        exclude(".godot/**", "tests/**")
    })
    outputs.file(pack)
    doFirst { pack.get().asFile.parentFile.mkdirs() }
    commandLine(rootProject.file("../.tools/godot/Godot.app/Contents/MacOS/Godot").absolutePath,
        "--headless", "--path", projectDir.absolutePath, "--export-pack", "Android PCK", pack.get().asFile.absolutePath)
}
tasks.configureEach {
    if (name.matches(Regex("merge.*DebugAssets"))) dependsOn(exportGodotPack)
}
