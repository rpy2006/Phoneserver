package com.yadaventerprise.phoneserver.ui.developer;

import android.content.Intent;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.yadaventerprise.phoneserver.ui.api.ApiTokensActivity;

public class DeveloperApiKeysActivity extends AppCompatActivity {
    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startActivity(new Intent(this, ApiTokensActivity.class));
        finish();
    }
}
