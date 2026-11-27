package io.encodal.idm;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.TextView;

/** Clean line icons for the player (drawn, not emoji, so they look the same on every phone). */
class Ico extends TextView {
    static final int PLAY = 1, PAUSE = 2, BACK10 = 3, FWD10 = 4, VOL = 5, MUTE = 6, LOCK = 7, UNLOCK = 8, FULL = 9, CC = 10, LIST = 11, PREV = 12, NEXT = 13, PIP = 14, TRACKS = 15,
            DOWNLOAD = 16, FOLDER = 17, GLOBE = 18, YT = 19, COG = 20, BACK = 21, MORE = 22, CHECK = 23, REFRESH = 24, CLOSE = 25, SEARCH = 26, SHARE = 27, TRASH = 28, INFO = 29, SPEED = 30, MOVIE = 31, PICTURE = 32, UPDATE = 33;
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

    Ico color(int c) {
        p.setColor(c);
        invalidate();
        return this;
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
            case DOWNLOAD:
                c.drawLine(cx, cy - s * .85f, cx, cy + s * .35f, p);
                path.reset();
                path.moveTo(cx - s * .5f, cy - s * .15f); path.lineTo(cx, cy + s * .4f); path.lineTo(cx + s * .5f, cy - s * .15f);
                c.drawPath(path, p);
                path.reset();
                path.moveTo(cx - s * .9f, cy + s * .35f); path.lineTo(cx - s * .9f, cy + s * .85f); path.lineTo(cx + s * .9f, cy + s * .85f); path.lineTo(cx + s * .9f, cy + s * .35f);
                c.drawPath(path, p);
                break;
            case FOLDER:
                path.reset();
                path.moveTo(cx - s * .95f, cy - s * .55f); path.lineTo(cx - s * .35f, cy - s * .55f); path.lineTo(cx - s * .1f, cy - s * .3f);
                path.lineTo(cx + s * .95f, cy - s * .3f); path.lineTo(cx + s * .95f, cy + s * .7f); path.lineTo(cx - s * .95f, cy + s * .7f);
                path.close();
                c.drawPath(path, p);
                break;
            case GLOBE:
                c.drawCircle(cx, cy, s * .9f, p);
                c.drawLine(cx - s * .9f, cy, cx + s * .9f, cy, p);
                c.drawOval(new RectF(cx - s * .4f, cy - s * .9f, cx + s * .4f, cy + s * .9f), p);
                break;
            case YT:
                c.drawRoundRect(new RectF(cx - s * 1.0f, cy - s * .7f, cx + s * 1.0f, cy + s * .7f), s * .35f, s * .35f, p);
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(cx - s * .25f, cy - s * .32f); path.lineTo(cx + s * .38f, cy); path.lineTo(cx - s * .25f, cy + s * .32f);
                path.close();
                c.drawPath(path, p);
                break;
            case COG: {
                c.drawCircle(cx, cy, s * .32f, p);
                for (int i = 0; i < 8; i++) {
                    double a = Math.PI / 4 * i;
                    c.drawLine(cx + (float) Math.cos(a) * s * .62f, cy + (float) Math.sin(a) * s * .62f, cx + (float) Math.cos(a) * s * .95f, cy + (float) Math.sin(a) * s * .95f, p);
                }
                c.drawCircle(cx, cy, s * .66f, p);
                break;
            }
            case BACK:
                path.reset();
                path.moveTo(cx + s * .25f, cy - s * .8f); path.lineTo(cx - s * .5f, cy); path.lineTo(cx + s * .25f, cy + s * .8f);
                c.drawPath(path, p);
                break;
            case MORE:
                p.setStyle(Paint.Style.FILL);
                for (int i = -1; i <= 1; i++) c.drawCircle(cx, cy + i * s * .6f, s * .13f, p);
                break;
            case CHECK:
                path.reset();
                path.moveTo(cx - s * .8f, cy); path.lineTo(cx - s * .2f, cy + s * .6f); path.lineTo(cx + s * .85f, cy - s * .55f);
                c.drawPath(path, p);
                break;
            case REFRESH: case UPDATE:
                c.drawArc(new RectF(cx - s * .8f, cy - s * .8f, cx + s * .8f, cy + s * .8f), -40, 280, false, p);
                path.reset();
                path.moveTo(cx + s * .85f, cy - s * .75f); path.lineTo(cx + s * .65f, cy - s * .2f); path.lineTo(cx + s * .15f, cy - s * .55f);
                c.drawPath(path, p);
                break;
            case CLOSE:
                c.drawLine(cx - s * .7f, cy - s * .7f, cx + s * .7f, cy + s * .7f, p);
                c.drawLine(cx + s * .7f, cy - s * .7f, cx - s * .7f, cy + s * .7f, p);
                break;
            case SEARCH:
                c.drawCircle(cx - s * .15f, cy - s * .15f, s * .6f, p);
                c.drawLine(cx + s * .3f, cy + s * .3f, cx + s * .85f, cy + s * .85f, p);
                break;
            case SHARE:
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx - s * .6f, cy, s * .24f, p);
                c.drawCircle(cx + s * .6f, cy - s * .6f, s * .24f, p);
                c.drawCircle(cx + s * .6f, cy + s * .6f, s * .24f, p);
                p.setStyle(Paint.Style.STROKE);
                c.drawLine(cx - s * .6f, cy, cx + s * .6f, cy - s * .6f, p);
                c.drawLine(cx - s * .6f, cy, cx + s * .6f, cy + s * .6f, p);
                break;
            case TRASH:
                c.drawLine(cx - s * .85f, cy - s * .5f, cx + s * .85f, cy - s * .5f, p);
                c.drawLine(cx - s * .3f, cy - s * .8f, cx + s * .3f, cy - s * .8f, p);
                path.reset();
                path.moveTo(cx - s * .6f, cy - s * .5f); path.lineTo(cx - s * .5f, cy + s * .85f); path.lineTo(cx + s * .5f, cy + s * .85f); path.lineTo(cx + s * .6f, cy - s * .5f);
                c.drawPath(path, p);
                break;
            case INFO:
                c.drawCircle(cx, cy, s * .9f, p);
                c.drawLine(cx, cy - s * .05f, cx, cy + s * .45f, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx, cy - s * .4f, s * .09f, p);
                break;
            case SPEED:
                c.drawArc(new RectF(cx - s * .9f, cy - s * .9f, cx + s * .9f, cy + s * .9f), 150, 240, false, p);
                c.drawLine(cx, cy + s * .1f, cx + s * .5f, cy - s * .45f, p);
                break;
            case MOVIE:
                c.drawRoundRect(new RectF(cx - s * .9f, cy - s * .7f, cx + s * .9f, cy + s * .7f), s * .2f, s * .2f, p);
                p.setStyle(Paint.Style.FILL);
                path.reset();
                path.moveTo(cx - s * .2f, cy - s * .35f); path.lineTo(cx + s * .35f, cy); path.lineTo(cx - s * .2f, cy + s * .35f);
                path.close();
                c.drawPath(path, p);
                break;
            case PICTURE:
                c.drawRoundRect(new RectF(cx - s * .9f, cy - s * .7f, cx + s * .9f, cy + s * .7f), s * .2f, s * .2f, p);
                path.reset();
                path.moveTo(cx - s * .8f, cy + s * .5f); path.lineTo(cx - s * .2f, cy - s * .1f); path.lineTo(cx + s * .2f, cy + s * .3f); path.lineTo(cx + s * .45f, cy + s * .05f); path.lineTo(cx + s * .8f, cy + s * .45f);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(cx + s * .45f, cy - s * .3f, s * .12f, p);
                break;
            default:
                break;
        }
    }
}
