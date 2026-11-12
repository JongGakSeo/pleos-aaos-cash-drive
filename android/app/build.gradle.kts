plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes only from the environment. The keystore lives outside the repository and is
// never committed; a release built without all four variables stays unsigned and cannot be installed.
val signingEnv = listOf("CASHDRIVE_KEYSTORE", "CASHDRIVE_KEY_ALIAS", "CASHDRIVE_STORE_PASSWORD", "CASHDRIVE_KEY_PASSWORD")
    .associateWith { providers.environmentVariable(it).orNull?.takeIf { value -> value.isNotBlank() } }
val releaseSigningReady = signingEnv.values.all { it != null }

// The PSA excerpt (BriefingCatalog.psa) is the only third-party creative. It ships only once its permission record
// is complete: build with -Pkilomoa.includePsa=true and it takes the first slot of the briefing rotation and its
// clip and lineup picture (src/psa/) go into the APK. Until then src/psa/ is not in this repository (the files are
// kept in the team's working repository and copied here for such a build). Off by default, the APK
// carries none of it and the three self-made briefings rotate.
val includePsa = providers.gradleProperty("kilomoa.includePsa").orNull == "true"

android {
    namespace = "ai.cashdrive.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.kilomoa.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // A signed demonstration must opt in explicitly; production release stays closed.
        buildConfigField("boolean", "LOCAL_MOCK", "false")
        buildConfigField("boolean", "INCLUDE_PSA", includePsa.toString())
    }

    if (releaseSigningReady) {
        signingConfigs.create("showcase") {
            storeFile = file(signingEnv.getValue("CASHDRIVE_KEYSTORE")!!)
            storePassword = signingEnv.getValue("CASHDRIVE_STORE_PASSWORD")
            keyAlias = signingEnv.getValue("CASHDRIVE_KEY_ALIAS")
            keyPassword = signingEnv.getValue("CASHDRIVE_KEY_PASSWORD")
        }
    } else if (gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }) {
        logger.warn("Release signing is not configured: set CASHDRIVE_KEYSTORE, CASHDRIVE_KEY_ALIAS, " +
            "CASHDRIVE_STORE_PASSWORD and CASHDRIVE_KEY_PASSWORD. The release APK will be unsigned and not installable.")
    }

    buildTypes.getByName("debug") {
        buildConfigField("boolean", "LOCAL_MOCK", "true")
    }
    buildTypes.getByName("release") {
        if (releaseSigningReady) signingConfig = signingConfigs.getByName("showcase")
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
        create("showcase") {
            // Submission build: real vehicle signals, no demo controls, release-signable, Godot included.
            dimension = "signal"; buildConfigField("boolean", "DEMO_SIGNALS", "false")
            // Explicit opt-in to the LOCAL_MOCK session: the briefing rotation, the ledger, the account and the
            // redemption choice run on the device with no server (the build has no INTERNET permission). This
            // flag never authorizes synthetic vehicle signals and never bypasses UXR; the fail-closed gate stays.
            buildConfigField("boolean", "LOCAL_MOCK", "true")
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        }
    }

    // The home (HomeActivity, AdPlayer, Godot preview bridge, stills) is shared by every debug variant
    // and by the showcase flavor; the main source set stays free of the engine and the mock session UI.
    // Each variant gets the home folders exactly once: registering them on both the debug build type and
    // the showcase flavor put them into showcaseDebug twice, and incremental resource merges then failed
    // with "Duplicate resources" on every new drawable.
    sourceSets {
        listOf("showcase", "demoDebug", "emuDebug", "vehicleDebug").forEach { name ->
            maybeCreate(name).apply { java.srcDir("src/home/java"); res.srcDir("src/home/res") }
        }
        if (includePsa) getByName("main").apply { assets.srcDir("src/psa/assets"); res.srcDir("src/psa/res") }
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
        if (variant.productFlavors.any { it.second !in setOf("vehicle", "showcase") }) variant.enable = false
    }
}

android.applicationVariants.all {
    val variant = this
    outputs.all {
        // Keep AGP's "-unsigned" marker so an uninstallable release is never mistaken for the signed submission.
        val suffix = if (variant.signingConfig == null) "-unsigned" else ""
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            "KiloMoa-${variant.flavorName}-${variant.buildType.name}$suffix.apk"
    }
}

dependencies {
    implementation(project(":core"))
    // Maven's published AAR is templateRelease; the engine ships only in debug variants and the showcase flavor.
    listOf("debugImplementation", "showcaseImplementation").forEach { configuration ->
        add(configuration, "org.godotengine:godot:4.7.2.stable")
        add(configuration, "androidx.fragment:fragment:1.8.6")
    }
    // Pleos Connect SDKs: odometer/EV battery reads and navigation route information. Read-only use;
    // no set* calls (App Market review rejects them). Permissions are declared in the main manifest.
    implementation("ai.pleos.playground:Vehicle:2.3.2")
    implementation("ai.pleos.playground:NaviHelper:3.0.5")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

val exportGodotPack by tasks.registering(Exec::class) {
    val projectDir = rootProject.file("../godot")
    val pack = layout.buildDirectory.file("generated/godotAssets/cash_drive.pck")
    inputs.files(fileTree(projectDir) {
        // The 3D scene: scripts, scenes and the theme models (GLB with embedded textures).
        include("**/*.gd", "**/*.tscn", "**/*.glb", "**/*.import", "**/*.png", "project.godot", "export_presets.cfg")
        exclude(".godot/**", "tests/**", "tools/**")
    })
    outputs.file(pack)
    val toolsDir = rootProject.file(providers.environmentVariable("CASHDRIVE_TOOLS_ROOT").orElse("../.tools").get())
    val godotExecutable = toolsDir.resolve("godot/Godot.app/Contents/MacOS/Godot").absolutePath
    doFirst {
        pack.get().asFile.parentFile.mkdirs()
        // Cold worktrees need imported model/texture resources before the Android export.
        project.exec {
            commandLine(godotExecutable, "--headless", "--editor", "--import", "--path", projectDir.absolutePath)
        }
    }
    commandLine(godotExecutable,
        "--headless", "--path", projectDir.absolutePath, "--export-pack", "Android PCK", pack.get().asFile.absolutePath)
}
// The pack travels with the home: every debug variant and the showcase flavor, each exactly once.
listOf("showcase", "demoDebug", "emuDebug", "vehicleDebug").forEach {
    android.sourceSets.maybeCreate(it).assets.srcDir(layout.buildDirectory.dir("generated/godotAssets"))
}
tasks.configureEach {
    // AGP 8.7.3 model writers and lint analysis/report tasks read the generated assets.
    val homeVariant = name.contains("Debug") || name.contains("Showcase")
    if (homeVariant && (name.matches(Regex("merge.*Assets")) || name.contains("lint", ignoreCase = true))) dependsOn(exportGodotPack)
}
