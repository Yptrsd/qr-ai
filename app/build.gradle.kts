plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.qrai"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.qrai"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // 只保留中文+英文资源，减少多语言资源体积
        resConfigs("zh", "en")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        jvmToolchain(25)
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 临时用 debug 签名，方便直接安装测试 release 版体积（正式发布再换正式签名）
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    // 零依赖。所有东西用 Android SDK 自带的。
}
