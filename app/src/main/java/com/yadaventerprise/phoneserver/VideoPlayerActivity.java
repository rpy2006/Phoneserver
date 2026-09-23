package com.yadaventerprise.phoneserver;

import android.net.Uri;
import android.os.Bundle;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.appcompat.app.AppCompatActivity;

import java.io.File;

public class VideoPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "path";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        String path = getIntent().getStringExtra(EXTRA_PATH);
        File file = path != null ? new File(path) : null;

        TextView title = findViewById(R.id.video_title);
        VideoView videoView = findViewById(R.id.video_view);

        if (file == null || !file.exists()) {
            Toast.makeText(this, "Video not found", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        title.setText(file.getName());

        MediaController controller = new MediaController(this);
        controller.setAnchorView(videoView);
        videoView.setMediaController(controller);

        videoView.setVideoURI(Uri.fromFile(file));
        videoView.setOnPreparedListener(mp -> videoView.start());
        videoView.setOnErrorListener((mp, what, extra) -> {
            Toast.makeText(this, "Could not play this video", Toast.LENGTH_SHORT).show();
            return true;
        });
        videoView.requestFocus();
    }
}
