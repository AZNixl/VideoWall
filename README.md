# 视频墙 VideoWall

至多 4 路视频同时铺满屏幕播放的 Android 应用。**零第三方依赖**，只用平台 API（`VideoView` / `MediaPlayer`）。

血统：UI 骨架与交互思路来自一份 Tasker「Java 代码」动作里的 650 行内嵌脚本
（悬浮球 + 路径列表 + 信息流 + 内联 `VideoView` 播放）。本工程把它重写为正规 `Activity`，
再按实际使用反馈逐步重做交互、排布与设置。

可分发状态：`com.aznixl.videowall`，minSdk 24 / targetSdk 36，APK ≈ 936 KB。

---

## 1. 功能一览

| 能力 | 做法 |
|---|---|
| 至多 4 路同时播放 | 4 个独立 `VideoView`，各自一条 `MediaCodec` 解码会话 |
| **两种排布** | **2×2 网格** / **一字排开（1×4）**，手动切换 |
| **不足 4 个弹性铺满** | 只选 1～3 个时按数量撑满屏幕，不留黑格 |
| **排布与横竖屏解耦** | 排布由设置决定，不随系统旋转自动改；旋转时 Activity 不重建，视频随屏幕一起转 |
| **逐格独立控制** | 每格自己的 ▶/‖、−10s、+10s、**↻ 各自重播**、独立进度条 |
| **信息一键显隐** | 进度条 / 文件名 / 编号一键切换「播放时隐藏」或「常显」 |
| 4 路音频同时出声 | 各自一条 `AudioTrack` 交给系统混音；可切「单路」「全静音」 |
| **文件夹排除** | 设置里勾掉某些文件夹，首页不再出现（其视频也不进「全部视频」） |
| 按文件夹挑片 | `MediaStore` 的 `BUCKET_ID` 归组，可设文件夹/视频排序 |
| 选好再播、铺满全屏 | 选择页 → 「开始播放」→ 进沉浸式，隐藏系统栏 |
| **4K 起播前预检** | 有 4K / 多路 2K 时先提醒，并给「只播前 2 路」的选项 |
| 顶部不被状态栏压住 | 显式消费 `WindowInsets`（`systemBars + displayCutout`，四边都吃） |
| 摸清设备能力 | 设置页 →「设备并发解码能力」：MPC / 各 codec 的并发实例上限 |
| 哪一格解码失败看得见 | 每格独立 `setOnErrorListener`，显示分辨率 + 错误码 + 可能原因 |

## 2. 交互流程

```
进入 App
  └─ 文件夹列表（可设按数量/名称排序；被排除的不出现）
       └─ 点文件夹 → 视频列表，点一下勾选（最多 4 个）
            └─ 底部 4 个槽位显示已选，点槽位可移除
                 └─ 「开始播放 · N 路」
                      └─ 沉浸式铺满全屏
                           ├─ 点画面任意处 = 选中该格 + 唤出菜单（**不改播放状态**）
                           ├─ 格内底部（从上到下）：编号+文件名 ｜ 逐格控制条 ｜ 进度条
                           │    逐格控制条：−10 ｜ ▶/‖ ｜ +10 ｜ ↻（各自重播）
                           │    2 列以内（2×2 / 1 路 / 上下两分）四格都显示；
                           │    1×4、1×3 时每格太窄，只给选中格显示
                           ├─ 选中格：强调色描边 + 编号底色变强调色（一眼看出选中谁）
                           ├─ 拖每格底部细进度条 = 只 seek 这一格
                           ├─ 收起：静置后自动隐藏（时长可设），没有单独的手动收起入口
                           └─ 顶栏（唯一的浮层，只有 5 个键）：
                                ‹ 返回 ｜ ⏸ 暂停 ｜ ▶ 播放 ｜ 2×2(or 1×4) ｜ 音频·全部(or 单路/静音)
```

> **为什么点格子不再切播放/暂停**：原来「控制条收起时唤出控制条、控制条已显示时切播放/暂停」
> 是同一个手势承担两件事 —— 想选中一格（比如切到「单路音频」后指定哪格出声）时手一抖就把它暂停了，
> 而且无法判断到底选中没有。现在**点击只做"选中"**，播放/暂停交给格内那排的 ▶/‖ 键，
> 语义唯一、可预期。切到「单路」时还会 toast 一次「点哪一格，哪一格出声」。
>
> **「点不灵敏」的坑（v2.0 修）**：点选监听器必须挂在**铺满整格的 `VideoView`** 上。
> 曾写成 `vv.setOnClickListener(null)` + 监听器挂到 cell 上，想让 VideoView 透传触摸 ——
> 但 AOSP 的 `View.setOnClickListener` 是 `if (!isClickable()) setClickable(true);`，
> **传 null 只清监听器、clickable 反被置成 true**，于是 VideoView 的 `onTouchEvent` 返回 true
> 把整格触摸全吞了，cell 永远收不到。详见技能 `android-platform-pitfalls` 第七节。

> **为什么没有底部栏**：全屏铺满的网格里，任何贴边的**整宽浮层**都会吃掉某一排格子的控件
> —— 原来的底部栏正好压住下排两格的进度条与控制条；同类问题在顶部也一样（格子的编号/文件名
> 原先贴格子顶部，会被顶栏盖住）。所以现在：
> **全局操作只放在顶栏**，**每格的控件全部集中在格内底部**，格子顶部保持干净。
> 顶栏只会盖到上排的画面，不会盖到任何控件。
>
> 顺带删掉了这几个冗余/低价值入口：「重选」和「返回」是同一个动作；
> 「全部重播」有了逐格 ↻ 之后只在极少数场合用得上；「信息」显隐与「设置」在设置页里都有，
> 不必占用播放页的位置。顶栏只留 5 个真正高频的键 —— 这是"放太多其它的无用"的直接反馈。
>
> 暂停与播放**拆成两个键**而不是一个切换键：切换键的当前状态要靠标签去猜，
> 拆开后点哪个就是哪个。

## 3. 设置项

设置页的条目词汇参考 NextPlayer 的 settings 模块：只有「开关项 / 可选项（弹窗单选）/ 跳转项」三类，
用分组标题切块。全部落在 `SharedPreferences`，改完返回播放页立即生效（`onResume` 重放）。

| 分组 | 条目 | 默认 |
|---|---|---|
| 排布与显示 | 排布模式：2×2 网格 / 一字排开（1×4） | 2×2 |
| | 不足 4 个时自动铺满 | 开 |
| | 起播前检查分辨率 | 开 |
| | 播放时一直显示进度条 | **关** |
| | 播放时一直显示文件名 | **关** |
| | 播放时一直显示格子编号 | **关** |
| | 控制条自动隐藏：从不 / 2s / 4s / 8s | 4s |
| 音频 | 音频模式：四路同时出声 / 只让选中格出声 / 全部静音 | 四路同时 |
| 播放行为 | 开始播放时自动起播 | 开 |
| | 单个视频循环播放 | 关 |
| | 播放中保持屏幕常亮 | 开 |
| | 记住上次选的视频 | 开 |
| 媒体库 | 列表显示缩略图 | 开 |
| | **不显示的文件夹** | 无 |
| | 文件夹排序：按视频数量 / 按名称 | 数量 |
| | 视频排序：按修改时间 / 按名称 / 按时长 | 时间 |
| 关于 | 关于本应用（版本 / 包名 / 说明） | — |
| | 版本 | — |
| | **开源许可**（第三方依赖与致谢） | — |
| | 设备并发解码能力（弹窗） | — |

> 三个「播放时一直显示…」默认都是关的（即播放时隐藏，只在唤出控制条时出现）。
> 播放页顶栏的「信息·隐藏 / 信息·常显」和它们是**同一份设置**，一键切换，不会出现两套状态。
> 若发现播放时标题/进度条不消失，先看这个按钮显示的是不是「信息·常显」。

## 4. 关于 4K 与并发解码（重要）

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
手机 SoC 的真实瓶颈往往是**内存带宽**，不是实例计数。

所以本应用的做法是「让失败可读」，而不是假装能行：

1. **起播前预检**：读每个视频的显示分辨率（含旋转修正）。有 4K（短边 ≥ 2000）就弹窗列出、
   说明原因，并给「照常播放 / 只播前 2 路 / 取消」。
2. **每格错误可见**：起不来的一格显示 `分辨率 + 原因 + what/extra 错误码`。
3. **一次性降级建议**：有格子失败且还有 ≥2 路在播时，问一次「保留 2 路」——
   把后两格的解码器让出去（解码器耗尽通常先打到后起播的格）。
4. 不想每次弹窗就把「起播前检查分辨率」关掉。

**没做的**：把 4K 降到 1080p 播放。本地文件只有一个码流，`VideoView` 也没有选轨/降采样能力 ——
真要做得换 Media3 路线（见 §9）。

## 5. 排布是怎么做的（三个坑）

### 5.1 排布不跟横竖屏走，但视频跟随

- manifest 给 Activity 声明
  `configChanges="orientation|screenSize|smallestScreenSize|screenLayout|keyboardHidden|uiMode|density"`
  → 旋转时 **Activity 不重建**，画面随屏幕转，正在播的几路不会被打断重来。
- `onConfigurationChanged` 把四边 inset 缓存清零重新量一次（横竖屏安全区不同）。
- 排布只在设置或顶栏按钮里改，存 `SharedPreferences`。

### 5.2 切换排布不能摘挂子 View

「新建容器把 4 个子 View 搬过去」会让 `SurfaceView` detach/attach → Surface 销毁重建 →
正在播的视频必然闪一下。正解是**复用同一个 `GridLayout`**，只改 spec 与行列计数。

### 5.3 GridLayout 动态改排布：两个入口的校验方向相反

本项目踩得最狠的坑。`GridLayout` 在两个入口上都做**同步**校验，且方向相反：

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

### 5.4 多路多实例：`MediaPlayer.isPlaying()` 会抛异常

任意一路解码失败进入 Error 态后，`isPlaying()` / `getCurrentPosition()` 会抛
`IllegalStateException`。而 ticker、`onStop`、「全部暂停」、`onPrepared` 回调这些地方都会
**遍历全部 4 格** —— 一路坏掉就会把另外几路一起炸掉。

因此所有触及 `MediaPlayer` 的地方都走带兜底的包装（`mpIsPlaying` / `mpPosition` / `mpPause` …），
并在 `onError` 里**立刻把 `c.mp` 置空**，别留这个"毒指针"。

### 5.5 退出播放后「视频在后台自己播起来」

`VideoView.stopPlayback()` 的内部是 `mMediaPlayer.stop()` + `release()`，
后面还有两句 `mCurrentState/mTargetState = STATE_ERROR`。
若此刻 `MediaPlayer` 还在 **Preparing**（大文件 / 4K 准备慢时很常见），`stop()` 会抛
`IllegalStateException` —— **`release()` 与那两句状态复位全都执行不到**。

于是：① 播放器泄漏；② `mTargetState` 还停在 `STATE_PLAYING`，而 `VideoView.onPrepared()`
里有一句 `if (mTargetState == STATE_PLAYING) start();` —— prepare 完成时它**自己把自己播起来**。
画面不可见、业务侧的引用又已置空，谁都没法停它。

**修法**：`Cell.reset()` 里**先 `vv.pause()` 再 `vv.stopPlayback()`**。
`pause()` 的最后一句是无条件执行的 `mTargetState = STATE_PAUSED;`，先把 target 钉死，
自启路径就没了。另外加了 `Cell.stale` 标志挡住"迟到的 `onPrepared`"，
`onStop()` 也去掉了 `if (playing)` 门控（只要还有活着的播放器就压住）。

## 6. 应用图标

- 设计：四块面板 = 四路视频；右下一块填强调色并带播放三角 = 「其中一路正在播」。
- 自适应图标（API 26+）：`res/mipmap-anydpi-v26/ic_launcher.xml`
  = 背景色 `@color/ic_launcher_background` + 矢量前景 `res/drawable/ic_launcher_foreground.xml`。
  内容全部落在 108 网格的 26..82 之间，即自适应图标的安全区，**不会被任何系统遮罩裁掉**。
- 低版本（API 24/25）：`tools/make_icon.py` 用同一套几何关系渲染各密度 PNG
  （mdpi 48 / hdpi 72 / xhdpi 96 / xxhdpi 144 / xxxhdpi 192），含圆形版 `ic_launcher_round`。

改图标后重新生成 PNG：

```bash
python tools/make_icon.py      # 需要 Pillow；会同时输出 tools/icon-preview.png
```

> PIL 自带字体没有中文字形，预览图上的中文会变方块 —— 脚本里已显式加载 `C:/Windows/Fonts/msyh.ttc`。

## 7. 编译

需要 JDK 17 + Android SDK（`compileSdk 36`，`build-tools 36.0.0`）。

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` 里的 `sdk.dir` 按自己的 SDK 路径改。
没有 Android Studio 也能构建：直接用缓存里的 Gradle（见技能 `android-gradle-build-windows`）。

## 8. 代码结构

| 文件 | 职责 |
|---|---|
| `MainActivity.java` | 两段式界面（选择页 / 播放页）、排布、逐格控制、4K 预检、诊断 |
| `SettingsActivity.java` | 设置页（沿用 NextPlayer 的「开关 / 可选项 / 跳转项」三件套） |
| `Prefs.java` | 全部设置项的唯一入口 + 文件夹排除的序列化格式 |
| `tools/make_icon.py` | 从同一套几何生成各密度 PNG 图标与预览图 |

## 9. 已知边界

- **渲染层是 `VideoView`/`MediaPlayer`**，不是 Media3/ExoPlayer。没有字幕、音轨选择、
  外挂解码器、网络源（SMB/SFTP/WebDAV）、播放进度记忆、播放列表。
- 没有「1 主 3 副」布局，也没有单格全屏放大。
- 每格可播放/暂停/seek/±10s，但**没有逐格音量**（要单独出声就切「单路」再点那一格）。
- **四路同时出声时未处理 `AudioManager` 音频焦点**：交给系统混音，能同时响；
  但接电话或其他 App 抢焦点时行为未定义。
- `onStop` 后不自动恢复播放，回到前台需手动点「全部播放」。
- 未做视频缩放模式（适应/裁剪/拉伸）。**不做是有原因的**：裁剪要靠把 `VideoView` 撑得比格子大
  再让父容器裁掉，但 `SurfaceView` 的 Surface 由系统合成器独立放置，**不受父 View 的 `clipChildren` 约束**，
  会溢到邻格里。
- `VideoView` 每个实例都会建完整 `MediaPlayer` 栈，比 ExoPlayer 更吃资源；
  4 路能跑起来不代表能长时间稳定跑。

## 10. 两条路线的定位

| | 本工程（VideoView 路线） | fork NextPlayer（Media3 路线） |
|---|---|---|
| 起量 | 小，4 个 Java 文件 | 大，新增 8～10 个文件 + 改导航/设置/manifest |
| 能拿到的功能 | 纯粹的多路播放 + 排布/设置 | 字幕、音轨、FFmpeg 软解、网络源、进度记忆、完整手势体系 |
| 适合 | 自用、原型、验证设备能力 | 做成可分发的产品 |

本工程在真机（OnePlus PJZ110）上已实测 **4 路可同时播放**，可行性报告里的 P1（Go / No-Go）关卡已通过。
要继续往「产品」走，就得换到 Media3 路线。
