package max.plus.frp.ui;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.afollestad.materialdialogs.MaterialDialog;
import com.jeremyliao.liveeventbus.LiveEventBus;

import java.util.ArrayList;
import java.util.List;

import max.plus.frp.FrpcService;
import max.plus.frp.FrpsService;
import max.plus.frp.R;
import max.plus.frp.library.Client;
import max.plus.frp.library.FrpLibraryManager;
import max.plus.frp.library.Server;
import max.plus.frp.library.VersionApi;
import max.plus.frp.library.VersionDownloader;
import max.plus.frp.library.VersionApi.VersionInfo;
import max.plus.frp.ui.HomeFragment;
import max.plus.frp.ui.HomeFragmentFrps;

/**
 * 版本管理页面
 * 显示本地和服务器版本，支持切换和下载
 */
public class VersionActivity extends AppCompatActivity {
    private static final String TAG = "VersionActivity";
    
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView statusText;
    private androidx.swiperefreshlayout.widget.SwipeRefreshLayout swipeRefresh;
    private VersionAdapter adapter;
    private FrpLibraryManager libraryManager;
    private VersionApi versionApi;
    private VersionDownloader downloader;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_version);
        
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setHomeButtonEnabled(true);
            getSupportActionBar().setTitle("版本管理");
        }
        
        swipeRefresh = findViewById(R.id.swipeRefresh);
        recyclerView = findViewById(R.id.recyclerView);
        progressBar = findViewById(R.id.progressBar);
        statusText = findViewById(R.id.statusText);
        
        try {
            libraryManager = FrpLibraryManager.getInstance(this);
            versionApi = new VersionApi();
            downloader = new VersionDownloader();
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize VersionActivity", e);
            Toast.makeText(this, "初始化失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        
        adapter = new VersionAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // 下拉刷新
        swipeRefresh.setOnRefreshListener(this::loadVersions);
        
        loadVersions();
    }
    
    private void loadVersions() {
        progressBar.setVisibility(View.VISIBLE);
        statusText.setVisibility(View.VISIBLE);
        statusText.setText("加载中...");
        
        if (libraryManager == null) {
            progressBar.setVisibility(View.GONE);
            statusText.setText("库管理器未初始化");
            return;
        }
        
        // 获取本地版本
        String[] localVersions = libraryManager.getAvailableVersions();
        // 使用 Map 合并本地和远程版本，key 为版本号
        java.util.Map<String, VersionItem> itemMap = new java.util.HashMap<>();
        for (String version : localVersions) {
            VersionItem item = new VersionItem();
            item.version = version;
            item.hasLocal = true;
            item.isCurrent = version.equals(libraryManager.getCurrentVersion());
            itemMap.put(version, item);
        }
        
        // 获取服务器版本
        versionApi.getVersions(new VersionApi.VersionListListener() {
            @Override
            public void onSuccess(List<VersionInfo> serverVersions) {
                runOnUiThread(() -> {
                    // 合并服务器版本到 Map
                    for (VersionInfo serverVersion : serverVersions) {
                        VersionItem existing = itemMap.get(serverVersion.version);
                        if (existing != null) {
                            existing.serverInfo = serverVersion;
                        } else {
                            VersionItem item = new VersionItem();
                            item.version = serverVersion.version;
                            item.hasLocal = false;
                            item.isCurrent = false;
                            item.serverInfo = serverVersion;
                            itemMap.put(serverVersion.version, item);
                        }
                    }

                    List<VersionItem> items = new ArrayList<>(itemMap.values());

                    // 按版本号从大到小排序（字符串比较即可，形如 0.61.1、0.66.0）
                    java.util.Collections.sort(items, (a, b) -> {
                        if (a.version == null) return 1;
                        if (b.version == null) return -1;
                        return b.version.compareTo(a.version);
                    });

                    adapter.setItems(items);
                    progressBar.setVisibility(View.GONE);
                    statusText.setText("");
                    statusText.setVisibility(View.GONE);
                    swipeRefresh.setRefreshing(false);
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Log.e(TAG, "Failed to load server versions: " + error);
                    // 只显示本地版本（Map 中已经有本地）
                    List<VersionItem> items = new ArrayList<>(itemMap.values());
                    // 按版本号从大到小排序
                    java.util.Collections.sort(items, (a, b) -> {
                        if (a.version == null) return 1;
                        if (b.version == null) return -1;
                        return b.version.compareTo(a.version);
                    });
                    adapter.setItems(items);
                    progressBar.setVisibility(View.GONE);
                    // 如果既没有本地也没有远程版本，才显示错误文字；否则隐藏这块区域
                    if (items.isEmpty()) {
                        statusText.setText("无法加载服务器版本");
                        statusText.setVisibility(View.VISIBLE);
                    } else {
                        statusText.setText("");
                        statusText.setVisibility(View.GONE);
                    }
                    swipeRefresh.setRefreshing(false);
                });
            }
        });
    }

    /**
     * 比较两个形如 "0.66.0" 的版本号，返回正数表示 v1 > v2
     */
    private int compareVersion(String v1, String v2) {
        if (v1 == null) v1 = "";
        if (v2 == null) v2 = "";
        String[] p1 = v1.split("\\.");
        String[] p2 = v2.split("\\.");
        int len = Math.max(p1.length, p2.length);
        for (int i = 0; i < len; i++) {
            int n1 = i < p1.length ? parseIntSafe(p1[i]) : 0;
            int n2 = i < p2.length ? parseIntSafe(p2[i]) : 0;
            if (n1 != n2) {
                return n1 - n2;
            }
        }
        return 0;
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }
    
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    private void switchVersion(String version) {
        if (libraryManager == null) {
            Toast.makeText(this, "库管理器未初始化", Toast.LENGTH_SHORT).show();
            return;
        }

        MaterialDialog progress = new MaterialDialog.Builder(this)
                .content("切换版本中...")
                .canceledOnTouchOutside(false)
                .progress(true, 100)
                .show();
        
        new Thread(() -> {
            try {
                // 切换版本前，先停止所有 frpc / frps 以及对应的前台服务
                stopAllFrp();
                
                // 等待服务完全停止，确保旧的 native 库引用被释放
                // 这可以避免切换版本后返回时崩溃（旧的 ClassLoader 还在引用旧的 native 库路径）
                try {
                    Thread.sleep(500); // 等待 500ms
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                boolean success = libraryManager.loadLibrary(version);
                
                // 加载成功后，再等待一段时间确保 native 库完全初始化
                if (success) {
                    try {
                        Thread.sleep(300); // 等待 300ms 让 native 库完全初始化
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                
                runOnUiThread(() -> {
                    progress.dismiss();
                    if (success) {
                        Toast.makeText(this, "已切换到版本 " + version , Toast.LENGTH_SHORT).show();
                        // 切换版本成功后，立即结束进程，让系统自动重启应用
                        // 这样可以彻底清理所有旧的 ClassLoader 引用，避免崩溃
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                // 先启动 MainActivity（系统会在进程结束后自动恢复）
                                Intent intent = new Intent(this, MainActivity.class);
                                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                                startActivity(intent);
                                // 立即结束进程，确保完全重启
                                android.os.Process.killProcess(android.os.Process.myPid());
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to restart app", e);
                                // 如果失败，至少结束进程
                                try {
                                    android.os.Process.killProcess(android.os.Process.myPid());
                                } catch (Exception ex) {
                                    Log.e(TAG, "Failed to kill process", ex);
                                }
                            }
                        }, 800); // 延迟 800ms 让 Toast 显示
                    } else {
                        Toast.makeText(this, "切换失败，请检查版本文件是否存在", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.dismiss();
                    Log.e(TAG, "Failed to switch version", e);
                    Toast.makeText(this, "切换失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        }).start();
    }

    /**
     * 停止所有正在运行的 frpc / frps 以及对应 Service
     * 在切换版本时，直接停止 Service，不调用可能使用旧 ClassLoader 的方法
     */
    private void stopAllFrp() {
        // 直接停止前台 Service，让 Service 自己处理停止逻辑
        // 不调用 Client.getUids() 或 Server.getUids()，避免使用旧的 ClassLoader
        try {
            stopService(new android.content.Intent(this, FrpcService.class));
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop FrpcService", e);
        }

        try {
            stopService(new android.content.Intent(this, FrpsService.class));
        } catch (Exception e) {
            Log.e(TAG, "Failed to stop FrpsService", e);
        }
        
        // 等待 Service 停止
        try {
            Thread.sleep(500); // 增加等待时间，确保 Service 完全停止
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 通知 UI 重置所有播放按钮状态（frpc / frps 列表）
        // 注意：不调用 Client/Server 的方法，避免使用旧的 ClassLoader
        try {
            LiveEventBus.get(HomeFragment.EVENT_RESET_ALL_STATE, Boolean.class).post(true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to post reset state event for HomeFragment", e);
        }
        try {
            LiveEventBus.get(HomeFragmentFrps.EVENT_RESET_ALL_STATE, Boolean.class).post(true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to post reset state event for HomeFragmentFrps", e);
        }
    }
    
    private void downloadVersion(VersionInfo versionInfo) {
        MaterialDialog progress = new MaterialDialog.Builder(this)
                .content("下载中... 0%")
                .canceledOnTouchOutside(false)
                .progress(false, 100) // 显示具体进度
                .show();
        
        downloader.downloadVersion(this, versionInfo, new VersionDownloader.DownloadListener() {
            @Override
            public void onProgress(int progressValue) {
                runOnUiThread(() -> {
                    if (progress.isShowing()) {
                        progress.setProgress(progressValue);
                        progress.setContent("下载中... " + progressValue + "%");
                    }
                });
            }
            
            @Override
            public void onSuccess(String filePath) {
                runOnUiThread(() -> {
                    if (progress.isShowing()) {
                        progress.dismiss();
                    }
                    Toast.makeText(VersionActivity.this, "下载成功", Toast.LENGTH_SHORT).show();
                    loadVersions(); // 重新加载版本列表
                });
            }
            
            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    if (progress.isShowing()) {
                        progress.dismiss();
                    }
                    Toast.makeText(VersionActivity.this, "下载失败: " + error, Toast.LENGTH_SHORT).show();
                });
            }
        });
    }
    
    private class VersionItem {
        String version;
        boolean hasLocal;
        boolean isCurrent;
        VersionInfo serverInfo; // 有则表示远程也存在
    }
    
    // 版本列表 Adapter
    private class VersionAdapter extends RecyclerView.Adapter<VersionAdapter.ViewHolder> {
        private List<VersionItem> items = new ArrayList<>();
        
        public void setItems(List<VersionItem> items) {
            this.items = items;
            notifyDataSetChanged();
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_version, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            VersionItem item = items.get(position);
            String title = item.version + (item.isCurrent ? " (使用中)" : "");
            holder.textVersion.setText(title);

            // 状态文案：已下载 / 未下载
            holder.textSource.setText(item.hasLocal ? "已下载" : "未下载");

            // 主操作按钮：只有“未下载”时显示“下载”，已下载时隐藏（整行点击即可使用）
            if (!item.hasLocal) {
                holder.textAction.setVisibility(View.VISIBLE);
                holder.textAction.setText("下载");
                holder.textAction.setEnabled(true);
                holder.textAction.setAlpha(1.0f);
            } else {
                holder.textAction.setVisibility(View.GONE);
                holder.textAction.setEnabled(false);
                holder.textAction.setAlpha(0.0f);
            }

            // 删除按钮：仅对“已下载且非当前版本”显示
            if (item.hasLocal && !item.isCurrent) {
                holder.textDelete.setVisibility(View.VISIBLE);
                holder.textDelete.setEnabled(true);
                holder.textDelete.setAlpha(1.0f);
            } else {
                holder.textDelete.setVisibility(View.GONE);
                holder.textDelete.setEnabled(false);
                holder.textDelete.setAlpha(0.0f);
            }

            holder.itemView.setOnClickListener(v -> handleItemClick(item));
            holder.textAction.setOnClickListener(v -> handleItemClick(item));
            holder.textDelete.setOnClickListener(v -> handleDeleteClick(item));
        }
        
        @Override
        public int getItemCount() {
            return items.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textVersion;
            TextView textSource;
            TextView textAction;
            TextView textDelete;
            
            ViewHolder(View view) {
                super(view);
                textVersion = view.findViewById(R.id.textVersion);
                textSource = view.findViewById(R.id.textSource);
                textAction = view.findViewById(R.id.textAction);
                textDelete = view.findViewById(R.id.textDelete);
            }
        }
    }

    private void handleItemClick(VersionItem item) {
        // 行点击逻辑：
        // - 已下载且非当前：切换为使用中
        // - 未下载：开始下载该版本
        if (item.hasLocal && !item.isCurrent) {
            switchVersion(item.version);
        } else if (!item.hasLocal && item.serverInfo != null) {
            downloadVersion(item.serverInfo);
        }
    }

    private void handleDeleteClick(VersionItem item) {
        if (!item.hasLocal) return;
        if (item.isCurrent) {
            Toast.makeText(this, "当前使用中的版本不能删除", Toast.LENGTH_SHORT).show();
            return;
        }

        new MaterialDialog.Builder(this)
                .content("确定要删除版本 " + item.version + " 吗？")
                .positiveText("删除")
                .negativeText("取消")
                .onPositive((dialog, which) -> {
                    new Thread(() -> {
                        boolean ok = false;
                        try {
                            ok = libraryManager.deleteVersion(item.version);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to delete version: " + item.version, e);
                        }
                        boolean finalOk = ok;
                        runOnUiThread(() -> {
                            if (finalOk) {
                                Toast.makeText(this, "已删除版本 " + item.version, Toast.LENGTH_SHORT).show();
                                loadVersions();
                            } else {
                                Toast.makeText(this, "删除失败", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }).start();
                })
                .show();
    }
}
