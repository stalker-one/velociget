package io.encodal.idm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.TextView;

/** Clean line icons for the player (drawn, not emoji, so they look the same on every phone). */
class Ico extends TextView {
    static final int PLAY = 1, PAUSE = 2, BACK10 = 3, FWD10 = 4, VOL = 5, MUTE = 6, LOCK = 7, UNLOCK = 8, FULL = 9, CC = 10, LIST = 11, PREV = 12, NEXT = 13, PIP = 14, TRACKS = 15;
    private int kind;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    Ico(Context c, int kind) {
        super(c);
        this.kind = kind;
        p.setColor(0xFFFFFFFF);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    void setKind(int k) {
        if (k != kind) { kind = k; invalidate(); }
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight(), s = Math.min(w, h) * 0.46f, cx = w / 2, cy = h / 2;
        p.setStrokeWidth(Math.max(2f, s * 0.14f));
        p.setStyle(Paint.Style.STROKE);
        switch (kind) {
            case PLAY: case PREV: case NEXT: {
                p.setStyle(Paint.Style.FILL);
                path.reset();
                if (kind == PLAY) {
                    path.moveTo(cx - s * .5f, cy - s * .7f); path.lineTo(cx + s * .7f, cy); path.lineTo(cx - s * .5f, cy + s * .7f);
                } else if (kind == NEXT) {
                    path.moveTo(cx - s * .6f, cy - s * .6f); path.lineTo(cx + s * .3f, cy); path.lineTo(cx - s * .6f, cy + s * .6f);
                    c.drawRect(cx + s * .4f, cy - s * .6f, cx + s * .62f, cy + s * .6f, p);
                } else {
                    path.moveTo(cx + s * .6f, cy - s * .6f); path.lineTo(cx - s * .3f, cy); path.lineTo(cx + s * .6f, cy + s * .6f);
                    c.drawRect(cx - s * .62f, cy - s * .6f, cx - s * .4f, cy + s * .6f, p);
                }
                path.close();
                c.drawPath(path, p);
                break;
            }
            case PAUSE:
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(cx - s * .6f, cy - s * .7f, cx - s * .15f, cy + s * .7f), 4, 4, p);
                c.drawRoundRect(new RectF(cx + s * .15f, cy - s * .7f, cx + s * .6f, cy + s * .7f), 4, 4, p);
                break;
            case BACK10: case FWD10: {
                boolean f = kind == FWD10;
                RectF r = new RectF(cx - s * .85f, cy - s * .85f + s * .1f, cx + s * .85f, cy + s * .85f + s * .1f);
                c.drawArc(r, f ? -60 : -120, f ? 300 : -300, false, p);
                path.reset();
                float ax = cx + (f ? 1 : -1) * s * .43f, ay = cy + s * .1f - s * .8f;
                path.moveTo(ax, ay - s * .3f); path.lineTo(ax + (f ? 1 : -1) * s * .35f, ay + s * .05f); path.lineTo(ax - (f ? 1 : -1) * s * .05f, ay + s * .3f);
                p.setStyle(Paint.Style.FILL);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(s * .8f);
                p.setTextAlign(Paint.Align.CENTER);
                c.drawText("10", cx, cy + s * .42f, p);
                break;
            }
            case VOL: case MUTE: {
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(cx - s * .9f, cy - s * .3f); path.lineTo(cx - s * .45f, cy - s * .3f); path.lineTo(cx, cy - s * .75f);
                path.lineTo(cx, cy + s * .75f); path.lineTo(cx - s * .45f, cy + s * .3f); path.lineTo(cx - s * .9f, cy + s * .3f);
                path.close();
                c.drawPath(path, p);
                p.setStyle(Paint.Style.STROKE);
                if (kind == VOL) {
                    c.drawArc(new RectF(cx - s * .3f, cy - s * .45f, cx + s * .35f, cy + s * .45f), -50, 100, false, p);
                    c.drawArc(new RectF(cx - s * .3f, cy - s * .85f, cx + s * .75f, cy + s * .85f), -50, 100, false, p);
                } else {
                    c.drawLine(cx + s * .25f, cy - s * .4f, cx + s * .95f, cy + s * .4f, p);
                    c.drawLine(cx + s * .95f, cy - s * .4f, cx + s * .25f, cy + s * .4f, p);
                }
                break;
            }
            case LOCK: case UNLOCK:
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(cx - s * .7f, cy - s * .1f, cx + s * .7f, cy + s * .8f), s * .15f, s * .15f, p);
                p.setStyle(Paint.Style.STROKE);
                if (kind == LOCK) c.drawArc(new RectF(cx - s * .45f, cy - s * .85f, cx + s * .45f, cy + s * .2f), 180, 180, false, p);
                else c.drawArc(new RectF(cx - s * .2f, cy - s * .85f, cx + s * .7f, cy + s * .2f), 180, 180, false, p);
                break;
            case FULL: {
                float a = s * .85f, l = s * .45f;
                for (int sx = -1; sx <= 1; sx += 2) for (int sy = -1; sy <= 1; sy += 2) {
                    c.drawLine(cx + sx * a, cy + sy * a, cx + sx * (a - l), cy + sy * a, p);
                    c.drawLine(cx + sx * a, cy + sy * a, cx + sx * a, cy + sy * (a - l), p);
                }
                break;
            }
            case CC: {
                c.drawRoundRect(new RectF(cx - s * .95f, cy - s * .7f, cx + s * .95f, cy + s * .7f), s * .25f, s * .25f, p);
                p.setStyle(Paint.Style.FILL);
                p.setTextSize(s * .9f);
                p.setTextAlign(Paint.Align.CENTER);
                p.setFakeBoldText(true);
                c.drawText("CC", cx, cy + s * .3f, p);
                p.setFakeBoldText(false);
                break;
            }
            case LIST:
                for (int i = -1; i <= 1; i++) {
                    c.drawLine(cx - s * .2f, cy + i * s * .55f, cx + s * .9f, cy + i * s * .55f, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx - s * .75f, cy + i * s * .55f, s * .1f, p);
                    p.setStyle(Paint.Style.STROKE);
                }
                break;
            case PIP:
                c.drawRoundRect(new RectF(cx - s * .95f, cy - s * .7f, cx + s * .95f, cy + s * .7f), s * .2f, s * .2f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawRoundRect(new RectF(cx + s * .1f, cy + s * .05f, cx + s * .8f, cy + s * .55f), s * .1f, s * .1f, p);
                break;
            case TRACKS:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - s * .4f, cy + s * .45f, s * .3f, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(cx - s * .1f, cy + s * .45f, cx - s * .1f, cy - s * .8f, p);
                c.drawLine(cx - s * .1f, cy - s * .8f, cx + s * .7f, cy - s * .5f, p);
                break;
            default:
                break;
        }
    }
}
