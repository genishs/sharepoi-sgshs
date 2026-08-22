import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
}

android {
    namespace = "com.sgshs.sharepoi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sgshs.sharepoi"
        minSdk = 26
        targetSdk = 36
        // 이번 내부테스트 빌드는 versionCode 5. 다음엔 6
        versionCode = 5
        versionName = "0.1t"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
        }
        val kakaoMapKey = properties.getProperty("KAKAO_MAP_API_KEY") ?: "DUMMY_KEY"
        val kakaoRestKey = properties.getProperty("KAKAO_REST_API_KEY") ?: properties.getProperty("KAKAO_MAP_API_KEY") ?: "DUMMY_KEY"
        
        buildConfigField("String", "KAKAO_MAP_API_KEY", "\"${kakaoMapKey}\"")
        buildConfigField("String", "KAKAO_REST_API_KEY", "\"${kakaoRestKey}\"")
    }

    // Upload-key signing for Play App Signing. Secrets come from environment variables so
    // nothing sensitive is committed: SHAREPOI_KEYSTORE, SHAREPOI_KEYSTORE_PW, SHAREPOI_KEY_ALIAS, SHAREPOI_KEY_PW
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("SHAREPOI_KEYSTORE")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("SHAREPOI_KEYSTORE_PW")
                keyAlias = System.getenv("SHAREPOI_KEY_ALIAS") ?: "upload"
                keyPassword = System.getenv("SHAREPOI_KEY_PW") ?: System.getenv("SHAREPOI_KEYSTORE_PW")
            }
        }
    }

    buildTypes {
        release {
            if (System.getenv("SHAREPOI_KEYSTORE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
    }
}

dependencies {
    implementation("com.kakao.maps.open:android:2.14.1")
    implementation("com.google.android.gms:play-services-location:21.2.0")

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
}
