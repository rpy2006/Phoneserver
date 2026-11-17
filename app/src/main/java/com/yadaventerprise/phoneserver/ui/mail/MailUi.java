package com.yadaventerprise.phoneserver.ui.mail;

import android.app.Activity;
import android.content.Context;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import com.yadaventerprise.phoneserver.mail.MailValidation;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class MailUi {

    interface Task {
        void run() throws Exception;
    }

    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool();

    private MailUi() {
    }

    static LinearLayout root(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 16);
        root.setPadding(pad, pad, pad, pad);
        return root;
    }

    static LinearLayout.LayoutParams matchWrap(Context context) {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static TextView title(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(22);
        view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        view.setPadding(0, 0, 0, dp(context, 12));
        return view;
    }

    static TextView label(Context context, String text) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(13);
        view.setPadding(0, dp(context, 8), 0, dp(context, 3));
        return view;
    }

    static TextView text(Context context, String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(14);
        view.setTextIsSelectable(true);
        view.setPadding(0, dp(context, 4), 0, dp(context, 4));
        return view;
    }

    static Button button(Context context, String label, android.view.View.OnClickListener listener) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(listener);
        return button;
    }

    static EditText edit(Context context, String hint) {
        EditText edit = new EditText(context);
        edit.setHint(hint);
        edit.setSingleLine(true);
        return edit;
    }

    static Spinner spinner(Context context, List<String> values) {
        Spinner spinner = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        return spinner;
    }

    static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density);
    }

    static void run(Activity activity, TextView status, Task task, Runnable after) {
        status.setText("Working...");
        EXECUTOR.execute(() -> {
            try {
                task.run();
                activity.runOnUiThread(() -> {
                    status.setText("Done");
                    if (after != null) after.run();
                });
            } catch (Exception e) {
                String message = MailValidation.cleanError(e.getMessage());
                activity.runOnUiThread(() -> status.setText("Error: " + message));
            }
        });
    }
}
