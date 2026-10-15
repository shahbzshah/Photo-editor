import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release signing credentials live in keystore.properties (repo root, gitignored,
// never committed). If the file is absent (e.g. CI), the release build is left unsigned.
val keystorePropsFile = rootProject.file("keystore.properties")
val hasReleaseKey = keystorePropsFile.exists()
val keystoreProps = Properties().apply {
    if (hasReleaseKey) load(keystorePropsFile.inputStream())
}

android {
    namespace = "app.photopromptcodes"
    compileSdk = 34
    buildToolsVersion = "34.0.0"

    defaultConfig {
        applicationId = "app.photopromptcodes"
        minSdk = 26
        targetSdk = 34
        versionCode = 23
        versionName = "1.6"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseKey) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        checkReleaseBuilds = false
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
    packaging {
        resources {
            excludes += "**/linkdata/**"
            excludes += "META-INF/*.version"
            excludes += "META-INF/kotlin-project-structure-metadata.json"
            excludes += "commonMain/**"
            excludes += "nativeMain/**"
            excludes += "**/*.knm"
            excludes += "**/manifest"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.serialization.json)
}

configurations.all {
    exclude(group = "androidx.collection", module = "collection-ktx")
    exclude(group = "androidx.lifecycle", module = "lifecycle-viewmodel-ktx")
    exclude(group = "androidx.lifecycle", module = "lifecycle-runtime-ktx")
    exclude(group = "androidx.lifecycle", module = "lifecycle-livedata-ktx")
}
