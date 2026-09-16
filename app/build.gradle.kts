import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// key 类配置放 local.properties(gitignore 内),不进版本库
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// 发布仓库(GitHub Releases 在线检查更新用): local.properties 填 github_repo=用户名/仓库名,留空则检查更新按钮走纯本地文案
val githubRepo = localProps.getProperty("github_repo") ?: ""
// 公共Key额度门禁开关: 分发正式版时在 local.properties 加 key_enforce=true 重新出包;开发阶段留空=完全不限制
val keyEnforce = localProps.getProperty("key_enforce")?.toBoolean() ?: false

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
        versionCode = 6
        versionName = "0.0.5"

        // 检查更新的发布仓库(GitHub Releases);留空 = 检查更新按钮走纯本地文案
        buildConfigField("String", "GITHUB_REPO", "\"$githubRepo\"")
        // 公共Key额度门禁: 默认关(开发阶段零限制);分发版构建前在 local.properties 开
        buildConfigField("boolean", "KEY_ENFORCE", "$keyEnforce")

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

    buildFeatures {
        buildConfig = true   // release 强制 Key 门禁需要 BuildConfig.DEBUG 区分构建类型
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
    // 真实的 org.json:Android 平台 jar 里的 org.json 是桩实现(方法体直接抛 not mocked),
    // 测试类路径让它优先,RouteStore 的单测才能真跑 JSON
    testImplementation(libs.org.json)
}
