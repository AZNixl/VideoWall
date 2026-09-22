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
        // versionCode 必须比上一次发布的 22 大，否则装不上（覆盖安装要求 code 递增）
        versionCode = 23
        versionName = "3.1"
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

    buildFeatures {
        buildConfig = false
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

// fork 之后一般没有发布密钥。这时候 release 会退回 debug 签名 —— 包能装，
// 但**装不上官方版本**（签名不同，Android 不允许覆盖）。这条提示就是给这种情况的，
// 免得拿到一个"看着像 release 其实是 debug"的包，装机失败还找不到原因。
//
// 注意：这里重新判定了一次条件，而不是去读 signingConfigs ——
// 脚本顶层作用域拿不到 android 扩展里的 signingConfigs（会 Unresolved reference）。
afterEvaluate {
    val hasReleaseKey = rootProject.file("keystore.properties").exists()
            || System.getenv("KEYSTORE_FILE") != null
    logger.lifecycle(
        if (hasReleaseKey) {
            "[videowall] release 用项目自己的发布密钥签名"
        } else {
            "[videowall] 没找到发布密钥（keystore.properties 或 KEYSTORE_FILE 环境变量），" +
                "release 退回 debug 签名 —— 能装，但覆盖安装不了官方版本。" +
                "自用无所谓；要发版就自己生成一个 keystore（见 README「发布签名」）"
        }
    )
}

dependencies {
    // Material 3（含 AppCompat 与明暗切换能力）。
    // 这是本项目唯一的第三方依赖 —— 此前版本是零依赖，为了拿到
    // Material 的组件样式、涟漪反馈与 DayNight 主题才引入。
    implementation("com.google.android.material:material:1.14.0")

    // 首页下拉刷新。Material 里没有等价组件，只能单独引一个。
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
