import java.util.Properties

plugins {
    id("com.android.application")
}

// 카카오 지도 SDK 버전. 의존성과 Local API 요청의 KA 헤더(sdk/…)가 이 값 하나를 쓴다(#19).
val kakaoMapSdkVersion = "2.14.1"

android {
    namespace = "com.sgshs.sharepoi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sgshs.sharepoi"
        minSdk = 26
        targetSdk = 36
        // 1.0.11: 릴리스 빌드에서 지도 SDK 진단 로그 차단(#35). versionCode 11
        versionCode = 11
        versionName = "1.0.11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        val properties = Properties()
        val localPropertiesFile = project.rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            properties.load(localPropertiesFile.inputStream())
        }
        // 빌드에 들어가는 카카오 키는 네이티브 앱 키 하나뿐이다(#19). 지도 SDK 인증과 Local API 호출
        // (KakaoAK 네이티브 앱 키 + KA 헤더의 서명 키 해시·패키지명)에 같이 쓴다. REST API 키는 넣지 않는다 —
        // 키 해시로 묶이지 않아 APK 에서 꺼내면 어디서든 쓸 수 있기 때문이다.
        val kakaoMapKey = properties.getProperty("KAKAO_MAP_API_KEY") ?: "DUMMY_KEY"

        buildConfigField("String", "KAKAO_MAP_API_KEY", "\"${kakaoMapKey}\"")
        buildConfigField("String", "KAKAO_MAP_SDK_VERSION", "\"${kakaoMapSdkVersion}\"")
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
    implementation("com.kakao.maps.open:android:$kakaoMapSdkVersion")
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
