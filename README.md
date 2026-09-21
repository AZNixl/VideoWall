# 视频墙 VideoWall

至多 4 路视频同时铺满屏幕播放的 Android 应用。

- 项目地址：<https://github.com/AZNixl/VideoWall>
- **下载**：<https://github.com/AZNixl/VideoWall/releases> —— 取 `app-release.apk`，用发布密钥签名
- 包名 `com.aznixl.videowall` · minSdk 24 / targetSdk 36
- 第三方依赖只有 **Material Components**（Material 3 主题 + 明暗切换）
- 视频解码/渲染全部走平台 API：`VideoView` / `MediaPlayer`
- 版本历史与踩坑记录见 [CHANGELOG.md](CHANGELOG.md)

最初的东西是一份 Tasker「Java 代码」动作里的 650 行内嵌脚本，本工程把它重写成正规 `Activity`，
再按实际使用反馈迭代了十几版。

---

## 1. 它能做什么

### 挑片

- 按文件夹浏览（`MediaStore` 的 `BUCKET_ID` 归组），可设文件夹/视频排序
- 首页可切**单列列表**或**两列网格** —— 这一档**贯穿两级**：点进文件夹后的视频列表也是两列卡片
- 文件夹可以在设置里排除掉，首页不再出现
- 会记住上次选的那 4 个视频

### 播放

- **两种排布**：2×2 网格 / 一字排开（1×4），手动切换
  - 排布**不随系统横竖屏自动改**；但旋转时 Activity 不重建，画面跟着屏幕转
- 不足 4 个时按数量**弹性铺满**，不留黑格
- **逐格独立控制**：每格自己的 后退 / 播放暂停 / 前进 / 全屏，加独立进度条
- **放大为全屏**：某一格独占整屏，其余几路暂停（不是释放）；还原时按原状态续起
- **播放时隐藏信息**：进度条/文件名/编号默认只在唤出控制条时出现
- **续播或从头播**：可记住每路看到哪儿，也能一键清空
- **音频两档**：四路同时出声 / 只让选中格出声（选中格会带强调色描边）

### 界面

- Material 3，**亮色 / 暗色 / 跟随系统**三档，首页顶栏一键切换
- 播放页浮层为保证可读性固定深色（压在不确定亮度的视频上，跟主题走反而会糊）
- 首次启动有介绍页：用法 + 权限用途 + 拒绝授权后的补救路径

### 起了问题能看见

- 起播前预检：有 4K / 多路 2K 时先给结论性提醒（可勾「不再提醒」）
- 每一格用自己的 `setOnErrorListener`，把分辨率、错误码、可能原因画在格子上
- 设置页有诊断：`MEDIA_PERFORMANCE_CLASS`、各 codec 的并发实例上限、
  以及每个文件"平台会派哪个解码器（硬解/软解）"

---

## 2. 交互流程

### 首页（挑片）

```
文件夹列表
  · 顶栏：视频墙 ｜ [主题图标] ｜ [排布图标] ｜ [设置图标]
  │        主题：☀ 亮色 / ☾ 暗色 / A 跟随系统（三档循环）
  │        排布：▤ 列表 / ▦ 两列网格（同时作用于文件夹内部）
  └─ 点文件夹 → 视频列表，点一下勾选（最多 4 个）
       · 底部 4 个槽位显示已选，点槽位可移除
       └─ 「开始播放 · N 路」→（有 4K 时先弹性能提醒）
```

### 播放页

```
沉浸式铺满全屏
  · 点画面任意处 = 选中该格 + 唤出菜单（不改播放状态）
  · 格内底部（从上到下）：编号 + 文件名 + 分辨率 + 编码 ｜ 逐格控制条 ｜ 进度条
      逐格控制条：[« 后退] [⏯ 播放暂停] [» 前进] [⛶ 全屏]
      2 列以内四格都显示；1×4、1×3 时每格太窄，只给选中格显示
      最下排的控件按底部手势区往上抬；首列/末列的进度条按左右手势区往里缩
  · 选中格：强调色描边 + 编号变白
  · 收起：静置后自动隐藏（时长可设），没有单独的手动收起入口
  · 顶栏（唯一的浮层，5 个图标键）：
      [← 返回] [⏯ 播放/暂停·全局] [↻ 全部重播] [▦ 布局切换] [🎧 音频切换]
      图标表示"当前是什么"：在播显暂停、排布显当前排布、音频显当前档
```

**为什么点格子不切播放/暂停**：原来「控制条收起时唤出控制条、控制条已显示时切播放/暂停」
是同一个手势承担两件事 —— 想选中一格（比如切到「单路音频」后指定哪格出声）时手一抖就把它暂停了。
现在**点击只做"选中"**，播放/暂停交给格内那排的按键，语义唯一、可预期。

---

## 3. 设置项

| 分组 | 条目 | 默认 |
|---|---|---|
| 外观 | 主题：跟随系统 / 亮色 / 暗色 | 跟随系统 |
| 排布与显示 | 排布模式：2×2 网格 / 一字排开（1×4） | 2×2 |
| | 不足 4 个时自动铺满 | 开 |
| | 起播前检查分辨率 | 开 |
| | 播放时一直显示进度条 / 文件名 / 格子编号 | 关 |
| | 控制条自动隐藏：从不 / 2s / 4s / 8s | 4s |
| 音频 | 音频模式：四路同时出声 / 只让选中格出声 | 四路同时 |
| 播放行为 | 开始播放时：从头播放 / 继续播放 | 从头播放 |
| | 清除已记住的播放进度 | — |
| | 自动起播 / 单个循环 / 播放中常亮 / 记住上次选的视频 | 开 / 关 / 开 / 开 |
| 媒体库 | 列表显示缩略图 / 不显示的文件夹 / 文件夹排序 / 视频排序 | 开 / 无 / 数量 / 时间 |
| 关于 | 关于本应用、作者、版本、项目主页、开源许可、查看使用指引、设备并发解码能力 | — |

---

## 4. 关于 4K 与并发解码

**4 路 4K 在手机上是做不到的，这是硬件限制，不是软件问题。**

Android 兼容性定义（CDD）对声明了 `MEDIA_PERFORMANCE_CLASS` 的设备给出的并发硬解会话下限：

| MPC 档位 | 并发实例数（任意 codec 组合） | 分辨率 |
|---|---|---|
| 10 | 2 | 720p@30 |
| 20 | 4 | 720p@30 |
| 30 / 31 | 6 | 720p@30 |
| 33 | 6 | 1080p@30 |
| 34 / 35 | 6 = 3×1080p30 + 3×4K30 | 混合 |

即使最高的 34/35 档也只保证 **3 路 1080p + 3 路 4K**，而 4K 解码器通常只能同时开 1～2 个。
真实瓶颈往往是**内存带宽**，不是实例计数。

应用的做法是「让失败可读」：起播前给结论性提醒（可勾「不再提醒」）→
失败的那一格显示分辨率与错误码 → 还有 ≥2 路在播时问一次「保留 2 路」。

### 解码器能不能调？—— 现在不能

`MediaPlayer` / `VideoView` **不提供**选择解码器的 API：平台自己从 `MediaCodecList` 里挑，
既不能强制软解，也没有"降级到 1080p 解码"（本地文件只有一个码流）。

所以本项目只做到**如实报告**：用 `findDecoderForFormat()` 告诉你
平台**会**用哪个解码器、是硬解还是软解、并发上限多少。
注意那是**预测值** —— `MediaPlayer` 最终实际用了哪个并不对外开放，无法从应用层确认。

真要能选，得换 Media3 / ExoPlayer（它允许自定义 `MediaCodecSelector`）。

---

## 5. 工程结构

| 文件 | 职责 |
|---|---|
| `MainActivity.java` | 介绍页 / 选择页 / 播放页三段式界面、排布、逐格控制、预检、诊断 |
| `SettingsActivity.java` | 设置页 |
| `Prefs.java` | 全部设置项的唯一入口 + 各处序列化格式 |
| `Palette.java` | 从当前主题解析界面用色 |
| `App.java` | Application，按设置决定明暗模式 |
| `tools/make_icon.py` | 从同一套几何生成各密度 PNG 图标与预览图 |
| `res/drawable/ic_*.xml` | 13 个手写矢量图标 |

界面**全部是代码里手工搭的**（没有布局 XML），所以颜色不能是写死的常量：

1. `attrs.xml` 定义 11 个主题属性
2. `values/themes.xml`（亮）与 `values-night/themes.xml`（暗）用**同名 style** 分别赋值
3. `Palette.of(context)` 在 Activity 的 `onCreate` 里一次性解析成字段

---

## 6. ABI：v7a / v8a 都支持

包内 `0 个 .so`、没有 `lib/` 目录，只有 dex —— **纯 Java 应用，架构无关**，
armeabi-v7a / arm64-v8a / x86 都能装。
视频解码走系统自带的 `MediaCodec`（由系统按 CPU 架构提供），不打包进 APK。

---

## 7. 编译与发布签名

需要 JDK 17 + Android SDK（`compileSdk 36`，`build-tools 36.0.0`）。

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

签名材料按顺序找两处，都没有时 release **退回 debug 签名**
（保证 `assembleRelease` 在任何机器上都能出可安装的包）：

1. `keystore.properties`（已 gitignore，永不入库）
2. CI 环境变量 `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`

生成自己的密钥：

```bash
keytool -genkeypair -v -keystore keystore/videowall-release.jks \
  -alias videowall -keyalg RSA -keysize 2048 -validity 10950 \
  -dname "CN=你的名字, OU=VideoWall, O=你的组织, C=CN"

cat > keystore.properties <<'EOF'
storeFile=keystore/videowall-release.jks
storePassword=...
keyAlias=videowall
keyPassword=...
EOF
```

CI 上让它签正式包，在仓库 Secrets 里加四个值：
`KEYSTORE_BASE64`（`base64 -w0 keystore/videowall-release.jks` 的输出）、
`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。

推 `v*` 标签会自动发一个 Release 并附上 APK。

---

## 8. 已知边界

- **渲染层是 `VideoView`/`MediaPlayer`**，不是 Media3/ExoPlayer。没有字幕、音轨选择、网络源、播放列表
- **解码器不可指定**（见 §4）
- 没有「1 主 3 副」布局
- 每格可播放/暂停/seek/全屏，但**没有逐格音量**（要单独出声就切「单路」再点那一格）
- **四路同时出声时未处理 `AudioManager` 音频焦点**：交给系统混音，能同时响；
  但接电话或其他 App 抢焦点时行为未定义
- `onStop` 后不自动恢复播放，回到前台需手动点播放
- 未做视频缩放模式（适应/裁剪/拉伸）—— 原因见 [CHANGELOG](CHANGELOG.md) 末节
- 引入 Material 后 APK 约 5.6 MB（AppCompat + Material 的资源占大头）
