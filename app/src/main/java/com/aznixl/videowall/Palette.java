package com.aznixl.videowall;

import android.content.Context;
import android.util.TypedValue;

/**
 * 从当前主题解析界面用色。
 *
 * 这个应用的界面全是代码里手工搭的（没有布局 XML），所以颜色没法靠主题自动应用 ——
 * 必须显式从主题属性里取值。所有颜色都定义在 attrs.xml，
 * 由 values/themes.xml（亮色）与 values-night/themes.xml（暗色）赋值。
 *
 * 用法：Activity 在 onCreate 里 resolve 一次存成字段。主题变了要重建 Activity，
 * 光改字段不会让已经画好的 View 变色。
 */
public final class Palette {

    public final int bg;
    public final int card;
    public final int stroke;
    public final int chip;
    public final int chipOn;
    public final int textPrimary;
    public final int textSecondary;
    public final int textTertiary;
    public final int accent;
    public final int warn;
    public final int section;

    private Palette(Context ctx) {
        bg = attr(ctx, R.attr.vwBg);
        card = attr(ctx, R.attr.vwCard);
        stroke = attr(ctx, R.attr.vwStroke);
        chip = attr(ctx, R.attr.vwChip);
        chipOn = attr(ctx, R.attr.vwChipOn);
        textPrimary = attr(ctx, R.attr.vwTextPrimary);
        textSecondary = attr(ctx, R.attr.vwTextSecondary);
        textTertiary = attr(ctx, R.attr.vwTextTertiary);
        accent = attr(ctx, R.attr.vwAccent);
        warn = attr(ctx, R.attr.vwWarn);
        section = attr(ctx, R.attr.vwSection);
    }

    public static Palette of(Context ctx) {
        return new Palette(ctx);
    }

    private static int attr(Context ctx, int attrId) {
        TypedValue tv = new TypedValue();
        if (ctx.getTheme().resolveAttribute(attrId, tv, true)) {
            if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT
                    && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                return tv.data;
            }
            if (tv.resourceId != 0) {
                return ctx.getResources().getColor(tv.resourceId, ctx.getTheme());
            }
            return tv.data;
        }
        // 解析不到说明主题没定义这个属性 —— 用洋红报出来，比默默变透明容易发现
        return 0xFFFF00FF;
    }

    /** 叠加透明度。用于"同一个色、不同透明"的场合（进度条底、遮罩等）。 */
    public static int alpha(int color, float factor) {
        int a = Math.round(((color >>> 24) & 0xFF) * factor);
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
