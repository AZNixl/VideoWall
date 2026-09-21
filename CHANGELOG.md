# 更新记录

按时间顺序，从最初一版写起。

每条不只是"改了什么"，还写了**为什么**和**当时踩到的坑** ——
这个项目一大半时间花在定位平台行为上，那些结论比最终代码更值得留下来。
凡是写着「根因」的地方，都是真机 logcat 或 dumpsys 里抓到的确证，不是推测。

---

## v1.1 — 起点：Tasker 脚本重写为正规 Activity

最初的东西是一份 Tasker「Java 代码」动作里的 650 行内嵌脚本
（悬浮球 + 目录列表 + 信息流 + 内联 `VideoView`）。把它重写成一个正规 App：

- 载体从 `WindowManager.addView(TYPE_APPLICATION_OVERLAY)` 悬浮窗换成 `Activity`
- 媒体来源从"手填目录 + BFS 深搜"换成 `MediaStore` 查询（免配置、权限更干净）
- 播放形态从垂直信息流（上限硬编码 3）换成固定 2×2 网格（上限 4）
- 每格独立 `setOnErrorListener`，把 `what` / `extra` 错误码直接画在格子上
- 加了「诊断」页：读 `MEDIA_PERFORMANCE_CLASS` 与各 codec 的 `getMaxSupportedInstances()`

**实测结果**：目标机（OnePlus PJZ110）上 4 路 1080p 可同时播放 ——
可行性报告里的 P1（Go / No-Go）关卡通过。

---

## v1.2 — 两种排布、设置页、逐格控制、隐藏信息、应用图标

一次提了五条需求，一起做的：

| 需求 | 做法 |
|---|---|
| 两种排布 | **2×2 网格** / **一字排开（1×4）**，手动切换 |
| 增加设置项 | 新建 `SettingsActivity`，条目词汇参考 NextPlayer 的设置模块（开关项 / 可选项 / 跳转项 + 分组标题） |
| 播放时隐藏信息 | 进度条/文件名/编号默认只在唤出控制条时出现 |
| 每格独立控制 | 每格自己的 ▶/‖、−10s、+10s、独立进度条 |
| 应用图标 | 四块面板 = 四路视频，右下一块填强调色带播放三角 |

**排布切换的实现取舍**：一开始想"新建一个容器把 4 个子 View 搬过去"，
但那样 `SurfaceView` 会 detach/attach → Surface 销毁重建 → 正在播的视频必然闪一下。
改成**复用同一个 `GridLayout`**，只改 spec 与行列计数，不摘挂子 View。

图标用矢量前景（自适应图标，内容全落在 108 网格的 26..82 安全区）+ 脚本生成各密度 PNG。
生成 PNG 的脚本里 PIL 必须显式加载 `C:/Windows/Fonts/msyh.ttc`，否则预览图上的中文是方块。

---

## v1.3 / v1.4 — 播放中切换排布闪退

**根因：`GridLayout` 在两个入口上都做同步校验，而且方向相反。**

| 入口 | 校验规则 | 异常原文 |
|---|---|---|
| `setColumnCount` / `setRowCount` | `count >= 子项 spec 的 max(start+span)` | `columnCount must be greater than or equal to the maximum of all grid indices (and spans) defined in the LayoutParams of each child.` |
| `View.setLayoutParams`（子项） | `spec 的 start+span <= 当前 count` | `row indices (start + span) mustn't exceed the row count.` |

**先改计数再改 spec** 会被旧 spec 拦下；**先改 spec 再改计数** 会被旧计数拦下 ——
v1.3 里我只把顺序反过来，于是换了个异常继续崩。v1.4 才找到唯一走得通的三步：

```java
grid.setColumnCount(MAX_CELLS);   // ① 先放宽到超集（列 4 / 行 2）
grid.setRowCount(MAX_ROWS);
for (int i = 0; i < 4; i++) {     // ② 再改每个子项的 spec（含隐藏的格子，也必须给 spec）
    child.setLayoutParams(lp);
}
grid.setColumnCount(cols);        // ③ 最后收紧到目标值（须等于新 spec 的最大 start+span）
grid.setRowCount(rows);
```

另外：`cols`/`rows` 必须与 spec 循环**同源** —— 多了会让权重列分走空间
（2 路上下排却声明 2 列，右列会变 0 宽再把位置留空），少了直接抛异常。

真机验证通过。

---

## v1.5 — 4K 起播前预检

有 4K 时先弹窗说明，并给「只播前 2 路 / 取消」；起不来的一格显示分辨率 + 原因 + 错误码；
有格子失败且还有 ≥2 路在播时问一次「保留 2 路」。
设置里可关掉预检。

**为什么不是"软件解决"**：4 路 4K 在手机上是硬件做不到的。CDD 对声明了
`MEDIA_PERFORMANCE_CLASS` 的设备给出的并发硬解会话下限，最高的 34/35 档也只保证
**3 路 1080p + 3 路 4K**，而 4K 解码器通常只能同时开 1～2 个；真实瓶颈往往是内存带宽。
所以应用的做法是「让失败可读」，而不是假装能行。

---

## v1.6 — 「播放时标题/进度条不隐藏」

用户报的现象，查下来**不是代码 bug**：从应用私有目录 `run-as cat shared_prefs/videowall.xml`
读到 `showName=true`、`showSeekBar=true` —— 是在设置页把那两个开关打开了。

有个反证很有用：用户只提到"标题、进度条"，**没提格子编号**，而 `showBadge` 确实没被写入
（保持默认隐藏）。如果是代码问题，三个都会留着。

不过这暴露了真问题：想隐藏/显示这些信息不该跑到设置页翻两个开关。
于是播放页顶栏加了「信息·隐藏 / 常显」一键切换，和设置页**共用同一份偏好**。

---

## v1.7 — 单个视频的重播

用户指出"重播逻辑要变一下，可以单个重播，因为同时播放，视频有长有短"——
原来那个「⏮ 重播」是把四格一起拉回开头，对长短不一的四路基本没用。
加了逐格 ↻，全局那个改名「全部重播」。

---

## v1.8 — 去掉底部栏

**根因是一类问题而不是一个**：全屏铺满的网格里，**任何贴边的整宽浮层都会吃掉某一排格子的控件**。
当时有两处：底部栏压住下排两格的逐格控制条与进度条；格子的编号/文件名贴在格子顶部，会被顶栏盖住。

一次根治：**全局操作只留顶栏，每格控件全部收进格内底部**，格子顶部保持干净。
顶栏于是只会盖到上排的**画面**，不盖任何控件。

顺带清掉两个冗余：「重选」和「返回」是同一个动作；「全部重播」有了逐格 ↻ 之后几乎没用。

---

## v1.9 — 顶栏瘦身 + 点选语义唯一

用户："顶部控件只需保留：返回，暂停，播放，布局，音频，放太多其它的无用"、
"现在播放时不能很好的点选视频，切换音频时点选不到我要的"。

**后一条的根因是我自己的手势设计**：原来一个点击承担两件事
（控制条收起时唤出控制条、控制条已显示时切播放/暂停）——
想选中一格（切到「单路音频」后指定哪格出声）时手一抖就把它暂停了，也没法判断选中没有。

改成**点击只做「选中」，绝不改播放状态**；播放/暂停交给格内那排的 ▶/‖ 键。
选中格的描边 + 编号底色变强调色，切到「单路」时 toast 一次「点哪一格，哪一格出声」。

---

## v2.0 — 「点选不灵敏」的真根因：`setOnClickListener(null)` 陷阱

**根因**（v1.2 我自己引入的）：把点选监听器从 `VideoView` 挪到 cell 上时，
顺手写了 `c.vv.setOnClickListener(null)` 想让它「透传触摸」。但 AOSP 的实现是**只加不减**的：

```java
public void setOnClickListener(OnClickListener l) {
    if (!isClickable()) setClickable(true);   // ← 传 null 也会走到这里
    getListenerInfo().mOnClickListener = l;
}
```

传 `null` 只清掉监听器，**`clickable` 反而被置成 `true`**。而 `View.onTouchEvent`
对 clickable 的 View 在 ACTION_DOWN 就 `return true` —— 铺满整格的 VideoView
把所有触摸都吞了，cell 永远收不到点击。

证据链很干净：v1.1 时监听器挂在 VideoView 上，点是好的；v1.2 挪到 cell 上之后就不灵了。

**修法**：把监听器直接挂回 `VideoView`，cell 也挂一份覆盖边角，不依赖触摸透传。

---

## v2.1 — 「退出播放后视频在后台自己播起来」+ 关于/开源许可

**根因：`VideoView` 的 target state 陷阱。**
`stopPlayback()` 的内部是 `mMediaPlayer.stop()` + `release()`，
之后还有两句 `mCurrentState/mTargetState = STATE_ERROR`。
若此刻 `MediaPlayer` 还在 **Preparing**（大文件 / 4K 准备慢时很常见），`stop()` 会抛
`IllegalStateException` —— **`release()` 与那两句状态复位全都执行不到**。

于是：① 播放器泄漏；② `mTargetState` 还停在 `STATE_PLAYING`，
而 `VideoView.onPrepared()` 里有一句 `if (mTargetState == STATE_PLAYING) start();` ——
prepare 完成时它**自己把自己播起来**，画面不可见、引用又已置空，谁都停不掉。

**修法**：`Cell.reset()` 里**先 `vv.pause()` 再 `vv.stopPlayback()`**。
`pause()` 最后一句是无条件执行的 `mTargetState = STATE_PAUSED;`，先把 target 钉死。
另加 `Cell.stale` 挡住迟到的 `onPrepared`；`onStop()` 也去掉了 `if (playing)` 门控。

同版还补了设置页的「关于本应用 / 版本 / 开源许可 / 项目主页」。

---

## v2.2 — 上 GitHub、性能提醒加「不再提醒」、首次启动介绍页

- 仓库建在 <https://github.com/AZNixl/VideoWall>，配了 Actions 出 debug + release
- 性能提醒弹窗加「不再提醒」勾选（连到设置里的「起播前检查分辨率」）
- 逐格控件按系统手势区往上抬
- **首次启动介绍页**：讲清用法 4 条 + 权限用途 + 拒绝授权后的补救路径

---

## v2.3 — Material 3、亮/暗主题、续播、解码器信息

**Material 化**：全量转 Material 3（`com.google.android.material:material`，
这是本项目唯一的第三方依赖），两个 Activity 换 `AppCompatActivity`，
对话框用 `MaterialAlertDialogBuilder`。顺带修正了"零第三方依赖"这个已经不成立的声明。

**主题的做法**（界面全是代码手搭、没有布局 XML，颜色没法自动跟主题）：
`attrs.xml` 定义 11 个主题属性 → `values/themes.xml`（亮）与 `values-night/themes.xml`（暗）
**同名 style** 分别赋值 → `Palette.of(context)` 在 `onCreate` 里解析成字段。
**关键取巧**：字段沿用旧的常量名，所有使用点一行都不用改。

显式例外：**播放页压在视频画面上的元素固定用深色半透明，不跟主题走** ——
它们底下是不确定亮度的视频，跟主题走反而在亮色主题下糊成一片。

**续播**：按视频 id 记住每路的位置；位置 < 2s 或 > 时长−3s 的记录会清掉，下次从头。

**解码器的结论（重要）**：`MediaPlayer`/`VideoView` **不提供**选择解码器的 API ——
既不能强制软解，也不能降分辨率。能用 `MediaCodecList.findDecoderForFormat()`
**预测**平台会选哪个（本项目已做，显示在格子信息标签里）。
真要可选得上 Media3/ExoPlayer 的 `MediaCodecSelector`。

---

## v2.4 — 「打开即闪退」

**根因**（低级错误）：`buildIntroView()` 建了 `ScrollView sv` 和内容列 `col`，
`sv.addView(col)` 之后**却 `return col`** —— col 已经有父容器了。
`buildRoot()` 再 `root.addView(introView)` 必然抛：

```
IllegalStateException: The specified child already has a parent.
```

而且发生在 Activity 显示之前，所以连第一帧都看不到。

**为什么漏掉**：介绍页是上一轮加的，加完手机就被拔了，那一版只做了编译 + 资源核对。
**教训：没有真机就不要声称"改好了"。**

修法：`return sv`，方法返回类型与字段类型一并由 `LinearLayout` 改成 `View`
（返回类型是具体类时会"被迫"返回那个子节点，改成 `View` 能挡住这类错误）。

---

## v2.5 — 放大为全屏 + 手势区的真相

**放大为全屏**：点格内控制条的图标，该格独占整屏，其余几路**暂停而不是释放**
（释放再还原要重新 prepare，那是几秒黑屏）。还原时按放大前的状态续起。

**手势区不一定在底部。** 一开始按常识只把最下排的控件"往上抬"，
后来从真机 `dumpsys activity top` 的 insets 读到实际值：

```
systemGestures left   [0,0][120,3168]      左边缘 120px（30dp）整条
systemGestures right  [1320,0][1440,3168]  右边缘 120px 整条
systemGestures bottom —— 0
navigationBars        [0,3168][1440,3168]  高度 0（沉浸式下已隐藏）
```

**这台设备的手势区在左右两侧，底部没有。** 而进度条横跨整格宽度，
最外两列的轨道末端正好压在这两条返回手势带上。
**教训：别按常识猜手势区在哪，读一次 insets 就有确切答案。**

---

## v2.6 / v2.7 — 逐格控制条定形、主页三键、网格贯穿两级

- 逐格控制条固定四项：后退 / 播放暂停 / 前进 / 全屏
- 主页顶栏三键：主题、文件夹排布、设置；去掉重复的「已选 n/4」
- **网格排布贯穿两级**：首页选了两列网格，点进文件夹后的视频列表也是两列卡片

**逐格控件曾被说"不对劲"，三个真因**：
1. 编号和文件名也画成了深色胶囊，和真按钮长得一样 → 一排像有 6 个按钮、2 个点了没反应。
   改成纯文字 + 阴影。
2. 左右内缩加在**整个控件区**上 → 比视频窄、看着整体错位。改成**只缩进度条**
   （按钮是点击不是拖动，不需要躲手势）。
3. 控制条按键尺寸太大，2×2 在 360dp 竖屏下四键排不下。

---

## v2.8 / v2.9 — 全图标化

- 新建 13 个矢量图标：返回 / 播放 / 暂停 / 重播 / 后退 / 前进 / 音量 / 耳机 /
  设置 / 亮色 / 暗色 / 跟随系统(A) / 全屏 / 退出全屏
- **全部手写矢量，不用字体字形** —— ⛶ 这类字符不在 Android 默认字体的保证范围内，
  缺字形就是豆腐块
- 图标语义统一为「表示当前是什么」：在播显暂停、排布显当前排布、音频显当前档、主题显当前档
- 音频去掉「静音」档，只留「全部 / 单路」（老数据里存的 2 在 getter 里夹回 0）
- 主题三档三个图标：太阳 / 月亮 / **A**（Auto，跟随系统）

**v2.9 修「点选又不灵敏了」**：根因是每次勾选都走 `refreshPickUi()` →
`removeAllViews()` 把整个列表重建、每个缩略图重新 `setImageBitmap` ——
视频一多就卡，连点还会丢事件。改成**只刷新被点那一项的外观 + 底部槽位**，不整表重建。

---

## 附：工程侧踩过的坑

| 坑 | 现象 | 结论 |
|---|---|---|
| `android-actions/setup-android@v3` | CI 在装 SDK 那步就死 | 它内部执行 `sdkmanager tools`，而 `tools` 包在现代仓库里已不存在。改用 runner 预装的 `ANDROID_HOME` |
| API 通道推 CRLF | CI 里 `./gradlew` **exit 127**，本地怎么都复现不出来 | `.gitattributes` 的 `eol=lf` 只保证**仓库里**是 LF，不保证**工作区**。`push_via_api.py` 早期版本 `open()` 读工作区文件，把 CRLF 传了上去 → shebang 成了 `#!/bin/sh\r`。改成 `git cat-file blob <sha>` 从对象库取内容 |
| Kotlin DSL 里的 `java.util.Properties` | `Unresolved reference 'util'` | `java` 被解析成 Gradle 的 java 扩展，要显式 `import java.util.Properties` |
| Windows 提交把 gradlew 变 CRLF | CI 上 `./gradlew` 报 bad interpreter | 加 `.gitattributes` 统一 LF |
| 代理间歇性放行 | `git push` 时而成功时而 `CONNECT tunnel failed, response 502` | 先重试 3～4 次（实测常第 2 次就过）；持续不通才走 API 通道 —— API 通道会让本地/远程 SHA 分叉 |

## 附：几处「有意为之」的例外

- **播放页压在视频上的浮层固定深色**，不跟主题走 —— 底下是不确定亮度的视频
- **`uiMode` 留在 `configChanges` 里** —— 系统到点切深色时不重建 Activity，
  免得把正在播的视频打断。代价是手动改主题后要 App 自己 `recreate()`
- **不做视频缩放模式（适应/裁剪/拉伸）** —— 裁剪要把 `VideoView` 撑得比格子大再让父容器裁掉，
  但 `SurfaceView` 的 Surface 由系统合成器独立放置，**不受父 View 的 `clipChildren` 约束**，
  会溢到邻格里。宁可不做，也不做一个看起来能用实则串格的功能
- **不开 R8** —— 诊断页用反射读 `Build.SOC_MODEL` / `Build.VERSION.MEDIA_PERFORMANCE_CLASS`，
  混淆有可能把这两个字段改名或裁掉，省下来的那点体积不值得冒这个风险
