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

/**
 * Draws a photo with a very slow zoom-and-pan ("Ken Burns" drift) and soft crossfades between photos,
 * or slowly drifting colour glows. Soft dark edges keep the clock and dock readable.
 */
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

    private static final long DRIFT_MS = 90000;   // one slow sweep across the photo
    private static final long FADE_MS = 2500;     // crossfade between photos
    private static final float ZOOM = 1.14f;      // how far the photo slowly zooms
    // Each photo drifts in a different direction.
    private static final float[] DIR_X = {-1f, 1f, 1f, -1f};
    private static final float[] DIR_Y = {-0.7f, 0.6f, -0.5f, 0.8f};

    private static final class Shot {
        final Bitmap bmp;
        final long start;
        final int variant;
        Shot(Bitmap b, long s, int v) { bmp = b; start = s; variant = v; }
    }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Shader[] shaders = new Shader[3];
    private final GradientDrawable topScrim = new GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x80000000, 0x00000000});
    private final GradientDrawable bottomScrim = new GradientDrawable(
            GradientDrawable.Orientation.BOTTOM_TOP, new int[]{0xB8000000, 0x00000000});
    private final Runnable tick = new Runnable() { @Override public void run() { invalidate(); } };
    private Shot cur, old;
    private long fadeStart;
    private boolean fading;
    private int shots;
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

    /** Turns the slow motion on or off without touching the chosen wallpaper. */
    void setAnimated(boolean on) { animated = on; invalidate(); }

    void setPhoto(Bitmap b) { setPhoto(b, false); }

    /** Pass null to go back to the colour glows. With fade, the new photo dissolves in over the old picture. */
    void setPhoto(Bitmap b, boolean fade) {
        long now = SystemClock.uptimeMillis();
        old = (fade && b != null) ? cur : null;
        cur = b == null ? null : new Shot(b, now, shots++ & 3);
        fadeStart = now;
        fading = fade && b != null;
        removeCallbacks(tick);
        invalidate();
    }

    void resume() { running = true; removeCallbacks(tick); invalidate(); }
    void pause() { running = false; removeCallbacks(tick); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        topScrim.setBounds(0, 0, w, (int) (h * 0.40f));
        bottomScrim.setBounds(0, (int) (h * 0.40f), w, h);
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
        long now = SystemClock.uptimeMillis();
        float f = 1f;
        if (fading) {
            f = Math.min(1f, (now - fadeStart) / (float) FADE_MS);
            if (f >= 1f) { fading = false; old = null; }
        }
        if (cur == null) {
            drawGlows(c, now);
        } else {
            if (f < 1f) {
                if (old != null) drawShot(c, old, 1f, now);
                else drawGlows(c, now);
            }
            drawShot(c, cur, f, now);
        }
        topScrim.draw(c);
        bottomScrim.draw(c);
        boolean moving = animated && (cur != null || shaders[0] != null);
        if (running && (moving || fading)) { removeCallbacks(tick); postDelayed(tick, 33); }
    }

    private void drawShot(Canvas c, Shot s, float alpha, long now) {
        int w = getWidth(), h = getHeight();
        Bitmap b = s.bmp;
        float t = 0f;
        if (animated) {
            // Back-and-forth, eased at both ends, so the motion never jumps or stops.
            float phase = ((now - s.start) % (2 * DRIFT_MS)) / (float) DRIFT_MS;
            t = phase <= 1f ? phase : 2f - phase;
            t = t * t * (3f - 2f * t);
        }
        float zoom = 1f + (ZOOM - 1f) * t;
        float sc = Math.max((float) w / b.getWidth(), (float) h / b.getHeight()) * zoom;
        float ox = b.getWidth() * sc - w, oy = b.getHeight() * sc - h;
        float k = animated ? (t - 0.5f) * 0.9f : 0f;
        float tx = -ox * (0.5f + DIR_X[s.variant] * k);
        float ty = -oy * (0.5f + DIR_Y[s.variant] * k);
        bmpPaint.setAlpha(Math.round(255 * alpha));
        c.save();
        c.translate(tx, ty);
        c.scale(sc, sc);
        c.drawBitmap(b, 0, 0, bmpPaint);
        c.restore();
    }

    private void drawGlows(Canvas c, long now) {
        int w = getWidth(), h = getHeight();
        if (shaders[0] == null) return;
        c.drawColor(COLORS[theme][0]);
        double ms = animated ? now : 0;
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
    }
}
