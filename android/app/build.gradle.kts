plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.cmfopen.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.cmfopen.companion"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Sideloaded personal build: sign release with the debug key so it installs without setup.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/watchApps"))

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Bundle the web store's watch apps (web/public/apps) so they run on the phone unchanged.
val copyWatchApps by tasks.registering(Copy::class) {
    from(rootProject.file("../web/public/apps"))
    include("*.js", "index.json")
    into(layout.buildDirectory.dir("generated/watchApps/apps"))
}
tasks.named("preBuild") { dependsOn(copyWatchApps) }

dependencies {
    implementation(project(":protocol"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.webkit:webkit:1.12.1")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
}
