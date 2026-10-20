package app.glow.tv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.View;

/** Draws a photo (centre-cropped) or slowly drifting colour glows, with soft dark edges for readability. */
final class WallpaperView extends View {
    static final String[] NAMES = {"Aurora", "Dusk", "Ocean", "Graphite"};
    // {background, glow1, glow2, glow3}
    private static final int[][] COLORS = {
            {0xFF06121F, 0xCC1B8A8A, 0xCC3A2F8F, 0xCC2B6E4F},
            {0xFF140B1F, 0xCCB5456B, 0xCC5A3A9E, 0xCCE08A4B},
            {0xFF03101C, 0xCC0E5C8C, 0xCC12A0A0, 0xCC1B3A7A},
            {0xFF0E0E10, 0xCC2C2C34, 0xCC1F1F26, 0xCC34343C}};
    private static final double[] SPEED = {0.00007, 0.00005, 0.00009};
    private static final double[] PHASE = {0, 2.1, 4.2};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Shader[] shaders = new Shader[3];
    private final GradientDrawable topScrim = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0xAA000000, 0x00000000});
    private final GradientDrawable bottomScrim = new GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xBB000000, 0x00000000});
    private final Runnable tick = new Runnable() { @Override public void run() { invalidate(); } };
    private Bitmap photo;
    private int theme;
    private boolean animated = true, running;
    private float radius;

    WallpaperView(Context c) { super(c); }

    void configure(int theme, boolean animated) {
        this.theme = theme;
        this.animated = animated;
        buildShaders();
        invalidate();
    }

    /** Pass null to go back to the colour glows. */
    void setPhoto(Bitmap b) {
        photo = b;
        removeCallbacks(tick);
        invalidate();
    }

    void resume() { running = true; removeCallbacks(tick); invalidate(); }
    void pause() { running = false; removeCallbacks(tick); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        topScrim.setBounds(0, 0, w, (int) (h * 0.45f));
        bottomScrim.setBounds(0, (int) (h * 0.45f), w, h);
        buildShaders();
    }

    private void buildShaders() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        radius = Math.max(w, h) * 0.55f;
        for (int i = 0; i < 3; i++) {
            int c = COLORS[theme][i + 1];
            shaders[i] = new RadialGradient(0, 0, radius, c, c & 0x00FFFFFF, Shader.TileMode.CLAMP);
        }
    }

    @Override protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        if (photo != null) {
            float s = Math.max((float) w / photo.getWidth(), (float) h / photo.getHeight());
            c.save();
            c.translate((w - photo.getWidth() * s) / 2f, (h - photo.getHeight() * s) / 2f);
            c.scale(s, s);
            c.drawBitmap(photo, 0, 0, bmpPaint);
            c.restore();
        } else if (shaders[0] != null) {
            c.drawColor(COLORS[theme][0]);
            double ms = animated ? SystemClock.uptimeMillis() : 0;
            for (int i = 0; i < 3; i++) {
                double a = ms * SPEED[i] + PHASE[i];
                float cx = (float) (w * (0.5 + 0.38 * Math.sin(a)));
                float cy = (float) (h * (0.5 + 0.32 * Math.cos(a * 1.3 + i)));
                paint.setShader(shaders[i]);
                c.save();
                c.translate(cx, cy);
                c.drawCircle(0, 0, radius, paint);
                c.restore();
            }
            if (animated && running) { removeCallbacks(tick); postDelayed(tick, 40); }
        }
        topScrim.draw(c);
        bottomScrim.draw(c);
    }
}
