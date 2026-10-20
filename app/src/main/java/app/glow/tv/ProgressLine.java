package app.glow.tv;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/** A thin rounded progress bar. */
final class ProgressLine extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float frac;

    ProgressLine(Context c) { super(c); }

    void set(float f) {
        if (Math.abs(f - frac) > 0.001f) { frac = f; invalidate(); }
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), rad = h / 2f;
        p.setColor(0x55FFFFFF);
        r.set(0, 0, w, h);
        c.drawRoundRect(r, rad, rad, p);
        if (frac > 0) {
            p.setColor(0xFFFFFFFF);
            r.set(0, 0, Math.max(h, w * frac), h);
            c.drawRoundRect(r, rad, rad, p);
        }
    }
}
