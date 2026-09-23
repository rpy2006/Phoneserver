package com.yadaventerprise.phoneserver;

import android.media.MediaPlayer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.util.Locale;

public class MusicPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    private MediaPlayer player;
    private SeekBar seekBar;
    private TextView timeCurrent, timeTotal;
    private Button playPauseButton;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (player != null) {
                try {
                    seekBar.setProgress(player.getCurrentPosition());
                    timeCurrent.setText(formatTime(player.getCurrentPosition()));
                } catch (IllegalStateException ignored) {
                }
            }
            handler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_music_player);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        File file = path != null ? new File(path) : null;

        TextView title = findViewById(R.id.track_title);
        seekBar = findViewById(R.id.seek_bar);
        timeCurrent = findViewById(R.id.time_current);
        timeTotal = findViewById(R.id.time_total);
        playPauseButton = findViewById(R.id.play_pause_button);

        if (file == null || !file.exists()) {
            Toast.makeText(this, "Audio file not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        title.setText(file.getName());

        try {
            player = new MediaPlayer();
            player.setDataSource(file.getAbsolutePath());
            player.setOnPreparedListener(mp -> {
                seekBar.setMax(player.getDuration());
                timeTotal.setText(formatTime(player.getDuration()));
                startPlayback();
            });
            player.setOnCompletionListener(mp -> {
                playPauseButton.setText("Play");
                seekBar.setProgress(0);
                timeCurrent.setText(formatTime(0));
            });
            player.prepareAsync();
        } catch (Exception e) {
            Toast.makeText(this, "Could not play this file", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        playPauseButton.setOnClickListener(v -> togglePlayback());

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    player.seekTo(progress);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
    }

    private void startPlayback() {
        player.start();
        playPauseButton.setText("Pause");
        handler.post(progressRunnable);
    }

    private void togglePlayback() {
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
            playPauseButton.setText("Play");
        } else {
            player.start();
            playPauseButton.setText("Pause");
        }
    }

    private String formatTime(int millis) {
        int totalSeconds = millis / 1000;
        int minutes = totalSeconds / 60;
        int seconds = totalSeconds % 60;
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(progressRunnable);
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
