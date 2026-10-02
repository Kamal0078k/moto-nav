import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.motonav.simulator"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.motonav.simulator"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use(::load)
    }
    val mapsApiKey = localProperties.getProperty("google.mapsApiKey", "")
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
    buildTypes {
        getByName("debug") {
            buildConfigField("String", "MAPS_API_KEY", "\"$mapsApiKey\"")
        }
        getByName("release") {
            // A distributable artifact must not contain a locally configured credential.
            buildConfigField("String", "MAPS_API_KEY", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            it.systemProperty("repositoryRoot", rootProject.projectDir.parentFile.absolutePath)
        }
    }
}

dependencies {
    // 1.17.0 is the newest stable Core compatible with this API-36 / AGP-8.12 project.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("com.google.android.libraries.places:places:5.1.1")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    testImplementation("junit:junit:4.13.2")
}
