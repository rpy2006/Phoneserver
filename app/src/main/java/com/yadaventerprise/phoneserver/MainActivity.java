package com.yadaventerprise.phoneserver;

import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNav;
    private boolean suppressNavCallback = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.view_pager);
        bottomNav = findViewById(R.id.bottom_nav);

        viewPager.setAdapter(new MainPagerAdapter(this));
        viewPager.setOffscreenPageLimit(4);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                suppressNavCallback = true;
                bottomNav.setSelectedItemId(pageToNavId(position));
                suppressNavCallback = false;
            }
        });

        bottomNav.setOnItemSelectedListener(item -> {
            if (suppressNavCallback) return true;
            int page = navIdToPage(item.getItemId());
            if (page >= 0) {
                viewPager.setCurrentItem(page, true);
            }
            return true;
        });
    }

    public void goToPage(int page) {
        viewPager.setCurrentItem(page, true);
    }

    private int pageToNavId(int page) {
        switch (page) {
            case MainPagerAdapter.PAGE_DASHBOARD:
                return R.id.nav_dashboard;
            case MainPagerAdapter.PAGE_QR:
                return R.id.nav_qr;
            case MainPagerAdapter.PAGE_CHAT:
                return R.id.nav_chat;
            case MainPagerAdapter.PAGE_SETTINGS:
                return R.id.nav_settings;
            case MainPagerAdapter.PAGE_HOME:
            default:
                return R.id.nav_home;
        }
    }

    private int navIdToPage(int navId) {
        if (navId == R.id.nav_home) return MainPagerAdapter.PAGE_HOME;
        if (navId == R.id.nav_dashboard) return MainPagerAdapter.PAGE_DASHBOARD;
        if (navId == R.id.nav_qr) return MainPagerAdapter.PAGE_QR;
        if (navId == R.id.nav_chat) return MainPagerAdapter.PAGE_CHAT;
        if (navId == R.id.nav_settings) return MainPagerAdapter.PAGE_SETTINGS;
        return -1;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
