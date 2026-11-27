package io.encodal.idm;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.TypedValue;
import android.view.View;

/** Branded on/off switch: blue gradient track with a white knob when on, grey when off. */
class Toggle extends View {
    interface Listener { void onChange(boolean on); }

    private boolean on;
    private float pos; // 0 off … 1 on
    private Listener listener;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final boolean dark;

    Toggle(Context c, boolean dark) {
        super(c);
        this.dark = dark;
        setClickable(true);
        setOnClickListener(v -> set(!on, true));
    }

    void setListener(Listener l) { listener = l; }

    boolean isOn() { return on; }

    void set(boolean v, boolean animate) {
        boolean changed = v != on;
        on = v;
        if (animate) {
            ValueAnimator a = ValueAnimator.ofFloat(pos, on ? 1f : 0f);
            a.setDuration(160);
            a.addUpdateListener(x -> { pos = (float) x.getAnimatedValue(); invalidate(); });
            a.start();
        } else {
            pos = on ? 1f : 0f;
            invalidate();
        }
        if (changed && listener != null && animate) listener.onChange(on);
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    @Override protected void onMeasure(int w, int h) {
        setMeasuredDimension(dp(50), dp(30));
    }

    private static int mix(int a, int b, float t) {
        int ar = a >> 16 & 255, ag = a >> 8 & 255, ab = a & 255, br = b >> 16 & 255, bg = b >> 8 & 255, bb = b & 255;
        return 0xFF000000 | (int) (ar + (br - ar) * t) << 16 | (int) (ag + (bg - ag) * t) << 8 | (int) (ab + (bb - ab) * t);
    }

    @Override protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), pad = dp(3);
        r.set(0, dp(2), w, h - dp(2));
        int offC = dark ? 0xFF334155 : 0xFFCBD5E1;
        p.setShader(new LinearGradient(0, 0, w, 0, mix(offC, 0xFF1D4ED8, pos), mix(offC, 0xFF60A5FA, pos), Shader.TileMode.CLAMP));
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p);
        p.setShader(null);
        float kr = (r.height() - pad * 2) / 2;
        float cx = pad + kr + (w - pad * 2 - kr * 2) * pos, cy = r.centerY();
        p.setColor(0x33000000);
        c.drawCircle(cx, cy + dp(1), kr, p);
        p.setColor(0xFFFFFFFF);
        c.drawCircle(cx, cy, kr, p);
        if (pos > 0.5f) { // tiny check mark in the knob
            p.setColor(0xFF2563EB);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(dp(2));
            p.setStrokeCap(Paint.Cap.ROUND);
            c.drawLine(cx - kr * .4f, cy, cx - kr * .1f, cy + kr * .3f, p);
            c.drawLine(cx - kr * .1f, cy + kr * .3f, cx + kr * .42f, cy - kr * .3f, p);
            p.setStyle(Paint.Style.FILL);
        }
    }
}
