import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// 本机 local.properties（不进仓库）
val localProps = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } } ?: Properties()

// 正式签名：local.properties 里 releaseSigning 指向仓库外的 signing.properties；没有就退回调试签名。
val releaseSigning = localProps.getProperty("releaseSigning")?.let(::File)?.takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "app.dayweave"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.dayweave"
        minSdk = 31
        targetSdk = 35
        versionCode = 3
        versionName = "0.3"
        ndk { abiFilters += "arm64-v8a" }
        // 拾日云的地址：-PcloudUrl=… 优先，其次本机 local.properties 里的 cloudUrl（不进仓库）；都没有就是源码版，只能自带密钥。
        buildConfigField("String", "CLOUD_URL", "\"${project.findProperty("cloudUrl") ?: localProps.getProperty("cloudUrl") ?: ""}\"")
    }

    signingConfigs {
        if (releaseSigning != null) create("release") {
            storeFile = File(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // -PdebugSigned：给已装调试签名版本的手机原地升级，不丢本地档案。
            signingConfig = signingConfigs.findByName("release")?.takeUnless { project.hasProperty("debugSigned") }
                ?: signingConfigs.getByName("debug")
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
    implementation("com.android.billingclient:billing-ktx:8.3.0")
    // Play Billing 间接带来旧版 fragment，会让 registerForActivityResult 出错，固定到新版
    implementation("androidx.fragment:fragment:1.8.5")
}
