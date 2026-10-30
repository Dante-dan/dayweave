plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "app.dayweave"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.dayweave"
        minSdk = 31
        targetSdk = 35
        versionCode = 2
        versionName = "0.2"
        ndk { abiFilters += "arm64-v8a" }
        // 拾日云的地址。源码版构建留空，只能自带密钥：./gradlew assembleRelease -PcloudUrl=https://...
        buildConfigField("String", "CLOUD_URL", "\"${project.findProperty("cloudUrl") ?: ""}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 本地体验用调试签名；正式发布换成自己的签名。
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }
    androidResources {
        noCompress += "onnx"
    }
}

dependencies {
    implementation(fileTree("libs") { include("*.aar") })

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("dev.chrisbanes.haze:haze:1.1.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
}
