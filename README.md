# 视频墙 VideoWall

至多 4 路视频同时铺满屏幕播放的 Android 应用。

项目地址：<https://github.com/AZNixl/VideoWall>

血统：UI 骨架与交互思路来自一份 Tasker「Java 代码」动作里的 650 行内嵌脚本
（悬浮球 + 路径列表 + 信息流 + 内联 `VideoView` 播放）。本工程把它重写为正规 `Activity`，
再按实际使用反馈逐步重做交互、排布与设置。

- 包名 `com.aznixl.videowall`，minSdk 24 / targetSdk 36
- 第三方依赖**只有一个**：Material Components（Material 3 主题 + 明暗切换），见 §6
- 视频解码/渲染全部走平台 API：`VideoView` / `MediaPlayer`

---

## 1. 功能一览

| 能力 | 做法 |
|---|---|
| 至多 4 路同时播放 | 4 个独立 `VideoView`，各自一条 `MediaCodec` 解码会话 |
| **两种排布** | **2×2 网格** / **一字排开（1×4）**，手动切换 |
| **不足 4 个弹性铺满** | 只选 1～3 个时按数量撑满屏幕，不留黑格 |
| **排布与横竖屏解耦** | 排布由设置决定，不随系统旋转自动改；旋转时 Activity 不重建，视频随屏幕一起转 |
| **逐格独立控制** | 每格自己的 ▶/‖、−10s、+10s、独立进度条（控制条固定这四项 + 全屏） |
| **放大为全屏** | 点格内控制条的全屏图标，该格独占整屏，其余几路**暂停**（不是释放）；再点还原即按原状态继续 |
| **信息一键显隐** | 进度条 / 文件名 / 编号一键切换「播放时隐藏」或「常显」 |
| **续播或从头播** | 可记住每路看到哪儿，下次接着放；也能一键清空进度 |
| 4 路音频同时出声 | 各自一条 `AudioTrack` 交给系统混音；可切「单路」「全静音」 |
| **亮色 / 暗色主题** | Material 3 DayNight，跟随系统或强制指定；播放页浮层为保证可读性固定深色 |
| **文件夹排除** | 设置里勾掉某些文件夹，首页不再出现（其视频也不进「全部视频」） |
| 按文件夹挑片 | `MediaStore` 的 `BUCKET_ID` 归组，可设文件夹/视频排序 |
| **首页三键** | 标题右侧：主题切换（亮色/暗色/跟随）、文件夹排布切换（列表 ⇄ 两列网格，图标键）、设置 |
| 选好再播、铺满全屏 | 选择页 → 「开始播放」→ 进沉浸式，隐藏系统栏 |
| **首次启动介绍页** | 讲清用法与权限，拒绝授权后还有补救指引；设置里可重新查看 |
| **4K 起播前预检** | 列出每个文件的分辨率、编码、会用哪个解码器（硬解/软解），并给「只播前 2 路」 |
| 顶部不被状态栏压住 | 显式消费 `WindowInsets`（`systemBars + displayCutout`，四边都吃） |
| 摸清设备能力 | 设置页 →「设备并发解码能力」：MPC / 各 codec 的并发实例上限 |
| 哪一格解码失败看得见 | 每格独立 `setOnErrorListener`，显示分辨率 + 错误码 + 可能原因 |

## 2. 交互流程

```
首次启动 → 介绍页（用法 4 条 + 权限说明 + 授权按钮）
  └─ 授权后 → 文件夹列表
  │    · 顶栏：视频墙 ｜ [主题·亮色/暗色/跟随] ｜ [排布图标·列表/网格] ｜ 设置
  │    · 排布可切「单列列表」或「两列网格」（图标键，选择记在偏好里）
       └─ 点文件夹 → 视频列表，点一下勾选（最多 4 个）
            └─ 底部 4 个槽位显示已选，点槽位可移除
                 └─ 「开始播放 · N 路」→（有 4K 时先弹性能提醒）
                      └─ 沉浸式铺满全屏
                           ├─ 点画面任意处 = 选中该格 + 唤出菜单（不改播放状态）
                           ├─ 格内底部（从上到下）：编号+文件名+分辨率+编码 ｜ 逐格控制条 ｜ 进度条
                           │    逐格控制条固定四项：−10s ｜ ▶/‖ ｜ +10s ｜ 全屏（矢量图标）
                           │    2 列以内四格都显示；1×4、1×3 时每格太窄，只给选中格显示
                           │    最下排的控件按**底部**手势区往上抬；
                           │    首列/末列的控件按**左右**手势区往里缩（见 §5.6）
                           ├─ 选中格：强调色描边 + 编号底色变强调色
                           ├─ 收起：静置后自动隐藏（时长可设），没有单独的手动收起入口
                           └─ 顶栏（唯一的浮层，只有 5 个键）：
                                ‹ 返回 ｜ ⏸ 暂停 ｜ ▶ 播放 ｜ 2×2(or 1×4) ｜ 音频·全部(or 单路/静音)
```

> **为什么点格子不切播放/暂停**：原来「控制条收起时唤出控制条、控制条已显示时切播放/暂停」
> 是同一个手势承担两件事 —— 想选中一格（比如切到「单路音频」后指定哪格出声）时手一抖就把它暂停了。
> 现在**点击只做"选中"**，播放/暂停交给格内那排的 ▶/‖ 键，语义唯一、可预期。
>
> **「点不灵敏」的坑**：点选监听器必须挂在**铺满整格的 `VideoView`** 上。
> 曾写成 `vv.setOnClickListener(null)` + 监听器挂到 cell 上，想让 VideoView 透传触摸 ——
> 但 AOSP 的 `View.setOnClickListener` 是 `if (!isClickable()) setClickable(true);`，
> **传 null 只清监听器、clickable 反被置成 true**，于是 VideoView 的 `onTouchEvent` 返回 true
> 把整格触摸全吞了。详见技能 `android-platform-pitfalls` 第七节。

## 3. 设置项

| 分组 | 条目 | 默认 |
|---|---|---|
| 外观 | **主题：跟随系统 / 亮色 / 暗色** | 跟随系统 |
| 排布与显示 | 排布模式：2×2 网格 / 一字排开（1×4） | 2×2 |
| | 不足 4 个时自动铺满 | 开 |
| | 起播前检查分辨率 | 开 |
| | 播放时一直显示进度条 / 文件名 / 格子编号 | 关 |
| | 控制条自动隐藏：从不 / 2s / 4s / 8s | 4s |
| 音频 | 音频模式：四路同时出声 / 只让选中格出声 / 全部静音 | 四路同时 |
| 播放行为 | **开始播放时：从头播放 / 继续播放** | 从头播放 |
| | 清除已记住的播放进度 | — |
| | 开始播放时自动起播 / 单个视频循环播放 / 播放中保持屏幕常亮 / 记住上次选的视频 | 开 / 关 / 开 / 开 |
| 媒体库 | 列表显示缩略图 / 不显示的文件夹 / 文件夹排序 / 视频排序 | 开 / 无 / 数量 / 时间 |
| 关于 | 关于本应用、作者、版本、项目主页、开源许可、查看使用指引、设备并发解码能力 | — |

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

应用的做法是「让失败可读」：

1. **起播前预检**：读每个文件的分辨率（含旋转修正）、编码格式，
   以及平台**会**给它的解码器（`MediaCodecList.findDecoderForFormat()`）—— 标出硬解/软解与 `max=` 并发上限。
   有 4K 就弹窗列出，并给「照常播放 / 只播前 2 路 / 取消」，弹窗里可勾「不再提醒」。
2. **每格错误可见**：起不来的一格显示 `分辨率 + 原因 + what/extra 错误码`。
3. **一次性降级建议**：有格子失败且还有 ≥2 路在播时，问一次「保留 2 路」。

### 解码器能不能调？—— 现在不能

`MediaPlayer` / `VideoView` **不提供**选择解码器的 API：平台自己从
`MediaCodecList` 里挑（`findDecoderForFormat` 就是它用的那套规则），
既不能让某一路强制软解，也没有"降级到 1080p 解码"这种能力（本地文件只有一个码流）。

所以本项目只做到**如实报告**：告诉你会用哪个解码器、是硬解还是软解、并发上限多少。
真要能选，得换成 Media3 / ExoPlayer —— 它允许自定义 `MediaCodecSelector`
（可以按名字禁掉某个解码器、优先软解等）。那是另一条路线（见 §8）。

> 预检里显示的"解码器"是**预测值**：按平台规则它*应该*选这个。
> `MediaPlayer` 最终实际用了哪个并不对外开放，无法从应用层确认。

## 5. 排布与生命周期上的坑

### 5.1 排布不跟横竖屏走，但视频跟随

manifest 给 Activity 声明 `configChanges="…|orientation|screenSize|uiMode|density"`，
旋转时 **Activity 不重建**，画面随屏幕转，正在播的几路不会被打断重来。
`onConfigurationChanged` 把四边 inset 缓存清零重新量一次（横竖屏安全区不同）。

保留 `uiMode` 是为了**系统到点自动切深色时也不打断播放**。
代价：用户在设置里手动改明暗后，系统不会自动重建，所以由 App 自己 `recreate()`。

### 5.2 切换排布不能摘挂子 View

「新建容器把 4 个子 View 搬过去」会让 `SurfaceView` detach/attach → Surface 销毁重建 →
正在播的视频必然闪一下。正解是**复用同一个 `GridLayout`**，只改 spec 与行列计数。

### 5.3 GridLayout 动态改排布：两个入口的校验方向相反

| 入口 | 校验规则 | 异常原文 |
|---|---|---|
| `setColumnCount` / `setRowCount` | `count >= 子项 spec 的 max(start+span)` | `columnCount must be greater than or equal to the maximum of all grid indices (and spans) defined in the LayoutParams of each child.` |
| `View.setLayoutParams`（子项） | `spec 的 start+span <= 当前 count` | `row indices (start + span) mustn't exceed the row count.` |

**先改计数再改 spec** 会被旧 spec 拦下；**先改 spec 再改计数** 会被旧计数拦下（换个异常照样崩）。
唯一走得通的顺序是三步：

```java
grid.setColumnCount(MAX_CELLS);   // ① 先放宽到超集（列 4 / 行 2）
grid.setRowCount(MAX_ROWS);
for (int i = 0; i < 4; i++) {     // ② 再改每个子项的 spec（含隐藏的格子，也必须给 spec）
    child.setLayoutParams(lp);
}
grid.setColumnCount(cols);        // ③ 最后收紧到目标值（须等于新 spec 的最大 start+span）
grid.setRowCount(rows);
```

### 5.4 退出播放后「视频在后台自己播起来」

`VideoView.stopPlayback()` 内部是 `mMediaPlayer.stop()` + `release()`，
之后还有 `mCurrentState/mTargetState = STATE_ERROR`。
若此刻 `MediaPlayer` 还在 **Preparing**（大文件 / 4K 准备慢时很常见），`stop()` 抛
`IllegalStateException` —— **`release()` 与状态复位全都执行不到**。

于是：① 播放器泄漏；② `mTargetState` 还停在 `STATE_PLAYING`，
而 `VideoView.onPrepared()` 里有 `if (mTargetState == STATE_PLAYING) start();` ——
prepare 完成时它**自己把自己播起来**，画面不可见、引用又已置空，谁都停不掉。

**修法**：`Cell.reset()` 里**先 `vv.pause()` 再 `vv.stopPlayback()`**。
`pause()` 最后一句是无条件执行的 `mTargetState = STATE_PAUSED;`，先把 target 钉死。
另加 `Cell.stale` 挡住迟到的 `onPrepared`。

### 5.5 多实例：`MediaPlayer.isPlaying()` 在 Error 态会抛异常

任意一路解码失败进入 Error 态后，`isPlaying()` / `getCurrentPosition()` 会抛 `IllegalStateException`。
而 ticker、`onStop`、「全部暂停」、`onPrepared` 回调都会**遍历全部 4 格** ——
一路坏掉就把另外几路一起炸掉。所以所有触及 `MediaPlayer` 的地方都走带兜底的包装
（`mpIsPlaying` / `mpPosition` / `mpPause` …），并在 `onError` 里**立刻把 `c.mp` 置空**。

### 5.6 控件避开系统手势区 —— 手势区可能不在底部

一开始按常识只把最下排的控件"往上抬"，依据是"底部手势区"。后来从真机
`dumpsys activity top` 的 insets 里读到了实际值：

```
systemGestures left   [0,0][120,3168]      左边缘 120px（30dp）整条
systemGestures right  [1320,0][1440,3168]  右边缘 120px 整条
systemGestures bottom —— 0
navigationBars        [0,3168][1440,3168]  高度 0（沉浸式下已隐藏）
```

**这台设备的手势区在左右两侧，底部没有。** 而进度条横跨整格宽度，
最外两列的进度条末端正好压在这两条返回手势带上 —— 拖到边上就触发系统手势。
所以真正的修法是**水平内缩**：

- 首列 / 末列的格内控件，按左右手势区宽度往里让（`systemGestures().left/right`）
- 垂直方向的小抬升保留（有些设备手势区确实在底部）

教训：**别按常识猜手势区在哪**，读一次 insets 就有确切答案。

### 5.7 ABI：为什么 v7a / v8a 都能装

包内 `0 个 .so`、没有 `lib/` 目录，只有两个 dex —— **纯 Java 应用，架构无关**，
armeabi-v7a / arm64-v8a / x86 都能装。视频解码走系统自带的 `MediaCodec`
（由系统按 CPU 架构提供），不打包进 APK。

## 6. 主题与 Material

界面**全部是代码里手工搭的**（没有布局 XML），所以颜色不能是写死的常量 ——
否则明暗切换无从谈起。做法：

1. `res/values/attrs.xml` 定义 11 个自定义主题属性（`vwBg` / `vwCard` / `vwAccent` …）
2. `res/values/themes.xml`（亮色）与 `res/values-night/themes.xml`（暗色）
   用**同名 style** 分别给这些属性赋值
3. `Palette.of(context)` 在 Activity 的 `onCreate` 里一次性解析成字段，
   代码里统一用字段名（故意沿用了旧的常量名，这样一个使用点都不用改）

**有意为之的一处例外**：播放页压在**视频画面**上的元素（顶栏底衬、格子编号、文件名、逐格按钮）
固定用深色半透明，不跟主题走 —— 它们底下是不确定亮度的视频，跟主题走反而在亮色主题下会糊成一片。

第三方依赖：

| 库 | 版本 | 许可 | 用途 |
|---|---|---|---|
| Material Components for Android | 1.14.0 | Apache-2.0 | Material 3 主题、组件样式、涟漪反馈、DayNight 明暗切换 |

## 7. 编译与发布签名

需要 JDK 17 + Android SDK（`compileSdk 36`，`build-tools 36.0.0`）。

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
```

### 本地发布签名

`app/build.gradle.kts` 按顺序找两处签名材料：

1. **`keystore.properties`**（已 gitignore，永不入库）
2. CI 上的环境变量 `KEYSTORE_FILE` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`

两者都没有时，release **退回 debug 签名** —— 保证 `assembleRelease` 在任何机器上都能出可安装的包。

生成一套自己的密钥：

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

### CI 上签名

在仓库 Secrets 里加四个值：

| Secret | 内容 |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 keystore/videowall-release.jks` 的输出 |
| `KEYSTORE_PASSWORD` | storePassword |
| `KEY_ALIAS` | keyAlias |
| `KEY_PASSWORD` | keyPassword |

不配也能跑，只是 release 用 debug 签名。

## 8. 代码结构

| 文件 | 职责 |
|---|---|
| `MainActivity.java` | 介绍页 / 选择页 / 播放页三段式界面、排布、逐格控制、预检、诊断 |
| `SettingsActivity.java` | 设置页（沿用 NextPlayer 的「开关 / 可选项 / 跳转项」三件套） |
| `Prefs.java` | 全部设置项的唯一入口 + 各处的序列化格式 |
| `Palette.java` | 从当前主题解析界面用色 |
| `App.java` | Application，按设置决定明暗模式 |
| `tools/make_icon.py` | 从同一套几何生成各密度 PNG 图标与预览图 |

## 9. 已知边界

- **渲染层是 `VideoView`/`MediaPlayer`**，不是 Media3/ExoPlayer。没有字幕、音轨选择、
  网络源（SMB/SFTP/WebDAV）、播放列表。
- **解码器不可指定**（见 §4）：不能强制软解，也不能降分辨率。
- 没有「1 主 3 副」布局，也没有单格全屏放大。
- 每格可播放/暂停/seek/±10s/重播，但**没有逐格音量**（要单独出声就切「单路」再点那一格）。
- **四路同时出声时未处理 `AudioManager` 音频焦点**：交给系统混音，能同时响；
  但接电话或其他 App 抢焦点时行为未定义。
- `onStop` 后不自动恢复播放，回到前台需手动点「播放」。
- 未做视频缩放模式（适应/裁剪/拉伸）。**不做是有原因的**：裁剪要把 `VideoView` 撑得比格子大
  再让父容器裁掉，但 `SurfaceView` 的 Surface 由系统合成器独立放置，
  **不受父 View 的 `clipChildren` 约束**，会溢到邻格里。
- 引入 Material 后 APK 从 ~940 KB 涨到 ~5.4 MB（AppCompat + Material 的资源占大头）。

## 10. 两条路线的定位

| | 本工程（VideoView 路线） | fork NextPlayer（Media3 路线） |
|---|---|---|
| 起量 | 小 | 大，新增 8～10 个文件 + 改导航/设置/manifest |
| 能拿到的功能 | 多路播放 + 排布/设置 | 字幕、音轨、**可选解码器**、FFmpeg 软解、网络源、进度记忆 |
| 适合 | 自用、原型、验证设备能力 | 做成可分发的产品 |

本工程在真机（OnePlus PJZ110）上已实测 **4 路可同时播放**，可行性报告里的 P1（Go / No-Go）关卡已通过。
要继续往「产品」或「可选解码器」走，就得换到 Media3 路线。
