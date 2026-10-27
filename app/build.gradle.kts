plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.swarnimbandekar.knightlingo"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.swarnimbandekar.knightlingo"
        minSdk = 34
        targetSdk = 36
        versionCode = 23
        versionName = "1.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/libstockfish.so"
        }
    }
}

dependencies {
    implementation("com.github.bhlangonijr:chesslib:1.3.7")
    testImplementation("junit:junit:4.13.2")
}
