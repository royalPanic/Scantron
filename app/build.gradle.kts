plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.scantron"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.scantron"
        minSdk = 24
        targetSdk = 33
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")

    // Lifecycle must stay on 2.7.x while the Compose BOM below pins Compose to 1.6.x.
    //
    // Lifecycle 2.8.0 relocated LocalLifecycleOwner from androidx.compose.ui.platform to
    // androidx.lifecycle.compose, and only Compose 1.7.0+ installs the new local. Against this
    // BOM (Compose 1.6.7) collectAsStateWithLifecycle therefore reads a CompositionLocal that no
    // host provides and throws "CompositionLocal LocalLifecycleOwner not present" the moment the
    // Transfer screen composes. Raising lifecycle to 2.8.x instead requires moving the BOM to
    // 2024.09.00+, which needs compileSdk 35 - a larger change than this bug warrants.
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    // collectAsStateWithLifecycle, so a screen in the background stops collecting instead of
    // holding onto state while the operator is doing something else on the handheld.
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose UI
    implementation(platform("androidx.compose:compose-bom:2024.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Room Database
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
        // Compose UI assertions on the JVM. The layout regression this guards - the pull button
        // being clipped off a short screen - is invisible to logic tests and only shows up when the
        // tree is actually composed, so these belong next to the Robolectric tests rather than on
        // the device, where they would need a handheld attached to every run.
        testImplementation(platform("androidx.compose:compose-bom:2024.05.00"))
        testImplementation("androidx.compose.ui:ui-test-junit4")
        debugImplementation("androidx.compose.ui:ui-test-manifest")
        androidTestImplementation("androidx.test.ext:junit:1.1.5")
        androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
        androidTestImplementation(platform("androidx.compose:compose-bom:2024.05.00"))
        androidTestImplementation("androidx.compose.ui:ui-test-junit4")
        debugImplementation("androidx.compose.ui:ui-tooling")
    }
