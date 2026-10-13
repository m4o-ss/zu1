package com.example.bubblepop;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Игровое поле в стиле Zuma: цепочка цветных шариков катится по спирали
 * к "норе" в центре. Игрок целится и стреляет шариками из пушки внизу экрана.
 * Три и более шарика одного цвета подряд — лопаются, возможны комбо-цепочки.
 *
 * Архитектура (исправления мерцания/шлейфа):
 *  - один поток (game-loop) отвечает и за логику, и за отрисовку;
 *  - все игровые коллекции guarded объектом gameLock;
 *  - поверхность рисуется полностью каждый кадр (фон закрывает весь экран),
 *    canvas отдаётся через lockCanvas(null) — корректная двойная буферизация;
 *  - геометрия пересобирается только на UI-потоке в surfaceChanged
 *    (никаких рестартов уровня из потока отрисовки).
 */
public class GameView extends SurfaceView implements Runnable {

    // ===== Состояния игры =====
    static final int STATE_READY = 0;   // ожидание старта (тап — начать)
    static final int STATE_PLAYING = 1;
    static final int STATE_PAUSED = 2;
    static final int STATE_GAME_OVER = 3;
    static final int STATE_LEVEL_DONE = 4;

    // ===== Цвета шариков =====
    static final int[] BALL_COLORS = {
            Color.rgb(244, 67, 54),   // красный
            Color.rgb(33, 150, 243),  // синий
            Color.rgb(255, 235, 59),  // жёлтый
            Color.rgb(76, 175, 80),   // зелёный
            Color.rgb(156, 39, 176),  // фиолетовый
            Color.rgb(255, 152, 0),   // оранжевый
    };

    /** Единый монитор игрового состояния: логика (game-loop) и ввод (UI-поток). */
    private final Object gameLock = new Object();

    private volatile int state = STATE_READY;
    private volatile boolean running = false;

    // Геометрия (пересобирается только на UI-потоке в surfaceChanged)
    private volatile float width, height;
    private volatile Path track;                    // визуальный путь (android.graphics.Path)
    private volatile GamePath gamePath;             // путь с равномерной параметризацией по длине
    private volatile float trackWidth;     // ширина "дорожки"
    private volatile float ballRadius;

    // Цепочка шариков — доступ строго под gameLock
    private final List<Ball> chain = new ArrayList<>();
    private final List<Projectile> projectiles = new ArrayList<>();
    private final List<Effect> effects = new ArrayList<>();

    // Пушка (позиция — геометрия; угол/цвета меняются из двух потоков)
    private final PointF cannonPos = new PointF();
    private volatile float cannonAngle = -((float) Math.PI / 2f);
    private volatile int nextColor = 0;
    private volatile int currentColor = 0;

    // Игровая статистика — доступ под gameLock
    private int level = 1;
    private int score = 0;
    private long highScore = 0;
    private int lives = 3;
    private int ballsToSpawn;
    private int colorsUsed = 4;             // сколько цветов задействовано на уровне
    private float spawnTimer = 0f;
    private float chainSpeed;              // px/сек скорость движения цепочки
    private float spawnGap;                // дистанция между шариками при появлении

    // Эффект тряски экрана
    private volatile float shakeTime = 0f;

    // Отрисовка
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** Потокобезопасный источник случайных чисел (логика + выстрелы из разных потоков). */
    private static final class SafeRandom extends Random {
        @Override public synchronized int nextInt(int bound) { return super.nextInt(bound); }
        @Override public synchronized float nextFloat() { return super.nextFloat(); }
    }
    private final Random random = new SafeRandom();
    private SoundManager sound;

    private long lastFrameNs = 0;

    /** Шарик в цепочке. dist — положение вдоль пути (px от начала). */
    private static class Ball {
        float dist;
        int color;      // индекс в BALL_COLORS
        final PointF pos = new PointF();
        float recoil;   // остаток "пробуксовки" после попадания (px, тормозится со временем)
        Ball(float dist, int color) {
            this.dist = dist;
            this.color = color;
        }
    }

    /** Летящий шарик, выпущенный из пушки. */
    private static class Projectile {
        float x, y, vx, vy;
        int color;
        float radius;
    }

    /** Визуальный эффект лопания. */
    private static class Effect {
        float x, y, t;           // t — время жизни 0..1
        int color;
        float r;
    }

    public GameView(Context context) {
        super(context);
        getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override public void surfaceCreated(SurfaceHolder holder) {
                // Если поток уже жив (например, поверхность пересоздалась) — не запускаем второй
                if (gameThread == null || !gameThread.isAlive()) {
                    gameThread = new Thread(GameView.this, "game-loop");
                    gameThread.start();
                }
                running = true;
                lastFrameNs = System.nanoTime();
            }
            @Override public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
                // Пересборка геометрии — ТОЛЬКО на UI-потоке и только при реальном
                // изменении размера. В v1.2 этот же код выполнялся ещё и из потока
                // отрисовки (draw), из-за чего уровень бесконечно перезапускался
                // ("экран моргал"). Теперь здесь один источник истины.
                if (w > 1 && h > 1 && (w != width || h != height)) {
                    buildGeometry(w, h);
                    synchronized (gameLock) {
                        if (state == STATE_PLAYING) startLevel(level);
                    }
                }
            }
            @Override public void surfaceDestroyed(SurfaceHolder holder) {
                running = false;
                if (gameThread != null) {
                    try {
                        gameThread.join(1000);
                    } catch (InterruptedException ignored) {}
                    gameThread = null;
                }
            }
        });
        sound = new SoundManager(context);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private volatile Thread gameThread;

    public long getHighScore() {
        return highScore;
    }

    public void setHighScore(long hs) {
        if (hs > 0) highScore = hs;
    }

    /** Возобновление после сворачивания: сбрасываем таймер кадра, чтобы не было скачка dt. */
    public void resumeGame() {
        lastFrameNs = System.nanoTime();
    }

    // =====================================================================
    //  Инициализация геометрии под размер экрана
    // =====================================================================
    private void buildGeometry(float w, float h) {
        width = w;
        height = h;
        trackWidth = Math.min(w, h) * 0.115f;
        ballRadius = trackWidth * 0.47f;

        gamePath = new GamePath(w, h);
        track = gamePath.buildGraphicsPath();

        cannonPos.set(w / 2f, h - trackWidth * 1.1f);
    }

    // =====================================================================
    //  Запуск уровня (вызывать под gameLock)
    // =====================================================================
    private void startLevel(int lvl) {
        chain.clear();
        projectiles.clear();
        effects.clear();

        ballsToSpawn = 28 + lvl * 8;
        colorsUsed = Math.min(BALL_COLORS.length, 3 + lvl / 2);
        chainSpeed = (Math.min(width, height)) * (0.055f + lvl * 0.012f); // px/сек
        spawnGap = ballRadius * 2.05f;

        // Стартовая группа шариков уже на пути
        float dist = spawnGap;
        int initial = 8 + lvl * 2;
        for (int i = 0; i < initial && ballsToSpawn > 0; i++) {
            int c = random.nextInt(colorsUsed);
            chain.add(new Ball(dist, c));
            if (random.nextFloat() < 0.45f && ballsToSpawn > 1) {
                // иногда добавляем пару одинаковых цветов, чтобы были шансы на матч
                chain.add(new Ball(dist + spawnGap, c));
                dist += spawnGap * 2;
                ballsToSpawn -= 2;
            } else {
                dist += spawnGap;
                ballsToSpawn -= 1;
            }
        }

        nextColor = pickColorFromChain(colorsUsed);
        currentColor = nextColor;
        nextColor = pickColorFromChain(colorsUsed);

        state = STATE_PLAYING;
    }

    /** Цвет шарика, который есть в цепочке (или случайный, если цепочка пуста). */
    private int pickColorFromChain(int colorsUsed) {
        if (!chain.isEmpty()) {
            Ball b = chain.get(random.nextInt(chain.size()));
            return b.color;
        }
        return random.nextInt(Math.min(BALL_COLORS.length, colorsUsed));
    }

    // =====================================================================
    //  Основной игровой цикл
    // =====================================================================
    @Override
    public void run() {
        try {
            while (running) {
                long now = System.nanoTime();
                float dt = Math.min(0.05f, (now - lastFrameNs) / 1_000_000_000f);
                if (dt < 0f) dt = 0f;
                lastFrameNs = now;

                Canvas canvas = null;
                synchronized (getHolder()) {
                    if (getHolder().getSurface().isValid()) {
                        // lockCanvas(null): корректная двойная буферизация —
                        // каждый кадр рисуется заново поверх всего предыдущего,
                        // поэтому "шлейфов" за прицельной линией не остаётся.
                        canvas = getHolder().lockCanvas(null);
                    }
                }
                if (canvas != null) {
                    // Кадр: сначала физика/коллизии, затем отрисовка актуальных позиций.
                    synchronized (gameLock) {
                        if (state == STATE_PLAYING) {
                            update(dt);
                        }
                        renderFrame(canvas);
                    }
                    getHolder().unlockCanvasAndPost(canvas);
                } else {
                    try { Thread.sleep(30); } catch (InterruptedException ignored) {}
                }

                // стабильный темп ~60 fps без наложения кадров
                long frameMs = (System.nanoTime() - now) / 1_000_000L;
                if (frameMs < 16) {
                    try { Thread.sleep(16 - frameMs); } catch (InterruptedException ignored) {}
                }
            }
        } catch (Throwable t) {
            android.util.Log.e("BubblePop", "FATAL: падение игрового потока", t);
        }
    }

    // =====================================================================
    //  Логика обновления
    // =====================================================================
    /** Вспомогательные точки (единый поток логики и отрисовки). */
    private final PointF tmpPoint = new PointF();
    private final PointF logicPoint = new PointF();
    private final PointF aimTangent = new PointF();

    private void update(float dt) {
        GamePath gp = gamePath; // volatile-снимок: защита от гонки с пересборкой геометрии
        if (gp == null) return;
        float total = gp.getTotalLength();
        if (total <= 0f) return;
        float gap = ballRadius * 2f;

        // 1. Движение цепочки вперёд (к норе). Шарики с активным "откатом"
        //    после попадания стоят/тормозятся — как пробуксовка в Zuma.
        for (Ball b : chain) {
            if (b.recoil > 0f) {
                float back = Math.min(b.recoil, chainSpeed * dt);
                b.dist -= back;
                b.recoil -= back;
            } else {
                b.dist += chainSpeed * dt;
            }
            if (Float.isNaN(b.dist) || Float.isInfinite(b.dist)) b.dist = 0f;
        }

        // 2. Выпуск новых шариков с хвоста: они появляются ЗА левым краем экрана
        //    и въезжают на трассу, как в оригинальной Zuma.
        if (ballsToSpawn > 0 && chain.size() < 200) {
            spawnTimer -= dt;
            if (spawnTimer <= 0f) {
                Ball newest = chain.isEmpty() ? null : chain.get(chain.size() - 1);
                float nd = (newest == null)
                        ? -ballRadius * 6f
                        : Math.min(newest.dist - gap, -gap);
                chain.add(new Ball(nd, pickColorFromChain(colorsUsed)));
                ballsToSpawn--;
                spawnTimer = 0.12f;
            }
        }

        // 3. Разъезд шариков: расстояние между соседями строго spawnGap..2*spawnGap.
        //    Проход от головы к хвосту прижимает шарики вплотную друг к другу
        //    (устраняет "налезание" соседей после попаданий и разрывы цепочки).
        for (int i = 1; i < chain.size(); i++) {
            Ball prev = chain.get(i - 1), cur = chain.get(i);
            float d = prev.dist - cur.dist;
            if (d < spawnGap) cur.dist = prev.dist - spawnGap;
            else if (d > spawnGap * 2f) cur.dist = prev.dist - spawnGap * 2f;
        }

        // 4. Снаряды и эффекты
        updateProjectiles(dt);
        updateEffects(dt);

        if (shakeTime > 0) shakeTime -= dt;

        // 5. Проверка поражения: первый шарик дошёл до норы
        if (!chain.isEmpty() && chain.get(0).dist >= gp.getTotalLength()) {
            loseLife();
        }
    }

    private void updateProjectiles(float dt) {
        GamePath gp = gamePath;
        if (gp == null) return;

        // Актуальные экранные позиции цепочки — ДО проверки столкновений,
        // иначе снаряд может "пролететь" сквозь шарики из-за устаревших pos.
        for (Ball b : chain) {
            gp.getPoint(b.dist, b.pos);
            if (Float.isNaN(b.pos.x) || Float.isNaN(b.pos.y)) b.pos.set(-1000f, -1000f);
        }

        for (int i = projectiles.size() - 1; i >= 0; i--) {
            Projectile p = projectiles.get(i);
            // шаг меньше радиуса, чтобы не "проскакивать" шарики
            float remaining = (float) Math.hypot(p.vx, p.vy) * dt;
            float step = ballRadius * 0.5f;
            int steps = Math.max(1, (int) Math.ceil(remaining / step));
            float sdt = dt / steps;
            boolean removed = false;
            for (int s = 0; s < steps && !removed; s++) {
                p.x += p.vx * sdt;
                p.y += p.vy * sdt;

                // вне экрана?
                if (p.x < -p.radius || p.x > width + p.radius ||
                    p.y < -p.radius || p.y > height + p.radius) {
                    projectiles.remove(i);
                    removed = true;
                    break;
                }

                // столкновение с шариком цепочки?
                int hit = findBallAt(p.x, p.y, p.radius + ballRadius * 0.92f);
                if (hit >= 0) {
                    insertBall(hit, p);
                    projectiles.remove(i);
                    removed = true;
                }
            }
        }
    }

    /** Индекс ближайшего шарика цепочки, чей центр в радиусе r от (x,y); -1 если нет. */
    private int findBallAt(float x, float y, float r) {
        int best = -1;
        float bestD = r * r;
        for (int i = 0; i < chain.size(); i++) {
            Ball b = chain.get(i);
            float dx = b.pos.x - x, dy = b.pos.y - y;
            float d2 = dx * dx + dy * dy;
            if (d2 < bestD) {
                bestD = d2;
                best = i;
            }
        }
        return best;
    }

    /** Вставить выстреленный шарик рядом с ударным и разрешить матч. */
    private void insertBall(int hitIdx, Projectile p) {
        Ball hit = chain.get(hitIdx);
        // Определяем сторону удара по касательной пути в точке шарика:
        // если снаряд летит "по ходу" цепочки — вставляем ПЕРЕД ударным, иначе — ПОСЛЕ.
        GamePath gpI = gamePath;
        if (gpI == null) return;
        gpI.getTangent(hit.dist, aimTangent);
        float dx = p.x - hit.pos.x, dy = p.y - hit.pos.y;
        float dot = dx * aimTangent.x + dy * aimTangent.y;
        int idx = dot >= 0 ? hitIdx : hitIdx + 1; // позиция вставки в списке
        float newDist = (dot >= 0) ? hit.dist + spawnGap : hit.dist - spawnGap;

        chain.add(idx, new Ball(newDist, p.color));

        // Эффект "пробуксовки": короткий импульс назад вокруг точки вставки
        // (цепочка заметно тормозится — как в оригинальной Zuma).
        for (int i = Math.max(0, idx - 3); i < Math.min(chain.size(), idx + 4); i++) {
            Ball b = chain.get(i);
            b.recoil = ballRadius * 0.5f;
        }

        resolveMatches(idx);
    }

    /**
     * Поиск и удаление МАТЧЕЙ рядом с индексом idx.
     * Лопается ТОЛЬКО группа из 3+ подряд идущих шариков одного цвета;
     * соседние группы других цветов остаются на месте. Поддержка комбо-цепочек:
     * после удаления проверяется стык двух половин цепочки.
     */
    private void resolveMatches(int idx) {
        boolean any = true;
        int combo = 0;
        while (any) {
            any = false;
            if (chain.isEmpty()) break;
            if (idx >= chain.size()) idx = chain.size() - 1;
            if (idx < 0) idx = 0;

            int c = chain.get(idx).color;
            int left = idx, right = idx;
            while (left - 1 >= 0 && chain.get(left - 1).color == c) left--;
            while (right + 1 < chain.size() && chain.get(right + 1).color == c) right++;
            int len = right - left + 1;
            if (len >= 3) {
                popRange(left, right, len, ++combo);
                idx = Math.min(left, chain.size() - 1); // проверяем стык после схлопывания
                any = true;
            }
        }
        if (combo > 0) {
            sound.playChain();
        }
    }

    /** Удалить шарики [left..right] с эффектами и очками. */
    private void popRange(int left, int right, int count, int combo) {
        int base = count * 10 * combo;
        score += base;
        if (score > highScore) highScore = score;

        for (int i = left; i <= right; i++) {
            Ball b = chain.get(i);
            Effect e = new Effect();
            e.x = b.pos.x; e.y = b.pos.y; e.t = 0f;
            e.color = BALL_COLORS[b.color]; e.r = ballRadius;
            effects.add(e);
        }
        for (int i = right; i >= left; i--) chain.remove(i);

        sound.playPop(combo);
        if (count >= 5) shakeTime = 0.25f;

        // уровень пройден, когда все шарики выпущены и цепочка пуста
        if (chain.isEmpty() && ballsToSpawn <= 0) {
            score += 500 + level * 100; // бонус за уровень
            if (score > highScore) highScore = score;
            state = STATE_LEVEL_DONE;
        }
    }

    private void loseLife() {
        lives--;
        shakeTime = 0.5f;
        sound.playGameOver();
        if (lives <= 0) {
            if (score > highScore) highScore = score;
            state = STATE_GAME_OVER;
        } else {
            GamePath gp = gamePath;
            float total = gp != null ? gp.getTotalLength() : 0f;
            if (Float.isNaN(total) || total <= 0f) total = Math.min(width, height) * 4f;
            // откатываем цепочку назад как в оригинальной Зуме
            for (Ball b : chain) {
                b.dist -= total * 0.25f;
            }
        }
    }

    private void updateEffects(float dt) {
        for (int i = effects.size() - 1; i >= 0; i--) {
            Effect e = effects.get(i);
            e.t += dt * 3f;
            if (e.t >= 1f) effects.remove(i);
        }
    }

    // =====================================================================
    //  Отрисовка
    // =====================================================================
    private void renderFrame(Canvas canvas) {
        // Геометрия НЕ пересобирается здесь (см. surfaceChanged): в v1.2 эта
        // проверка в потоке отрисовки конфликтовала с UI-потоком и вызывала
        // бесконечные рестарты уровня — "экран моргал".
        GamePath gp = gamePath;
        Path tr = track;
        if (width <= 1 || gp == null || tr == null) return;

        // фон-градиент
        drawBackground(canvas);

        float sx = 0, sy = 0;
        if (shakeTime > 0) {
            sx = (random.nextFloat() - 0.5f) * 14f;
            sy = (random.nextFloat() - 0.5f) * 14f;
        }
        canvas.save();
        canvas.translate(sx, sy);

        // Дорожка
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(trackWidth);
        paint.setColor(Color.argb(70, 0, 0, 0));
        canvas.drawPath(tr, paint);
        paint.setStrokeWidth(trackWidth * 0.82f);
        paint.setColor(Color.argb(60, 255, 255, 255));
        canvas.drawPath(tr, paint);

        // Нора в конце пути
        gp.getPoint(gp.getTotalLength(), tmpPoint);
        drawLair(canvas, tmpPoint.x, tmpPoint.y);

        // Позиции шариков уже актуальны (пересчитаны в update). Для состояний
        // вне игры (пауза, оверлеи) update не запускается — синхронизируем тут.
        if (state != STATE_PLAYING) {
            for (Ball b : chain) {
                gp.getPoint(b.dist, b.pos);
                if (Float.isNaN(b.pos.x) || Float.isNaN(b.pos.y)) {
                    b.pos.set(-1000f, -1000f);
                }
            }
        }

        // Шарики (рисуем от хвоста к голове)
        for (int i = chain.size() - 1; i >= 0; i--) {
            Ball b = chain.get(i);
            if (b.dist < -ballRadius * 4f) continue; // ещё не виден
            drawBall(canvas, b.pos.x, b.pos.y, ballRadius, BALL_COLORS[b.color]);
        }

        // Снаряды
        for (Projectile p : projectiles) {
            drawBall(canvas, p.x, p.y, p.radius, BALL_COLORS[p.color]);
        }

        // Эффекты лопания
        for (Effect e : effects) {
            drawPopEffect(canvas, e);
        }

        // Прицельная линия
        if (state == STATE_PLAYING) {
            drawAimLine(canvas);
            drawCannon(canvas);
        }

        canvas.restore();

        // HUD
        drawHud(canvas);

        // Оверлеи состояний
        switch (state) {
            case STATE_READY: drawOverlay(canvas, "BUBBLE POP",
                    "Лопай шарики одного цвета!\nТапни, чтобы начать", "УРОВЕНЬ " + level); break;
            case STATE_PAUSED: drawOverlay(canvas, "ПАУЗА", "Тапни, чтобы продолжить", null); break;
            case STATE_GAME_OVER: drawOverlay(canvas, "ИГРА ОКОНЧЕНА",
                    "Счёт: " + score + "\nРекорд: " + highScore + "\nТапни — заново", null); break;
            case STATE_LEVEL_DONE: drawOverlay(canvas, "УРОВЕНЬ ПРОЙДЕН!",
                    "Счёт: " + score + "\nТапни — следующий уровень", null); break;
        }
    }

    /** Кешированные Paint/шейдеры для статичных слоёв (фон, оверлей). */
    private final Paint bgPaint = new Paint();
    private float bgCacheW = -1f, bgCacheH = -1f;

    private void drawBackground(Canvas canvas) {
        // фон закрашивает ВЕСЬ кадр — это обязательное условие корректной
        // двойной буферизации SurfaceView (никаких остатков предыдущего кадра)
        if (bgCacheW != width || bgCacheH != height) {
            bgPaint.setShader(new RadialGradient(width / 2f, height * 0.45f,
                    Math.max(width, height),
                    Color.rgb(30, 40, 90), Color.rgb(6, 8, 28), Shader.TileMode.CLAMP));
            bgCacheW = width; bgCacheH = height;
        }
        canvas.drawRect(0, 0, width, height, bgPaint);
    }

    private void drawLair(Canvas canvas, float x, float y) {
        float r = trackWidth * 0.75f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(20, 12, 8));
        canvas.drawCircle(x, y, r, paint);
        paint.setColor(Color.rgb(70, 45, 25));
        canvas.drawCircle(x, y, r * 0.72f, paint);
        paint.setColor(Color.BLACK);
        canvas.drawCircle(x, y, r * 0.5f, paint);
    }

    private void drawBall(Canvas canvas, float x, float y, float r, int color) {
        // тело шарика с бликом (radial gradient)
        int light = blend(color, Color.WHITE, 0.55f);
        int dark = blend(color, Color.BLACK, 0.45f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(x - r * 0.35f, y - r * 0.35f, r * 1.6f,
                new int[]{light, color, dark},
                new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(x, y, r, paint);
        paint.setShader(null);
        // контур
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1.5f, r * 0.08f));
        paint.setColor(blend(color, Color.BLACK, 0.6f));
        canvas.drawCircle(x, y, r, paint);
        // блик
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(120, 255, 255, 255));
        canvas.drawCircle(x - r * 0.32f, y - r * 0.38f, r * 0.2f, paint);
    }

    private static int blend(int c1, int c2, float f) {
        int r = (int) (Color.red(c1) * (1 - f) + Color.red(c2) * f);
        int g = (int) (Color.green(c1) * (1 - f) + Color.green(c2) * f);
        int b = (int) (Color.blue(c1) * (1 - f) + Color.blue(c2) * f);
        return Color.rgb(r, g, b);
    }

    private void drawPopEffect(Canvas canvas, Effect e) {
        float r = e.r * (0.6f + e.t * 1.6f);
        int alpha = (int) (200 * (1f - e.t));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(e.r * 0.25f * (1f - e.t) + 1f);
        paint.setColor(Color.argb(alpha,
                Color.red(e.color), Color.green(e.color), Color.blue(e.color)));
        canvas.drawCircle(e.x, e.y, r, paint);
        // осколки
        for (int k = 0; k < 6; k++) {
            double ang = k * Math.PI / 3 + e.t * 2;
            float d = e.r * (0.5f + e.t * 2.2f);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(e.x + (float) Math.cos(ang) * d,
                    e.y + (float) Math.sin(ang) * d,
                    e.r * 0.16f * (1f - e.t), paint);
        }
    }

    private void drawAimLine(Canvas canvas) {
        GamePath gp = gamePath;
        if (gp == null) return;
        // Ищем точку пересечения луча пушки с трассой — прицельная линия
        // заканчивается на дорожке, а не уходит за край экрана.
        float cx = cannonPos.x, cy = cannonPos.y;
        float ax = (float) Math.cos(cannonAngle), ay = (float) Math.sin(cannonAngle);
        float maxLen = (float) Math.hypot(cx, cy) + height;
        float hitLen = -1f;
        float bestD = Float.MAX_VALUE;
        for (Ball b : chain) {
            // расстояние от центра шарика до луча
            float vx = b.pos.x - cx, vy = b.pos.y - cy;
            float along = vx * ax + vy * ay;
            if (along <= 0f) continue;
            float perp = Math.abs(vx * ay - vy * ax);
            if (perp < ballRadius * 1.4f && along < bestD) {
                bestD = along;
                hitLen = along;
            }
        }
        if (hitLen < 0f) {
            // нет цепочки на линии — рисуем до верхней границы игрового поля
            hitLen = Math.min(maxLen, cy / Math.max(0.2f, -ay));
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(4f);
        // мягкий градиент прозрачности к концу линии
        paint.setShader(new android.graphics.LinearGradient(cx, cy,
                cx + ax * hitLen, cy + ay * hitLen,
                new int[]{Color.argb(150, 255, 255, 255), Color.argb(20, 255, 255, 255)},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawLine(cx, cy, cx + ax * hitLen, cy + ay * hitLen, paint);
        paint.setShader(null);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    private void drawCannon(Canvas canvas) {
        float r = trackWidth * 0.62f;
        // ствол
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(60, 60, 70));
        canvas.save();
        canvas.rotate((float) Math.toDegrees(cannonAngle) + 90f, cannonPos.x, cannonPos.y);
        RectF barrel = new RectF(cannonPos.x - r * 0.32f, cannonPos.y - r * 1.5f,
                cannonPos.x + r * 0.32f, cannonPos.y);
        canvas.drawRoundRect(barrel, r * 0.3f, r * 0.3f, paint);
        canvas.restore();
        // база
        paint.setColor(Color.rgb(90, 90, 105));
        canvas.drawCircle(cannonPos.x, cannonPos.y, r, paint);
        // заряженный шарик
        drawBall(canvas, cannonPos.x, cannonPos.y, r * 0.72f, BALL_COLORS[currentColor]);
        // следующий шарик сбоку
        drawBall(canvas, cannonPos.x + r * 1.6f, cannonPos.y, r * 0.5f, BALL_COLORS[nextColor]);
        textPaint.setTextSize(r * 0.5f);
        canvas.drawText("→", cannonPos.x + r * 1.05f, cannonPos.y + r * 0.18f, textPaint);
    }

    private void drawHud(Canvas canvas) {
        float pad = trackWidth * 0.4f;
        textPaint.setColor(Color.WHITE);
        textPaint.setTextAlign(Paint.Align.LEFT);
        float ts = trackWidth * 0.42f;
        textPaint.setTextSize(ts);
        canvas.drawText("Уровень " + level, pad, pad + ts, textPaint);
        canvas.drawText("Счёт: " + score, pad, pad + ts * 2.2f, textPaint);

        // жизни сердечками справа
        textPaint.setTextAlign(Paint.Align.RIGHT);
        StringBuilder hearts = new StringBuilder();
        for (int i = 0; i < lives; i++) hearts.append("♥ ");
        canvas.drawText(hearts.toString().trim(), width - pad, pad + ts, textPaint);
        canvas.drawText("Рекорд: " + highScore, width - pad, pad + ts * 2.2f, textPaint);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void drawOverlay(Canvas canvas, String title, String subtitle, String badge) {
        canvas.drawColor(Color.argb(170, 0, 0, 0));
        float cx = width / 2f, cy = height / 2f;
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(trackWidth * 0.75f);
        canvas.drawText(title, cx, cy - trackWidth * 0.6f, textPaint);
        textPaint.setTextSize(trackWidth * 0.38f);
        String[] lines = subtitle.split("\n");
        float yy = cy + trackWidth * 0.4f;
        for (String l : lines) {
            canvas.drawText(l, cx, yy, textPaint);
            yy += trackWidth * 0.55f;
        }
        if (badge != null) {
            textPaint.setTextSize(trackWidth * 0.45f);
            canvas.drawText(badge, cx, cy - trackWidth * 1.5f, textPaint);
        }
    }

    // =====================================================================
    //  Управление
    // =====================================================================
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                if (state == STATE_PLAYING) {
                    // не даём пальцу возле самой пушки "ломать" прицел
                    if (Math.hypot(x - cannonPos.x, y - cannonPos.y) > trackWidth * 0.6f) {
                        float a = (float) Math.atan2(y - cannonPos.y, x - cannonPos.x);
                        // вектор вниз — зеркалим вверх, затем ограничиваем верхней полусферой
                        if (a > 0) a = -a;
                        cannonAngle = Math.max(-(float) Math.PI + 0.1f,
                                Math.min(-0.1f, a));
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
                handleTap(x, y);
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                // второй палец — смена шарика
                synchronized (gameLock) {
                    if (state == STATE_PLAYING) swapBalls();
                }
                break;
        }
        return true;
    }

    private void handleTap(float x, float y) {
        // Все изменения игрового состояния — под тем же монитором, что и игровой цикл
        synchronized (gameLock) {
            switch (state) {
                case STATE_READY:
                    level = 1; score = 0; lives = 3;
                    startLevel(level);
                    break;
                case STATE_PLAYING:
                    shoot();
                    break;
                case STATE_PAUSED:
                    state = STATE_PLAYING;
                    lastFrameNs = System.nanoTime();
                    break;
                case STATE_GAME_OVER:
                    level = 1; score = 0; lives = 3;
                    startLevel(level);
                    break;
                case STATE_LEVEL_DONE:
                    level++;
                    startLevel(level);
                    break;
            }
        }
    }

    private void swapBalls() {
        int t = currentColor;
        currentColor = nextColor;
        nextColor = t;
    }

    /** Выстрел (вызывать под gameLock — меняет chain-цветовую ротацию). */
    private void shoot() {
        Projectile p = new Projectile();
        p.color = currentColor;
        p.radius = ballRadius * 0.95f;
        float muzzle = trackWidth * 0.7f;
        p.x = cannonPos.x + (float) Math.cos(cannonAngle) * muzzle;
        p.y = cannonPos.y + (float) Math.sin(cannonAngle) * muzzle;
        float speed = Math.min(width, height) * 1.6f;
        p.vx = (float) Math.cos(cannonAngle) * speed;
        p.vy = (float) Math.sin(cannonAngle) * speed;
        projectiles.add(p);

        currentColor = nextColor;
        nextColor = pickColorFromChain(colorsUsed);
        sound.playShoot();
    }

    public void pauseGame() {
        synchronized (gameLock) {
            if (state == STATE_PLAYING) state = STATE_PAUSED;
        }
    }

    public void releaseSound() {
        sound.release();
    }
}
