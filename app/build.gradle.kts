plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Signing config comes from SHELLWAVE_RELEASE_STORE_FILE / _STORE_PASSWORD / _KEY_ALIAS /
// _KEY_PASSWORD, as env vars or in ~/.gradle/gradle.properties. Nothing inside the checkout.
//
// Unset, release builds unsigned. Do not "fix" that by falling back to the debug key: it is a
// well-known key, and a real signed APK will not install over one signed with it, so the user
// uninstalls and loses their vault. Unsigned at least cannot be installed by accident.
//
// -Pshellwave.allowDebugSignedRelease=true debug-signs a release for local R8 testing. Throwaway
// devices only.

// Generated, not hand-copied: a stale copy means LicenseScreen shows wrong attribution, which is
// a GPLv3 problem. (license_gplv3.txt stays manual; that text does not change.)
val copyNoticeToRes =
    tasks.register<Copy>("copyNoticeToRes") {
        from(rootProject.file("NOTICE"))
        into(layout.buildDirectory.dir("generated/notice/res/raw"))
        rename { "notice.txt" }
    }

// builtBy alone is too late: AGP wires the per-variant resource-merge tasks only after android {}
// evaluates. preBuild is an ancestor of every preXBuild, so it covers them without naming any.
tasks.named("preBuild") {
    dependsOn(copyNoticeToRes)
}

fun releaseSigningProp(name: String): String? =
    System.getenv(name) ?: (project.findProperty(name) as String?)

val releaseStoreFilePath = releaseSigningProp("SHELLWAVE_RELEASE_STORE_FILE")
val releaseStorePassword = releaseSigningProp("SHELLWAVE_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = releaseSigningProp("SHELLWAVE_RELEASE_KEY_ALIAS")
val releaseKeyPassword = releaseSigningProp("SHELLWAVE_RELEASE_KEY_PASSWORD")

val hasReleaseSigningConfig =
    releaseStoreFilePath != null &&
            File(releaseStoreFilePath).exists() &&
            releaseStorePassword != null &&
            releaseKeyAlias != null &&
            releaseKeyPassword != null

val allowDebugSignedRelease =
    (project.findProperty("shellwave.allowDebugSignedRelease") as String?)?.toBoolean() == true

if (!hasReleaseSigningConfig) {
    if (allowDebugSignedRelease) {
        logger.warn(
            "Shellwave: shellwave.allowDebugSignedRelease=true and no real release signing config is " +
                    "set (SHELLWAVE_RELEASE_* as an env var or a ~/.gradle/gradle.properties entry) - this " +
                    "release build will be signed with the DEBUG key (~/.android/debug.keystore, well-known " +
                    "password). This artifact is for LOCAL VERIFICATION ONLY and must never be distributed: " +
                    "installing it locks that install to the debug key, and a later properly-signed release " +
                    "will then fail to install over it (signature mismatch) until it's uninstalled, which " +
                    "destroys that install's saved hosts, scripts, and vault.",
        )
    } else {
        logger.warn(
            "Shellwave: no release signing config found (SHELLWAVE_RELEASE_* as an env var or a " +
                    "~/.gradle/gradle.properties entry) - the release build will be UNSIGNED and cannot be " +
                    "installed as-is. Pass -Pshellwave.allowDebugSignedRelease=true for a debug-signed, " +
                    "local-verification-only build instead.",
        )
    }
}

android {
    namespace = "io.github.lordofpolls.shellwave"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.lordofpolls.shellwave"
        minSdk = 31
        targetSdk = 37
        versionCode = 10401
        versionName = "1.4.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Play Billing is proprietary, and F-Droid will not build an app that links a proprietary
    // library - so the donation lives behind a flavour rather than in `main`. `foss` is the
    // flavour F-Droid builds (see fdroid/io.github.lordofpolls.shellwave.yml); `play` is the only
    // one with com.android.billingclient on its classpath. The two are otherwise identical, down
    // to the applicationId, so a user can move between the builds without reinstalling.
    flavorDimensions += "distribution"

    productFlavors {
        create("play") { dimension = "distribution" }
        create("foss") { dimension = "distribution" }
    }

    signingConfigs {
        if (hasReleaseSigningConfig) {
            create("release") {
                storeFile = File(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            // Keep rules are load-bearing here; see proguard-rules.pro.
            optimization {
                enable = true
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            } else if (allowDebugSignedRelease) {
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    sourceSets {
        // MigrationTestHelper reads exported schema JSON out of assets at test time.
        getByName("androidTest").assets.directories.add("$projectDir/schemas")

        getByName("main").res.srcDir(
            files(layout.buildDirectory.dir("generated/notice/res")).builtBy(
                copyNoticeToRes
            )
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests {
            // Without this, constructing an sshj SSHClient on the JVM dies in DefaultConfig:
            // slf4j-android logs the cipher list via android.util.Log.isLoggable, which the stub
            // android.jar throws from.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
            )
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

configurations.all {
    resolutionStrategy {
        force("org.slf4j:slf4j-api:${libs.versions.slf4jAndroid.get()}")

        // Room's POM pins 1.7.3, which AbstractMethodErrors in the migration tests.
        force("org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:1.8.1")
        force("org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:1.8.1")
    }
}

dependencies {
    implementation(project(":terminal-core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    // Stable releases only, here and below: this catalogue stays off alpha and beta unless
    // there is no stable release at all.
    implementation(libs.androidx.window)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material3.adaptive.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation)

    // glance-appwidget transitively pulls plain androidx.glance, which is all a few-button
    // widget needs. No glance-material3.
    implementation(libs.androidx.glance.appwidget)

    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.sshj)
    implementation(libs.slf4j.android)

    // A View-system library in an otherwise Compose-only app, for one call site
    // (SchemeHarmonizer.harmonizeColor): material-color-utilities' `Blend.harmonize` ships here,
    // and Compose Material3's copy of the same CAM16/HCT solver is `internal`.
    implementation(libs.google.material)

    // play flavour only - see productFlavors above.
    "playImplementation"(libs.google.billing)

    coreLibraryDesugaring(libs.android.desugar.jdk.libs)

    testImplementation(libs.junit)
    // Test-only. `org.json` is a platform API, so on the JVM classpath it is the stub
    // android.jar, and isReturnDefaultValues above makes it answer null instead of throwing -
    // which surfaces as an NPE inside `ScriptModel.encodeParams` that reads like a real bug.
    testImplementation(libs.json)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
}
