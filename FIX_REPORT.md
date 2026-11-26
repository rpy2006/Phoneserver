# FIX_REPORT.md

## Fix status

- **FIX-01 — remove ViewPager2 from startup path**: applied; compile VERIFIED; runtime NOT VERIFIED.
  - Files: `app/src/main/java/com/yadaventerprise/phoneserver/ui/main/MainActivity.java`, `app/src/main/java/com/yadaventerprise/phoneserver/ui/main/MainPagerAdapter.java`, `app/src/main/res/layout/activity_main.xml`.
  - Intent: remove `ViewPager2.setAdapter`/`registerOnPageChangeCallback` from the startup path and drive the same five screens with a `FrameLayout` plus explicit fragment transactions.
  - Honest result: the crash did not disappear. The last installed build still died at `MainActivity.onCreate:listenersStart`. This is not claimed as a root-cause fix.

- **FIX-02 — finer startup checkpoints**: applied; compile VERIFIED; runtime NOT VERIFIED.
  - File: `app/src/main/java/com/yadaventerprise/phoneserver/ui/main/MainActivity.java`.
  - Adds `bottomListenerSet`, `drawerListenerStart`, `drawerListenerSet`, `showPage:createStart/createDone`, `showPage:navStart/navDone`, `showPage:transactionStart/transactionCommitted`.
  - Purpose: identify whether death is in listener attachment or fragment creation/transaction.

- **FIX-03 — temporary diagnostic trace class**: applied; compile VERIFIED; runtime VERIFIED only for writing trace lines.
  - File: `app/src/main/java/com/yadaventerprise/phoneserver/util/StartupTrace.java`.
  - Must be removed after diagnosis; it is not a product fix.

No root-cause fix is claimed yet. BUG-01 and BUG-02 remain UNVERIFIED, so no further behavioral rewrite is applied.

## Full changed files (current on-device-build state)

### `app/src/main/java/com/yadaventerprise/phoneserver/ui/main/MainActivity.java`

```java
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

        StartupTrace.write(this, "MainActivity.onCreate:restoreStart");
        for (int page = MainPagerAdapter.PAGE_HOME; page <= MainPagerAdapter.PAGE_SETTINGS; page++) {
            Fragment existing = getSupportFragmentManager().findFragmentByTag(pageTag(page));
            if (existing instanceof ChatFragment) chatFragment = (ChatFragment) existing;
            pages[page] = existing;
        }
        StartupTrace.write(this, "MainActivity.onCreate:restoreDone");

        StartupTrace.write(this, "MainActivity.onCreate:listenersStart");
        bottomNav.setOnItemSelectedListener(item -> {
            if (suppressNavCallback) return true;
            int page = navIdToPage(item.getItemId());
            if (page >= 0) showPage(page);
            return true;
        });
        StartupTrace.write(this, "MainActivity.onCreate:bottomListenerSet");

        StartupTrace.write(this, "MainActivity.onCreate:drawerListenerStart");
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
        StartupTrace.write(this, "MainActivity.onCreate:drawerListenerSet");
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
            StartupTrace.write(this, "MainActivity.showPage:createStart:" + page);
            Fragment fragment = MainPagerAdapter.createFragment(page);
            StartupTrace.write(this, "MainActivity.showPage:createDone:" + page);
            pages[page] = fragment;
            if (fragment instanceof ChatFragment) chatFragment = (ChatFragment) fragment;
        }
        currentPage = page;

        StartupTrace.write(this, "MainActivity.showPage:navStart:" + page);
        suppressNavCallback = true;
        bottomNav.setSelectedItemId(pageToNavId(page));
        navDrawer.setCheckedItem(pageToDrawerNavId(page));
        suppressNavCallback = false;
        StartupTrace.write(this, "MainActivity.showPage:navDone:" + page);

        StartupTrace.write(this, "MainActivity.showPage:transactionStart:" + page);
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
        StartupTrace.write(this, "MainActivity.showPage:transactionCommitted:" + page);

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
```

### `app/src/main/java/com/yadaventerprise/phoneserver/ui/main/MainPagerAdapter.java`

```java
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
```

### `app/src/main/res/layout/activity_main.xml`

```xml
<?xml version="1.0" encoding="utf-8"?>
<androidx.drawerlayout.widget.DrawerLayout xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/drawer_layout"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="@color/home_bg">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent"
        android:orientation="vertical">

        <FrameLayout
            android:id="@+id/view_pager"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1" />

        <com.google.android.material.bottomnavigation.BottomNavigationView
            android:id="@+id/bottom_nav"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:background="@color/home_surface"
            app:itemIconTint="@color/home_accent"
            app:itemTextColor="@color/home_accent"
            app:menu="@menu/bottom_nav_menu" />

    </LinearLayout>

    <com.google.android.material.navigation.NavigationView
        android:id="@+id/nav_drawer"
        android:layout_width="wrap_content"
        android:layout_height="match_parent"
        android:layout_gravity="start"
        android:background="@color/home_surface"
        app:headerLayout="@layout/nav_drawer_header"
        app:itemIconTint="@color/nav_drawer_item_tint"
        app:itemTextColor="@color/nav_drawer_item_tint"
        app:itemHorizontalPadding="20dp"
        app:itemIconPadding="20dp"
        app:itemVerticalPadding="4dp"
        app:menu="@menu/nav_drawer_menu" />

</androidx.drawerlayout.widget.DrawerLayout>
```

### `app/src/main/java/com/yadaventerprise/phoneserver/util/StartupTrace.java`

```java
package com.yadaventerprise.phoneserver.util;

import android.content.Context;
import android.os.Environment;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

public final class StartupTrace {
    private StartupTrace() {
    }

    public static void write(Context context, String event) {
        File[] dirs = {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStorageDirectory(),
                context.getExternalFilesDir(null),
                context.getFilesDir()
        };
        for (File dir : dirs) {
            if (dir == null) continue;
            try {
                File trace = new File(dir, "phoneserver-startup-trace.log");
                FileOutputStream out = new FileOutputStream(trace, true);
                out.write((System.currentTimeMillis() + " " + event + "\n").getBytes(StandardCharsets.UTF_8));
                out.close();
                return;
            } catch (Throwable ignored) {
            }
        }
    }

    public static void write(Context context, String event, Throwable failure) {
        StringWriter writer = new StringWriter();
        PrintWriter printer = new PrintWriter(writer);
        failure.printStackTrace(printer);
        printer.flush();
        write(context, event + "\n" + writer);
    }
}
```
