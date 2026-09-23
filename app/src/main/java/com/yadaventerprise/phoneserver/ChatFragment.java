package com.yadaventerprise.phoneserver;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class ChatFragment extends Fragment {

    private TextView modelStatusText, conversationText;
    private Button importModelButton, sendButton;
    private EditText promptInput;
    private ScrollView conversationScroll;

    private final ActivityResultLauncher<String[]> filePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onModelFilePicked);

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        modelStatusText = view.findViewById(R.id.model_status_text);
        conversationText = view.findViewById(R.id.conversation_text);
        conversationScroll = view.findViewById(R.id.conversation_scroll);
        importModelButton = view.findViewById(R.id.import_model_button);
        sendButton = view.findViewById(R.id.send_button);
        promptInput = view.findViewById(R.id.prompt_input);

        importModelButton.setOnClickListener(v -> filePickerLauncher.launch(new String[]{"*/*"}));
        sendButton.setOnClickListener(v -> onSendClicked());

        refreshModelStatus();
    }

    private void refreshModelStatus() {
        Context context = getContext();
        if (context == null) return;

        if (!LlmEngine.get().isNativeLibraryAvailable()) {
            modelStatusText.setText("AI chat isn't available in this build (native engine not installed yet)");
            importModelButton.setVisibility(View.GONE);
            setInputEnabled(false);
            return;
        }

        if (LlmEngine.get().isModelLoaded()) {
            modelStatusText.setText("Model ready");
            importModelButton.setVisibility(View.GONE);
            setInputEnabled(true);
            return;
        }

        if (!ModelStore.isModelPresent(context)) {
            modelStatusText.setText("No model found - import a .gguf file to get started");
            importModelButton.setVisibility(View.VISIBLE);
            setInputEnabled(false);
            return;
        }

        modelStatusText.setText("Loading model...");
        importModelButton.setVisibility(View.GONE);
        setInputEnabled(false);

        new Thread(() -> {
            boolean loaded = ModelStore.loadIntoEngine(context);
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (loaded) {
                    modelStatusText.setText("Model ready");
                    setInputEnabled(true);
                } else {
                    modelStatusText.setText("Failed to load model - it may be corrupted or an unsupported format");
                    importModelButton.setVisibility(View.VISIBLE);
                }
            });
        }).start();
    }

    private void onModelFilePicked(Uri uri) {
        if (uri == null) return;
        Context context = getContext();
        if (context == null) return;
        if (!LlmEngine.get().isNativeLibraryAvailable()) {
            Toast.makeText(context, "AI chat isn't available in this build", Toast.LENGTH_SHORT).show();
            return;
        }

        modelStatusText.setText("Copying model file...");
        importModelButton.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                ModelStore.importModel(context, uri);
                boolean loaded = ModelStore.loadIntoEngine(context);
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (loaded) {
                        modelStatusText.setText("Model ready");
                        setInputEnabled(true);
                    } else {
                        modelStatusText.setText("Model file copied, but failed to load - check it's a valid GGUF file");
                        importModelButton.setVisibility(View.VISIBLE);
                    }
                });
            } catch (Exception e) {
                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(context, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    modelStatusText.setText("No model found - import a .gguf file to get started");
                    importModelButton.setVisibility(View.VISIBLE);
                });
            }
        }).start();
    }

    private void setInputEnabled(boolean enabled) {
        promptInput.setEnabled(enabled);
        sendButton.setEnabled(enabled);
    }

    private void onSendClicked() {
        String prompt = promptInput.getText().toString().trim();
        if (prompt.isEmpty()) return;
        if (!LlmEngine.get().isModelLoaded()) {
            Toast.makeText(getContext(), "Model isn't loaded yet", Toast.LENGTH_SHORT).show();
            return;
        }

        appendToConversation("\nYou: " + prompt + "\n\nAssistant: ");
        promptInput.setText("");
        setInputEnabled(false);

        LlmEngine.get().generate(prompt, new LlmEngine.GenerationCallback() {
            @Override
            public void onToken(String token) {
                appendToConversation(token);
            }

            @Override
            public void onComplete(String fullResponse) {
                appendToConversation("\n");
                setInputEnabled(true);
            }

            @Override
            public void onError(String message) {
                appendToConversation("\n[Error: " + message + "]\n");
                setInputEnabled(true);
            }
        });
    }

    private void appendToConversation(String text) {
        conversationText.append(text);
        conversationScroll.post(() -> conversationScroll.fullScroll(View.FOCUS_DOWN));
    }
}
