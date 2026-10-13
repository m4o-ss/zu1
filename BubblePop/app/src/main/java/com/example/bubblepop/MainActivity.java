package com.example.bubblepop;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "BubblePop";
    private static final String PREFS = "bubble_pop_prefs";
    private static final String KEY_HIGH_SCORE = "high_score";

    private GameView gameView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Ловим любые исключения на самых ранних этапах запуска, чтобы
        // понять причину, если игра всё же падает (пишем в logcat и не крашимся).
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Log.e(TAG, "FATAL: необработанное исключение в потоке " + t.getName(), e);
            try {
                android.os.Process.killProcess(android.os.Process.myPid());
            } finally {
                System.exit(10);
            }
        });

        super.onCreate(savedInstanceState);
        try {
            // держим экран включённым во время игры
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            gameView = new GameView(this);
            long high = getSharedPreferences(PREFS, MODE_PRIVATE).getLong(KEY_HIGH_SCORE, 0);
            gameView.setHighScore(high);
            setContentView(gameView);
        } catch (Throwable t) {
            Log.e(TAG, "FATAL: ошибка при создании активности", t);
            finish();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (gameView != null) gameView.resumeGame();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (gameView != null) {
            gameView.pauseGame();
            // сохраняем рекорд
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putLong(KEY_HIGH_SCORE, gameView.getHighScore())
                    .apply();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (gameView != null) gameView.releaseSound();
    }
}
