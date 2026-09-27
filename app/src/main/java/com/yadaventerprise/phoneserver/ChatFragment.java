package com.yadaventerprise.phoneserver;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
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

    private static final String TAG = "ChatFragment";
    private static final String STATE_CONVERSATION = "state_conversation";
    private static final int MAX_HISTORY_CHARS = 20000;

    private TextView modelStatusText, conversationText;
    private Button importModelButton, sendButton;
    private EditText promptInput;
    private ScrollView conversationScroll;

    /**
     * View references are only valid between onViewCreated and onDestroyView, but the
     * token stream from LlmEngine runs for seconds and posts a callback per token on
     * the main thread. Every view touch has to re-check that the view still exists.
     */
    private boolean viewAlive = false;

    /** Tokens produced while no view was attached, flushed when the view comes back. */
    private final StringBuilder pendingTokens = new StringBuilder();
    private boolean flushScheduled = false;
    private boolean generating = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** Everything shown in the transcript, so it survives view recreation. */
    private final StringBuilder history = new StringBuilder();

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
        viewAlive = true;

        importModelButton.setOnClickListener(v -> filePickerLauncher.launch(new String[]{"*/*"}));
        sendButton.setOnClickListener(v -> onSendClicked());

        if (savedInstanceState != null) {
            history.setLength(0);
            history.append(savedInstanceState.getString(STATE_CONVERSATION, ""));
        }
        conversationText.setText(history);
        scrollToBottom();

        setInputEnabled(!generating && LlmEngine.get().isModelLoaded());

        refreshModelStatus();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_CONVERSATION, history.toString());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        viewAlive = false;
        mainHandler.removeCallbacks(flushRunnable);
        flushScheduled = false;
        modelStatusText = null;
        conversationText = null;
        conversationScroll = null;
        importModelButton = null;
        sendButton = null;
        promptInput = null;
    }

    private void refreshModelStatus() {
        Context context = getContext();
        if (context == null) return;

        if (!LlmEngine.get().isNativeLibraryAvailable()) {
            setStatus("AI chat isn't available in this build (native engine not installed yet)");
            setImportVisible(false);
            setInputEnabled(false);
            return;
        }

        if (LlmEngine.get().isModelLoaded()) {
            setStatus("Model ready");
            setImportVisible(false);
            setInputEnabled(!generating);
            return;
        }

        if (!ModelStore.isModelPresent(context)) {
            setStatus("No model found - import a .gguf text model to get started");
            setImportVisible(true);
            setInputEnabled(false);
            return;
        }

        setStatus("Loading model...");
        setImportVisible(false);
        setInputEnabled(false);

        // Named thread so a crash here is attributable in logcat. An uncaught
        // throwable on a bare Thread goes to the default handler and kills the
        // process, taking the file-server UI down with the chat tab.
        Thread loader = new Thread(() -> {
            ModelStore.LoadResult result;
            try {
                result = ModelStore.loadIntoEngine(context);
            } catch (Throwable t) {
                Log.e(TAG, "Model auto-load failed", t);
                result = ModelStore.LoadResult.failure(
                        "Could not load the model: " + t);
            }
            // The catch block reassigns result, so it is not effectively final and
            // cannot be captured by the lambda below. Copy it into a final local,
            // the same way the import path already does with `outcome`.
            final ModelStore.LoadResult outcome = result;
            postToUi(() -> {
                if (outcome.success) {
                    setStatus("Model ready");
                    setInputEnabled(!generating);
                } else {
                    setStatus(outcome.error);
                    setImportVisible(true);
                    setInputEnabled(false);
                }
            });
        }, "chat-model-autoload");
        loader.start();
    }

    private void onModelFilePicked(Uri uri) {
        if (uri == null) return;
        Context context = getContext();
        if (context == null) return;
        if (!LlmEngine.get().isNativeLibraryAvailable()) {
            Toast.makeText(context, "AI chat isn't available in this build", Toast.LENGTH_SHORT).show();
            return;
        }

        // OpenDocument grants a persistable read permission; without claiming it the
        // URI grant dies with the task and a later retry fails with a bare
        // SecurityException.
        try {
            context.getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            Log.w(TAG, "Could not persist read permission for " + uri, e);
        }

        setStatus("Copying model file...");
        setImportVisible(false);

        Thread importer = new Thread(() -> {
            ModelStore.LoadResult result;
            try {
                result = ModelStore.importModel(context, uri);
                if (result.success) {
                    // importModel only copies and validates; the engine still has to
                    // be pointed at the new file before input can be enabled.
                    postToUi(() -> setStatus("Loading model..."));
                    result = ModelStore.loadIntoEngine(context);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Model import failed", t);
                result = ModelStore.LoadResult.failure("Import failed: " + t);
            }
            final ModelStore.LoadResult outcome = result;
            postToUi(() -> {
                if (outcome.success) {
                    setStatus("Model ready");
                    setInputEnabled(!generating);
                } else {
                    setStatus(outcome.error);
                    setImportVisible(true);
                    setInputEnabled(false);
                    Toast.makeText(context, outcome.error, Toast.LENGTH_LONG).show();
                }
            });
        }, "chat-model-import");
        importer.start();
    }

    /**
     * getActivity() can go null between the null check and runOnUiThread on a
     * worker thread, and an NPE there escapes an unnamed Thread and kills the
     * process. Route every worker result through the main handler and re-check
     * that the fragment still has a view to update.
     */
    private void postToUi(Runnable action) {
        mainHandler.post(() -> {
            if (!isAdded() || !viewAlive) return;
            action.run();
        });
    }

    private void setStatus(String text) {
        if (!viewAlive || modelStatusText == null) return;
        modelStatusText.setText(text);
    }

    private void setImportVisible(boolean visible) {
        if (!viewAlive || importModelButton == null) return;
        importModelButton.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void setInputEnabled(boolean enabled) {
        if (!viewAlive || promptInput == null || sendButton == null) return;
        promptInput.setEnabled(enabled);
        sendButton.setEnabled(enabled);
    }

    private void onSendClicked() {
        if (promptInput == null) return;
        String prompt = promptInput.getText().toString().trim();
        if (prompt.isEmpty()) return;
        if (!LlmEngine.get().isModelLoaded()) {
            Toast.makeText(getContext(), "Model isn't loaded yet", Toast.LENGTH_SHORT).show();
            return;
        }
        if (generating) return;

        promptInput.setText("");
        hideKeyboard();
        generating = true;
        setInputEnabled(false);

        render("\nYou: " + prompt + "\n\nAssistant: ", true);

        LlmEngine.get().generate(prompt, new LlmEngine.GenerationCallback() {
            @Override
            public void onToken(String token) {
                queueToken(token);
            }

            @Override
            public void onComplete(String fullResponse) {
                finishGeneration("\n");
            }

            @Override
            public void onError(String message) {
                finishGeneration("\n[Error: " + message + "]\n");
            }
        });
    }

    private void finishGeneration(String trailer) {
        // The last few tokens can still be sitting in the buffer when onComplete
        // arrives, since they are coalesced onto one pending runnable. Drain first
        // or the trailing newline lands in the middle of the reply.
        flushPendingTokens();
        generating = false;
        if (trailer != null) render(trailer, true);
        setInputEnabled(LlmEngine.get().isModelLoaded());
    }

    private void hideKeyboard() {
        Context context = getContext();
        if (context == null || promptInput == null) return;
        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(promptInput.getWindowToken(), 0);
        }
    }

    /**
     * Tokens arrive one main-thread post each. Appending and re-laying out the
     * TextView per token is what made the UI stall long enough for the system to
     * reclaim the process, so buffer and flush on a single pending runnable instead.
     */
    private void queueToken(String token) {
        pendingTokens.append(token);
        if (flushScheduled) return;
        flushScheduled = true;
        mainHandler.post(flushRunnable);
    }

    private final Runnable flushRunnable = new Runnable() {
        @Override
        public void run() {
            flushScheduled = false;
            flushPendingTokens();
        }
    };

    private void flushPendingTokens() {
        if (pendingTokens.length() == 0) return;
        String chunk = pendingTokens.toString();
        pendingTokens.setLength(0);
        render(chunk, true);
    }

    private boolean isScrolledToBottom() {
        if (!viewAlive || conversationScroll == null) return true;
        View child = conversationScroll.getChildAt(0);
        int scrollRange = child == null ? 0 : child.getHeight() - conversationScroll.getHeight();
        return scrollRange - conversationScroll.getScrollY() < conversationScroll.getHeight() / 4;
    }

    private void scrollToBottom() {
        ScrollView scroll = conversationScroll;
        if (!viewAlive || scroll == null) return;
        scroll.post(() -> {
            if (viewAlive && conversationScroll != null) {
                conversationScroll.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    /**
     * Records the text in {@link #history} and draws it if a view is currently
     * attached. Safe to call with no view: the text is replayed by onViewCreated.
     */
    private void render(String text, boolean scrollNow) {
        boolean wasAtBottom = isScrolledToBottom();

        history.append(text);
        boolean trimmed = false;
        if (history.length() > MAX_HISTORY_CHARS) {
            history.delete(0, history.length() - MAX_HISTORY_CHARS);
            trimmed = true;
        }

        if (!viewAlive || conversationText == null) return;

        if (trimmed) {
            conversationText.setText(history);
        } else {
            conversationText.append(text);
        }

        if (scrollNow && wasAtBottom) {
            scrollToBottom();
        }
    }
}
