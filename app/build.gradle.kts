plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.github.fowles.stochastic_strength"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.fowles.stochastic_strength"
        minSdk = 33
        targetSdk = 36
        versionCode = 51
        versionName = "5.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "STRAVA_CLIENT_ID", "\"${providers.gradleProperty("STRAVA_CLIENT_ID").getOrElse("")}\"")
        buildConfigField("String", "STRAVA_CLIENT_SECRET", "\"${providers.gradleProperty("STRAVA_CLIENT_SECRET").getOrElse("")}\"")
    }

    signingConfigs {
        create("release") {
            storeFile = file(providers.gradleProperty("STOCHASTIC_UPLOAD_STORE_FILE").get())
            storePassword =
providers.gradleProperty("STOCHASTIC_UPLOAD_STORE_PASSWORD").get()
            keyAlias = providers.gradleProperty("STOCHASTIC_UPLOAD_KEY_ALIAS").get()
            keyPassword = providers.gradleProperty("STOCHASTIC_UPLOAD_KEY_PASSWORD").get()
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
            }
        }
        create("releaseLocal") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    constraints {
        // play-services-base/basement drag in fragment 1.1.0, which Play Console
        // flags as outdated. The app itself is Compose-only and uses no fragments.
        implementation(libs.androidx.fragment)
    }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.play.services.location)
    implementation(libs.androidx.room.ktx)
    implementation(libs.vico.compose.m3)
    implementation(libs.reorderable)
    implementation(libs.okhttp)
    implementation(libs.tink.android)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    implementation(libs.kotlinx.coroutines.core)
    // Not used directly. Room 2.8.4's MigrationTestHelper needs kotlinx-serialization >= 1.8.1, but
    // lifecycle 2.11 pulls 1.7.3 into the app runtime and Gradle's consistent resolution then pins
    // the androidTest classpath to that. Declaring it here lifts the app runtime to what Room needs.
    implementation(libs.kotlinx.serialization.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // No test imports espresso, but compose ui-test drives input through it, and the version it
    // pulls in transitively crashes on current API levels (InputManager.getInstance is gone).
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}


composeCompiler {
    // Treats Kotlin's collection interfaces as stable — see compose-stability.conf for the promise
    // that makes and why this codebase keeps it. Without it, every data class holding a List is
    // unstable, and the composables taking one re-run on every parent recomposition.
    stabilityConfigurationFiles.add(layout.projectDirectory.file("compose-stability.conf"))

    // Skippability is a compile-time property, so it is checked by reading these rather than by a
    // runtime recomposition-counting test. After a build, app/build/compose_reports/
    // app-composables.txt lists each composable's parameters; a parameter with no `stable` prefix
    // is what stops its composable skipping.
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
}
