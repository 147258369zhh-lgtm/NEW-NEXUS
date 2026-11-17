plugins {
    alias(libs.plugins.android.library)
}

java { toolchain { languageVersion = JavaLanguageVersion.of(25) } }

android {
    namespace = "io.github.zhh_lgtm.nexus.plugin.runtime"
    compileSdk = 37
    ndkVersion = "27.0.12077973"
    defaultConfig {
        minSdk = 33
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild { cmake { cppFlags("-std=c++17") } }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_25
        targetCompatibility = JavaVersion.VERSION_25
    }
}
