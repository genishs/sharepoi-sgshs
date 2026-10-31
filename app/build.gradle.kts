import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.android.libraries.mapsplatform.secrets-gradle-plugin")
}

android {
    namespace = "com.sgshs.sharepoi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sgshs.sharepoi"
        minSdk = 26
        targetSdk = 36
        // 1.0.9: 안정화 묶음(#8 #11 #13 #15 #29 #30 #32). 버전 정책(맨 뒤 = 빌드 번호 = versionCode). versionCode 9
        versionCode = 9
        versionName = "1.0.9"

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
            isMinifyEnabled = true
            isShrinkResources = true
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
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("com.kakao.maps.open:android:2.14.1")
    implementation("com.google.android.gms:play-services-location:21.3.0")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    // ViewModel·LiveData(공유 위치 확인, #29)와 lifecycleScope(검색, #18). 한 버전으로 묶는다.
    val lifecycleVersion = "2.9.4"
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:$lifecycleVersion")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:$lifecycleVersion")

    testImplementation("junit:junit:4.13.2")
}
