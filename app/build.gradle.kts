// 必须显式 import：在 Kotlin DSL 里写 java.util.Properties 会被解析成
// Gradle 的 java 扩展（Unresolved reference 'util'），而不是包名。
import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "com.aznixl.videowall"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.aznixl.videowall"
        minSdk = 24
        targetSdk = 36
        versionCode = 13
        versionName = "2.2"
    }

    // 发布签名。
    //
    // 两套来源，按顺序取：
    //   ① 本地 keystore.properties（已 gitignore，永不入库）
    //   ② CI 上的环境变量 KEYSTORE_FILE / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD
    // 两者都没有时 release 退回 debug 签名，
    // 保证 assembleRelease 在任何机器（含没配密钥的 CI）上都能出一个可安装的包。
    signingConfigs {
        create("release") {
            val propsFile = rootProject.file("keystore.properties")
            if (propsFile.exists()) {
                val props = Properties()
                propsFile.inputStream().use { props.load(it) }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            } else if (System.getenv("KEYSTORE_FILE") != null) {
                storeFile = file(System.getenv("KEYSTORE_FILE"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            // 不开 R8：诊断页用反射读 Build.SOC_MODEL /
            // Build.VERSION.MEDIA_PERFORMANCE_CLASS，混淆有可能把这两个字段改名或裁掉。
            // 省下来的那点体积不值得冒这个风险。
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }
}
