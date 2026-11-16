plugins {
    id("com.android.application")
}

android {
    namespace = "com.elliot.blindspot"
    compileSdk = 34
    
    // 启用 AIDL 支持虚拟摄像头跨进程通信
    buildFeatures {
        aidl = true
    }

    defaultConfig {
        applicationId = "com.elliot.blindspot"
        minSdk = 28
        targetSdk = 34
        versionCode = 7
        versionName = "1.3.4-E02"
        
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // OpenCV for Android - 鱼眼去畸变
    // 暂时注释，需要手动下载OpenCV SDK
    // implementation("org.opencv:opencv-android:4.5.3")
    
    // ZXing - 二维码生成
    implementation("com.google.zxing:core:3.5.2")
    
    // AndroidX - RecyclerView for video list
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.core:core:1.12.0")
}
