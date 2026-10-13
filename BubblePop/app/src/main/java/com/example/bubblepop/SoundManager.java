package com.example.bubblepop;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;

/**
 * Простые синтезированные звуки через ToneGenerator (без аудиофайлов):
 * выстрел, лопание шарика, комбо, конец уровня.
 */
public class SoundManager {

    private final SoundPool soundPool;
    private final android.media.ToneGenerator tgTone;
    private final android.media.ToneGenerator tgNoise;

    public SoundManager(Context ctx) {
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(8)
                .setAudioAttributes(aa)
                .build();
        tgTone = new android.media.ToneGenerator(AudioAttributes.USAGE_GAME, 60);
        tgNoise = new android.media.ToneGenerator(AudioAttributes.USAGE_GAME, 60);
    }

    public void playShoot() {
        tgTone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 60);
    }

    public void playPop(int comboLevel) {
        // Чем больше комбо — тем выше тон
        int tone = android.media.ToneGenerator.TONE_PROP_BEEP;
        tgTone.startTone(tone, 40);
        tgNoise.startTone(android.media.ToneGenerator.TONE_CDMA_ABBR_ALERT, 50);
    }

    public void playChain() {
        tgTone.startTone(android.media.ToneGenerator.TONE_DTMF_1, 80);
    }

    public void playGameOver() {
        tgTone.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 300);
    }

    public void release() {
        soundPool.release();
        tgTone.release();
        tgNoise.release();
    }
}
