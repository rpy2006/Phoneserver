package com.yadaventerprise.phoneserver;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

public class MainPagerAdapter extends FragmentStateAdapter {

    public static final int PAGE_HOME = 0;
    public static final int PAGE_DASHBOARD = 1;
    public static final int PAGE_QR = 2;
    public static final int PAGE_CHAT = 3;
    public static final int PAGE_SETTINGS = 4;

    public MainPagerAdapter(@NonNull FragmentActivity activity) {
        super(activity);
    }

    @NonNull
    @Override
    public Fragment createFragment(int position) {
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

    @Override
    public int getItemCount() {
        return 5;
    }
}
