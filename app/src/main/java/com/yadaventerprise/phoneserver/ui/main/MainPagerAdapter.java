package com.yadaventerprise.phoneserver.ui.main;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import com.yadaventerprise.phoneserver.ui.dashboard.DashboardFragment;
import com.yadaventerprise.phoneserver.ui.qr.QrFragment;

public final class MainPagerAdapter {

    public static final int PAGE_HOME = 0;
    public static final int PAGE_DASHBOARD = 1;
    public static final int PAGE_QR = 2;
    public static final int PAGE_CHAT = 3;
    public static final int PAGE_SETTINGS = 4;

    private MainPagerAdapter() {
    }

    @NonNull
    public static Fragment createFragment(int position) {
        switch (position) {
            case PAGE_DASHBOARD:
                return new DashboardFragment();
            case PAGE_QR:
                return new QrFragment();
            case PAGE_CHAT:
                return new ChatFragment();
            case PAGE_SETTINGS:
                return new SettingsFragment();
            case PAGE_HOME:
            default:
                return new HomeFragment();
        }
    }
}
