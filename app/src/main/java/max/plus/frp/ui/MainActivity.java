package max.plus.frp.ui;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.text.TextUtils;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import max.plus.frp.CommonUtils;
import max.plus.frp.FrpsService;
import max.plus.frp.R;
import max.plus.frp.database.Config;
import max.plus.frp.library.FrpLibraryManager;
import com.google.android.material.navigation.NavigationView;
import com.jeremyliao.liveeventbus.LiveEventBus;

import butterknife.BindView;
import butterknife.ButterKnife;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class MainActivity extends AppCompatActivity implements NavigationView.OnNavigationItemSelectedListener {

    public static final String EVENT_SET_MENU_ICON = "EVENT_SET_MENU_ICON";
    public static final String EVENT_SET_SERVER_TYPE = "EVENT_SET_SERVER_TYPE";
    public static final String EVENT_KEY_FILE_GETUIDS = "EVENT_KEY_FILE_GETUIDS_MainActivity";
    public static final String EVENT_SET_VERSION_LABEL = "EVENT_SET_VERSION_LABEL";

    @BindView(R.id.toolbar)
    Toolbar toolbar;

    @BindView(R.id.nav_view)
    NavigationView navView;
    @BindView(R.id.drawer_layout)
    DrawerLayout drawerLayout;
    private AppBarConfiguration mAppBarConfiguration;
    NavController navController;
    public static String serverType="frpc";
    public static Boolean homeFrmtIsRdy=false;
    
    // 隐藏菜单项的快速点击检测
    private static final String PREFS_NAME = "hidden_menu_prefs";
    private static final String KEY_HIDDEN_MENU_VISIBLE = "hidden_menu_visible";
    private static final int REQUIRED_CLICKS = 5;
    private static final long CLICK_INTERVAL_MS = 500; // 快速点击的最大间隔（毫秒）
    private long[] clickTimes = new long[REQUIRED_CLICKS];
    private int clickCount = 0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        setSupportActionBar(toolbar);
        mAppBarConfiguration = new AppBarConfiguration.Builder(
                R.id.nav_home_frpc,
                R.id.nav_home_frps)
                .setOpenableLayout(drawerLayout)
                .build();
        navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        NavigationUI.setupActionBarWithNavController(this, navController, mAppBarConfiguration);
        NavigationUI.setupWithNavController(navView, navController);
        navView.setNavigationItemSelectedListener(this);
        
        // 初始化隐藏菜单项的状态
        initHiddenMenuItems();
        
        // 设置快速点击检测（在抽屉的空白区域）
        setupQuickClickDetection();

        toolbar.setNavigationIcon(R.drawable.ic_menu_more_white);
        toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // your stuff
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        LiveEventBus.config().lifecycleObserverAlwaysActive(false).autoClear(true);

        LiveEventBus.get(EVENT_SET_SERVER_TYPE, String.class).observe(this, type -> {
            serverType=type;
        });

        LiveEventBus.get(EVENT_SET_MENU_ICON, Boolean.class).observe(this, bool -> {
            if(bool) toolbar.setNavigationIcon(R.drawable.ic_menu_more_white);
            Menu menu = toolbar.getMenu();
            if (menu != null) {
                // 由于每条配置都可以单独启动，统一启动按钮已不需要
                MenuItem allStartItem = menu.findItem(R.id.action_all_start);
                if (allStartItem != null) {
                    allStartItem.setVisible(false);
                }
                // 确保导入导出按钮始终可见
                MenuItem exportItem = menu.findItem(R.id.action_export);
                MenuItem importItem = menu.findItem(R.id.action_import);
                if (exportItem != null) {
                    exportItem.setVisible(true);
                }
                if (importItem != null) {
                    importItem.setVisible(true);
                }
            }
            invalidateOptionsMenu();
        });

        // 监听版本标签更新事件
        LiveEventBus.get(EVENT_SET_VERSION_LABEL, String.class).observe(this, label -> {
            if (navView != null) {
                Menu menu = navView.getMenu();
                MenuItem versionItem = menu.findItem(R.id.version);
                if (versionItem != null && !TextUtils.isEmpty(label)) {
                    versionItem.setTitle(label);
                }
            }
        });

        // 初始时根据当前版本设置一次菜单标题
        try {
            FrpLibraryManager manager = FrpLibraryManager.getInstance();
            String currentVersion = manager.getCurrentVersion();
            if (!TextUtils.isEmpty(currentVersion) && navView != null) {
                Menu menu = navView.getMenu();
                MenuItem versionItem = menu.findItem(R.id.version);
                if (versionItem != null) {
                    versionItem.setTitle("版本管理 (" + currentVersion + ")");
                }
            }
        } catch (Exception ignored) {
        }

        LiveEventBus.get(EVENT_KEY_FILE_GETUIDS, String.class).observe(this, uids -> {
            Menu menu = toolbar.getMenu();
            if (menu == null) return;
            MenuItem allStartItem = menu.findItem(R.id.action_all_start);
            if (allStartItem == null || !allStartItem.isVisible()) return;
            if (uids.equals("")) {
                allStartItem.setIcon(R.drawable.ic_play_white);
                allStartItem.setTitle(R.string.action_all_start);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    allStartItem.setIconTintList(ColorStateList.valueOf(getResources().getColor(R.color.white)));
                }
            } else {
                allStartItem.setIcon(R.drawable.ic_stop_white);
                allStartItem.setTitle(R.string.action_all_stop);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    allStartItem.setIconTintList(ColorStateList.valueOf(getResources().getColor(R.color.colorStop)));
                }
            }
        });

//        getSupportActionBar().hide();
//        toolbar.setVisibility(View.GONE);
//        ImageView playbtn=findViewById(R.id.iv_play);
//        playbtn.setImageResource(R.drawable.ic_stop_white);
//        toolbar.getMenu().getItem(0).setVisible(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
    }

    @Override
    public void onContentChanged(){
        super.onContentChanged();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int rawId = 0;
        String format = "";
        
        switch (item.getItemId()) {
            case R.id.action_export:
                // 显示确认对话框
                new com.afollestad.materialdialogs.MaterialDialog.Builder(this)
                        .title("导出配置")
                        .content("导出当前界面的所有配置。")
                        .canceledOnTouchOutside(true)
                        .negativeText(R.string.cancel)
                        .positiveText(R.string.done)
                        .onNegative((dialog, which) -> dialog.dismiss())
                        .onPositive((dialog, which) -> {
                            dialog.dismiss();
                            // 通知当前 Fragment 执行导出
                            if (serverType.equals("frps")) {
                                LiveEventBus.get(HomeFragmentFrps.EVENT_EXPORT_CONFIGS).post(true);
                            } else {
                                LiveEventBus.get(HomeFragment.EVENT_EXPORT_CONFIGS).post(true);
                            }
                        })
                        .show();
                return true;
            case R.id.action_import:
                // 显示确认对话框
                new com.afollestad.materialdialogs.MaterialDialog.Builder(this)
                        .title("导入配置")
                        .content("如果配置已存在，将会被更新。")
                        .canceledOnTouchOutside(true)
                        .negativeText(R.string.cancel)
                        .positiveText(R.string.done)
                        .onNegative((dialog, which) -> dialog.dismiss())
                        .onPositive((dialog, which) -> {
                            dialog.dismiss();
                            // 通知当前 Fragment 执行导入
                            if (serverType.equals("frps")) {
                                LiveEventBus.get(HomeFragmentFrps.EVENT_IMPORT_CONFIGS).post(true);
                            } else {
                                LiveEventBus.get(HomeFragment.EVENT_IMPORT_CONFIGS).post(true);
                            }
                        })
                        .show();
                return true;
            case R.id.action_clear_all:
                new com.afollestad.materialdialogs.MaterialDialog.Builder(this)
                        .title(R.string.action_clear_all)
                        .content(R.string.configClearAllConfirm)
                        .canceledOnTouchOutside(true)
                        .negativeText(R.string.cancel)
                        .positiveText(R.string.done)
                        .onNegative((dialog, which) -> dialog.dismiss())
                        .onPositive((dialog, which) -> {
                            dialog.dismiss();
                            if (serverType.equals("frps")) {
                                LiveEventBus.get(HomeFragmentFrps.EVENT_CLEAR_ALL).post(true);
                            } else {
                                LiveEventBus.get(HomeFragment.EVENT_CLEAR_ALL).post(true);
                            }
                        })
                        .show();
                return true;
            case R.id.action_new_ini:
                rawId = serverType.equals("frps") ? R.raw.frps_ini : R.raw.frpc_ini;
                format = "ini";
                break;
            case R.id.action_new_toml:
                rawId = serverType.equals("frps") ? R.raw.frps_toml : R.raw.frpc_toml;
                format = "toml";
                break;
            case R.id.action_new_yaml:
                rawId = serverType.equals("frps") ? R.raw.frps_yaml : R.raw.frpc_yaml;
                format = "yaml";
                break;
            case R.id.action_new_json:
                rawId = serverType.equals("frps") ? R.raw.frps_json : R.raw.frpc_json;
                format = "json";
                break;
            default:
                return super.onOptionsItemSelected(item);
        }
        
        if (rawId != 0) {
            final String finalFormat = format;
            CommonUtils.getStringFromRaw(this, rawId)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<String>() {
                        @Override
                        public void onSubscribe(@NonNull Disposable d) {

                        }

                        @Override
                        public void onNext(@NonNull String content) {
                            Config config = new Config(content);
                            config.setFormat(finalFormat);
                            LiveEventBus.get(IniEditActivity.INTENT_EDIT_INI).post(config);
                            startActivity(new Intent(getApplicationContext(), IniEditActivity.class));

                        }

                        @Override
                        public void onError(@NonNull Throwable e) {

                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        }
        return super.onOptionsItemSelected(item);
    }


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);
        // 由于每条配置都可以单独启动，统一启动按钮已不需要
        menu.findItem(R.id.action_all_start).setVisible(false);
        // 确保导入导出按钮始终可见
        menu.findItem(R.id.action_export).setVisible(true);
        menu.findItem(R.id.action_import).setVisible(true);
        return true;
    }
    
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // 每次准备菜单时，确保导入导出按钮始终可见
        MenuItem exportItem = menu.findItem(R.id.action_export);
        MenuItem importItem = menu.findItem(R.id.action_import);
        if (exportItem != null) {
            exportItem.setVisible(true);
        }
        if (importItem != null) {
            importItem.setVisible(true);
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment);
        return NavigationUI.navigateUp(navController, mAppBarConfiguration) || super.onSupportNavigateUp();
    }


    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        Intent intent=new Intent();
        ComponentName cn;
        switch (item.getItemId()) {
            case R.id.nav_home_frps:
                if(!homeFrmtIsRdy) break;
                //navController.getGraph().clear();
                LiveEventBus.get(EVENT_SET_SERVER_TYPE).post("frps");
                navController.navigate(R.id.to_nav_home_frps);
                //startActivity(new Intent(this, MainActivityFrps.class));
                //overridePendingTransition(R.anim.nav_default_enter_anim, R.anim.nav_default_enter_anim);
                //finish();
                break;

            case R.id.nav_home_frpc:
                if(!homeFrmtIsRdy) break;
                //navController.getGraph().clear();
                LiveEventBus.get(EVENT_SET_SERVER_TYPE).post("frpc");
                navController.navigate(R.id.to_nav_home_frpc);
                break;

            case R.id.nav_home_fwd:
                try {
                    cn = new ComponentName("com.elixsr.portforwarder", "com.elixsr.portforwarder.ui.MainActivity");
                    intent.setComponent(cn);
                    if (intent != null) {
                        startActivity(intent);
                    } else {
                        Toast.makeText(this, "应用未安装", Toast.LENGTH_SHORT).show();
                        gourl("https://elix.sr/fwd");//"https://github.com/elixsr/FwdPortForwardingApp"
                    }
                }catch (Exception e){
                    Toast.makeText(this, "应用未安装", Toast.LENGTH_SHORT).show();
                    gourl("https://elix.sr/fwd");//"https://github.com/elixsr/FwdPortForwardingApp"
                }
                break;

            case R.id.nav_home_clash:
                try {
                    //intent.setPackage("com.github.kr328.clash");
                    intent.setPackage("com.github.metacubex.clash.meta");
                    if (intent != null) {
                        intent.setAction(Intent.ACTION_MAIN);
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(intent);
                    } else {
                        Toast.makeText(this, "应用未安装", Toast.LENGTH_SHORT).show();
                        gourl("https://github.com/MetaCubeX/ClashMetaForAndroid/releases");//gourl("https://github.com/Kr328/ClashForAndroid/releases");
                    }
                }catch (Exception e){
                    Toast.makeText(this, "应用未安装", Toast.LENGTH_SHORT).show();
                    gourl("https://github.com/MetaCubeX/ClashMetaForAndroid/releases");//gourl("https://github.com/Kr328/ClashForAndroid/releases");
                }
                break;

            case R.id.nav_home_v2rayng:
                try {
                    cn = new ComponentName("com.v2ray.ang", "com.v2ray.ang.ui.MainActivity");
                    intent.setComponent(cn);
                    if (intent != null) {
                        startActivity(intent);
                    } else {
                        Toast.makeText(this, "应用未安装", Toast.LENGTH_SHORT).show();
                        gourl("https://github.com/2dust/v2rayNG/releases");
                    }
                }catch (Exception e){
                    Toast.makeText(this, "应用未安装", Toast.LENGTH_SHORT).show();
                    gourl("https://github.com/2dust/v2rayNG/releases");
                }
                break;

            case R.id.logcat:
                startActivity(new Intent(this, LogcatActivity.class));
                break;

            case R.id.version:
                startActivity(new Intent(this, VersionActivity.class));
                break;

            case R.id.repository:
                startActivity(new Intent(this, AboutActivity.class));
                break;

        }
        drawerLayout.closeDrawer(GravityCompat.START);
        return super.onOptionsItemSelected(item);
    }

    private void gourl(String url){
        Uri uri = Uri.parse(url);
        Intent it = new Intent(Intent.ACTION_VIEW, uri);
        startActivity(it);
    }
    
    /**
     * 初始化隐藏菜单项的状态（从 SharedPreferences 读取）
     */
    private void initHiddenMenuItems() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean isVisible = prefs.getBoolean(KEY_HIDDEN_MENU_VISIBLE, false);
        setHiddenMenuItemsVisibility(isVisible);
    }
    
    /**
     * 设置隐藏菜单项的可见性
     */
    private void setHiddenMenuItemsVisibility(boolean visible) {
        Menu menu = navView.getMenu();
        MenuItem itemFwd = menu.findItem(R.id.nav_home_fwd);
        MenuItem itemClash = menu.findItem(R.id.nav_home_clash);
        MenuItem itemV2rayng = menu.findItem(R.id.nav_home_v2rayng);
        
        if (itemFwd != null) {
            itemFwd.setVisible(visible);
        }
        if (itemClash != null) {
            itemClash.setVisible(visible);
        }
        if (itemV2rayng != null) {
            itemV2rayng.setVisible(visible);
        }
    }
    
    /**
     * 保存隐藏菜单项的状态到 SharedPreferences
     */
    private void saveHiddenMenuItemsState(boolean visible) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_HIDDEN_MENU_VISIBLE, visible).apply();
    }
    
    /**
     * 设置快速点击检测（在抽屉的空白区域）
     */
    private void setupQuickClickDetection() {
        // 给整个 nav_header 添加点击监听
        View headerView = navView.getHeaderView(0);
        if (headerView != null) {
            headerView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    handleQuickClick();
                }
            });
            // 确保 header 可以点击
            headerView.setClickable(true);
        }
    }
    
    /**
     * 处理快速点击
     */
    private void handleQuickClick() {
        long currentTime = System.currentTimeMillis();
        
        // 如果这是第一次点击，初始化数组
        if (clickCount == 0) {
            clickTimes[0] = currentTime;
            clickCount = 1;
            return;
        }
        
        // 检查距离上次点击的时间
        long timeSinceLastClick = currentTime - clickTimes[clickCount - 1];
        
        // 如果距离上次点击超过间隔时间，重置计数
        if (timeSinceLastClick > CLICK_INTERVAL_MS) {
            clickCount = 0;
            clickTimes[0] = currentTime;
            clickCount = 1;
            return;
        }
        
        // 记录当前点击时间
        clickTimes[clickCount] = currentTime;
        clickCount++;
        
        // 如果达到要求的点击次数
        if (clickCount >= REQUIRED_CLICKS) {
            // 检查所有点击是否都在快速点击范围内
            boolean isQuickClick = true;
            for (int i = 1; i < REQUIRED_CLICKS; i++) {
                if ((clickTimes[i] - clickTimes[i - 1]) > CLICK_INTERVAL_MS) {
                    isQuickClick = false;
                    break;
                }
            }
            
            if (isQuickClick) {
                // 切换隐藏菜单项的可见性
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                boolean currentVisible = prefs.getBoolean(KEY_HIDDEN_MENU_VISIBLE, false);
                boolean newVisible = !currentVisible;
                setHiddenMenuItemsVisibility(newVisible);
                saveHiddenMenuItemsState(newVisible);
            }
            
            // 重置计数（无论是否成功切换）
            clickCount = 0;
        }
    }

}
