package app.glow.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/** Four little bars that dance while music plays and settle flat when it is paused. */
final class EqualizerView extends View {
    private static final float[] SPEED = {0.0052f, 0.0071f, 0.0043f, 0.0063f};
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean playing;
    private float level;   // 0 = flat, 1 = fully dancing; eased so it settles smoothly

    EqualizerView(Context c) { super(c); p.setColor(0xFFFFFFFF); }

    void setPlaying(boolean on) { playing = on; invalidate(); }
    void setColor(int c) { p.setColor(c); invalidate(); }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        int n = SPEED.length;
        float gap = w * 0.14f, bw = (w - gap * (n - 1)) / n;
        long t = SystemClock.uptimeMillis();
        level += ((playing ? 1f : 0f) - level) * 0.15f;
        for (int i = 0; i < n; i++) {
            float amp = 0.5f + 0.5f * (float) Math.sin(t * SPEED[i] + i * 1.7f);
            float bh = h * (0.2f + 0.8f * amp * level);
            float x = i * (bw + gap);
            r.set(x, h - bh, x + bw, h);
            c.drawRoundRect(r, bw / 2f, bw / 2f, p);
        }
        if (playing || level > 0.02f) postInvalidateDelayed(50);
    }
}
