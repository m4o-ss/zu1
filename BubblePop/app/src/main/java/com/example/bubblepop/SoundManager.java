package com.example.bubblepop;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.SoundPool;

/**
 * Простые синтезированные звуки через ToneGenerator (без аудиофайлов):
 * выстрел, лопание шарика, комбо, конец уровня.
 * Все вызовы обёрнуты в try/catch: на некоторых прошивках ToneGenerator /
 * AudioManager могут бросать исключения — игра не должна из-за этого падать.
 */
public class SoundManager {

    private SoundPool soundPool;
    private android.media.ToneGenerator tgTone;
    private android.media.ToneGenerator tgNoise;

    public SoundManager(Context ctx) {
        try {
            AudioAttributes aa = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(8)
                    .setAudioAttributes(aa)
                    .build();
            tgTone = new android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 60);
            tgNoise = new android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 60);
        } catch (Throwable t) {
            // звук — не критично, играем без него
            soundPool = null;
            tgTone = null;
            tgNoise = null;
        }
    }

    public void playShoot() {
        try {
            if (tgTone != null)
                tgTone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 60);
        } catch (Throwable ignored) {}
    }

    public void playPop(int comboLevel) {
        try {
            if (tgTone != null)
                tgTone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 40);
            if (tgNoise != null)
                tgNoise.startTone(android.media.ToneGenerator.TONE_CDMA_ABBR_ALERT, 50);
        } catch (Throwable ignored) {}
    }

    public void playChain() {
        try {
            if (tgTone != null)
                tgTone.startTone(android.media.ToneGenerator.TONE_DTMF_1, 80);
        } catch (Throwable ignored) {}
    }

    public void playGameOver() {
        try {
            if (tgTone != null)
                tgTone.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 300);
        } catch (Throwable ignored) {}
    }

    public void release() {
        try { if (soundPool != null) soundPool.release(); } catch (Throwable ignored) {}
        try { if (tgTone != null) tgTone.release(); } catch (Throwable ignored) {}
        try { if (tgNoise != null) tgNoise.release(); } catch (Throwable ignored) {}
        soundPool = null; tgTone = null; tgNoise = null;
    }
}
