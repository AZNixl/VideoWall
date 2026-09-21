package com.aznixl.videowall;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 视频墙 —— 4 路视频同时铺满屏幕播放。
 *
 * 流程：进入 → 按文件夹挑视频（最多 4 个）→ 开始播放 → 按选定排布铺满全屏。
 *
 * 血统：UI 骨架与交互思路来自一份 Tasker「Java 代码」动作里的 650 行内嵌脚本，
 * 本文件把它重写为正规 Activity，并按实际使用反馈逐步重做交互与设置。
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "VideoWall";
    private static final int MAX_CELLS = 4;
    /** 排布最多用到的行数（2×2、以及「铺满」时 1 大 2 小 / 上下两分）。 */
    private static final int MAX_ROWS = 2;
    private static final int REQ_PERM = 1001;
    private static final int THUMB_CACHE_LIMIT = 120;
    private static final long SEEK_STEP_MS = 10_000L;

    // 界面用色。
    //
    // 以前这些是 static final 常量（写死的暗色）。现在改成实例字段、在 onCreate 里
    // 从当前主题解析 —— 名字保持不变，所以下面所有使用点都不用改。
    // 想加新色值就加一个字段 + 在 applyPalette() 里赋一次。
    private int BG;
    private int CARD;
    private int STROKE;
    private int ACCENT;
    private int WARN;
    private int MUTED;
    private int SECTION_ACCENT;
    /** 正文主色（列表项标题等）。 */
    private int TEXT_PRIMARY;
    /** 中性胶囊底色（设置键、禁用态主按钮）。 */
    private int CHIP;
    /** 选中态胶囊底色（已选槽位、文件夹图标、选中行）。 */
    private int CHIP_ON;
    /** 占位/禁用文字。 */
    private int TEXT3;

    /**
     * 播放页里那些压在**视频画面**上的元素（顶栏底衬、编号、文件名、逐格按钮）
     * 一律保持深色半透明 —— 它们底下是不确定亮度的视频，
     * 跟主题走反而会在亮色主题下糊成一片。这是有意为之，不是漏改。
     */
    private static final int OVER_VIDEO_SCRIM = 0xB3000000;
    private static final int OVER_VIDEO_CHIP = 0x33FFFFFF;
    private static final int OVER_VIDEO_LABEL = 0x99000000;
    private static final int OVER_VIDEO_BTN = 0x8C000000;

    private float dp;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final Map<Long, Bitmap> thumbCache = new HashMap<>();

    private Prefs prefs;

    /**
     * 窗口内边距（状态栏 / 导航栏 / 挖孔）。四个方向都只记录「见过的最大值」：
     * 进沉浸式后系统栏隐藏、insets 归零，此时必须继续用缓存值，
     * 否则控制条会顶进状态栏或刘海。
     */
    private int insetTop;
    private int insetBottom;
    private int insetLeft;
    private int insetRight;
    /** 系统手势区（底部"上滑回桌面"那条）的高度。用来把最下排的控件抬出去。 */
    private int insetGestureBottom;
    /**
     * 系统手势区在左右两侧的宽度。
     *
     * 实测（OnePlus PJZ110 + 手势导航）：竖屏下 systemGestures 是
     *   left  [0,0][120,3168]      ← 左边缘 120px（30dp）整条
     *   right [1320,0][1440,3168]  ← 右边缘 120px 整条
     *   bottom 高度 0（导航栏在沉浸式下已隐藏）
     * 也就是说**冲突在左右两侧，不在底部** —— 进度条横跨整格宽度，
     * 最外两列的进度条末端正落在手势带里，拖到边上就触发返回手势。
     */
    private int insetGestureLeft;
    private int insetGestureRight;

    // ---- 数据
    private final List<Item> allItems = new ArrayList<>();
    private final List<Folder> folders = new ArrayList<>();
    private final List<Item> picked = new ArrayList<>();
    private Folder openedFolder;
    private boolean loading = true;
    /** 媒体库只会加载一次；介绍页和 onResume 都可能触发，得防重入。 */
    private boolean libraryLoadStarted;
    /** 本次启动是否已经弹过一次系统权限框 —— 用来区分"还没问过"和"问过被拒了"。 */
    private boolean permAskedOnce;
    /** onCreate 时用的主题档位；onResume 发现设置变了就重建自己。 */
    private int themeModeAtCreate;
    /** onCreate 时系统是不是深色。用于「跟随系统」时把系统变化也接住。 */
    private boolean nightAtCreate;

    // ---- 选择页
    private FrameLayout root;
    private View introView;
    private TextView introPrimary;
    private TextView introStatus;
    private boolean introShowing;
    private LinearLayout pickView;
    private LinearLayout pickBottomBar;
    private LinearLayout listContainer;
    private TextView breadcrumb;
    private LinearLayout slotsBar;
    private TextView startButton;
    /** 顶栏的主题切换键（图标表示当前是亮色还是暗色）。 */
    private ImageView themeButton;
    /** 顶栏的文件夹排布切换键（图标）。 */
    private ImageView layoutToggleButton;

    // ---- 播放页
    private FrameLayout playView;
    private GridLayout grid;
    private LinearLayout topBar;
    private final Cell[] cells = new Cell[MAX_CELLS];
    private ImageView audioButton;
    private ImageView layoutButton;
    /** 播放页顶栏的「播放/暂停」全局键（图标随在播状态变）。 */
    private ImageView playPauseAllButton;
    /** 上一次的"有没有在播"，用来避免每 400ms 无脑换图标。 */
    private boolean lastAnyPlaying = true;
    private boolean playing;
    private int focused;
    private boolean controlsVisible = true;
    /** 本次播放实际用到的格子数（1～4），「弹性铺满」按它决定排布。 */
    private int assignedCount;
    /** 上次应用的排布指纹（模式/数量/屏幕宽高/是否铺满），避免无谓重排。 */
    private String appliedLayoutSig = "";
    /** 当前列数。1×4 时每格太窄放不下逐格控制条，只给选中格显示。 */
    private int appliedCols = 2;
    /** 当前行数。用来判断哪些格子在最下排（最下排的控件要抬离系统手势区）。 */
    private int appliedRows = 2;
    /**
     * 正在"放大为全屏"的那一格；-1 表示没有。
     * 放大时该格独占整个容器，其余格子隐藏并**暂停**（不是释放）。
     */
    private int zoomed = -1;
    /** 放大前哪些格在播，用于还原时把它们接着放回去。 */
    private final boolean[] wasPlayingBeforeZoom = new boolean[MAX_CELLS];
    /** 「降到 2 路」的建议每次播放最多弹一次。 */
    private boolean degradeSuggested;
    /** 进播放页的操作提示每次启动只提示一次，别每次播放都烦人。 */
    private boolean hintShown;

    private Runnable ticker;
    private Runnable autoHide;

    // ------------------------------------------------------------------ 模型

    private static class Item {
        final long id;
        final String name;
        final String size;
        final long durationMs;
        final long dateAdded;
        final long bucketId;
        final String bucketName;

        /** 起播前探测出的显示分辨率（已按旋转角修正）；未知为 0。 */
        int videoW;
        int videoH;
        /** 起播前探测出的编码与平台会选用的解码器。 */
        String videoMime = "";
        String videoDecoder = "";
        int videoDecoderMax;

        Item(long id, String name, String size, long durationMs, long dateAdded,
             long bucketId, String bucketName) {
            this.id = id;
            this.name = name;
            this.size = size;
            this.durationMs = durationMs;
            this.dateAdded = dateAdded;
            this.bucketId = bucketId;
            this.bucketName = bucketName;
        }

        Uri uri() {
            return ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id);
        }
    }

    private static class Folder {
        final long id;
        final String name;
        final List<Item> items = new ArrayList<>();

        Folder(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private class Cell {
        FrameLayout root;
        VideoView vv;
        MediaPlayer mp;
        SeekBar sb;
        TextView placeholder;
        TextView error;
        TextView stateIcon;
        TextView badge;
        TextView nameLabel;
        LinearLayout ctrlStrip;
        LinearLayout bottomOverlay;
        /** 逐格控制条的播放/暂停键（图标）。 */
        ImageView toggleButton;
        /** 上一帧这个键画的是不是"暂停"图标，避免每 400ms 无脑重设。 */
        boolean toggleShowsPause;
        /** 「全屏 / 还原」键（图标）。 */
        ImageView zoomButton;
        boolean userSeeking;
        boolean highlighted;
        /** 这一格已被作废（reset 过或已解码失败）。迟到的 onPrepared 一律不再当作有效事件。 */
        boolean stale;
        String name = "";

        void reset() {
            // 顺序很关键：先 pause()，再 stopPlayback()。
            //
            // VideoView.pause() 会**无条件**把内部的 mTargetState 置成 STATE_PAUSED；
            // 而 stopPlayback() 的内部实现是 mMediaPlayer.stop() + release()，
            // 一旦此刻 MediaPlayer 还在 Preparing（大文件 / 4K 准备慢时很常见），
            // stop() 会抛 IllegalStateException —— **release() 和
            // `mTargetState = STATE_ERROR` 这两句都不会执行**。
            //
            // 后果有两层：
            //  ① 播放器泄漏（mMediaPlayer 仍非空、仍在解码）；
            //  ② mTargetState 还停在 STATE_PLAYING，于是 prepare 完成时
            //     VideoView 的 onPrepared 会自己调 start() 把视频播起来 ——
            //     而此时画面不可见、c.mp 也已被置空，谁都停不掉它，
            //     表现就是"退出播放后视频在后台自动播放"。
            //
            // 先 pause() 把 target 钉死在 PAUSED，从根上堵掉这条自启路径。
            stale = true;
            try {
                vv.pause();
            } catch (Throwable ignored) {
            }
            try {
                vv.stopPlayback();
            } catch (Throwable ignored) {
            }
            mp = null;
            name = "";
            sb.setProgress(0);
            sb.setMax(0);
            error.setVisibility(View.GONE);
            stateIcon.setVisibility(View.GONE);
            placeholder.setVisibility(View.VISIBLE);
            nameLabel.setText("");
            highlighted = false;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.BLACK);
            bg.setCornerRadius(6 * dp);
            root.setBackground(bg);
        }
    }

    // ------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        prefs = new Prefs(this);
        applyPalette();
        buildRoot();

        if (!prefs.onboarded()) {
            // 首次启动：先讲清楚这个应用是干什么的、要什么权限，再让用户自己点授权
            showIntro();
        } else if (!hasMediaPermission()) {
            requestPermissions(new String[]{mediaPermission()}, REQ_PERM);
        } else {
            loadLibrary();
        }
    }

    /** 界面用色都在这里一次性从主题取出来。主题变了必须重建 Activity —— 已画好的 View 不会自己变色。 */
    private void applyPalette() {
        Palette p = Palette.of(this);
        BG = p.bg;
        CARD = p.card;
        STROKE = p.stroke;
        ACCENT = p.accent;
        WARN = p.warn;
        MUTED = p.textSecondary;
        SECTION_ACCENT = p.section;
        TEXT_PRIMARY = p.textPrimary;
        CHIP = p.chip;
        CHIP_ON = p.chipOn;
        TEXT3 = p.textTertiary;
        themeModeAtCreate = prefs.themeMode();
        nightAtCreate = isNightNow();
    }

    /** 系统当前是不是深色。 */
    private boolean isNightNow() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private boolean hasMediaPermission() {
        return checkSelfPermission(mediaPermission()) == PackageManager.PERMISSION_GRANTED;
    }
    private String mediaPermission() {
        return Build.VERSION.SDK_INT >= 33
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户可能刚在设置里改了明暗。manifest 里为了不打断播放而声明了 uiMode，
        // 系统不会自动重建 Activity —— 这里自己比对、自己重建一次。
        //
        // 顺带接住"系统自己切到深色"：那种情况同样不会重建，所以回到前台时补一次。
        // 但**正在播放时不动** —— 那条 uiMode 声明本来就是为了别把视频打断。
        boolean themePrefChanged = prefs.themeMode() != themeModeAtCreate;
        boolean systemThemeChanged = !playing && isNightNow() != nightAtCreate;
        if (themePrefChanged || systemThemeChanged) {
            recreate();
            return;
        }
        // 设置页里的「查看使用指引」把介绍页重新叫出来
        if (prefs.introRequested()) {
            prefs.setIntroRequested(false);
            showIntro();
            return;
        }
        // 用户可能刚从系统设置里把权限打开，回来时补上
        if (introShowing && hasMediaPermission()) {
            finishIntro();
            return;
        }
        // 设置页可能改过排布/音频/显隐，回来统一重放一遍
        if (playing) {
            applyLayoutMode(false);
            applyAudio();
            applyKeepScreenOn();
            updateCellChrome();
        }
        syncQuickButtons();
        syncTopButtons();
        if (loading) return;
        sortFolders();
        sortVideosInFolders();
        refreshPickUi();
    }

    /**
     * 横竖屏变化：Activity 不重建（manifest 里声明了 configChanges），
     * 排布模式也不跟着变 —— 但平台尺寸变了，必须让 insets 用新方向重新量一次。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        insetTop = 0;
        insetBottom = 0;
        insetLeft = 0;
        insetRight = 0;
        root.requestApplyInsets();
        if (playing) {
            applyImmersive(true);
            // 「弹性铺满」时 2 路是按屏幕宽高决定左右还是上下的，旋转后必须重排一次；
            // 手动模式不受影响（指纹里的 wide 变了但 fill=false，实际 spec 相同）。
            applyLayoutMode(true);
            showControls();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERM) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (introShowing) {
                finishIntro();
            } else {
                prefs.setOnboarded(true);
                loadLibrary();
            }
        } else if (introShowing) {
            // 别只弹个 toast 就完事 —— 介绍页要把"怎么补救"写清楚
            updateIntroPermissionState();
        } else {
            toast("没有媒体读取权限，无法列出视频");
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 不再用 if (playing) 兜着 —— 只要还有活着的播放器就压住，
        // 免得"已经退出播放页但某个播放器没被清干净"时继续在后台出声。
        for (Cell c : cells) {
            if (mpIsPlaying(c)) mpPause(c);
        }
        savePositions();                 // 切出去时把进度记下，回来能续播
    }

    /**
     * 记下每一路当前播放到哪，供「继续播放」下次接着放。
     *
     * 写入与读取（assign 的 onPrepared）共用同一套判据：
     *   · 位置 < 2 秒 —— 没什么可续的，删掉记录，下次从头
     *   · 位置 > 时长 − 3 秒 —— 视作已看完，删掉记录，下次从头
     * 显式 remove 而不是跳过，避免旧记录一直躺在偏好里让用户困惑。
     */
    private void savePositions() {
        if (prefs.startMode() != Prefs.START_RESUME) return;
        LinkedHashMap<Long, Long> map = Prefs.parseNumbers(prefs.resumePositions());
        for (int i = 0; i < MAX_CELLS && i < picked.size(); i++) {
            Cell c = cells[i];
            if (c == null || c.mp == null) continue;
            long id = picked.get(i).id;
            int pos = mpPosition(c);
            int dur = mpDuration(c);
            if (pos < 2000 || (dur > 0 && pos > dur - 3000)) {
                map.remove(id);
                continue;
            }
            map.put(id, (long) pos);
        }
        prefs.setResumePositions(Prefs.formatNumbers(map));
    }

    @Override
    protected void onDestroy() {
        if (ticker != null) ui.removeCallbacks(ticker);
        if (autoHide != null) ui.removeCallbacks(autoHide);
        ui.removeCallbacksAndMessages(null);
        for (Cell c : cells) {
            if (c != null) {
                try {
                    c.vv.stopPlayback();
                } catch (Throwable ignored) {
                }
            }
        }
        thumbCache.clear();
        pool.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (playing) {
            exitPlayMode();
            return;
        }
        if (openedFolder != null) {
            showFolders();
            return;
        }
        super.onBackPressed();
    }

    // -------------------------------------------------------------- 窗口骨架

    private void buildRoot() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // targetSdk 35+ 强制 edge-to-edge，必须自己消费 insets，
        // 否则顶栏会被状态栏压住。
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int t;
            int b;
            int l;
            int r;
            int g = 0;
            int gl = 0;
            int gr = 0;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                t = bars.top;
                b = bars.bottom;
                l = bars.left;
                r = bars.right;
                Insets ges = insets.getInsets(WindowInsets.Type.systemGestures());
                g = ges.bottom;
                gl = ges.left;
                gr = ges.right;
            } else {
                t = legacyTop(insets);
                b = legacyBottom(insets);
                l = legacyLeft(insets);
                r = legacyRight(insets);
                if (Build.VERSION.SDK_INT >= 29) {
                    g = legacyGestureBottom(insets);
                    gl = legacyGestureLeft(insets);
                    gr = legacyGestureRight(insets);
                }
            }
            if (t > insetTop || b > insetBottom || l > insetLeft || r > insetRight
                    || g > insetGestureBottom || gl > insetGestureLeft || gr > insetGestureRight) {
                insetTop = Math.max(insetTop, t);
                insetBottom = Math.max(insetBottom, b);
                insetLeft = Math.max(insetLeft, l);
                insetRight = Math.max(insetRight, r);
                insetGestureBottom = Math.max(insetGestureBottom, g);
                insetGestureLeft = Math.max(insetGestureLeft, gl);
                insetGestureRight = Math.max(insetGestureRight, gr);
                applyInsetPadding();
            }
            return insets;
        });

        pickView = buildPickView();
        root.addView(pickView, new FrameLayout.LayoutParams(-1, -1));

        introView = buildIntroView();
        introView.setVisibility(View.GONE);
        root.addView(introView, new FrameLayout.LayoutParams(-1, -1));

        playView = buildPlayView();
        playView.setVisibility(View.GONE);
        root.addView(playView, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        root.requestApplyInsets();
    }

    @SuppressWarnings("deprecation")
    private int legacyTop(WindowInsets i) {
        return i.getSystemWindowInsetTop();
    }

    @SuppressWarnings("deprecation")
    private int legacyBottom(WindowInsets i) {
        return i.getSystemWindowInsetBottom();
    }

    @SuppressWarnings("deprecation")
    private int legacyLeft(WindowInsets i) {
        return i.getSystemWindowInsetLeft();
    }

    @SuppressWarnings("deprecation")
    private int legacyRight(WindowInsets i) {
        return i.getSystemWindowInsetRight();
    }

    @SuppressWarnings("deprecation")
    private int legacyGestureBottom(WindowInsets i) {
        return i.getSystemGestureInsets().bottom;
    }

    @SuppressWarnings("deprecation")
    private int legacyGestureLeft(WindowInsets i) {
        return i.getSystemGestureInsets().left;
    }

    @SuppressWarnings("deprecation")
    private int legacyGestureRight(WindowInsets i) {
        return i.getSystemGestureInsets().right;
    }

    private void applyInsetPadding() {
        if (pickView != null) {
            pickView.setPadding(insetLeft, insetTop, insetRight, 0);
        }
        if (pickBottomBar != null) {
            pickBottomBar.setPadding(d(10), d(6), d(10), insetBottom + d(8));
        }
        if (topBar != null) {
            topBar.setPadding(insetLeft + d(12), insetTop + d(8), insetRight + d(12), d(8));
        }
        applyCellChromeInsets();
    }

    /**
     * 把格内控件从屏幕边缘让开。
     *
     * **控件区本身只做垂直让位，不做左右内缩** —— 一内缩它就比视频窄，
     * 看着像整体错位（这正是上一版被说"不对劲"的原因）。
     * 左右只收**进度条**：它横跨整格宽度，最外两列的轨道末端正压在左右两条
     * 返回手势带上（实测该机 systemGestures 是左右各 120px 的整条边），
     * 拖到边上会把系统手势触发出来。按钮是点击不是拖动，不需要躲。
     */
    private void applyCellChromeInsets() {
        if (cells[0] == null) return;
        int lift = insetGestureBottom + d(20);
        int cols = Math.max(1, appliedCols);
        int rows = Math.max(1, appliedRows);
        for (int i = 0; i < MAX_CELLS; i++) {
            Cell c = cells[i];
            if (c == null || c.bottomOverlay == null) continue;

            int col = i % cols;
            boolean leftEdge = col == 0;
            boolean rightEdge = col == cols - 1;
            boolean bottomRow = (i / cols) == (rows - 1);

            int wantBottom = bottomRow ? lift : d(6);
            if (c.bottomOverlay.getLayoutParams() instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams) c.bottomOverlay.getLayoutParams();
                if (lp.bottomMargin != wantBottom) {
                    lp.bottomMargin = wantBottom;
                    c.bottomOverlay.setLayoutParams(lp);
                }
            }

            if (c.sb.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams sp =
                        (ViewGroup.MarginLayoutParams) c.sb.getLayoutParams();
                int sl = d(4) + (leftEdge ? insetGestureLeft : 0);
                int sr = d(4) + (rightEdge ? insetGestureRight : 0);
                if (sp.leftMargin != sl || sp.rightMargin != sr) {
                    sp.leftMargin = sl;
                    sp.rightMargin = sr;
                    c.sb.setLayoutParams(sp);
                }
            }
        }
    }

    /** 播放页沉浸式：隐藏系统栏，让排布真正铺满。 */
    private void applyImmersive(boolean on) {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c == null) return;
            if (on) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                c.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            setLegacyImmersive(on);
        }
    }

    @SuppressWarnings("deprecation")
    private void setLegacyImmersive(boolean on) {
        View dv = getWindow().getDecorView();
        if (on) {
            dv.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            dv.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    private void applyKeepScreenOn() {
        if (playing && prefs.keepScreenOn()) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    // ------------------------------------------------------------ 启动介绍页

    /**
     * 首次启动的介绍页：讲清楚这东西干什么、怎么用、要什么权限。
     *
     * 权限不在这里自动弹 —— 先把「为什么需要」讲明白，再让用户自己点授权。
     * 上来就弹系统权限框、用户不知道要给什么的时候，拒绝率最高，
     * 拒了之后又没有补救指引，就卡死在空列表上。
     */
    private View buildIntroView() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setPadding(d(22), d(34), d(22), d(28));
        sv.addView(col);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.mipmap.ic_launcher);
        icon.setLayoutParams(new LinearLayout.LayoutParams(d(88), d(88)));
        col.addView(icon);

        TextView title = new TextView(this);
        title.setText("视频墙");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(24);
        title.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(-2, -2);
        tLp.setMargins(0, d(14), 0, 0);
        title.setLayoutParams(tLp);
        col.addView(title);

        TextView sub = new TextView(this);
        sub.setText("至多 4 路视频同时铺满屏幕播放");
        sub.setTextColor(MUTED);
        sub.setTextSize(13);
        sub.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-2, -2);
        sLp.setMargins(0, d(6), 0, 0);
        sub.setLayoutParams(sLp);
        col.addView(sub);

        // ---- 指引卡片
        LinearLayout guide = new LinearLayout(this);
        guide.setOrientation(LinearLayout.VERTICAL);
        guide.setPadding(d(14), d(14), d(14), d(14));
        card(guide);
        LinearLayout.LayoutParams gLp = new LinearLayout.LayoutParams(-1, -2);
        gLp.setMargins(0, d(26), 0, 0);
        guide.setLayoutParams(gLp);

        guide.addView(introSection("怎么用"));
        guide.addView(guideRow("1", "挑视频", "按文件夹找，最多勾 4 个；底部槽位可移除"));
        guide.addView(guideRow("2", "开始播放", "铺满全屏，可选 2×2 网格或一字排开"));
        guide.addView(guideRow("3", "点画面唤出菜单", "顶栏只有 5 个键，静置几秒自动收起"));
        guide.addView(guideRow("4", "每格独立控制", "暂停 / 快进 / 重播 / 拖动进度互不影响"));
        col.addView(guide);

        // ---- 权限卡片
        LinearLayout perm = new LinearLayout(this);
        perm.setOrientation(LinearLayout.VERTICAL);
        perm.setPadding(d(14), d(14), d(14), d(14));
        card(perm);
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(-1, -2);
        pLp.setMargins(0, d(14), 0, 0);
        perm.setLayoutParams(pLp);

        perm.addView(introSection("关于权限"));
        perm.addView(introBody("需要「" + permLabel() + "」权限才能列出本机视频。\n\n"
                + "· 只读取视频的文件名 / 时长 / 分辨率，不读取内容、不上传\n"
                + "· 本应用不申请网络权限，结构上就无法联网\n"
                + "· 不授权也能安装，但列表会是空的（什么都播不了）"));
        col.addView(perm);

        introStatus = new TextView(this);
        introStatus.setTextColor(WARN);
        introStatus.setTextSize(12);
        introStatus.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(-1, -2);
        stLp.setMargins(0, d(16), 0, 0);
        introStatus.setLayoutParams(stLp);
        introStatus.setVisibility(View.GONE);
        col.addView(introStatus);

        introPrimary = new TextView(this);
        introPrimary.setText("开始使用");
        introPrimary.setTextColor(Color.WHITE);
        introPrimary.setTextSize(16);
        introPrimary.setGravity(Gravity.CENTER);
        introPrimary.setPadding(0, d(15), 0, d(15));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-1, -2);
        bLp.setMargins(0, d(20), 0, 0);
        introPrimary.setLayoutParams(bLp);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(ACCENT);
        bg.setCornerRadius(12 * dp);
        introPrimary.setBackground(bg);
        introPrimary.setOnClickListener(v -> onIntroPrimary());
        col.addView(introPrimary);

        TextView recheck = new TextView(this);
        recheck.setText("已经授权了？点这里再检查一次");
        recheck.setTextColor(MUTED);
        recheck.setTextSize(12);
        recheck.setGravity(Gravity.CENTER);
        recheck.setPadding(d(8), d(16), d(8), d(8));
        recheck.setOnClickListener(v -> {
            if (hasMediaPermission()) finishIntro();
            else toast("还没拿到权限");
        });
        col.addView(recheck);

        // 返回 sv，不是 col。
        // col 已经被 sv.addView(col) 收编成子 View 了，它已经有父容器；
        // 再拿它去 root.addView() 会直接抛
        //   IllegalStateException: The specified child already has a parent.
        // 而且是在 buildRoot() 里、Activity 还没显示出来就崩 —— 打开即闪退。
        return sv;
    }

    private String permLabel() {
        return Build.VERSION.SDK_INT >= 33 ? "照片和视频" : "存储";
    }

    private TextView introSection(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(SECTION_ACCENT);
        t.setTextSize(12);
        t.getPaint().setFakeBoldText(true);
        t.setPadding(d(2), 0, d(2), d(4));
        return t;
    }

    private TextView introBody(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(MUTED);
        t.setTextSize(12);
        t.setLineSpacing(0, 1.25f);
        t.setPadding(d(2), 0, d(2), 0);
        return t;
    }

    private View guideRow(String index, String title, String desc) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, d(9), 0, d(9));

        TextView num = new TextView(this);
        num.setText(index);
        num.setTextColor(Color.WHITE);
        num.setTextSize(11);
        num.setGravity(Gravity.CENTER);
        GradientDrawable nb = new GradientDrawable();
        nb.setColor(ACCENT);
        nb.setShape(GradientDrawable.OVAL);
        num.setBackground(nb);
        r.addView(num, new LinearLayout.LayoutParams(d(21), d(21)));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(d(11), 0, 0, 0);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT_PRIMARY);
        t.setTextSize(14);
        TextView s = new TextView(this);
        s.setText(desc);
        s.setTextColor(MUTED);
        s.setTextSize(11);
        s.setPadding(0, d(3), 0, 0);
        col.addView(t);
        col.addView(s);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        return r;
    }

    private void showIntro() {
        introShowing = true;
        pickView.setVisibility(View.GONE);
        playView.setVisibility(View.GONE);
        introView.setVisibility(View.VISIBLE);
        updateIntroPermissionState();
    }

    private void onIntroPrimary() {
        if (hasMediaPermission()) {
            finishIntro();
            return;
        }
        if (!permAskedOnce || shouldShowRequestPermissionRationale(mediaPermission())) {
            permAskedOnce = true;
            requestPermissions(new String[]{mediaPermission()}, REQ_PERM);
        } else {
            openAppSettings();
        }
    }

    private void openAppSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
            toast("打开「权限」，允许「" + permLabel() + "」后再回来");
        } catch (Throwable t) {
            toast("请到 系统设置 → 应用 → 视频墙 → 权限 里打开");
        }
    }

    private void updateIntroPermissionState() {
        if (introStatus == null || introPrimary == null) return;
        if (hasMediaPermission()) {
            introStatus.setVisibility(View.GONE);
            introPrimary.setText("开始使用");
            return;
        }
        boolean canAsk = !permAskedOnce || shouldShowRequestPermissionRationale(mediaPermission());
        if (canAsk) {
            introStatus.setVisibility(View.GONE);
            introPrimary.setText("授予" + permLabel() + "权限");
        } else {
            introStatus.setText("权限被拒绝了。点下面的按钮去系统设置里打开，回来会自动继续；"
                    + "也可以点最下面那行再检查一次。");
            introStatus.setVisibility(View.VISIBLE);
            introPrimary.setText("去系统设置授权");
        }
    }

    private void finishIntro() {
        introShowing = false;
        prefs.setOnboarded(true);
        introView.setVisibility(View.GONE);
        pickView.setVisibility(View.VISIBLE);
        loadLibrary();
    }

    // ------------------------------------------------------------ 选择页顶栏

    /**
     * 主题切换：亮色 → 暗色 → 跟随系统 → 亮色。
     *
     * 三档都保留（和设置页一致），只是把最常用的搬到手边。
     * 改完必须自己重建 Activity —— manifest 里为了不打断播放声明了 uiMode，
     * 系统不会自动重建。
     */
    private void toggleTheme() {
        int next;
        switch (prefs.themeMode()) {
            case Prefs.THEME_LIGHT:
                next = Prefs.THEME_DARK;
                break;
            case Prefs.THEME_DARK:
                next = Prefs.THEME_SYSTEM;
                break;
            default:
                next = Prefs.THEME_LIGHT;
                break;
        }
        prefs.setThemeMode(next);
        AppCompatDelegate.setDefaultNightMode(App.nightModeOf(next));
        // 延到下一轮再重建：在点击回调里直接 recreate 会踩到"视图正在派发事件"的状态
        ui.post(this::recreate);
    }

    /** 文件夹排布：单列列表 ⇄ 两列网格。 */
    private void toggleFolderGrid() {
        prefs.setFolderGrid(!prefs.folderGrid());
        syncTopButtons();
        refreshPickUi();
    }

    /** 刷新选择页顶栏两个切换键的图标（都表示"当前是什么"）。 */
    private void syncTopButtons() {
        if (themeButton != null) {
            // 三档三个图标：太阳 / 月亮 / A（Auto，跟随系统）
            int res;
            switch (prefs.themeMode()) {
                case Prefs.THEME_LIGHT:
                    res = R.drawable.ic_light_mode;
                    break;
                case Prefs.THEME_DARK:
                    res = R.drawable.ic_dark_mode;
                    break;
                default:
                    res = R.drawable.ic_theme_auto;
                    break;
            }
            themeButton.setImageResource(res);
        }
        if (layoutToggleButton != null) {
            layoutToggleButton.setImageResource(prefs.folderGrid()
                    ? R.drawable.ic_view_grid : R.drawable.ic_view_list);
        }
    }

    // ------------------------------------------------------------ 选择页 UI

    private LinearLayout buildPickView() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(d(14), d(12), d(14), d(8));

        TextView title = new TextView(this);
        title.setText("视频墙");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(19);
        title.getPaint().setFakeBoldText(true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        // 顶栏三个操作键，全用图标：主题切换、文件夹排布切换、设置。
        // 纯文字按钮在标题旁边显得散，图标紧凑也更容易一眼认出来。
        themeButton = pillIcon(R.drawable.ic_theme_auto, v -> toggleTheme());
        top.addView(themeButton);

        layoutToggleButton = pillIcon(R.drawable.ic_view_list, v -> toggleFolderGrid());
        top.addView(layoutToggleButton);

        top.addView(pillIcon(R.drawable.ic_settings,
                v -> startActivity(new Intent(this, SettingsActivity.class))));
        col.addView(top);

        breadcrumb = new TextView(this);
        breadcrumb.setTextColor(TEXT_PRIMARY);
        breadcrumb.setTextSize(14);
        breadcrumb.setPadding(d(14), d(4), d(14), d(10));
        breadcrumb.setVisibility(View.GONE);
        breadcrumb.setOnClickListener(v -> showFolders());
        col.addView(breadcrumb);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(d(10), 0, d(10), d(10));
        sv.addView(listContainer);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        pickBottomBar = new LinearLayout(this);
        pickBottomBar.setOrientation(LinearLayout.VERTICAL);
        pickBottomBar.setPadding(d(10), d(6), d(10), d(8));

        slotsBar = new LinearLayout(this);
        slotsBar.setOrientation(LinearLayout.HORIZONTAL);
        pickBottomBar.addView(slotsBar);

        startButton = new TextView(this);
        startButton.setText("先选视频");
        startButton.setTextColor(TEXT3);
        startButton.setTextSize(16);
        startButton.setGravity(Gravity.CENTER);
        startButton.setPadding(0, d(15), 0, d(15));
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(-1, -2);
        sbLp.setMargins(0, d(8), 0, 0);
        startButton.setLayoutParams(sbLp);
        startButton.setOnClickListener(v -> startPlayMode());
        pickBottomBar.addView(startButton);

        col.addView(pickBottomBar);
        return col;
    }

    private void renderSlots() {
        slotsBar.removeAllViews();
        for (int i = 0; i < MAX_CELLS; i++) {
            boolean has = i < picked.size();
            TextView slot = new TextView(this);
            slot.setText(has ? (i + 1) + ". " + picked.get(i).name : (i + 1) + ". 空");
            slot.setTextSize(11);
            slot.setTextColor(has ? TEXT_PRIMARY : TEXT3);
            slot.setMaxLines(1);
            slot.setGravity(Gravity.CENTER_VERTICAL);
            slot.setPadding(d(8), d(9), d(8), d(9));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(has ? CHIP_ON : CARD);
            bg.setCornerRadius(9 * dp);
            bg.setStroke(d(1), has ? ACCENT : STROKE);
            slot.setBackground(bg);
            if (has) {
                final int idx = i;
                slot.setOnClickListener(v -> {
                    picked.remove(idx);
                    savePick();
                    refreshPickUi();
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1f);
            lp.setMargins(d(3), 0, d(3), 0);
            slot.setLayoutParams(lp);
            slotsBar.addView(slot);
        }

        int n = picked.size();

        GradientDrawable sb = new GradientDrawable();
        sb.setColor(n > 0 ? ACCENT : CHIP);
        sb.setCornerRadius(12 * dp);
        startButton.setBackground(sb);
        startButton.setTextColor(n > 0 ? Color.WHITE : TEXT3);
        startButton.setText(n > 0 ? ("开始播放 · " + n + " 路") : "先选视频");
    }

    private void refreshPickUi() {
        renderSlots();
        // 正在看的文件夹在设置里被排除了 → 退回首页，别停在"看不见的"列表里
        if (openedFolder != null
                && Prefs.parseFolders(prefs.excludedFolders()).containsKey(openedFolder.id)) {
            openedFolder = null;
            breadcrumb.setVisibility(View.GONE);
        }
        if (openedFolder == null) {
            renderFolders();
        } else {
            renderVideos(openedFolder);
        }
    }

    // ---- 文件夹列表

    private void showFolders() {
        openedFolder = null;
        breadcrumb.setVisibility(View.GONE);
        renderFolders();
    }

    private void renderFolders() {
        listContainer.removeAllViews();
        if (loading) {
            listContainer.addView(hint("正在读取媒体库…"));
            return;
        }
        if (folders.isEmpty()) {
            listContainer.addView(hint("媒体库里没有视频"));
            return;
        }

        // 设置页里勾掉的文件夹不进首页。
        // 连同「全部视频」里的对应条目也一并排除 —— 排除的语义是"我不想看到这些"，
        // 只藏文件夹磁贴、却让它们从「全部视频」里冒出来是自相矛盾的。
        // 底部有明确的一行提示 + 可点进设置，所以不是静默隐藏。
        LinkedHashMap<Long, String> hidden = Prefs.parseFolders(prefs.excludedFolders());

        // 先收集要显示的文件夹（「全部视频」+ 未被排除的），再决定用列表还是网格渲染
        List<Folder> show = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();

        Folder all = new Folder(-1, "全部视频");
        for (Item it : allItems) {
            if (!hidden.containsKey(it.bucketId)) all.items.add(it);
        }
        show.add(all);
        counts.add(all.items.size());

        int hiddenCount = 0;
        for (Folder f : folders) {
            if (hidden.containsKey(f.id)) {
                hiddenCount++;
                continue;
            }
            show.add(f);
            counts.add(f.items.size());
        }

        if (prefs.folderGrid()) {
            // 两列网格：每两个文件夹并成一行，用 weight 平分宽度。
            // 不用 GridLayout —— 这里不需要跨行跨列，配 weight 的 LinearLayout 更省事也不会踩校验。
            for (int i = 0; i < show.size(); i += 2) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.setMargins(0, d(4), 0, d(4));
                row.setLayoutParams(rlp);
                for (int k = 0; k < 2; k++) {
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
                    clp.setMargins(k == 0 ? 0 : d(4), 0, k == 0 ? d(4) : 0, 0);
                    if (i + k < show.size()) {
                        row.addView(folderCard(show.get(i + k), counts.get(i + k)), clp);
                    } else {
                        // 奇数个时补一个等宽占位，否则最后一张卡会被拉满整行
                        row.addView(new View(this), clp);
                    }
                }
                listContainer.addView(row);
            }
        } else {
            for (int i = 0; i < show.size(); i++) {
                listContainer.addView(folderRow(show.get(i), counts.get(i)));
            }
        }

        if (hiddenCount > 0) {
            listContainer.addView(hiddenFooter(hiddenCount));
        }
    }

    private void openFolder(Folder f) {
        openedFolder = f;
        breadcrumb.setText("‹  " + f.name);
        breadcrumb.setVisibility(View.VISIBLE);
        renderVideos(f);
    }

    /** 两列网格里的文件夹卡：图标 + 名称 + 数量，竖排。 */
    private View folderCard(Folder f, int count) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(d(12), d(12), d(12), d(12));
        card(v);

        TextView icon = new TextView(this);
        icon.setText("▤");
        icon.setTextSize(16);
        icon.setTextColor(ACCENT);
        icon.setGravity(Gravity.CENTER);
        GradientDrawable ibg = new GradientDrawable();
        ibg.setColor(CHIP_ON);
        ibg.setCornerRadius(9 * dp);
        icon.setBackground(ibg);
        v.addView(icon, new LinearLayout.LayoutParams(d(34), d(34)));

        TextView n = new TextView(this);
        n.setText(f.name);
        n.setTextColor(TEXT_PRIMARY);
        n.setTextSize(13);
        n.setMaxLines(2);
        n.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.setMargins(0, d(9), 0, 0);
        v.addView(n, nlp);

        TextView c = new TextView(this);
        c.setText(count + " 个视频");
        c.setTextColor(MUTED);
        c.setTextSize(11);
        v.addView(c, new LinearLayout.LayoutParams(-1, -2));

        v.setOnClickListener(x -> openFolder(f));
        return v;
    }

    /** 首页底部那行"已隐藏 N 个文件夹"，点一下去设置里调整。 */
    private View hiddenFooter(int count) {
        TextView t = new TextView(this);
        t.setText("已隐藏 " + count + " 个文件夹（其中的视频也不进「全部视频」）· 点这里调整");
        t.setTextColor(MUTED);
        t.setTextSize(11);
        t.setGravity(Gravity.CENTER);
        t.setPadding(d(14), d(18), d(14), d(18));
        t.setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        return t;
    }

    private View folderRow(Folder f, int count) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(d(12), d(14), d(12), d(14));
        card(r);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, d(4), 0, d(4));
        r.setLayoutParams(lp);

        TextView icon = new TextView(this);
        icon.setText("▤");
        icon.setTextSize(16);
        icon.setTextColor(0xFF7F77DD);
        icon.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(d(34), d(34));
        GradientDrawable ibg = new GradientDrawable();
        ibg.setColor(CHIP_ON);
        ibg.setCornerRadius(9 * dp);
        icon.setBackground(ibg);
        r.addView(icon, ilp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(d(12), 0, 0, 0);
        TextView n = new TextView(this);
        n.setText(f.name);
        n.setTextColor(TEXT_PRIMARY);
        n.setTextSize(14);
        n.setMaxLines(1);
        TextView c = new TextView(this);
        c.setText(count + " 个视频");
        c.setTextColor(MUTED);
        c.setTextSize(11);
        col.addView(n);
        col.addView(c);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(MUTED);
        arrow.setTextSize(18);
        r.addView(arrow);

        r.setOnClickListener(v -> openFolder(f));
        return r;
    }

    // ---- 视频列表（多选）
    //
    // 排布跟着首页那个列表/网格开关走：首页用网格，进来也是网格 ——
    // 否则"我选了网格，点进文件夹又变回一列"，观感上就是没生效。

    private void renderVideos(Folder f) {
        listContainer.removeAllViews();
        if (prefs.folderGrid()) {
            for (int i = 0; i < f.items.size(); i += 2) {
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, -2);
                rlp.setMargins(0, d(4), 0, d(4));
                row.setLayoutParams(rlp);
                for (int k = 0; k < 2; k++) {
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
                    clp.setMargins(k == 0 ? 0 : d(4), 0, k == 0 ? d(4) : 0, 0);
                    if (i + k < f.items.size()) {
                        row.addView(videoCard(f.items.get(i + k)), clp);
                    } else {
                        row.addView(new View(this), clp);
                    }
                }
                listContainer.addView(row);
            }
        } else {
            for (Item it : f.items) {
                listContainer.addView(videoRow(it));
            }
        }
    }

    /** 两列网格里的视频卡：缩略图（右上角带勾）+ 文件名 + 时长/大小。 */
    private View videoCard(Item it) {
        boolean selected = picked.contains(it);

        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(d(6), d(6), d(6), d(8));
        card(v);
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CHIP_ON);
            bg.setCornerRadius(12 * dp);
            bg.setStroke(d(2), ACCENT);
            v.setBackground(bg);
        }

        // 缩略图 + 右上角勾选标记：用 FrameLayout 叠上去。
        // 高度写死 —— 建视图时拿不到卡片宽度，2 列在 360dp 屏上约 165dp 宽，
        // 16:9 就是 ~92dp。
        FrameLayout thumbBox = new FrameLayout(this);
        v.addView(thumbBox, new LinearLayout.LayoutParams(-1, d(92)));

        ImageView th = new ImageView(this);
        th.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable tbg = new GradientDrawable();
        tbg.setColor(CHIP);
        tbg.setCornerRadius(9 * dp);
        th.setBackground(tbg);
        th.setClipToOutline(true);
        thumbBox.addView(th, new FrameLayout.LayoutParams(-1, -1));
        if (prefs.showThumbnails()) loadThumb(it, th);

        TextView check = new TextView(this);
        check.setText(selected ? "✓" : "");
        check.setTextSize(13);
        check.setTextColor(Color.WHITE);
        check.setGravity(Gravity.CENTER);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(selected ? ACCENT : 0x66000000);
        cb.setShape(GradientDrawable.OVAL);
        check.setBackground(cb);
        FrameLayout.LayoutParams cLp = new FrameLayout.LayoutParams(d(24), d(24));
        cLp.gravity = Gravity.TOP | Gravity.END;
        cLp.setMargins(0, d(5), d(5), 0);
        thumbBox.addView(check, cLp);

        TextView n = new TextView(this);
        n.setText(it.name);
        n.setTextColor(TEXT_PRIMARY);
        n.setTextSize(12);
        n.setMaxLines(2);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-1, -2);
        nlp.setMargins(0, d(8), 0, 0);
        v.addView(n, nlp);

        TextView m = new TextView(this);
        m.setText(mmss(it.durationMs) + "  " + it.size);
        m.setTextColor(MUTED);
        m.setTextSize(11);
        v.addView(m, new LinearLayout.LayoutParams(-1, -2));

        v.setOnClickListener(x -> {
            togglePick(it);
            // 只刷新这一张卡，不重建列表
            applyCardSelection(v, check, picked.contains(it));
        });
        return v;
    }

    private View videoRow(Item it) {
        boolean selected = picked.contains(it);

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(d(8), d(8), d(10), d(8));
        card(r);
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CHIP_ON);
            bg.setCornerRadius(12 * dp);
            bg.setStroke(d(2), ACCENT);
            r.setBackground(bg);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, d(4), 0, d(4));
        r.setLayoutParams(lp);

        if (prefs.showThumbnails()) {
            ImageView th = new ImageView(this);
            th.setScaleType(ImageView.ScaleType.CENTER_CROP);
            th.setLayoutParams(new LinearLayout.LayoutParams(d(78), d(48)));
            r.addView(th);
            loadThumb(it, th);
        }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(d(10), 0, 0, 0);
        TextView n = new TextView(this);
        n.setText(it.name);
        n.setTextColor(TEXT_PRIMARY);
        n.setTextSize(13);
        n.setMaxLines(1);
        TextView m = new TextView(this);
        m.setText(mmss(it.durationMs) + "   " + it.size);
        m.setTextColor(MUTED);
        m.setTextSize(11);
        col.addView(n);
        col.addView(m);
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));

        TextView check = new TextView(this);
        check.setText(selected ? "✓" : "");
        check.setTextSize(14);
        check.setTextColor(Color.WHITE);
        check.setGravity(Gravity.CENTER);
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(selected ? ACCENT : 0x00000000);
        cb.setShape(GradientDrawable.OVAL);
        cb.setStroke(d(1), selected ? ACCENT : STROKE);
        check.setBackground(cb);
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(d(26), d(26));
        cLp.setMargins(d(8), 0, 0, 0);
        r.addView(check, cLp);

        r.setOnClickListener(v -> {
            togglePick(it);
            // 只刷新这一行，不重建列表
            applyRowSelection(r, check, picked.contains(it));
        });
        return r;
    }

    /**
     * 勾选 / 取消一个视频。
     *
     * 注意这里**不整表重建**：只刷新被点那一项的外观 + 底部槽位。
     * 原来的实现每次勾选都走 refreshPickUi() → removeAllViews() 把整个列表重建，
     * 每个缩略图还要重新 setImageBitmap一遍 —— 视频一多就卡，
     * 表现就是"点选不灵敏"，连点还会丢事件。
     */
    private void togglePick(Item it) {
        if (picked.contains(it)) {
            picked.remove(it);
        } else {
            if (picked.size() >= MAX_CELLS) {
                toast("最多选 " + MAX_CELLS + " 个，先去掉一个");
                return;
            }
            picked.add(it);
        }
        savePick();
        renderSlots();
    }

    /** 列表行的选中外观（同步刷新，不重建视图）。 */
    private void applyRowSelection(View row, TextView check, boolean selected) {
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CHIP_ON);
            bg.setCornerRadius(12 * dp);
            bg.setStroke(d(2), ACCENT);
            row.setBackground(bg);
        } else {
            card(row);
        }
        check.setText(selected ? "✓" : "");
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(selected ? ACCENT : 0x00000000);
        cb.setShape(GradientDrawable.OVAL);
        cb.setStroke(d(1), selected ? ACCENT : STROKE);
        check.setBackground(cb);
    }

    /** 网格卡片的选中外观。 */
    private void applyCardSelection(View cardView, TextView check, boolean selected) {
        if (selected) {
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(CHIP_ON);
            bg.setCornerRadius(12 * dp);
            bg.setStroke(d(2), ACCENT);
            cardView.setBackground(bg);
        } else {
            card(cardView);
        }
        check.setText(selected ? "✓" : "");
        GradientDrawable cb = new GradientDrawable();
        cb.setColor(selected ? ACCENT : 0x66000000);
        cb.setShape(GradientDrawable.OVAL);
        check.setBackground(cb);
    }

    private void savePick() {
        if (!prefs.rememberPick()) {
            prefs.setPickedIds("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Item it : picked) {
            if (sb.length() > 0) sb.append(',');
            sb.append(it.id);
        }
        prefs.setPickedIds(sb.toString());
    }

    // ------------------------------------------------------------ 播放页 UI

    private FrameLayout buildPlayView() {
        FrameLayout f = new FrameLayout(this);
        f.setBackgroundColor(Color.BLACK);

        grid = new GridLayout(this);
        for (int i = 0; i < MAX_CELLS; i++) {
            Cell c = new Cell();
            c.root = cellView(c, i);
            grid.addView(c.root);
            cells[i] = c;
        }
        f.addView(grid, new FrameLayout.LayoutParams(-1, -1));

        topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setBackgroundColor(OVER_VIDEO_SCRIM);
        topBar.addView(pillIconFlat(R.drawable.ic_back, v -> exitPlayMode()));

        // 播放/暂停合成一个键：图标显示"点它会做什么"（在播就显示暂停）
        playPauseAllButton = pillIconFlat(R.drawable.ic_pause, v -> {
            boolean anyPlaying = false;
            for (Cell c : cells) {
                if (mpIsPlaying(c)) anyPlaying = true;
            }
            for (Cell c : cells) {
                if (c == null || c.mp == null) continue;
                if (anyPlaying) mpPause(c);
                else mpStart(c);
            }
            updateCellChrome();
            showControls();
        });
        topBar.addView(playPauseAllButton);

        // 全部重播
        topBar.addView(pillIconFlat(R.drawable.ic_replay, v -> {
            for (Cell c : cells) {
                if (c == null || c.mp == null) continue;
                mpSeekTo(c, 0);
                mpStart(c);
            }
            updateCellChrome();
            showControls();
        }));

        // 布局切换：图标表示"当前是什么排布"
        layoutButton = pillIconFlat(R.drawable.ic_view_grid, v -> {
            // 放大态下排布被那一格占着，先退出放大，否则改了排布看不出任何变化
            if (zoomed >= 0) restoreFromZoom();
            prefs.setLayoutMode(prefs.layoutMode() == Prefs.LAYOUT_ROW ? Prefs.LAYOUT_GRID : Prefs.LAYOUT_ROW);
            applyLayoutMode(true);
            showControls();
        });
        topBar.addView(layoutButton);

        // 音频切换：只有两档（全部 / 单路）—— 静音那一档去掉了，按需求砍的
        audioButton = pillIconFlat(R.drawable.ic_volume_up, v -> {
            int next = prefs.audioMode() == Prefs.AUDIO_ALL ? Prefs.AUDIO_FOCUS : Prefs.AUDIO_ALL;
            prefs.setAudioMode(next);
            applyAudio();
            syncQuickButtons();
            showControls();
            if (next == Prefs.AUDIO_FOCUS) {
                toast("单路：点哪一格，哪一格出声");
            }
        });
        topBar.addView(audioButton);

        FrameLayout.LayoutParams tbLp = new FrameLayout.LayoutParams(-1, -2);
        tbLp.gravity = Gravity.TOP;
        f.addView(topBar, tbLp);

        // 不再单独做「菜单把手」。
        //
        // 加过两版：先是 30dp 半透明小圆点（太隐形），后改成 40dp 白描边（够明显了，
        // 但用户明确说不要）—— 因为**点画面任意处就能唤出菜单**本身就够了：
        // 格子铺满全屏，点任何一格都会唤出顶栏。少一个浮层，画面更干净。
        // 收起由「控制条自动隐藏」的设置负责，不提供手动收起入口。
        return f;
    }

    private FrameLayout cellView(Cell c, int index) {
        FrameLayout cell = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setCornerRadius(6 * dp);
        cell.setBackground(bg);
        cell.setClipToOutline(true);

        c.vv = new VideoView(this);
        c.vv.setLayoutParams(new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        cell.addView(c.vv);

        c.placeholder = new TextView(this);
        c.placeholder.setText("—");
        c.placeholder.setTextColor(0xFF3A3A40);
        c.placeholder.setTextSize(22);
        c.placeholder.setGravity(Gravity.CENTER);
        c.placeholder.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        cell.addView(c.placeholder);

        c.error = new TextView(this);
        c.error.setTextColor(WARN);
        c.error.setTextSize(11);
        c.error.setPadding(d(8), d(8), d(8), d(8));
        FrameLayout.LayoutParams errLp = new FrameLayout.LayoutParams(-1, -2);
        errLp.gravity = Gravity.CENTER;
        c.error.setLayoutParams(errLp);
        c.error.setVisibility(View.GONE);
        cell.addView(c.error);

        c.stateIcon = new TextView(this);
        c.stateIcon.setText("▶");
        c.stateIcon.setTextColor(0x99FFFFFF);
        c.stateIcon.setTextSize(30);
        c.stateIcon.setVisibility(View.GONE);
        FrameLayout.LayoutParams siLp = new FrameLayout.LayoutParams(-2, -2);
        siLp.gravity = Gravity.CENTER;
        c.stateIcon.setLayoutParams(siLp);
        cell.addView(c.stateIcon);

        // 编号与文件名放在格内**底部**的 metaRow 里，不再贴格子顶部。
        //
        // 原因：顶栏是整宽浮层，贴顶的信息会被它整条盖住 ——
        // 和「底栏压住下排格子的控制条」是同一类冲突。
        // 现在每格的控件全部集中在格内底部，格子顶部保持干净：
        // 顶栏只会盖到上排的**画面**，不会盖到任何控件；
        // 而上排格子的控件在屏幕竖直中线附近，离顶栏很远。
        // 编号与文件名**不再画成胶囊**。
        //
        // 之前它们各是一个深色圆角块，和真正的操作键长得一模一样 ——
        // 一整排看下来像是 6 个按钮，其中两个点了没反应，很乱。
        // 现在只留文字 + 阴影：压在视频上也看得清，而视觉上"像按钮"的只有下面那排操作键。
        c.badge = new TextView(this);
        c.badge.setText(String.valueOf(index + 1));
        c.badge.setTextColor(ACCENT);
        c.badge.setTextSize(11);
        c.badge.getPaint().setFakeBoldText(true);
        c.badge.setPadding(d(3), 0, d(7), 0);
        c.badge.setShadowLayer(5f, 0f, 1f, 0xFF000000);

        c.nameLabel = new TextView(this);
        c.nameLabel.setTextColor(0xE6FFFFFF);
        c.nameLabel.setTextSize(11);
        c.nameLabel.setSingleLine(true);
        c.nameLabel.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        c.nameLabel.setShadowLayer(5f, 0f, 1f, 0xFF000000);

        LinearLayout metaRow = new LinearLayout(this);
        metaRow.setOrientation(LinearLayout.HORIZONTAL);
        metaRow.setGravity(Gravity.CENTER_VERTICAL);
        metaRow.setPadding(d(4), 0, d(4), d(2));
        metaRow.addView(c.badge);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(0, -2, 1f);
        metaLp.setMargins(d(5), 0, 0, 0);
        metaRow.addView(c.nameLabel, metaLp);

        // 底部叠层：编号/文件名 + 逐格控制条 + 进度条
        LinearLayout bottomOverlay = new LinearLayout(this);
        bottomOverlay.setOrientation(LinearLayout.VERTICAL);
        bottomOverlay.setGravity(Gravity.CENTER_HORIZONTAL);
        bottomOverlay.addView(metaRow);
        FrameLayout.LayoutParams boLp = new FrameLayout.LayoutParams(-1, -2);
        boLp.gravity = Gravity.BOTTOM;
        bottomOverlay.setLayoutParams(boLp);

        c.ctrlStrip = new LinearLayout(this);
        c.ctrlStrip.setOrientation(LinearLayout.HORIZONTAL);
        c.ctrlStrip.setGravity(Gravity.CENTER);
        c.ctrlStrip.setPadding(0, 0, 0, d(2));

        final int fixIdx = index;
        // 顺序固定：后退 ｜ 播放/暂停 ｜ 前进 ｜ 全屏。全用图标。
        c.ctrlStrip.addView(miniIconButton(R.drawable.ic_rewind10,
                v -> seekBy(fixIdx, -SEEK_STEP_MS)));
        c.toggleButton = miniIconButton(R.drawable.ic_play, v -> toggleCell(fixIdx));
        c.ctrlStrip.addView(c.toggleButton);
        c.ctrlStrip.addView(miniIconButton(R.drawable.ic_forward10,
                v -> seekBy(fixIdx, SEEK_STEP_MS)));
        c.zoomButton = miniIconButton(R.drawable.ic_fullscreen, v -> toggleZoom(fixIdx));
        c.ctrlStrip.addView(c.zoomButton);
        bottomOverlay.addView(c.ctrlStrip);

        c.sb = new SeekBar(this);
        LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(-1, -2);
        sLp.setMargins(d(4), 0, d(4), 0);
        c.sb.setLayoutParams(sLp);
        c.sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (fromUser) mpSeekTo(c, progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                c.userSeeking = true;
                showControls();
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                c.userSeeking = false;
            }
        });
        bottomOverlay.addView(c.sb);
        cell.addView(bottomOverlay);
        c.bottomOverlay = bottomOverlay;

        // 点选："选中这一格"必须挂在 VideoView 上，因为 VideoView 铺满整格。
        //
        // 踩过的坑：之前写的是 c.vv.setOnClickListener(null) + 把监听器挂到 cell 上，
        // 结果整格点不动。原因是 AOSP 的 View.setOnClickListener 实现是
        //     if (!isClickable()) setClickable(true);
        //     getListenerInfo().mOnClickListener = l;
        // —— 传 null 只清监听器，clickable 反被置成 true，
        // 于是 VideoView 的 onTouchEvent 返回 true 把触摸全吞了，cell 永远收不到。
        // 显式 setClickable(false) 也不如直接把监听器挂在这里来得稳。
        c.vv.setOnClickListener(v -> onCellTap(fixIdx));

        // 视频之外的边角（格子内边距、空态占位、播放图标）也归到同一处理
        cell.setOnClickListener(v -> onCellTap(fixIdx));

        c.vv.setOnErrorListener((mp, what, extra) -> {
            // 关键：先断开引用再报错。
            // MediaPlayer 进入 Error 态后，isPlaying()/getCurrentPosition() 全部抛
            // IllegalStateException；而 ticker 每 400ms 就会遍历 4 格摸一遍，
            // 不断开的话这一格会把另外 3 格也一起炸掉。
            c.mp = null;
            c.stale = true;
            final Cell dead = c;
            ui.post(() -> {
                // 和 reset() 同理：先 pause() 把 VideoView 的 target 钉在 PAUSED，
                // 再 stopPlayback() 释放解码会话 —— 否则 Error 态下 stop() 会抛，
                // release 与 target 复位都执行不到，留下一个可能自启的播放器。
                try {
                    dead.vv.pause();
                } catch (Throwable ignored) {
                }
                try {
                    dead.vv.stopPlayback();
                } catch (Throwable ignored) {
                }
            });
            Item src = fixIdx < picked.size() ? picked.get(fixIdx) : null;
            String res = src == null || src.videoW <= 0
                    ? "" : ("\n" + src.videoW + "×" + src.videoH);
            String why = is4k(src)
                    ? "\n4K 并发解码实例不够"
                    : "\n解码器可能已被其他格占满";
            c.error.setText("解码失败" + res + why + "\nwhat=" + what + " extra=" + extra);
            c.error.setVisibility(View.VISIBLE);
            c.stateIcon.setVisibility(View.GONE);
            Log.e(TAG, "cell " + fixIdx + " error what=" + what + " extra=" + extra
                    + " res=" + (src == null ? "?" : src.videoW + "x" + src.videoH)
                    + " file=" + c.name);
            updateCellChrome();
            maybeSuggestDegrade(fixIdx);
            return true;
        });
        return cell;
    }

    // ---------------------------------------------------------- 单路控制

    /**
     * 点格子的语义：**只做"选中"，绝不改变播放状态。**
     *
     * 之前是"控制条收起时唤出控制条、控制条已显示时切播放/暂停"——
     * 于是想选中一格（比如切到「单路音频」后要指定哪格出声）时，
     * 手一抖就把它暂停了，也没法判断到底选中没有。
     * 现在点哪格就选哪格，控制条同时也唤出来；要暂停/播放请用格内那条的 ▶/‖ 键。
     */
    private void onCellTap(int index) {
        boolean changed = focused != index;
        focused = index;
        applyAudio();          // 顺带刷新选中高亮
        showControls();        // 每次交互都重置自动隐藏计时
        if (changed && prefs.audioMode() == Prefs.AUDIO_FOCUS) {
            toast("第 " + (index + 1) + " 格出声");
        }
    }

    private void toggleCell(int index) {
        Cell c = cells[index];
        if (c == null || c.mp == null) {
            applyAudio();
            updateCellChrome();
            showControls();
            return;
        }
        if (mpIsPlaying(c)) mpPause(c);
        else mpStart(c);
        applyAudio();
        updateCellChrome();
        showControls();
    }

    private void seekBy(int index, long deltaMs) {
        Cell c = cells[index];
        if (c == null || c.mp == null) return;
        long target = mpPosition(c) + deltaMs;
        if (target < 0) target = 0;
        int duration = mpDuration(c);
        if (duration > 0 && target > duration) target = duration;
        mpSeekTo(c, (int) target);
        c.sb.setProgress((int) target);
        showControls();
    }

    // ---------------------------------------------------------- 放大为全屏

    /**
     * 把某一格放大到占满整个容器，或者从放大态还原。
     *
     * 放大时**其他几格是暂停、不是释放** —— 释放了再还原就得重新 prepare，
     * 那是几秒的黑屏；暂停只花一帧，而且位置都还在。
     * 还原时按放大前的播放状态把该起的起回来，不会把原本暂停的也一起放了。
     */
    private void toggleZoom(int index) {
        if (zoomed >= 0) {
            restoreFromZoom();
            return;
        }
        Cell z = cells[index];
        if (z == null || z.mp == null) return;

        for (int i = 0; i < MAX_CELLS; i++) {
            wasPlayingBeforeZoom[i] = mpIsPlaying(cells[i]);
        }
        zoomed = index;
        focused = index;
        for (int i = 0; i < MAX_CELLS; i++) {
            if (i != index) mpPause(cells[i]);
        }
        applyLayoutMode(true);
        applyAudio();
        updateCellChrome();
        showControls();
    }

    private void restoreFromZoom() {
        int was = zoomed;
        zoomed = -1;
        applyLayoutMode(true);
        for (int i = 0; i < MAX_CELLS; i++) {
            if (i == was) continue;
            if (wasPlayingBeforeZoom[i] && cells[i].mp != null) mpStart(cells[i]);
        }
        applyAudio();
        updateCellChrome();
        showControls();
    }

    // ------------------------------------------------------------ 排布模式

    /**
     * 排布。两个手动模式（2×2 / 1×4）只由设置或顶栏决定，不跟着系统横竖屏走；
     * 另有一个「不足 4 个时自动铺满」的开关，按实际数量把屏幕吃满。
     *
     * 实现上复用同一个 GridLayout，只改子项 spec 与 columnCount/rowCount ——
     * 不摘挂子 View，也就不会重建 Surface、不会打断正在播的几路。
     */
    private void applyLayoutMode(boolean force) {
        int mode = prefs.layoutMode();
        boolean row = mode == Prefs.LAYOUT_ROW;
        boolean wide = wideScreen();
        boolean fill = prefs.fillScreen() && assignedCount > 0 && assignedCount < MAX_CELLS;
        // 放大态优先于一切排布设置：某一格独占整个容器
        boolean zoom = zoomed >= 0 && zoomed < MAX_CELLS;

        String sig = mode + "/" + assignedCount + "/" + (wide ? 1 : 0) + "/" + (fill ? 1 : 0)
                + "/z" + zoomed;
        if (!force && sig.equals(appliedLayoutSig)) {
            syncQuickButtons();
            return;
        }
        appliedLayoutSig = sig;

        // 目标排布。cols/rows 必须与下面 spec 循环里实际用到的最大索引严格一致 ——
        // 多了会让权重列/行分走空间（比如 2 路上下排却声明 2 列，右列会变 0 宽再把位置留空），
        // 少了直接触发 GridLayout 的计数校验异常。所以两边共用同一套分支判断。
        int cols;
        int rows;
        if (zoom) {
            // 放大态：1 列 1 行，只有那一格可见
            cols = 1;
            rows = 1;
        } else if (!fill) {
            // 手动模式：格子数固定，空的就空着（显示占位「—」）
            cols = row ? MAX_CELLS : 2;
            rows = row ? 1 : 2;
        } else if (assignedCount == 1) {
            cols = 1;
            rows = 1;
        } else if (assignedCount == 2) {
            if (row || wide) {
                cols = 2;
                rows = 1;
            } else {
                cols = 1;
                rows = 2;
            }
        } else { // assignedCount == 3
            if (row) {
                cols = 3;
                rows = 1;
            } else {
                cols = 2;
                rows = 2;
            }
        }

        // ⓪ 先把计数放宽成「超集」（列 4 / 行 2，即本应用可能出现的最大 start+span）。
        //
        // GridLayout 在下面两个入口上都会**同步**校验，而且方向相反：
        //   setColumnCount / setRowCount : count >= 子项 spec 的 max(start + span)
        //     → IllegalArgumentException: columnCount must be greater than or equal to the
        //       maximum of all grid indices (and spans) defined in the LayoutParams of each child.
        //       at GridLayout$Axis.setCount -> GridLayout.setColumnCount
        //   View.setLayoutParams        : spec 的 start + span <= 当前 count
        //     → IllegalArgumentException: row indices (start + span) mustn't exceed the row count.
        //       at GridLayout.onSetLayoutParams -> GridLayout.checkLayoutParams
        //   （以上两条都是真机 logcat 抓到的原始异常）
        //
        // 也就是说「先改 spec 再改计数」和「先改计数再改 spec」**都会撞**：
        // 前者在 setLayoutParams 时被旧计数拦下，后者在 setCount 时被旧 spec 拦下。
        // 唯一走得通的顺序是三步：放宽到超集 → 改 spec → 收紧到目标值。
        grid.setColumnCount(MAX_CELLS);
        grid.setRowCount(MAX_ROWS);

        // ① 给**每一个**子项（含即将隐藏的）换 spec。
        // 此时计数是超集，任何 spec 都能通过 setLayoutParams 的校验。
        //
        // 隐藏的格子也必须给 spec（不能留 null），且索引压到 0，
        // 否则它们残留的旧 spec 会把后面收紧计数时的下限顶上去。
        for (int i = 0; i < MAX_CELLS; i++) {
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 0;
            lp.height = 0;
            int col = 0;
            int colSpan = 1;
            int rw = 0;
            int rowSpan = 1;

            boolean used = zoom ? (i == zoomed) : (!fill || i < assignedCount);
            cells[i].root.setVisibility(used ? View.VISIBLE : View.GONE);

            if (used) {
                if (zoom) {
                    // 独占 1×1，col/row 都取默认的 0 即可
                } else if (!fill) {
                    if (row) {
                        col = i;
                    } else {
                        col = i % 2;
                        rw = i / 2;
                    }
                } else if (assignedCount == 1) {
                    // 1 路：独占全屏（col 0 / row 0）
                } else if (assignedCount == 2) {
                    // 2 路：屏宽就左右分栏，屏高就上下两行
                    if (row || wide) col = i;
                    else rw = i;
                } else {
                    // 3 路
                    if (row) {
                        col = i;                     // 一字排开三等分
                    } else if (i == 0) {
                        rowSpan = 2;                 // 一大：占左列整高
                    } else {
                        col = 1;                     // 两小：右列上下
                        rw = i - 1;
                    }
                }
            }

            lp.columnSpec = GridLayout.spec(col, colSpan, 1f);
            lp.rowSpec = GridLayout.spec(rw, rowSpan, 1f);
            lp.setMargins(d(1), d(1), d(1), d(1));
            cells[i].root.setLayoutParams(lp);
        }

        // ② 收紧到目标计数。此时校验依据的是刚写好的新 spec，
        //    而 cols/rows 就是按新 spec 的最大 start+span 算出来的，必然相等以上，不会抛。
        grid.setColumnCount(cols);
        grid.setRowCount(rows);
        appliedCols = cols;
        appliedRows = rows;

        grid.requestLayout();
        // 排布一变，"哪几格在最下排"就变了，控件抬升量得跟着重算
        applyCellChromeInsets();
        syncQuickButtons();
    }

    /** 屏幕是否偏宽 —— 只用来决定「铺满时 2 路是左右还是上下」。 */
    private boolean wideScreen() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return dm.widthPixels >= dm.heightPixels;
    }

    private void syncQuickButtons() {
        if (audioButton != null) {
            audioButton.setImageResource(prefs.audioMode() == Prefs.AUDIO_ALL
                    ? R.drawable.ic_volume_up : R.drawable.ic_headset);
        }
        if (layoutButton != null) {
            layoutButton.setImageResource(prefs.layoutMode() == Prefs.LAYOUT_ROW
                    ? R.drawable.ic_view_list : R.drawable.ic_view_grid);
        }
    }

    // ------------------------------------------------------------ 播放流程

    private void startPlayMode() {
        if (picked.isEmpty()) {
            toast("先选至少 1 个视频");
            return;
        }
        final List<Item> want = new ArrayList<>(picked.subList(0, Math.min(picked.size(), MAX_CELLS)));

        if (!prefs.preflightCheck()) {
            enterPlayMode(want.size());
            return;
        }

        // 起播前先探一遍分辨率。4K 的并发解码实例是硬件硬限制
        // （Android CDD 最高一档也只保证 3 路 1080p + 3 路 4K），
        // 4 路里混了 4K 很可能有格子起不来 —— 与其让它默默失败，不如先讲清楚。
        pool.execute(() -> {
            for (Item it : want) {
                probeMedia(it);
                // 探测结果只记日志，不再往弹窗里堆 —— 弹窗只留结论性提醒。
                // 要查细节看格子的信息标签，或设置里的诊断页。
                Log.i(TAG, "探测 " + it.name + "  " + it.videoW + "x" + it.videoH
                        + "  " + decodeLine(it));
            }
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (isRisky(want)) {
                    confirmRiskyPlayback(want);
                } else {
                    enterPlayMode(want.size());
                }
            });
        });
    }

    private void enterPlayMode(int n) {
        for (Cell c : cells) c.reset();
        zoomed = -1;                      // 新的一轮播放从正常排布开始

        assignedCount = n;
        for (int i = 0; i < n; i++) {
            assign(cells[i], picked.get(i), i);
        }

        degradeSuggested = false;
        playing = true;
        focused = 0;
        pickView.setVisibility(View.GONE);
        playView.setVisibility(View.VISIBLE);
        applyImmersive(true);
        applyKeepScreenOn();

        applyLayoutMode(true);
        applyAudio();
        updateCellChrome();
        showControls();
        startTicker();
        if (!hintShown) {
            hintShown = true;
            toast("点画面任意处唤出菜单，静置几秒自动收起");
        }
        Log.i(TAG, "开始播放 " + n + " 路，排布="
                + (prefs.layoutMode() == Prefs.LAYOUT_ROW ? "1x4" : "2x2"));
    }

    // ------------------------------------------------------- 分辨率预检

    /**
     * 读一格的显示分辨率（已按旋转角修正）以及平台会给它派哪个解码器。
     * 失败就把字段留成默认值，不影响起播。
     */
    private void probeMedia(Item it) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, it.uri());
            int w = intOf(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = intOf(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            int rot = intOf(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (rot == 90 || rot == 270) {
                int t = w;
                w = h;
                h = t;
            }
            it.videoW = w;
            it.videoH = h;
        } catch (Throwable ignored) {
        } finally {
            try {
                r.release();
            } catch (Throwable ignored) {
            }
        }

        // 编码格式 + 平台会选哪个解码器。
        // findDecoderForFormat() 走的是和 MediaPlayer 内部挑选同一套逻辑，
        // 所以这里拿到的名字有参考价值 —— 但注意：MediaPlayer 最终用哪个并不对外开放，
        // 只能说"按平台规则它应该选这个"，没法强制。
        //
        // MediaExtractor 没有 setDataSource(Context, Uri) 这个重载
        // （那个是 MediaMetadataRetriever 的），只能自己开 fd 喂进去。
        MediaExtractor ex = new MediaExtractor();
        ParcelFileDescriptor pfd = null;
        try {
            pfd = getContentResolver().openFileDescriptor(it.uri(), "r");
            if (pfd != null) {
                ex.setDataSource(pfd.getFileDescriptor());
                for (int i = 0; i < ex.getTrackCount(); i++) {
                    MediaFormat f = ex.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime == null || !mime.startsWith("video/")) continue;
                    it.videoMime = mime;
                    try {
                        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
                        String name = list.findDecoderForFormat(f);
                        it.videoDecoder = name == null ? "" : name;
                        if (name != null) {
                            for (MediaCodecInfo info : list.getCodecInfos()) {
                                if (!info.getName().equals(name)) continue;
                                it.videoDecoderMax =
                                        info.getCapabilitiesForType(mime).getMaxSupportedInstances();
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                ex.release();
            } catch (Throwable ignored) {
            }
            try {
                if (pfd != null) pfd.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /** 解码器是不是硬解（按命名惯例判断：OMX.google.* / c2.android.* 是软解）。 */
    private static boolean isHardwareDecoder(String name) {
        if (name == null || name.isEmpty()) return false;
        return !(name.startsWith("OMX.google") || name.startsWith("c2.android"));
    }

    /** 给界面用的短编码名。 */
    private static String shortCodec(String mime) {
        if (mime == null) return "";
        switch (mime) {
            case "video/avc":
                return "H.264";
            case "video/hevc":
                return "HEVC";
            case "video/vp9":
                return "VP9";
            case "video/av01":
                return "AV1";
            case "video/mp4v-es":
                return "MPEG-4";
            case "video/x-vnd.on2.vp8":
                return "VP8";
            default:
                return mime.startsWith("video/") ? mime.substring(6) : mime;
        }
    }

    /** 一行式的解码器描述，供预检弹窗与日志用。 */
    private static String decodeLine(Item it) {
        if (it.videoDecoder.isEmpty()) return "解码器未知";
        return shortCodec(it.videoMime)
                + (isHardwareDecoder(it.videoDecoder) ? " 硬解" : " 软解")
                + "  " + it.videoDecoder
                + (it.videoDecoderMax > 0 ? "  max=" + it.videoDecoderMax : "");
    }

    /** 格子上的信息标签：文件名 + 分辨率 + 编码 + 硬解/软解。 */
    private static String labelOf(Item it) {
        StringBuilder sb = new StringBuilder(it.name);
        if (it.videoW <= 0) return sb.toString();
        sb.append("   ").append(it.videoW).append('×').append(it.videoH);
        String codec = shortCodec(it.videoMime);
        if (!codec.isEmpty()) {
            sb.append("  ").append(codec);
            if (!it.videoDecoder.isEmpty()) {
                sb.append(isHardwareDecoder(it.videoDecoder) ? "·硬" : "·软");
            }
        }
        return sb.toString();
    }

    private static int intOf(String s) {
        if (s == null) return 0;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean is4k(Item it) {
        if (it == null || it.videoW <= 0 || it.videoH <= 0) return false;
        return Math.min(it.videoW, it.videoH) >= 2000;
    }

    private static boolean is2k(Item it) {
        if (it == null || it.videoW <= 0 || it.videoH <= 0) return false;
        return !is4k(it) && Math.min(it.videoW, it.videoH) >= 1300;
    }

    /** 有 4K 就必须先说明；4 路里有 2 路以上 2K 也值得提醒。 */
    private static boolean isRisky(List<Item> items) {
        int n4k = 0;
        int n2k = 0;
        for (Item it : items) {
            if (is4k(it)) n4k++;
            else if (is2k(it)) n2k++;
        }
        if (n4k > 0) return true;
        return n2k >= 2 && items.size() >= 4;
    }

    private void confirmRiskyPlayback(List<Item> items) {
        // 不再逐个列出视频信息 —— 起播前看那一长串文件名/分辨率/解码器没什么用，
        // 只留结论性的文字提醒。要查细节可以看格子上的信息标签或诊断页。
        int n4k = 0;
        for (Item it : items) {
            if (is4k(it)) n4k++;
        }

        StringBuilder sb = new StringBuilder();
        sb.append(n4k > 0
                ? "选中的视频里有 " + n4k + " 个 4K。手机的 4K 解码器通常只能同时开 1～2 个；\n"
                + "Android 兼容性定义里最高一档设备也只保证 3 路 1080p + 3 路 4K，\n"
                + "所以多路里混了 4K 时很可能有格子起不来。"
                : "选中的视频里有多个 2K 以上分辨率，并发解码压力较大，\n"
                + "可能有格子起不来。");
        sb.append("\n\n起不来的那一格会显示分辨率与错误码，");
        sb.append("届时可以一键降到 2 路，或者单独放大某一路来播。");

        // 「不再提醒」放在弹窗里，而不是只藏在设置页深处 ——
        // 用户第一次看到这个提醒时正是最想关掉它的时候。
        // 勾了就等于把设置里的「起播前检查分辨率」关掉，两处共用同一个偏好。
        CheckBox neverAsk = new CheckBox(this);
        neverAsk.setText("不再提醒（可在设置里重新打开）");
        neverAsk.setTextSize(13);
        neverAsk.setTextColor(MUTED);
        neverAsk.setPadding(d(4), d(6), d(4), 0);

        TextView body = new TextView(this);
        body.setText(sb.toString());
        body.setTextSize(13);
        body.setLineSpacing(0, 1.2f);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(d(20), d(8), d(20), 0);
        box.addView(body);
        box.addView(neverAsk);

        ScrollView sc = new ScrollView(this);
        sc.addView(box);

        new MaterialAlertDialogBuilder(this)
                .setTitle("性能提醒")
                .setView(sc)
                // 只留一个键。这是个说明性弹窗 —— 用户已经点了「开始播放」，
                // 这里问的只是"知道可能有格子起不来吗"，给三个选项反而是负担。
                // 想反悔直接返回键关掉即可。
                .setPositiveButton("继续播放", (d, w) -> {
                    rememberNoRemind(neverAsk.isChecked());
                    enterPlayMode(items.size());
                })
                .show();
    }

    private void rememberNoRemind(boolean checked) {
        if (!checked) return;
        prefs.setPreflightCheck(false);
        toast("已关闭起播前检查，可在「设置 → 排布与显示」里重新打开");
    }

    /** 起不来之后的降级建议：留 2 路，把后两格让出来（解码器耗尽通常先打到后起播的格）。 */
    private void maybeSuggestDegrade(int cellIndex) {
        if (degradeSuggested || !playing || assignedCount < 3) return;
        int alive = 0;
        for (Cell c : cells) {
            if (c != null && c.mp != null) alive++;
        }
        if (alive < 2) return;
        degradeSuggested = true;

        Item bad = cellIndex < picked.size() ? picked.get(cellIndex) : null;
        String res = bad == null || bad.videoW <= 0
                ? "" : "（" + bad.videoW + "×" + bad.videoH + "）";

        new MaterialAlertDialogBuilder(this)
                .setTitle("有一路解码失败")
                .setMessage("第 " + (cellIndex + 1) + " 格起不来" + res + "。\n"
                        + "现在还有 " + alive + " 路在播 —— 通常是把后两格的解码器让出去就能稳住。\n\n"
                        + "要只保留前 2 路吗？")
                .setPositiveButton("保留 2 路", (d, w) -> degradeTo(2))
                .setNegativeButton("继续 4 路", null)
                .show();
    }

    private void degradeTo(int n) {
        for (int i = n; i < MAX_CELLS; i++) {
            cells[i].reset();
        }
        assignedCount = Math.min(n, MAX_CELLS);
        applyLayoutMode(true);
        applyAudio();
        updateCellChrome();
    }

    private void exitPlayMode() {
        playing = false;
        zoomed = -1;
        savePositions();                 // 必须在 reset() 之前 —— reset 会把 c.mp 置空
        for (Cell c : cells) c.reset();
        applyKeepScreenOn();
        applyImmersive(false);
        playView.setVisibility(View.GONE);
        pickView.setVisibility(View.VISIBLE);
        if (ticker != null) ui.removeCallbacks(ticker);
        if (autoHide != null) ui.removeCallbacks(autoHide);
        showFolders();
    }

    private void assign(Cell c, Item it, int index) {
        c.name = it.name;
        c.badge.setText(String.valueOf(index + 1));
        c.nameLabel.setText(labelOf(it));
        c.error.setVisibility(View.GONE);
        c.placeholder.setVisibility(View.GONE);
        c.sb.setProgress(0);

        c.stale = false;                      // 这一格重新启用
        c.vv.setOnPreparedListener(mp -> {
            if (c.stale) {
                // 迟到的就绪回调：这一格在 prepare 期间已经被 reset 过了
                // （用户退出了播放，或者换了别的视频）。
                // 绝不能走下面的 start() —— 否则就是一个"看不见、也停不掉"的播放器。
                // 交给 VideoView 自己收尾即可。
                Log.i(TAG, "cell " + index + " 迟到的就绪回调，丢弃");
                ui.post(() -> {
                    try {
                        c.vv.stopPlayback();
                    } catch (Throwable ignored) {
                    }
                });
                return;
            }
            c.mp = mp;
            c.sb.setMax(Math.max(1, mpDuration(c)));
            mpSetLooping(c, prefs.loopEach());

            // 「继续播放」：把这一路拉回上次看到的位置。
            // 判据和写入端一致 —— 太靠近结尾就当作已看完，从头开始。
            if (prefs.startMode() == Prefs.START_RESUME) {
                Long saved = Prefs.parseNumbers(prefs.resumePositions()).get(it.id);
                int dur = mpDuration(c);
                if (saved != null && saved > 2000 && (dur <= 0 || saved < dur - 3000)) {
                    mpSeekTo(c, saved.intValue());
                    c.sb.setProgress(saved.intValue());
                    Log.i(TAG, "cell " + index + " 续播 @" + saved);
                }
            }

            applyAudio();
            if (prefs.autoPlay()) mpStart(c);
            updateCellChrome();
            Log.i(TAG, "cell " + index + " 就绪: " + c.name + " dur=" + mpDuration(c));
        });
        c.vv.setVideoURI(it.uri());
    }

    // ------------------------------------------------- 安全访问 MediaPlayer
    //
    // MediaPlayer.isPlaying() 在实例处于 **Error 状态**或已 release 时会抛：
    //   java.lang.IllegalStateException
    //     at android.media.MediaPlayer.isPlaying(Native Method)
    //
    // 而下面几条路径都会遍历全部 4 格 —— ticker(onTick) / onStop / 顶栏按钮 /
    // onPrepared 回调（新的一格刚就绪，却去问另外三格"在播吗"）。
    // 只要其中任意一格解码失败进了 Error 态，整轮遍历就会炸，
    // 连带把另外 3 个正在正常播的一起带走。
    //
    // 所以凡是从 Cell 上摸 MediaPlayer 的地方，一律走这组带兜底的方法。

    private boolean mpIsPlaying(Cell c) {
        if (c == null || c.mp == null) return false;
        try {
            return c.mp.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    private int mpPosition(Cell c) {
        if (c == null || c.mp == null) return 0;
        try {
            return c.mp.getCurrentPosition();
        } catch (Throwable t) {
            return 0;
        }
    }

    private int mpDuration(Cell c) {
        if (c == null || c.mp == null) return 0;
        try {
            return c.mp.getDuration();
        } catch (Throwable t) {
            return 0;
        }
    }

    private void mpPause(Cell c) {
        if (c == null || c.mp == null) return;
        try {
            c.mp.pause();
        } catch (Throwable ignored) {
        }
    }

    private void mpStart(Cell c) {
        if (c == null || c.mp == null) return;
        try {
            c.mp.start();
        } catch (Throwable ignored) {
        }
    }

    private void mpSeekTo(Cell c, int ms) {
        if (c == null || c.mp == null) return;
        try {
            c.mp.seekTo(ms);
        } catch (Throwable ignored) {
        }
    }

    private void mpVolume(Cell c, float v) {
        if (c == null || c.mp == null) return;
        try {
            c.mp.setVolume(v, v);
        } catch (Throwable ignored) {
        }
    }

    private void mpSetLooping(Cell c, boolean loop) {
        if (c == null || c.mp == null) return;
        try {
            c.mp.setLooping(loop);
        } catch (Throwable ignored) {
        }
    }

    /** 音频策略：四路各自一条 AudioTrack，全部出声时交给系统混音。 */
    private void applyAudio() {
        int mode = prefs.audioMode();
        for (int i = 0; i < MAX_CELLS; i++) {
            Cell c = cells[i];
            if (c == null) continue;
            // 只有两档：全部出声 / 只让选中格出声（静音那档按需求砍掉了）
            float v = (mode == Prefs.AUDIO_FOCUS) ? ((i == focused) ? 1f : 0f) : 1f;
            mpVolume(c, v);
        }
        // 选中格的强调边框要跟着"控制条显不显示"一起变，统一在 updateCellChrome 里画
        updateCellChrome();
    }

    /**
     * 统一刷新每格显隐。
     *
     * 需求「播放时隐藏进度条等信息」：默认只在唤出控制条时出现，
     * 设置里勾了「一直显示」才常驻。逐格控制条只在选中格 + 控制条可见时出现。
     */
    private void updateCellChrome() {
        boolean alwaysSeek = prefs.showSeekBar();
        boolean alwaysName = prefs.showName();
        boolean alwaysBadge = prefs.showBadge();

        boolean audioFocusMode = prefs.audioMode() == Prefs.AUDIO_FOCUS;
        // 2 列以内每格够宽，四格都露出逐格控制条；1×4 / 1×3 时每格太窄，只给选中格。
        boolean wideEnough = appliedCols <= 2;

        for (int i = 0; i < MAX_CELLS; i++) {
            Cell c = cells[i];
            if (c == null) continue;
            boolean hasVideo = c.mp != null;
            boolean paused = hasVideo && !mpIsPlaying(c);

            c.sb.setVisibility((controlsVisible || alwaysSeek) && hasVideo ? View.VISIBLE : View.GONE);
            c.badge.setVisibility(controlsVisible || alwaysBadge ? View.VISIBLE : View.GONE);
            c.nameLabel.setVisibility((controlsVisible || alwaysName) && !c.name.isEmpty()
                    ? View.VISIBLE : View.GONE);
            c.ctrlStrip.setVisibility(controlsVisible && (wideEnough || i == focused)
                    ? View.VISIBLE : View.GONE);
            // 「全屏/还原」跟着控制条显隐走（它是个操作，不是信息，不受"信息常显"设置影响）
            c.zoomButton.setVisibility(controlsVisible && hasVideo ? View.VISIBLE : View.GONE);
            c.zoomButton.setImageResource(zoomed == i
                    ? R.drawable.ic_fullscreen_exit : R.drawable.ic_fullscreen);
            // 图标表示"点它会做什么"：在播就显示暂停
            boolean showPause = !paused;
            if (showPause != c.toggleShowsPause) {
                c.toggleShowsPause = showPause;
                c.toggleButton.setImageResource(showPause
                        ? R.drawable.ic_pause : R.drawable.ic_play);
            }
            c.stateIcon.setVisibility(paused && !controlsVisible ? View.VISIBLE : View.GONE);

            // 选中格的高亮：描边 + 编号底色变强调色。
            // 只在状态真的变了才重建背景 —— 这方法被 ticker 每 400ms 调一次，
            // 无脑 new GradientDrawable 会白白产生垃圾。
            boolean highlight = playing && i == focused
                    && (controlsVisible || audioFocusMode);
            if (highlight != c.highlighted) {
                c.highlighted = highlight;

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.BLACK);
                bg.setCornerRadius(6 * dp);
                bg.setStroke(d(highlight ? 2 : 0), ACCENT);
                c.root.setBackground(bg);

                // 编号没底色了，选中改成变白（配上面那道强调描边，一眼看出是这一格）
                c.badge.setTextColor(highlight ? Color.WHITE : ACCENT);
            }
        }

        // 顶栏「播放/暂停」的图标跟着在播状态变。
        // 这里被 ticker 每 400ms 调一次，只在状态真变了才换图，免得白刷。
        if (playPauseAllButton != null) {
            boolean anyPlaying = false;
            for (Cell c : cells) {
                if (mpIsPlaying(c)) anyPlaying = true;
            }
            if (anyPlaying != lastAnyPlaying) {
                lastAnyPlaying = anyPlaying;
                playPauseAllButton.setImageResource(anyPlaying
                        ? R.drawable.ic_pause : R.drawable.ic_play);
            }
        }
    }

    private void startTicker() {
        if (ticker == null) {
            ticker = new Runnable() {
                @Override
                public void run() {
                    onTick();
                    ui.postDelayed(this, 400);
                }
            };
        }
        ui.removeCallbacks(ticker);
        ui.postDelayed(ticker, 400);
    }

    private void onTick() {
        for (Cell c : cells) {
            if (c == null || c.mp == null) continue;
            if (!c.userSeeking) c.sb.setProgress(mpPosition(c));
        }
        updateCellChrome();
    }

    private void showControls() {
        controlsVisible = true;
        topBar.setVisibility(View.VISIBLE);
        topBar.animate().alpha(1f).setDuration(150).start();
        updateCellChrome();

        int timeout = prefs.hideTimeout();
        ui.removeCallbacks(autoHide);
        if (timeout > 0) {
            if (autoHide == null) autoHide = this::hideControlsNow;
            ui.postDelayed(autoHide, timeout);
        }
    }

    private void hideControlsNow() {
        controlsVisible = false;
        ui.removeCallbacks(autoHide);
        topBar.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> topBar.setVisibility(View.GONE)).start();
        updateCellChrome();
    }

    // --------------------------------------------------------------- 媒体库

    private void loadLibrary() {
        if (libraryLoadStarted) return;
        libraryLoadStarted = true;
        pool.execute(() -> {
            final List<Item> found = new ArrayList<>();
            String[] proj = {
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.DATE_ADDED,
                    MediaStore.Video.Media.BUCKET_ID,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            };
            Cursor c = null;
            try {
                c = getContentResolver().query(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        proj, null, null,
                        MediaStore.Video.Media.DATE_ADDED + " DESC");
                if (c != null) {
                    int iId = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                    int iName = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                    int iSize = c.getColumnIndex(MediaStore.Video.Media.SIZE);
                    int iDur = c.getColumnIndex(MediaStore.Video.Media.DURATION);
                    int iDate = c.getColumnIndex(MediaStore.Video.Media.DATE_ADDED);
                    int iBucket = c.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
                    int iBucketName = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                    while (c.moveToNext() && found.size() < 800) {
                        long id = c.getLong(iId);
                        String name = c.getString(iName);
                        long size = iSize >= 0 ? c.getLong(iSize) : 0L;
                        long dur = iDur >= 0 && !c.isNull(iDur) ? c.getLong(iDur) : 0L;
                        long date = iDate >= 0 ? c.getLong(iDate) : 0L;
                        long bucket = iBucket >= 0 ? c.getLong(iBucket) : 0L;
                        String bname = iBucketName >= 0 ? c.getString(iBucketName) : null;
                        found.add(new Item(
                                id,
                                name == null ? "?" : name,
                                human(size),
                                dur,
                                date,
                                bucket,
                                bname == null || bname.isEmpty() ? "未分类" : bname));
                    }
                }
            } catch (Throwable t) {
                Log.e(TAG, "query failed", t);
            } finally {
                if (c != null) c.close();
            }

            Map<Long, Folder> byId = new LinkedHashMap<>();
            for (Item it : found) {
                Folder f = byId.get(it.bucketId);
                if (f == null) {
                    f = new Folder(it.bucketId, it.bucketName);
                    byId.put(it.bucketId, f);
                }
                f.items.add(it);
            }
            final List<Folder> fs = new ArrayList<>(byId.values());

            ui.post(() -> {
                loading = false;
                allItems.clear();
                allItems.addAll(found);
                folders.clear();
                folders.addAll(fs);
                restorePick();
                sortFolders();
                sortVideosInFolders();
                refreshPickUi();
                if (found.isEmpty()) toast("媒体库里没有视频");
            });
        });
    }

    private void sortFolders() {
        if (prefs.folderSort() == Prefs.FOLDER_BY_NAME) {
            Collections.sort(folders, (a, b) -> a.name.compareToIgnoreCase(b.name));
        } else {
            Collections.sort(folders, (a, b) -> Integer.compare(b.items.size(), a.items.size()));
        }
    }

    private void sortVideosInFolders() {
        int mode = prefs.videoSort();
        for (Folder f : folders) {
            if (mode == Prefs.VIDEO_BY_NAME) {
                Collections.sort(f.items, (a, b) -> a.name.compareToIgnoreCase(b.name));
            } else if (mode == Prefs.VIDEO_BY_DURATION) {
                Collections.sort(f.items, (a, b) -> Long.compare(b.durationMs, a.durationMs));
            } else {
                Collections.sort(f.items, (a, b) -> Long.compare(b.dateAdded, a.dateAdded));
            }
        }
    }

    /** 回填上次选中的视频（按媒体 id 匹配），并定位到它所在的文件夹。 */
    private void restorePick() {
        if (!prefs.rememberPick()) return;
        String raw = prefs.pickedIds();
        if (raw == null || raw.isEmpty()) return;

        picked.clear();
        for (String part : raw.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            long id;
            try {
                id = Long.parseLong(s);
            } catch (NumberFormatException e) {
                continue;
            }
            for (Item it : allItems) {
                if (it.id == id && !picked.contains(it)) {
                    picked.add(it);
                    break;
                }
            }
            if (picked.size() >= MAX_CELLS) break;
        }

        if (!picked.isEmpty()) {
            // 被排除的文件夹不自动定位到，否则会出现"首页看不见却进去了"的怪状态
            LinkedHashMap<Long, String> hidden = Prefs.parseFolders(prefs.excludedFolders());
            long bucket = picked.get(0).bucketId;
            for (Folder f : folders) {
                if (hidden.containsKey(f.id)) continue;
                if (f.id == bucket) {
                    openedFolder = f;
                    breadcrumb.setText("‹  " + f.name);
                    breadcrumb.setVisibility(View.VISIBLE);
                    break;
                }
            }
        }
    }

    private void loadThumb(Item it, ImageView target) {
        Bitmap cached = thumbCache.get(it.id);
        if (cached != null) {
            target.setImageBitmap(cached);
            return;
        }
        if (Build.VERSION.SDK_INT < 29) return;
        pool.execute(() -> {
            Bitmap bmp = null;
            try {
                bmp = getContentResolver().loadThumbnail(it.uri(), new Size(234, 144), null);
            } catch (Throwable ignored) {
            }
            final Bitmap f = bmp;
            if (f == null) return;
            ui.post(() -> {
                if (thumbCache.size() > THUMB_CACHE_LIMIT) thumbCache.clear();
                thumbCache.put(it.id, f);
                target.setImageBitmap(f);
            });
        });
    }

    // --------------------------------------------------------------- 诊断

    /** 供设置页调用。 */
    static void showDiagnosticsOn(Activity activity) {
        String text = collectDiagnostics();
        Log.i(TAG, "----- diagnostics -----\n" + text);

        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextSize(12);
        // 从主题取色 —— 之前写死 Color.WHITE，亮色主题下会白底白字看不见
        tv.setTextColor(Palette.of(activity).textPrimary);
        tv.setTypeface(Typeface.MONOSPACE);
        int pad = Math.round(20 * activity.getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, pad / 2);
        ScrollView sv = new ScrollView(activity);
        sv.addView(tv);

        new MaterialAlertDialogBuilder(activity)
                .setTitle("设备并发解码能力")
                .setView(sv)
                .setPositiveButton("关闭", null)
                .show();
    }

    private static String collectDiagnostics() {
        StringBuilder sb = new StringBuilder();
        sb.append("Android ").append(Build.VERSION.RELEASE)
                .append("  (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("机型  ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("SoC   ").append(reflectString("SOC_MODEL")).append('\n');
        sb.append("MPC   ").append(reflectInt("MEDIA_PERFORMANCE_CLASS")).append('\n');
        sb.append("核心  ").append(Runtime.getRuntime().availableProcessors()).append(" 核\n\n");

        for (String mime : new String[]{"video/avc", "video/hevc", "video/vp9", "video/av01"}) {
            sb.append(mime).append("   声明上限 ").append(maxInstances(mime)).append('\n');
            sb.append(codecLines(mime));
        }

        sb.append("\n声明上限只是参考，真实瓶颈常是内存带宽 ——").append('\n');
        sb.append("以四路能不能同时起播为准。");
        return sb.toString();
    }

    private static String codecLines(String mime) {
        StringBuilder sb = new StringBuilder();
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) continue;
                try {
                    int inst = info.getCapabilitiesForType(mime).getMaxSupportedInstances();
                    String n = info.getName();
                    boolean hw = !(n.startsWith("OMX.google") || n.startsWith("c2.android"));
                    sb.append("   ").append(hw ? "[HW] " : "[SW] ").append(n)
                            .append("  max=").append(inst).append('\n');
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static int maxInstances(String mime) {
        int best = 0;
        try {
            MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
            for (MediaCodecInfo info : list.getCodecInfos()) {
                if (info.isEncoder()) continue;
                try {
                    best = Math.max(best, info.getCapabilitiesForType(mime).getMaxSupportedInstances());
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return best;
    }

    private static int reflectInt(String field) {
        try {
            Field f = Build.VERSION.class.getField(field);
            return f.getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    private static String reflectString(String field) {
        try {
            Field f = Build.class.getField(field);
            Object v = f.get(null);
            return v == null ? "unknown" : v.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // --------------------------------------------------------------- 小件

    private void card(View v) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(12 * dp);
        bg.setStroke(d(1), STROKE);
        v.setBackground(bg);
    }

    /** 选择页顶栏的图标键：中性胶囊底色（不压视频，跟着主题走）。 */
    private ImageView pillIcon(int drawableRes, View.OnClickListener l) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(drawableRes);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(d(8), d(8), d(8), d(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CHIP);
        bg.setCornerRadius(50 * dp);
        iv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(34), d(34));
        lp.setMargins(d(6), 0, 0, 0);
        iv.setLayoutParams(lp);
        iv.setOnClickListener(l);
        return iv;
    }

    /**
     * 逐格控制条上的文字键。
     *
     * 尺寸压得比别处小：2×2 在 360dp 竖屏下每格只有约 178dp 宽，
     * 而这一排要放下 4 个键（−10 / ▶‖ / +10 / 全屏），每个连同间距不能超过 44dp。
     */
    /** 播放页顶栏的图标键。压视频上，所以用半透明白底（不跟主题走）。 */
    private ImageView pillIconFlat(int drawableRes, View.OnClickListener l) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(drawableRes);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(d(9), d(9), d(9), d(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(OVER_VIDEO_CHIP);
        bg.setCornerRadius(50 * dp);
        iv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(38), d(38));
        lp.setMargins(d(4), 0, d(4), 0);
        iv.setLayoutParams(lp);
        iv.setOnClickListener(l);
        return iv;
    }

    /**
     * 逐格控制条上的图标键。
     *
     * 尺寸压得小：2×2 在 360dp 竖屏下每格只有约 178dp 宽，这一排要放 4 个键，
     * 每个连同间距不能超过 44dp。压视频上，所以用深色半透明底、不跟主题走。
     */
    private ImageView miniIconButton(int drawableRes, View.OnClickListener l) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(drawableRes);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setPadding(d(8), d(8), d(8), d(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(OVER_VIDEO_BTN);
        bg.setCornerRadius(50 * dp);
        iv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(d(36), d(36));
        lp.setMargins(d(2), 0, d(2), 0);
        iv.setLayoutParams(lp);
        iv.setOnClickListener(l);
        return iv;
    }

    private TextView hint(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(MUTED);
        t.setTextSize(13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(d(20), d(40), d(20), d(40));
        return t;
    }

    private int d(float v) {
        return (int) (v * dp);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / 1048576.0);
    }

    private static String mmss(long ms) {
        long s = ms / 1000;
        return String.format("%02d:%02d", s / 60, s % 60);
    }
}
