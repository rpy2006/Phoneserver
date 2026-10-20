package com.yadaventerprise.phoneserver;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;

public class MainActivity extends AppCompatActivity {

    private static final String STATE_SELECTED_PAGE = "state_selected_page";

    private ViewPager2 viewPager;
    private BottomNavigationView bottomNav;
    private DrawerLayout drawerLayout;
    private NavigationView navDrawer;
    private MainPagerAdapter pagerAdapter;
    private boolean suppressNavCallback = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewPager = findViewById(R.id.view_pager);
        bottomNav = findViewById(R.id.bottom_nav);
        drawerLayout = findViewById(R.id.drawer_layout);
        navDrawer = findViewById(R.id.nav_drawer);

        pagerAdapter = new MainPagerAdapter(this);
        viewPager.setAdapter(pagerAdapter);
        viewPager.setOffscreenPageLimit(4);

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                suppressNavCallback = true;
                bottomNav.setSelectedItemId(pageToNavId(position));
                navDrawer.setCheckedItem(pageToDrawerNavId(position));
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

        navDrawer.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            int page = drawerNavIdToPage(id);
            if (page >= 0) {
                viewPager.setCurrentItem(page, true);
                item.setChecked(true);
            } else {
                Intent intent = drawerNavIdToIntent(id);
                if (intent != null) {
                    startActivity(intent);
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // Without this, every activity recreation (rotation, theme/font change, or
        // the system reclaiming this process while a large model is mapped) started a
        // fresh adapter at page 0, so the user was dropped back on Home and the tab
        // they were using looked like it had closed itself.
        if (savedInstanceState != null) {
            int restored = savedInstanceState.getInt(STATE_SELECTED_PAGE, MainPagerAdapter.PAGE_HOME);
            if (restored > MainPagerAdapter.PAGE_HOME && restored < pagerAdapter.getItemCount()) {
                viewPager.setCurrentItem(restored, false);
            }
        }

        navDrawer.setCheckedItem(pageToDrawerNavId(viewPager.getCurrentItem()));
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (viewPager != null) {
            outState.putInt(STATE_SELECTED_PAGE, viewPager.getCurrentItem());
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout != null && drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
            return;
        }
        super.onBackPressed();
    }

    public void goToPage(int page) {
        viewPager.setCurrentItem(page, true);
    }

    public void openDrawer() {
        if (drawerLayout != null) {
            drawerLayout.openDrawer(GravityCompat.START);
        }
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

    private int pageToDrawerNavId(int page) {
        switch (page) {
            case MainPagerAdapter.PAGE_DASHBOARD:
                return R.id.nav_drawer_dashboard;
            case MainPagerAdapter.PAGE_QR:
                return R.id.nav_drawer_qr;
            case MainPagerAdapter.PAGE_CHAT:
                return R.id.nav_drawer_chat;
            case MainPagerAdapter.PAGE_SETTINGS:
                return R.id.nav_drawer_settings;
            case MainPagerAdapter.PAGE_HOME:
            default:
                return R.id.nav_drawer_home;
        }
    }

    private int drawerNavIdToPage(int drawerNavId) {
        if (drawerNavId == R.id.nav_drawer_home) return MainPagerAdapter.PAGE_HOME;
        if (drawerNavId == R.id.nav_drawer_dashboard) return MainPagerAdapter.PAGE_DASHBOARD;
        if (drawerNavId == R.id.nav_drawer_qr) return MainPagerAdapter.PAGE_QR;
        if (drawerNavId == R.id.nav_drawer_chat) return MainPagerAdapter.PAGE_CHAT;
        if (drawerNavId == R.id.nav_drawer_settings) return MainPagerAdapter.PAGE_SETTINGS;
        return -1;
    }

    private Intent drawerNavIdToIntent(int drawerNavId) {
        if (drawerNavId == R.id.nav_drawer_files) {
            return new Intent(this, FilesActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_servers) {
            return new Intent(this, ServersListActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_projects) {
            return new Intent(this, ProjectsActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_api) {
            return new Intent(this, ApiEditorActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_api_docs) {
            return new Intent(this, ApiDocsActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_website) {
            return new Intent(this, WebsiteFilesActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_account) {
            return new Intent(this, AccountSettingsActivity.class);
        }
        return null;
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
