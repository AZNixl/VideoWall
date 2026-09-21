package com.aznixl.videowall;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置页。
 *
 * 条目词汇沿用 NextPlayer 的做法：只分三类 ——
 * 开关项（Switch）、可选项（点击弹单选）、跳转项（点击执行动作），
 * 再用分组标题把条目切成几块，避免一长条平铺。
 */
public class SettingsActivity extends AppCompatActivity {

    // 界面用色：改成实例字段，在 onCreate 里从主题解析（与 MainActivity 同一套做法）
    private int BG;
    private int CARD;
    private int STROKE;
    private int MUTED;
    private int SECTION;
    private int VALUE;
    private int TEXT_PRIMARY;

    /** 作者与项目地址 —— 关于页与「项目主页」共用。 */
    private static final String AUTHOR = "AZNixl";
    private static final String PROJECT_URL = "https://github.com/AZNixl/VideoWall";
    private static final String PROJECT_URL_SHORT = "github.com/AZNixl/VideoWall";

    private float dp;
    private Prefs prefs;
    private LinearLayout container;
    private int insetTop;
    private int insetBottom;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newSingleThreadExecutor();
    /** onCreate 时用的主题档位；onResume 发现变了就重建自己。 */
    private int themeModeAtCreate;

    private interface OnBool {
        void apply(boolean v);
    }

    private interface OnInt {
        void apply(int v);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        prefs = new Prefs(this);
        applyPalette();
        setContentView(buildUi());
    }

    /** 从当前主题取一套颜色。主题变了要重建 Activity —— 已画好的 View 不会自己变色。 */
    private void applyPalette() {
        Palette p = Palette.of(this);
        BG = p.bg;
        CARD = p.card;
        STROKE = p.stroke;
        MUTED = p.textSecondary;
        SECTION = p.section;
        VALUE = p.accent;
        TEXT_PRIMARY = p.textPrimary;
        themeModeAtCreate = prefs.themeMode();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 主题是在这一页改的；manifest 里声明了 uiMode，系统不会自动重建，所以自己来
        if (prefs.themeMode() != themeModeAtCreate) {
            recreate();
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        pool.shutdownNow();
        super.onDestroy();
    }

    private View buildUi() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setBackgroundColor(BG);

        col.setOnApplyWindowInsetsListener((v, insets) -> {
            int t;
            int b;
            if (Build.VERSION.SDK_INT >= 30) {
                Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                t = bars.top;
                b = bars.bottom;
            } else {
                t = legacyTop(insets);
                b = legacyBottom(insets);
            }
            if (t > insetTop || b > insetBottom) {
                insetTop = Math.max(insetTop, t);
                insetBottom = Math.max(insetBottom, b);
                v.setPadding(0, insetTop, 0, 0);
                if (container != null) {
                    container.setPadding(d(10), d(8), d(10), insetBottom + d(16));
                }
            }
            return insets;
        });

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(d(12), d(10), d(14), d(10));

        TextView back = new TextView(this);
        back.setText("‹  返回");
        back.setTextColor(TEXT_PRIMARY);
        back.setTextSize(14);
        back.setPadding(d(6), d(6), d(16), d(6));
        back.setOnClickListener(v -> finish());
        top.addView(back);

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextColor(TEXT_PRIMARY);
        title.setTextSize(18);
        title.getPaint().setFakeBoldText(true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        col.addView(top);

        ScrollView sv = new ScrollView(this);
        container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(d(10), d(8), d(10), d(24));
        sv.addView(container);
        col.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        buildItems();
        col.requestApplyInsets();
        return col;
    }

    @SuppressWarnings("deprecation")
    private int legacyTop(WindowInsets insets) {
        return insets.getSystemWindowInsetTop();
    }

    @SuppressWarnings("deprecation")
    private int legacyBottom(WindowInsets insets) {
        return insets.getSystemWindowInsetBottom();
    }

    // ------------------------------------------------------------- 条目清单

    private void buildItems() {
        section("外观");

        choice("主题", "暗色是原来的样子；亮色更适合白天和强光下",
                new String[]{"跟随系统", "亮色", "暗色"},
                prefs.themeMode(),
                v -> {
                    prefs.setThemeMode(v);
                    AppCompatDelegate.setDefaultNightMode(App.nightModeOf(v));
                    // 延到下一轮再重建：此刻单选框还没 dismiss，直接重建会让对话框挂在已销毁的 Activity 上。
                    // 之所以要手动重建，是因为 manifest 里为了不打断播放而声明了 uiMode，
                    // AppCompat 就不会自己重建了。
                    ui.post(this::recreate);
                });

        section("排布与显示");

        choice("排布模式", "手动选择，不随系统横竖屏自动切换",
                new String[]{"2×2 网格", "一字排开（1×4）"},
                prefs.layoutMode(),
                prefs::setLayoutMode);

        switchItem("不足 4 个时自动铺满", "只选了 1～3 个时按数量撑满屏幕，不留黑格",
                prefs.fillScreen(), prefs::setFillScreen);

        switchItem("起播前检查分辨率", "有 4K / 多路 2K 时先提醒 —— 4K 的并发解码实例是硬件硬限制",
                prefs.preflightCheck(), prefs::setPreflightCheck);

        switchItem("播放时一直显示进度条", "关掉则只在唤出控制条时显示",
                prefs.showSeekBar(), prefs::setShowSeekBar);

        switchItem("播放时一直显示文件名", "关掉则只在唤出控制条时显示",
                prefs.showName(), prefs::setShowName);

        switchItem("播放时一直显示格子编号", "关掉则只在唤出控制条时显示",
                prefs.showBadge(), prefs::setShowBadge);

        choice("控制条自动隐藏", "静置多久后收起顶栏、底栏与逐格控制",
                new String[]{"从不", "2 秒", "4 秒", "8 秒"},
                indexOfTimeout(prefs.hideTimeout()),
                v -> prefs.setHideTimeout(Prefs.HIDE_TIMEOUTS[v]));

        section("音频");

        choice("音频模式", audioHint(),
                new String[]{"四路同时出声", "只让选中格出声"},
                prefs.audioMode(),
                prefs::setAudioMode);

        section("播放行为");

        choice("开始播放时", "「继续播放」会记住每路看到哪儿，下次接着放",
                new String[]{"从头播放", "继续播放"},
                prefs.startMode(),
                prefs::setStartMode);

        action("清除已记住的播放进度", "清掉之后，所有视频都会从头开始播放",
                () -> {
                    prefs.setResumePositions("");
                    toast("已清除记下的播放进度");
                    rebuildItems();
                });

        switchItem("开始播放时自动起播", null,
                prefs.autoPlay(), prefs::setAutoPlay);

        switchItem("单个视频循环播放", "每格播完自动从头再来，适合做监看墙",
                prefs.loopEach(), prefs::setLoopEach);

        switchItem("播放中保持屏幕常亮", null,
                prefs.keepScreenOn(), prefs::setKeepScreenOn);

        switchItem("记住上次选的视频", "下次进入时自动回填上次那 4 个",
                prefs.rememberPick(), prefs::setRememberPick);

        section("媒体库");

        switchItem("列表显示缩略图", "关掉可加快大媒体库的打开速度",
                prefs.showThumbnails(), prefs::setShowThumbnails);

        foldersExclusionItem();

        choice("文件夹排序", null,
                new String[]{"按视频数量", "按名称"},
                prefs.folderSort(),
                prefs::setFolderSort);

        choice("视频排序", null,
                new String[]{"按修改时间", "按名称", "按时长"},
                prefs.videoSort(),
                prefs::setVideoSort);

        section("关于");

        action("关于本应用", "作者、版本、包名与说明", this::showAbout);

        info("作者", AUTHOR);

        info("版本", versionLine());

        action("项目主页", PROJECT_URL_SHORT, this::openProjectPage);

        action("开源许可", "第三方依赖与致谢", this::showLicenses);

        action("查看使用指引", "重新看一遍启动页的用法与权限说明", () -> {
            prefs.setIntroRequested(true);
            finish();
        });

        action("设备并发解码能力", "看 MPC / 各 codec 的并发实例上限",
                () -> MainActivity.showDiagnosticsOn(this));
    }

    private void openProjectPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(PROJECT_URL)));
        } catch (Throwable t) {
            toast("没有可打开链接的应用：" + PROJECT_URL);
        }
    }

    private void showAbout() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("关于视频墙")
                .setMessage(
                        "至多 4 路视频同时铺满屏幕播放的本地播放器。\n\n"
                                + "作者　　" + AUTHOR + "\n"
                                + "项目　　" + PROJECT_URL_SHORT + "\n"
                                + "包名　　" + getPackageName() + "\n"
                                + "版本　　" + versionLine() + "\n"
                                + "构建　　单 Activity + Java；第三方依赖只有 Material Components\n"
                                + "平台　　minSdk 24 / targetSdk 36\n\n"
                                + "只为本地文件设计：不联网、不申请网络权限、不采集任何数据，\n"
                                + "所有处理都在本机完成。\n\n"
                                + "之所以做成「多路同屏」而不是普通播放器，是因为手机的并发解码能力"
                                + "有硬件上限 —— 设置里那个「设备并发解码能力」就是给我们看这个上限的。")
                .setPositiveButton("关闭", null)
                .show();
    }

    private void showLicenses() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("开源许可与致谢")
                .setMessage(
                        "【第三方依赖】\n"
                                + "本应用只依赖一个第三方库：\n\n"
                                + "· Material Components for Android\n"
                                + "　com.google.android.material:material 1.14.0\n"
                                + "　Apache License 2.0 —— 提供 Material 3 主题、组件样式、\n"
                                + "　点击涟漪反馈，以及明暗两套配色（DayNight）的切换能力。\n\n"
                                + "其余全部使用 Android 平台 API 与 Java 标准库：\n"
                                + "· 视频解码与渲染　android.media.MediaPlayer / android.widget.VideoView\n"
                                + "· 媒体库读取　　　android.provider.MediaStore\n"
                                + "· 偏好存储　　　　android.content.SharedPreferences\n"
                                + "· 图标　　　　　　自行绘制（矢量 + tools/make_icon.py 生成的位图）\n\n"
                                + "【参考与致谢】\n"
                                + "· 最初的交互骨架来自一份 Tasker「Java 代码」脚本\n"
                                + "　（悬浮球 + 目录列表 + 信息流 + 内联 VideoView 播放），\n"
                                + "　本应用在此基础上重写为正规 Activity 并逐步重构。\n"
                                + "· 设置页的条目分类方式（开关项 / 可选项 / 分组标题）\n"
                                + "　参考了 NextPlayer（github.com/anilbeesetti/NextPlayer，GPL-3.0）\n"
                                + "　的信息架构；未复制其任何代码，本应用与该项目无衍生关系。\n"
                                + "· 并发硬解会话数的规范依据：Android 兼容性定义（CDD）\n"
                                + "　第 2.2.7.1 节与 MEDIA_PERFORMANCE_CLASS 补充文档。")
                .setPositiveButton("关闭", null)
                .show();
    }

    private String audioHint() {
        return prefs.audioMode() == Prefs.AUDIO_FOCUS
                ? "只让当前选中格出声，其余静音"
                : "四路音频同时响，由系统混音";
    }

    private static int indexOfTimeout(int ms) {
        for (int i = 0; i < Prefs.HIDE_TIMEOUTS.length; i++) {
            if (Prefs.HIDE_TIMEOUTS[i] == ms) return i;
        }
        return 2;
    }

    // ------------------------------------------------- 不显示的文件夹

    private static class Bucket {
        final long id;
        final String name;

        Bucket(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** 只显示个数，点了才去查文件夹列表 —— 避免每次进设置页都扫一遍媒体库。 */
    private void foldersExclusionItem() {
        int n = Prefs.parseFolders(prefs.excludedFolders()).size();
        LinearLayout r = row("不显示的文件夹",
                n > 0 ? ("已隐藏 " + n + " 个，点这里调整") : "首页只显示没被排除的文件夹");

        TextView value = new TextView(this);
        value.setText(n > 0 ? String.valueOf(n) : "");
        value.setTextColor(VALUE);
        value.setTextSize(13);
        r.addView(value);

        TextView arrow = new TextView(this);
        arrow.setText("  ›");
        arrow.setTextColor(MUTED);
        arrow.setTextSize(15);
        r.addView(arrow);

        r.setOnClickListener(v -> loadBucketsThen());
    }

    private void loadBucketsThen() {
        pool.execute(() -> {
            final List<Bucket> buckets = queryBuckets();
            ui.post(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (buckets.isEmpty()) {
                    toast("媒体库里没有文件夹");
                    return;
                }
                showFolderPicker(buckets);
            });
        });
    }

    private List<Bucket> queryBuckets() {
        LinkedHashMap<Long, String> map = new LinkedHashMap<>();
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.Video.Media.BUCKET_ID,
                            MediaStore.Video.Media.BUCKET_DISPLAY_NAME},
                    null, null, null);
            if (c != null) {
                int iId = c.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
                int iName = c.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                while (c.moveToNext()) {
                    long id = iId >= 0 ? c.getLong(iId) : 0L;
                    if (map.containsKey(id)) continue;
                    String name = iName >= 0 ? c.getString(iName) : null;
                    map.put(id, name == null || name.isEmpty() ? "未分类" : name);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        List<Bucket> out = new ArrayList<>();
        for (Map.Entry<Long, String> e : map.entrySet()) {
            out.add(new Bucket(e.getKey(), e.getValue()));
        }
        return out;
    }

    private void showFolderPicker(List<Bucket> buckets) {
        // 已被排除、但当前媒体库里已经找不到的文件夹也要进候选，
        // 否则用户没有地方取消它。
        LinkedHashMap<Long, String> current = Prefs.parseFolders(prefs.excludedFolders());
        LinkedHashMap<Long, String> all = new LinkedHashMap<>();
        for (Bucket b : buckets) all.put(b.id, b.name);
        for (Map.Entry<Long, String> e : current.entrySet()) {
            if (!all.containsKey(e.getKey())) {
                all.put(e.getKey(), e.getValue() + "（已不在媒体库）");
            }
        }

        final List<Long> ids = new ArrayList<>(all.keySet());
        final List<String> names = new ArrayList<>(all.values());
        final boolean[] checked = new boolean[ids.size()];
        for (int i = 0; i < ids.size(); i++) checked[i] = current.containsKey(ids.get(i));

        new MaterialAlertDialogBuilder(this)
                .setTitle("勾选的文件夹不出现在首页")
                .setMultiChoiceItems(names.toArray(new String[0]), checked,
                        (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("保存", (dialog, which) -> {
                    LinkedHashMap<Long, String> picked = new LinkedHashMap<>();
                    for (int i = 0; i < ids.size(); i++) {
                        if (checked[i]) picked.put(ids.get(i), names.get(i));
                    }
                    prefs.setExcludedFolders(Prefs.formatFolders(picked));
                    toast(picked.isEmpty() ? "已全部显示" : ("已隐藏 " + picked.size() + " 个文件夹"));
                    rebuildItems();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 保存后重建整个列表，让条目的摘要（已隐藏 N 个）立刻同步。 */
    private void rebuildItems() {
        container.removeAllViews();
        buildItems();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private String versionLine() {
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            return pi.versionName + " (" + pi.versionCode + ")";
        } catch (Exception e) {
            return "-";
        }
    }

    // ------------------------------------------------------------- 条目控件

    private void section(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(SECTION);
        t.setTextSize(12);
        t.getPaint().setFakeBoldText(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(d(6), d(18), d(6), d(6));
        t.setLayoutParams(lp);
        container.addView(t);
    }

    private LinearLayout row(String title, String desc) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(d(14), d(13), d(14), d(13));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD);
        bg.setCornerRadius(12 * dp);
        bg.setStroke(d(1), STROKE);
        r.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, d(3), 0, d(3));
        r.setLayoutParams(lp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT_PRIMARY);
        t.setTextSize(14);
        col.addView(t);
        if (desc != null && !desc.isEmpty()) {
            TextView dsc = new TextView(this);
            dsc.setText(desc);
            dsc.setTextColor(MUTED);
            dsc.setTextSize(11);
            dsc.setPadding(0, d(3), 0, 0);
            col.addView(dsc);
        }
        r.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        container.addView(r);
        return r;
    }

    private void switchItem(String title, String desc, boolean value, OnBool onChange) {
        LinearLayout r = row(title, desc);
        MaterialSwitch sw = new MaterialSwitch(this);
        sw.setChecked(value);
        sw.setOnCheckedChangeListener((CompoundButton b, boolean checked) -> onChange.apply(checked));
        r.addView(sw);
        r.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
    }

    private void choice(String title, String desc, String[] options, int index, OnInt onChange) {
        LinearLayout r = row(title, desc);
        TextView value = new TextView(this);
        value.setTextColor(VALUE);
        value.setTextSize(13);
        value.setText(options[Math.max(0, Math.min(index, options.length - 1))]);
        r.addView(value);

        TextView arrow = new TextView(this);
        arrow.setText("  ›");
        arrow.setTextColor(MUTED);
        arrow.setTextSize(15);
        r.addView(arrow);

        r.setOnClickListener(view -> new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setSingleChoiceItems(options, index, (dialog, which) -> {
                    onChange.apply(which);
                    value.setText(options[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show());
    }

    private void action(String title, String desc, Runnable r) {
        LinearLayout item = row(title, desc);
        TextView arrow = new TextView(this);
        arrow.setText("›");
        arrow.setTextColor(MUTED);
        arrow.setTextSize(16);
        item.addView(arrow);
        item.setOnClickListener(v -> r.run());
    }

    private void info(String title, String value) {
        LinearLayout r = row(title, null);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(MUTED);
        v.setTextSize(12);
        r.addView(v);
    }

    private int d(float v) {
        return (int) (v * dp);
    }
}
