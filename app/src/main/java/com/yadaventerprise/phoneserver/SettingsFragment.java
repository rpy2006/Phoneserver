package com.yadaventerprise.phoneserver;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/** Server Settings: General info, live-toggle server controls, security, permission status, and advanced actions. */
public class SettingsFragment extends Fragment {

    private static final int REQUEST_NOTIFICATION_PERMISSION = 501;

    private View rootView;
    private TextView authSubtitle;
    private ImageView storageStatusIcon, bootStatusIcon, notificationStatusIcon;
    private Switch switchStartOnBoot, switchRemoteAccess, switchHiddenFiles;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        rootView = inflater.inflate(R.layout.fragment_settings, container, false);
        return rootView;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context context = requireContext();

        TextView portValue = view.findViewById(R.id.port_value);
        portValue.setText(String.valueOf(ServerService.PORT));

        authSubtitle = view.findViewById(R.id.auth_subtitle);
        storageStatusIcon = view.findViewById(R.id.storage_status_icon);
        bootStatusIcon = view.findViewById(R.id.boot_status_icon);
        notificationStatusIcon = view.findViewById(R.id.notification_status_icon);

        switchStartOnBoot = view.findViewById(R.id.switch_start_on_boot);
        switchRemoteAccess = view.findViewById(R.id.switch_remote_access);
        switchHiddenFiles = view.findViewById(R.id.switch_hidden_files);

        switchStartOnBoot.setChecked(ContentStore.getStartOnBoot(context));
        switchRemoteAccess.setChecked(ContentStore.getAllowRemoteAccess(context));
        switchHiddenFiles.setChecked(ContentStore.getShowHiddenFiles(context));

        switchStartOnBoot.setOnCheckedChangeListener((buttonView, isChecked) -> {
            ContentStore.setStartOnBoot(getContext(), isChecked);
            refreshPermissionStatuses();
        });
        switchRemoteAccess.setOnCheckedChangeListener((buttonView, isChecked) ->
                ContentStore.setAllowRemoteAccess(getContext(), isChecked));
        switchHiddenFiles.setOnCheckedChangeListener((buttonView, isChecked) ->
                ContentStore.setShowHiddenFiles(getContext(), isChecked));

        view.findViewById(R.id.row_authentication).setOnClickListener(v ->
                startActivity(new Intent(getContext(), AccountSettingsActivity.class)));
        view.findViewById(R.id.row_edit_accounts).setOnClickListener(v ->
                startActivity(new Intent(getContext(), AccountSettingsActivity.class)));

        view.findViewById(R.id.row_storage_permission).setOnClickListener(v -> {
            if (ContentStore.hasFullStorageAccess(getContext())) {
                Toast.makeText(getContext(), "Already granted", Toast.LENGTH_SHORT).show();
            } else if (getActivity() != null) {
                ContentStore.requestFullStorageAccess(getActivity());
            }
        });

        view.findViewById(R.id.row_notification_permission).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && getActivity() != null) {
                if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(getActivity(),
                            new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATION_PERMISSION);
                } else {
                    Toast.makeText(getContext(), "Already granted", Toast.LENGTH_SHORT).show();
                }
            } else {
                Toast.makeText(getContext(), "Notifications don't need a separate permission on this Android version", Toast.LENGTH_SHORT).show();
            }
        });

        view.findViewById(R.id.row_device_admin).setOnClickListener(v ->
                Toast.makeText(getContext(),
                        "PhoneServer doesn't use Device Admin - there's nothing to enable here",
                        Toast.LENGTH_LONG).show());

        view.findViewById(R.id.row_advanced_settings).setOnClickListener(v ->
                startActivity(new Intent(getContext(), AdvancedSettingsActivity.class)));
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshAuthSubtitle();
        refreshPermissionStatuses();
    }

    private void refreshAuthSubtitle() {
        Context context = getContext();
        if (context == null || authSubtitle == null) return;
        String username = ContentStore.getUsername(context);
        String password = ContentStore.getPassword(context);
        if (password.isEmpty()) {
            authSubtitle.setText("No password set - open access on your Wi-Fi");
        } else if (!username.isEmpty()) {
            authSubtitle.setText("Username & password set");
        } else {
            authSubtitle.setText("Password set");
        }
    }

    private void refreshPermissionStatuses() {
        Context context = getContext();
        if (context == null) return;

        setStatusIcon(storageStatusIcon, ContentStore.hasFullStorageAccess(context));
        setStatusIcon(bootStatusIcon, ContentStore.getStartOnBoot(context));

        boolean notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        setStatusIcon(notificationStatusIcon, notificationsGranted);
    }

    private void setStatusIcon(ImageView icon, boolean granted) {
        if (icon == null) return;
        icon.setImageResource(granted ? R.drawable.ic_check_circle : R.drawable.ic_minus_circle);
    }
}
