plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace 'com.upiqr.quicktile'
    compileSdk 35

    defaultConfig {
        applicationId "com.upiqr.quicktile"
        minSdk 26
        targetSdk 35
        versionCode 1
        versionName "1.0.0"

        testInstrumentationRunner "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.core:core:1.15.0")
}