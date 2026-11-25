plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "com.gunyah.launcher"
    compileSdk = 36
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.gunyah.launcher"
        minSdk = 28
        targetSdk = 28
        versionCode = 137
        versionName = "1.9.81"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        resourceConfigurations += listOf("en", "zh")
    }

    val hasReleaseSigning = providers.gradleProperty("RELEASE_STORE_PASSWORD").isPresent &&
        providers.gradleProperty("RELEASE_KEY_ALIAS").isPresent &&
        providers.gradleProperty("RELEASE_KEY_PASSWORD").isPresent &&
        file("../keystore/release.jks").isFile

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file("../keystore/release.jks")
                storePassword = providers.gradleProperty("RELEASE_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            // 用固定 keystore 给 debug 包签名：各修改版之间可互相覆盖安装，不必卸载
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
        }
    }

    tasks.matching { it.name == "assembleRelease" }.configureEach {
        doFirst {
            check(hasReleaseSigning) {
                "Release 构建需要 keystore/release.jks 及 RELEASE_* 签名属性"
            }
        }
    }

    lint {
        checkReleaseBuilds = false
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        // 可执行 ELF：必须 STORED，压缩会破坏 mmap/执行且 openFd 拿不到真实长度
        noCompress += "gunydhcpd"
        noCompress += "gunytap"
        noCompress += "touchd"
        noCompress += "memlock-runner"
        noCompress += "zip"
        noCompress += "dat"
        noCompress += "iso"
        noCompress += "sh"
    }

    packaging {
        resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/DEPENDENCIES")
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("com.github.topjohnwu.libsu:service:6.0.0")
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    implementation("androidx.core:core-ktx:1.13.1")
    // v1.9.56：DroidVM vmpkg 解包——zstd（AAR 含 arm64-v8a .so）+ xz
    implementation("com.github.luben:zstd-jni:1.5.7-11@aar") { isTransitive = true }
    implementation("org.tukaani:xz:1.10")
}
