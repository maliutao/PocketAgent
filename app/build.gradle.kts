import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// 固定签名配置:优先环境变量(CI 用),其次 local.properties(本地用),都没有则退回默认 debug 签名。
// 用同一签名覆盖安装,应用数据(含 API Key)不会丢失。
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val keystorePath: String? get() = System.getenv("KEYSTORE_PATH") ?: localProps.getProperty("keystore.path")
val keystorePassword: String? get() = System.getenv("KEYSTORE_PASSWORD") ?: localProps.getProperty("keystore.password")
val keystoreFile = keystorePath?.let { rootProject.file(it) }
val fixedSigningReady = keystoreFile != null && keystoreFile.exists() && keystoreFile.length() > 0 &&
    !keystorePassword.isNullOrBlank()

android {
    namespace = "com.pocketagent.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pocketagent.app"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (fixedSigningReady) {
            create("fixed") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = "pocketagent"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        debug {
            if (fixedSigningReady) signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.security.crypto)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
