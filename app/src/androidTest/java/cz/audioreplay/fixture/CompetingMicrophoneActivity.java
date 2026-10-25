package cz.audioreplay.fixture;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;

/** Separate test APK UID, with no dependency on the target APK's Kotlin runtime. */
public class CompetingMicrophoneActivity extends Activity {
    public static final String CLOSE = "cz.audioreplay.test.CLOSE_COMPETING_MICROPHONE";
    private AudioRecord recorder;
    private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { finish(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView label = new TextView(this);
        label.setText("Test: jiná aplikace používá mikrofon");
        setContentView(label);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(closeReceiver, new IntentFilter(CLOSE), RECEIVER_EXPORTED);
        else registerLegacyReceiver();
        int rate = 24000;
        recorder = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(Math.max(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT) * 2, 48000)).build();
        recorder.startRecording();
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag") // Only API 29–32; flags are used on API 33+ above.
    private void registerLegacyReceiver() { registerReceiver(closeReceiver, new IntentFilter(CLOSE)); }

    @Override public void onDestroy() {
        if (recorder != null) { recorder.stop(); recorder.release(); recorder = null; }
        unregisterReceiver(closeReceiver);
        super.onDestroy();
    }
}
