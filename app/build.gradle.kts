import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// key 类配置放 local.properties(gitignore 内),不进版本库
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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
        versionCode = 2
        versionName = "0.0.1"

        // 高德 key 为空时地图显示空白网格但不崩;key 由用户申请后填入 local.properties
        manifestPlaceholders["amapKey"] = localProps.getProperty("amap_key") ?: ""

        // 地图 SDK 原生库只保留 arm64(目标机 Redmi K30 Pro 为 arm64,省一半体积)
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 毛坯 release 直接复用 debug 签名分发(个人使用,不上架),装过 debug 版可平滑覆盖
            signingConfig = signingConfigs.getByName("debug")
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
    implementation(libs.amap.map)
    implementation(libs.amap.search)
    testImplementation(libs.junit)
}
