package com.aznixl.videowall;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全部设置项的唯一入口。
 *
 * 词汇参考 NextPlayer 的 settings 模块：分组页 → 子页，子页里只有两类条目
 * —— 开关项（switch）与可选项（点击弹单选）。这里把两种都压成了 int/boolean 键。
 */
public final class Prefs {

    private static final String FILE = "videowall";

    // 排布
    public static final int LAYOUT_GRID = 0;   // 2x2
    public static final int LAYOUT_ROW = 1;    // 1x4 一字排开

    // 音频
    public static final int AUDIO_ALL = 0;     // 四路同时出声
    public static final int AUDIO_FOCUS = 1;   // 只让选中格出声
    // 曾经有过 AUDIO_MUTE = 2。按需求砍掉了 ——
    // 但老装机上可能还存着 2，getter 里夹一下顺序，避免落到"两档都不是"的尴尬值。

    // 文件夹排序
    public static final int FOLDER_BY_COUNT = 0;
    public static final int FOLDER_BY_NAME = 1;

    // 视频排序
    public static final int VIDEO_BY_DATE = 0;
    public static final int VIDEO_BY_NAME = 1;
    public static final int VIDEO_BY_DURATION = 2;

    /** 控制条自动隐藏的候选时长（毫秒），0 = 从不隐藏。 */
    public static final int[] HIDE_TIMEOUTS = {0, 2000, 4000, 8000};

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---- 排布

    public int layoutMode() {
        return sp.getInt("layout", LAYOUT_GRID);
    }

    public void setLayoutMode(int v) {
        sp.edit().putInt("layout", v).apply();
    }

    /**
     * 选的视频不足 4 个时，是否让它们弹性铺满屏幕（而不是留着黑格）。
     * 见 MainActivity.applyLayoutMode —— 铺满时的排布由数量决定。
     */
    public boolean fillScreen() {
        return sp.getBoolean("fillScreen", true);
    }

    public void setFillScreen(boolean v) {
        sp.edit().putBoolean("fillScreen", v).apply();
    }

    /**
     * 起播前是否探测各视频分辨率并在有 4K/2K 时先提醒。
     * 关掉会省掉一次元数据读取，但 4K 并发起不来时就只能靠格子上的错误码发现。
     */
    public boolean preflightCheck() {
        return sp.getBoolean("preflight", true);
    }

    public void setPreflightCheck(boolean v) {
        sp.edit().putBoolean("preflight", v).apply();
    }

    // ---- 明暗主题

    /** 跟随系统。 */
    public static final int THEME_SYSTEM = 0;
    /** 始终亮色。 */
    public static final int THEME_LIGHT = 1;
    /** 始终暗色。 */
    public static final int THEME_DARK = 2;

    public int themeMode() {
        return sp.getInt("themeMode", THEME_SYSTEM);
    }

    public void setThemeMode(int v) {
        sp.edit().putInt("themeMode", v).apply();
    }

    // ---- 从哪儿开始播

    /** 每次都从头播。 */
    public static final int START_FRESH = 0;
    /** 接着上次看到的位置播（按视频 id 记住每路的位置）。 */
    public static final int START_RESUME = 1;

    public int startMode() {
        return sp.getInt("startMode", START_FRESH);
    }

    public void setStartMode(int v) {
        sp.edit().putInt("startMode", v).apply();
    }

    // 播放进度表："mediaId\tpositionMs" 多行。
    // 只记"看到一半"的：看到尾巴 3 秒内、或开头 2 秒内的都不记，
    // 这样下次要么从头、要么接着看，不会停在"刚播完"的尴尬位置。

    public String resumePositions() {
        return sp.getString("resumePositions", "");
    }

    public void setResumePositions(String v) {
        sp.edit().putString("resumePositions", v).apply();
    }

    /** 解析 "id\t数值" 多行文本；非法行跳过。 */
    public static LinkedHashMap<Long, Long> parseNumbers(String raw) {
        LinkedHashMap<Long, Long> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                out.put(Long.parseLong(line.substring(0, tab)),
                        Long.parseLong(line.substring(tab + 1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static String formatNumbers(Map<Long, Long> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, Long> e : map.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(e.getKey()).append('\t').append(e.getValue());
        }
        return sb.toString();
    }

    // ---- 首次启动的介绍页

    /** 是否已经看过启动介绍页并完成授权。 */
    public boolean onboarded() {
        return sp.getBoolean("onboarded", false);
    }

    public void setOnboarded(boolean v) {
        sp.edit().putBoolean("onboarded", v).apply();
    }

    /** 一次性标志：设置页点「查看使用指引」时置位，主界面消费一次后清掉。 */
    public boolean introRequested() {
        return sp.getBoolean("introRequested", false);
    }

    public void setIntroRequested(boolean v) {
        sp.edit().putBoolean("introRequested", v).apply();
    }

    // ---- 音频

    public int audioMode() {
        // 夹一下：老装机上可能还存着已废弃的 2（静音），落到 0（全部出声）
        int v = sp.getInt("audio", AUDIO_ALL);
        return (v == AUDIO_FOCUS) ? AUDIO_FOCUS : AUDIO_ALL;
    }

    public void setAudioMode(int v) {
        sp.edit().putInt("audio", v).apply();
    }

    // ---- 播放行为

    public boolean autoPlay() {
        return sp.getBoolean("autoplay", true);
    }

    public void setAutoPlay(boolean v) {
        sp.edit().putBoolean("autoplay", v).apply();
    }

    public boolean loopEach() {
        return sp.getBoolean("loop", false);
    }

    public void setLoopEach(boolean v) {
        sp.edit().putBoolean("loop", v).apply();
    }

    public boolean keepScreenOn() {
        return sp.getBoolean("keepScreenOn", true);
    }

    public void setKeepScreenOn(boolean v) {
        sp.edit().putBoolean("keepScreenOn", v).apply();
    }

    public boolean rememberPick() {
        return sp.getBoolean("rememberPick", true);
    }

    public void setRememberPick(boolean v) {
        sp.edit().putBoolean("rememberPick", v).apply();
    }

    // ---- 界面（需求：播放时默认隐藏这些信息）

    public boolean showSeekBar() {
        return sp.getBoolean("showSeekBar", false);
    }

    public void setShowSeekBar(boolean v) {
        sp.edit().putBoolean("showSeekBar", v).apply();
    }

    public boolean showName() {
        return sp.getBoolean("showName", false);
    }

    public void setShowName(boolean v) {
        sp.edit().putBoolean("showName", v).apply();
    }

    public boolean showBadge() {
        return sp.getBoolean("showBadge", false);
    }

    public void setShowBadge(boolean v) {
        sp.edit().putBoolean("showBadge", v).apply();
    }

    public int hideTimeout() {
        return sp.getInt("hideTimeout", 4000);
    }

    public void setHideTimeout(int v) {
        sp.edit().putInt("hideTimeout", v).apply();
    }

    // ---- 媒体库

    public boolean showThumbnails() {
        return sp.getBoolean("thumbs", true);
    }

    public void setShowThumbnails(boolean v) {
        sp.edit().putBoolean("thumbs", v).apply();
    }

    public int folderSort() {
        return sp.getInt("folderSort", FOLDER_BY_COUNT);
    }

    public void setFolderSort(int v) {
        sp.edit().putInt("folderSort", v).apply();
    }

    /** 首页文件夹用两列网格还是单列列表。 */
    public boolean folderGrid() {
        return sp.getBoolean("folderGrid", false);
    }

    public void setFolderGrid(boolean v) {
        sp.edit().putBoolean("folderGrid", v).apply();
    }

    public int videoSort() {
        return sp.getInt("videoSort", VIDEO_BY_DATE);
    }

    public void setVideoSort(int v) {
        sp.edit().putInt("videoSort", v).apply();
    }

    // ---- 不显示的文件夹
    //
    // 存成多行文本，每行 "bucketId\t文件夹名"。
    // 之所以连名字一起存：这样即使某个文件夹暂时没有视频了，
    // 在设置页里也仍然能看到它、并且取消排除。
    // 目录名里的换行/制表符会被压成空格（极端路径，不为此上转义）。
    //
    // 解析/序列化放在这里而不是各自 Activity 里，保证读写格式只有一份定义。

    public String excludedFolders() {
        return sp.getString("excludedFolders", "");
    }

    public void setExcludedFolders(String v) {
        sp.edit().putString("excludedFolders", v).apply();
    }

    /** 解析 "bucketId\t名称" 多行文本；非法行直接跳过。 */
    public static LinkedHashMap<Long, String> parseFolders(String raw) {
        LinkedHashMap<Long, String> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            int tab = line.indexOf('\t');
            if (tab <= 0) continue;
            try {
                out.put(Long.parseLong(line.substring(0, tab)), line.substring(tab + 1));
            } catch (NumberFormatException ignored) {
            }
        }
        return out;
    }

    public static String formatFolders(Map<Long, String> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Long, String> e : map.entrySet()) {
            if (sb.length() > 0) sb.append('\n');
            String name = e.getValue() == null ? "" : e.getValue();
            sb.append(e.getKey()).append('\t')
                    .append(name.replace('\n', ' ').replace('\t', ' '));
        }
        return sb.toString();
    }

    // ---- 记住上次的选择（媒体 id 列表，逗号分隔；空串表示没有）

    public String pickedIds() {
        return sp.getString("pickedIds", "");
    }

    public void setPickedIds(String v) {
        sp.edit().putString("pickedIds", v).apply();
    }

    public long lastBucket() {
        return sp.getLong("lastBucket", Long.MIN_VALUE);
    }

    public void setLastBucket(long v) {
        sp.edit().putLong("lastBucket", v).apply();
    }
}
