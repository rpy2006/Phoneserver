package com.yadaventerprise.phoneserver.ui.main;

import android.content.Intent;
import android.os.Bundle;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationView;
import com.yadaventerprise.phoneserver.R;
import com.yadaventerprise.phoneserver.ads.AdNetworkActivity;
import com.yadaventerprise.phoneserver.ui.api.ApiDocsActivity;
import com.yadaventerprise.phoneserver.ui.api.ApiEditorActivity;
import com.yadaventerprise.phoneserver.ui.docs.DeveloperDocsActivity;
import com.yadaventerprise.phoneserver.ui.files.FilesActivity;
import com.yadaventerprise.phoneserver.ui.files.WebsiteFilesActivity;
import com.yadaventerprise.phoneserver.ui.mail.MailActivity;
import com.yadaventerprise.phoneserver.ui.projects.ProjectsActivity;
import com.yadaventerprise.phoneserver.ui.remote.RemoteManagementActivity;
import com.yadaventerprise.phoneserver.ui.security.SecurityCenterActivity;
import com.yadaventerprise.phoneserver.ui.sharing.SharingOverviewActivity;
import com.yadaventerprise.phoneserver.ui.servers.AccountSettingsActivity;
import com.yadaventerprise.phoneserver.ui.servers.ServersListActivity;
import com.yadaventerprise.phoneserver.ui.system.SystemManagementActivity;
import com.yadaventerprise.phoneserver.util.StartupTrace;

public class MainActivity extends AppCompatActivity {

    private static final String STATE_SELECTED_PAGE = "state_selected_page";
    private static final String PAGE_TAG_PREFIX = "main_page_";

    private final Fragment[] pages = new Fragment[5];
    private FrameLayout contentFrame;
    private BottomNavigationView bottomNav;
    private DrawerLayout drawerLayout;
    private NavigationView navDrawer;
    private ChatFragment chatFragment;
    private int currentPage = MainPagerAdapter.PAGE_HOME;
    private boolean suppressNavCallback = false;
    private boolean initialPageSelection = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StartupTrace.write(this, "MainActivity.onCreate:start");
        setContentView(R.layout.activity_main);
        StartupTrace.write(this, "MainActivity.onCreate:contentView");

        contentFrame = findViewById(R.id.view_pager);
        bottomNav = findViewById(R.id.bottom_nav);
        drawerLayout = findViewById(R.id.drawer_layout);
        navDrawer = findViewById(R.id.nav_drawer);
        StartupTrace.write(this, "MainActivity.onCreate:views");

        for (int page = MainPagerAdapter.PAGE_HOME; page <= MainPagerAdapter.PAGE_SETTINGS; page++) {
            Fragment existing = getSupportFragmentManager().findFragmentByTag(pageTag(page));
            if (existing instanceof ChatFragment) chatFragment = (ChatFragment) existing;
            pages[page] = existing;
        }

        bottomNav.setOnItemSelectedListener(item -> {
            if (suppressNavCallback) return true;
            int page = navIdToPage(item.getItemId());
            if (page >= 0) showPage(page);
            return true;
        });

        navDrawer.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();
            int page = drawerNavIdToPage(id);
            if (page >= 0) {
                showPage(page);
                item.setChecked(true);
            } else {
                Intent intent = drawerNavIdToIntent(id);
                if (intent != null) startActivity(intent);
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });
        StartupTrace.write(this, "MainActivity.onCreate:listeners");

        int restoredPage = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_SELECTED_PAGE, MainPagerAdapter.PAGE_HOME)
                : MainPagerAdapter.PAGE_HOME;
        if (restoredPage < MainPagerAdapter.PAGE_HOME || restoredPage > MainPagerAdapter.PAGE_SETTINGS) {
            restoredPage = MainPagerAdapter.PAGE_HOME;
        }
        showPage(restoredPage);
        initialPageSelection = false;
        StartupTrace.write(this, "MainActivity.onCreate:done");
    }

    private void showPage(int page) {
        if (page < MainPagerAdapter.PAGE_HOME || page > MainPagerAdapter.PAGE_SETTINGS || contentFrame == null) {
            return;
        }
        StartupTrace.write(this, "MainActivity.showPage:start:" + page);

        if (pages[page] == null) {
            Fragment fragment = MainPagerAdapter.createFragment(page);
            pages[page] = fragment;
            if (fragment instanceof ChatFragment) chatFragment = (ChatFragment) fragment;
        }
        currentPage = page;

        suppressNavCallback = true;
        bottomNav.setSelectedItemId(pageToNavId(page));
        navDrawer.setCheckedItem(pageToDrawerNavId(page));
        suppressNavCallback = false;

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for (int index = MainPagerAdapter.PAGE_HOME; index <= MainPagerAdapter.PAGE_SETTINGS; index++) {
            Fragment fragment = pages[index];
            if (fragment == null) continue;
            if (index == page) {
                if (fragment.isAdded()) {
                    transaction.show(fragment);
                } else {
                    transaction.add(R.id.view_pager, fragment, pageTag(index));
                }
            } else if (fragment.isAdded()) {
                transaction.hide(fragment);
            }
        }
        transaction.commit();

        if (page == MainPagerAdapter.PAGE_CHAT && !initialPageSelection && chatFragment != null) {
            chatFragment.activate();
        }
        StartupTrace.write(this, "MainActivity.showPage:done:" + page);
    }

    @Override
    protected void onStart() {
        super.onStart();
        StartupTrace.write(this, "MainActivity.onStart");
    }

    @Override
    protected void onResume() {
        super.onResume();
        StartupTrace.write(this, "MainActivity.onResume");
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_SELECTED_PAGE, currentPage);
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
        showPage(page);
    }

    public void openDrawer() {
        if (drawerLayout != null) {
            drawerLayout.openDrawer(GravityCompat.START);
        }
    }

    private String pageTag(int page) {
        return PAGE_TAG_PREFIX + page;
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
        if (drawerNavId == R.id.nav_drawer_system) {
            return new Intent(this, SystemManagementActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_developer) {
            return new Intent(this, com.yadaventerprise.phoneserver.ui.developer.DeveloperPlatformActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_ai) {
            return new Intent(this, com.yadaventerprise.phoneserver.ui.ai.AiAssistantActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_remote) {
            return new Intent(this, RemoteManagementActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_files) {
            return new Intent(this, FilesActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_sharing) {
            return new Intent(this, SharingOverviewActivity.class);
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
        if (drawerNavId == R.id.nav_drawer_ads) {
            return new Intent(this, AdNetworkActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_mail) {
            return new Intent(this, MailActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_dev_docs) {
            return new Intent(this, DeveloperDocsActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_account) {
            return new Intent(this, AccountSettingsActivity.class);
        }
        if (drawerNavId == R.id.nav_drawer_security) {
            return new Intent(this, SecurityCenterActivity.class);
        }
        return null;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
