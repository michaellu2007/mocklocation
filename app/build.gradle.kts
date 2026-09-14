plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.learning.mockrun"

    // 机器上只安装了 android-37 平台和 build-tools 36.0.0,
    // 与已安装版本对齐,避免构建期去 dl.google.com 下载失败。
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.learning.mockrun"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    lint {
        // ACCESS_MOCK_LOCATION 是系统 signature 级权限,声明它是模拟定位应用的常规做法,
        // lint 的 ProtectedPermissions 检查会报 error,这里显式关掉。
        disable += "ProtectedPermissions"
        // lint 默认要求模拟位置权限只出现在 debug manifest;
        // 本应用的个人使用场景就是日常模拟定位,主 manifest 必须带它,故关闭该检查。
        disable += "MockLocation"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
}
