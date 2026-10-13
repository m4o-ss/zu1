package com.example.bubblepop;

import android.graphics.PointF;

/**
 * Путь шариков — замкнутая спираль (как в Zuma).
 * Строится из сегментов кубических кривых Безье с равномерной параметризацией
 * по длине дуги, чтобы шарики двигались с постоянной скоростью.
 */
public class GamePath {

    /** Один сегмент кривой Безье с предвычисленной длиной. */
    private static class Cubic {
        final float x0, y0, cx1, cy1, cx2, cy2, x1, y1;
        float length;
        // lookup-таблица: t для доли длины
        final float[] tTable = new float[LOOKUP + 1];

        Cubic(float x0, float y0, float cx1, float cy1, float cx2, float cy2, float x1, float y1) {
            this.x0 = x0; this.y0 = y0;
            this.cx1 = cx1; this.cy1 = cy1;
            this.cx2 = cx2; this.cy2 = cy2;
            this.x1 = x1; this.y1 = y1;
        }

        static final int LOOKUP = 64;

        void measure() {
            float prevX = x0, prevY = y0, acc = 0f;
            tTable[0] = 0f;
            for (int i = 1; i <= LOOKUP; i++) {
                float t = (float) i / LOOKUP;
                PointF p = eval(t);
                acc += (float) Math.hypot(p.x - prevX, p.y - prevY);
                prevX = p.x; prevY = p.y;
                tTable[i] = acc;
            }
            length = acc;
        }

        PointF eval(float t) {
            float mt = 1f - t;
            float a = mt * mt * mt;
            float b = 3 * mt * mt * t;
            float c = 3 * mt * t * t;
            float d = t * t * t;
            return new PointF(
                    a * x0 + b * cx1 + c * cx2 + d * x1,
                    a * y0 + b * cy1 + c * cy2 + d * y1);
        }

        /** Возвращает точку на доле len/length от начала сегмента. */
        PointF atLength(float dist) {
            if (dist <= 0) return new PointF(x0, y0);
            if (dist >= length) return new PointF(x1, y1);
            // бинарный поиск по таблице длин
            int lo = 0, hi = LOOKUP;
            while (lo < hi - 1) {
                int mid = (lo + hi) >>> 1;
                if (tTable[mid] <= dist) lo = mid; else hi = mid;
            }
            float segLen = tTable[hi] - tTable[lo];
            float f = segLen > 0 ? (dist - tTable[lo]) / segLen : 0f;
            float t = (lo + f) / LOOKUP;
            return eval(t);
        }
    }

    private final Cubic[] segments;
    private final float[] cumLengths;
    private float totalLength;

    public GamePath(float w, float h) {
        // Строим спираль: вход сверху, несколько витков внутрь, центр — "нора".
        // Точки заданы в нормализованных координатах (0..1), масштабируются под экран.
        float[][] pts = {
                {-0.12f, 0.16f},   // старт за левым краем (отсюда въезжают шарики)
                {0.50f, 0.08f},
                {0.97f, 0.28f},
                {0.90f, 0.78f},
                {0.50f, 0.93f},
                {0.12f, 0.74f},
                {0.16f, 0.36f},
                {0.50f, 0.27f},
                {0.80f, 0.45f},
                {0.68f, 0.72f},
                {0.40f, 0.70f},
                {0.32f, 0.50f},
                {0.50f, 0.47f},   // финал — нора в центре
        };

        // Гладкая замкнутая... не замкнутая: открытая кривая через точки
        // (Catmull-Rom -> Безье). Для концов используем отражённые точки.
        int n = pts.length;
        segments = new Cubic[n - 1];
        float tension = 0.34f;

        for (int i = 0; i < n - 1; i++) {
            float[] pPrev = pts[Math.max(0, i - 1)];
            float[] p0 = pts[i];
            float[] p1 = pts[i + 1];
            float[] pNext = pts[Math.min(n - 1, i + 2)];

            float cx1 = p0[0] + (p1[0] - pPrev[0]) * tension / 2f;
            float cy1 = p0[1] + (p1[1] - pPrev[1]) * tension / 2f;
            float cx2 = p1[0] - (pNext[0] - p0[0]) * tension / 2f;
            float cy2 = p1[1] - (pNext[1] - p0[1]) * tension / 2f;

            segments[i] = new Cubic(
                    p0[0] * w, p0[1] * h,
                    cx1 * w, cy1 * h,
                    cx2 * w, cy2 * h,
                    p1[0] * w, p1[1] * h);
            segments[i].measure();
        }

        cumLengths = new float[n];
        cumLengths[0] = 0f;
        for (int i = 0; i < n - 1; i++) {
            cumLengths[i + 1] = cumLengths[i] + segments[i].length;
        }
        totalLength = cumLengths[n - 1];
    }

    public float getTotalLength() {
        return totalLength;
    }

    /** Точка на расстоянии dist от начала пути. dist<0 — продолжение за стартом. */
    public void getPoint(float dist, PointF out) {
        if (dist <= 0f) {
            // экстраполяция прямой по касательной в начале пути
            Cubic first = segments[0];
            float ex = first.x0 - first.cx1;
            float ey = first.y0 - first.cy1;
            float len = (float) Math.hypot(ex, ey);
            if (len < 0.001f) { ex = 1; ey = 0; len = 1; }
            out.set(first.x0 + ex / len * (-dist), first.y0 + ey / len * (-dist));
            return;
        }
        if (dist >= totalLength) {
            Cubic last = segments[segments.length - 1];
            out.set(last.x1, last.y1);
            return;
        }
        // найти сегмент
        int lo = 0, hi = segments.length - 1;
        while (lo < hi) {
            int mid = (lo + hi + 1) >>> 1;
            if (cumLengths[mid] <= dist) lo = mid; else hi = mid - 1;
        }
        PointF p = segments[lo].atLength(dist - cumLengths[lo]);
        out.set(p);
    }

    /** Тот же путь в виде android.graphics.Path для отрисовки дорожки. */
    public android.graphics.Path buildGraphicsPath() {
        android.graphics.Path gp = new android.graphics.Path();
        gp.moveTo(segments[0].x0, segments[0].y0);
        for (Cubic s : segments) {
            gp.cubicTo(s.cx1, s.cy1, s.cx2, s.cy2, s.x1, s.y1);
        }
        return gp;
    }
}
