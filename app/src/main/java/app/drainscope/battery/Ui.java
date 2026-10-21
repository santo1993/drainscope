package app.drainscope.battery;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small helpers to build the UI in code (the app ships without XML resources). */
public class Ui {

    public static final int ACCENT = 0xFF4FC3F7;
    public static final int GOOD = 0xFF66BB6A;
    public static final int WARN = 0xFFFFB74D;
    public static final int BAD = 0xFFEF5350;

    public static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    public static boolean isNight(Context c) {
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    public static int textPrimary(Context c) {
        return isNight(c) ? 0xFFE8EAF0 : 0xFF1A1C1E;
    }

    public static int textSecondary(Context c) {
        return isNight(c) ? 0xFF9AA0A6 : 0xFF5F6368;
    }

    public static int surface(Context c) {
        return isNight(c) ? 0xFF1E2126 : 0xFFFFFFFF;
    }

    public static int surfaceAlt(Context c) {
        return isNight(c) ? 0xFF262A31 : 0xFFF2F4F7;
    }

    public static TextView text(Context c, String s, float sp, int color) {
        TextView tv = new TextView(c);
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    public static GradientDrawable background(int color, float radiusDp, Context c) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(color);
        gd.setCornerRadius(dp(c, radiusDp));
        gd.setStroke(dp(c, 1), 0x22808080);
        return gd;
    }

    /** A titled card. Returns the content container to add rows to. */
    public static LinearLayout card(Context c, String title) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(background(surface(c), 14, c));
        card.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(c, 12);
        card.setLayoutParams(lp);
        if (title != null && title.length() > 0) {
            TextView t = text(c, title, 14, textSecondary(c));
            t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
            t.setLetterSpacing(0.06f);
            card.addView(t, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return card;
    }

    public static void addRow(LinearLayout parent, String label, String value, int valueColor) {
        Context c = parent.getContext();
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(c, 5), 0, dp(c, 5));
        TextView l = text(c, label, 14, textSecondary(c));
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(c, value, 14, valueColor);
        v.setGravity(Gravity.END);
        row.addView(l);
        row.addView(v);
        parent.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public static void addNote(LinearLayout parent, String note) {
        Context c = parent.getContext();
        TextView t = text(c, note, 12, textSecondary(c));
        t.setPadding(0, dp(c, 6), 0, 0);
        parent.addView(t);
    }

    public static void addBar(LinearLayout parent, String label, double fraction, int color, String valueText) {
        Context c = parent.getContext();
        LinearLayout wrap = new LinearLayout(c);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(0, dp(c, 4), 0, dp(c, 4));
        LinearLayout head = new LinearLayout(c);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = text(c, label, 13, textPrimary(c));
        l.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = text(c, valueText, 13, textSecondary(c));
        head.addView(l);
        head.addView(v);
        wrap.addView(head);
        BarView bar = new BarView(c, color);
        wrap.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 8)));
        parent.addView(wrap);
        bar.setFraction((float) fraction);
    }

    /** Simple horizontal bar. */
    public static class BarView extends View {
        private float fraction;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public BarView(Context c, int color) {
            super(c);
            this.barColor = color;
            paint.setColor(color);
        }

        public void setFraction(float f) {
            fraction = Math.max(0f, Math.min(1f, f));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = getHeight() / 2f;
            paint.setColor(isNight(getContext()) ? 0xFF333841 : 0xFFE1E4E8);
            canvas.drawRoundRect(new RectF(0, 0, getWidth(), getHeight()), r, r, paint);
            float w = getWidth() * fraction;
            if (w > 0) {
                paint.setColor(colorOf());
                canvas.drawRoundRect(new RectF(0, 0, Math.max(w, r * 2), getHeight()), r, r, paint);
            }
        }

        private int colorOf() {
            return barColor;
        }

        private int barColor = ACCENT;

        public void setColor(int c) {
            this.barColor = c;
            invalidate();
        }
    }

    /** Draws the app icon (same design as res/drawable/ic_launcher.png). */
    public static class IconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();

        public IconView(Context c) {
            super(c);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;
            float s = Math.min(w, h);
            float ox = (w - s) / 2f, oy = (h - s) / 2f;

            // rounded square with a diagonal teal -> blue gradient
            paint.setShader(new android.graphics.LinearGradient(ox, oy, ox + s, oy + s,
                    0xFF0EA58E, 0xFF1B4F9C, android.graphics.Shader.TileMode.CLAMP));
            rect.set(ox, oy, ox + s, oy + s);
            canvas.drawRoundRect(rect, s * 0.235f, s * 0.235f, paint);
            paint.setShader(null);

            // battery body
            float bx0 = ox + s * 0.255f, bx1 = ox + s * 0.745f;
            float by0 = oy + s * 0.285f, by1 = oy + s * 0.725f;
            float r = s * 0.075f, sw = s * 0.058f;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(sw);
            paint.setColor(0xFFFFFFFF);
            rect.set(bx0 + sw / 2, by0 + sw / 2, bx1 - sw / 2, by1 - sw / 2);
            canvas.drawRoundRect(rect, r, r, paint);
            paint.setStyle(Paint.Style.FILL);
            rect.set(ox + s * 0.435f, oy + s * 0.225f, ox + s * 0.565f, oy + s * 0.30f);
            canvas.drawRoundRect(rect, sw * 0.3f, sw * 0.3f, paint);

            // green level
            float ix0 = bx0 + sw, ix1 = bx1 - sw, iy0 = by0 + sw, iy1 = by1 - sw;
            float levelTop = iy1 - (iy1 - iy0) * 0.58f;
            paint.setShader(new android.graphics.LinearGradient(0, levelTop, 0, iy1,
                    0xFF4CD964, 0xFFC73B3B, android.graphics.Shader.TileMode.CLAMP));
            rect.set(ix0, levelTop, ix1, iy1);
            canvas.drawRoundRect(rect, r * 0.6f, r * 0.6f, paint);
            paint.setShader(null);

            // drain arrow
            float ax = ox + s * 0.5f, shaftW = s * 0.052f;
            float shaftTop = oy + s * 0.345f, shaftBot = oy + s * 0.545f;
            float headTop = oy + s * 0.505f, headHalf = s * 0.105f;
            float headBot = oy + s * 0.655f;
            paint.setColor(0xFFFFFFFF);
            rect.set(ax - shaftW, shaftTop, ax + shaftW, shaftBot);
            canvas.drawRect(rect, paint);
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(ax - headHalf, headTop);
            path.lineTo(ax + headHalf, headTop);
            path.lineTo(ax, headBot);
            path.close();
            canvas.drawPath(path, paint);
        }
    }

    /** Minimal bar chart for history values. */
    public static class ChartView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double[] values = new double[0];
        private String[] labels = new String[0];
        private double max = 0;
        private int color = ACCENT;

        public ChartView(Context c) {
            super(c);
        }

        public void setData(double[] v, String[] l, int c) {
            values = v == null ? new double[0] : v;
            labels = l == null ? new String[0] : l;
            color = c;
            max = 0;
            for (double d : values) max = Math.max(max, d);
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            if (values.length == 0) {
                paint.setColor(textSecondary(getContext()));
                paint.setTextSize(dp(getContext(), 11));
                canvas.drawText("no data yet", dp(getContext(), 8), h / 2f, paint);
                return;
            }
            int bottom = h - dp(getContext(), 16);
            int top = dp(getContext(), 6);
            float bw = (float) w / values.length;
            paint.setColor(isNight(getContext()) ? 0xFF333841 : 0xFFE1E4E8);
            canvas.drawRect(0, bottom, w, bottom + 1, paint);
            for (int i = 0; i < values.length; i++) {
                double v = max <= 0 ? 0 : values[i] / max;
                float barH = (float) (v * (bottom - top));
                float x = i * bw + bw * 0.15f;
                float barW = bw * 0.7f;
                paint.setColor(color);
                canvas.drawRect(x, bottom - barH, x + barW, bottom, paint);
                if (labels.length > i && labels[i] != null) {
                    paint.setColor(textSecondary(getContext()));
                    paint.setTextSize(dp(getContext(), 9));
                    canvas.drawText(labels[i], x, h - dp(getContext(), 4), paint);
                }
            }
            paint.setColor(textSecondary(getContext()));
            paint.setTextSize(dp(getContext(), 10));
            canvas.drawText(String.format("peak %.0f mAh/h", max), dp(getContext(), 4), top + dp(getContext(), 10), paint);
        }
    }
}
