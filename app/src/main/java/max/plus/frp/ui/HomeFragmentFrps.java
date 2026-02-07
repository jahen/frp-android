package max.plus.frp.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.afollestad.materialdialogs.MaterialDialog;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import max.plus.frp.CommonUtils;
import max.plus.frp.FrpsService;
import max.plus.frp.R;
import max.plus.frp.adapter.FileListAdapterFrps;
import max.plus.frp.database.Config;
import max.plus.frp.database.FrpsDatabase;
import com.jeremyliao.liveeventbus.LiveEventBus;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;
import max.plus.frp.library.Server;
import io.reactivex.CompletableObserver;
import io.reactivex.SingleObserver;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

public class HomeFragmentFrps extends Fragment {
    public static final String EVENT_UPDATE_CONFIG = "EVENT_UPDATE_CONFIG_FRPS";
    public static final String EVENT_RUNNING_ERROR = "EVENT_RUNNING_ERROR_FRPS";
    // 切换 FRP 版本时重置所有条目的运行状态（播放按钮状态）
    public static final String EVENT_RESET_ALL_STATE = "EVENT_RESET_ALL_STATE_FRPS";
    // 导入导出事件
    public static final String EVENT_EXPORT_CONFIGS = "EVENT_EXPORT_CONFIGS_FRPS";
    public static final String EVENT_IMPORT_CONFIGS = "EVENT_IMPORT_CONFIGS_FRPS";
    public static final String EVENT_CLEAR_ALL = "EVENT_CLEAR_ALL_FRPS";
    
    private static final int REQUEST_CODE_IMPORT_FILE = 1002;
    private static final int REQUEST_CODE_EXPORT_FILE = 1003;
    private static final String PREFS_EXPORT_DIR = "export_dir_prefs";
    private static final String KEY_EXPORT_DIR = "export_dir_frps";
    private String exportJsonData; // 临时保存要导出的 JSON 数据

    @BindView(R.id.recyclerView)
    RecyclerView recyclerView;
    @BindView(R.id.refreshView)
    SwipeRefreshLayout refreshView;

    private Unbinder bind;
    private FileListAdapterFrps listAdapter;
    private ItemTouchHelper itemTouchHelper;


    public View onCreateView(@NonNull LayoutInflater inflater,
                             ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_home, container, false);
        bind = ButterKnife.bind(this, root);
        LiveEventBus.get(MainActivity.EVENT_SET_MENU_ICON).post(true);
        MainActivity.homeFrmtIsRdy=false;
        init();
        return root;
    }

    private void init() {
        listAdapter = new FileListAdapterFrps();
        listAdapter.addChildClickViewIds(R.id.iv_play, R.id.iv_delete, R.id.iv_edit);

        listAdapter.setOnItemChildClickListener((adapter, view, position) -> {
            Config item = listAdapter.getItem(position);
            // 如果正在启动，禁用所有按钮点击
            if (item.getStarting() != null && item.getStarting()) {
                return;
            }
            if (view.getId() == R.id.iv_play) {
                boolean serviceAlreadyRunning = CommonUtils.isServiceRunning(FrpsService.class.getName(), getContext());
                if (!serviceAlreadyRunning) {
                    getContext().startService(new Intent(getContext(), FrpsService.class));
                }
                boolean isRunning = Server.isRunning(item.getUid());
                boolean isConnecting = (item.getConnecting() != null && item.getConnecting());
                Log.e("HomeFragmentFrps", "Play button clicked - uid: " + item.getUid() + ", Server.isRunning: " + isRunning + ", connecting: " + isConnecting);
                if (isRunning || isConnecting) {
                    Log.e("HomeFragmentFrps", "Stopping uid: " + item.getUid());
                    // 由于 frps 运行在独立进程，需要通过事件通知独立进程执行 Server.close
                    // 类似启动时通过 INTENT_KEY_FILE 事件通知独立进程执行 Server.runContent
                    LiveEventBus.get(FrpsService.INTENT_KEY_FILE_STOP).postAcrossProcess(item.getUid());
                    item.setConnecting(false);
                    listAdapter.notifyItemChanged(position);
                    checkAndStopService();
                    return;
                }
                
                final String uid = item.getUid();
                
                // 如果服务已经运行，说明已经 ready，直接发送启动事件
                if (serviceAlreadyRunning) {
                    item.setStarting(true);
                    listAdapter.notifyItemChanged(position);
                    Log.d("HomeFragmentFrps", "Service already running, sending INTENT_KEY_FILE immediately for uid: " + uid);
                    LiveEventBus.get(FrpsService.INTENT_KEY_FILE).postAcrossProcess(uid);
                    return;
                }
                
                // 服务未运行（首次或全部停止后再启动）。ready 在 Service onCreate 末尾发送，
                // 必须先注册观察者再 startService，否则 ready 发出时我们还没监听会漏接。
                final boolean[] readyReceived = {false};
                final androidx.lifecycle.Observer<Boolean>[] holder = new androidx.lifecycle.Observer[1];
                holder[0] = ready -> {
                    if (!readyReceived[0] && ready != null && ready) {
                        readyReceived[0] = true;
                        Log.d("HomeFragmentFrps", "FrpsService ready, sending INTENT_KEY_FILE for uid: " + uid);
                        LiveEventBus.get(FrpsService.INTENT_KEY_FILE).postAcrossProcess(uid);
                        LiveEventBus.get(FrpsService.EVENT_SERVICE_READY, Boolean.class).removeObserver(holder[0]);
                    }
                };
                LiveEventBus.get(FrpsService.EVENT_SERVICE_READY, Boolean.class).observe(HomeFragmentFrps.this, holder[0]);
                
                CommonUtils.waitService(FrpsService.class.getName(), getContext())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new CompletableObserver() {
                            MaterialDialog progress;

                            @Override
                            public void onSubscribe(Disposable d) {
                                progress = new MaterialDialog.Builder(getContext())
                                        .content(R.string.tipWaitService)
                                        .canceledOnTouchOutside(false)
                                        .progress(true, 100)
                                        .show();
                                item.setStarting(true);
                                listAdapter.notifyItemChanged(position);
                            }

                            @Override
                            public void onComplete() {
                                progress.dismiss();
                            }

                            @Override
                            public void onError(Throwable e) {
                                if (progress != null) progress.dismiss();
                            }
                        });
                return;
            }

            // 检查是否正在运行，如果正在运行则不能编辑或删除
            // 注意：frps 运行在独立进程，使用 connecting 状态来判断更可靠
            boolean isRunning = (item.getConnecting() != null && item.getConnecting()) || Server.isRunning(item.getUid());
            if (isRunning) {
                Toast.makeText(getContext(), getResources().getText(R.string.tipServiceRunning), Toast.LENGTH_SHORT).show();
                return;
            }
            // 检查是否正在启动
            if (item.getStarting() != null && item.getStarting()) {
                return;
            }
            if (view.getId() == R.id.iv_edit) {
                editConfig(position);
                return;
            }
            if (view.getId() == R.id.iv_delete) {
                new MaterialDialog.Builder(getContext())
                        .title(R.string.dialogConfirmTitle)
                        .content(R.string.configDeleteConfirm)
                        .canceledOnTouchOutside(false)
                        .negativeText(R.string.cancel)
                        .positiveText(R.string.done)
                        .onNegative((dialog, which) -> dialog.dismiss())
                        .onPositive((dialog, which) -> deleteConfig(position))
                        .show();
            }
        });
        recyclerView.setAdapter(listAdapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext(), RecyclerView.VERTICAL, false));
        setupDragAndDrop();
        refreshView.setOnRefreshListener(() -> getData());
        LiveEventBus.get(EVENT_UPDATE_CONFIG, Config.class).observe(this, config -> {
            int position = listAdapter.getData().indexOf(config);
            if (position < 0) {
                listAdapter.addData(config);
            } else {
                listAdapter.notifyItemChanged(position);
            }
        });
        LiveEventBus.get(EVENT_RUNNING_ERROR, String.class).observe(this, uid -> {

            int position = listAdapter.getData().indexOf(new Config().setUid(uid));
            Config item = listAdapter.getItem(position);
            item.setConnecting(false);
            item.setStarting(false); // 清除正在启动状态，恢复按钮可用
            listAdapter.notifyItemChanged(position);
            checkAndStopService();
        });

        // 切换版本时统一重置所有条目的播放状态（连接中 / 启动中）
        LiveEventBus.get(EVENT_RESET_ALL_STATE, Boolean.class).observe(this, ignore -> {
            if (listAdapter == null || listAdapter.getData() == null) {
                return;
            }
            for (Config config : listAdapter.getData()) {
                config.setConnecting(false);
                config.setStarting(false);
            }
            listAdapter.notifyDataSetChanged();
            checkAndStopService();
        });

        // 监听运行中的 uid 列表，恢复 connecting 状态（刷新页面时使用）
        LiveEventBus.get(MainActivity.EVENT_KEY_FILE_GETUIDS, String.class).observe(this, runningUids -> {
            Log.e("HomeFragmentFrps", "EVENT_KEY_FILE_GETUIDS received: " + runningUids);
            if (TextUtils.isEmpty(runningUids)) {
                // 没有运行中的配置，全部标记为未运行
                for (Config config : listAdapter.getData()) {
                    config.setConnecting(false);
                    config.setStarting(false); // 清除正在启动状态
                }
                // 如果一条成功的都没有，停止服务
                checkAndStopService();
            } else {
                // 解析运行中的 uid 列表，恢复 connecting 状态
                String[] runningUidArray = runningUids.split(",");
                for (Config config : listAdapter.getData()) {
                    boolean isRunning = false;
                    for (String uid : runningUidArray) {
                        if (config.getUid().equals(uid.trim())) {
                            isRunning = true;
                            break;
                        }
                    }
                    config.setConnecting(isRunning);
                    // 如果已运行，清除正在启动状态
                    if (isRunning) {
                        config.setStarting(false);
                    }
                }
            }
            // 通知适配器刷新显示
            listAdapter.notifyDataSetChanged();
        });


        recyclerView.postDelayed(this::getData, 1500);
        
        // 监听导入导出事件
        LiveEventBus.get(EVENT_EXPORT_CONFIGS, Boolean.class).observe(this, ignore -> exportConfigs());
        LiveEventBus.get(EVENT_IMPORT_CONFIGS, Boolean.class).observe(this, ignore -> importConfigs());
        LiveEventBus.get(EVENT_CLEAR_ALL, Boolean.class).observe(this, ignore -> clearAll());

    }

    private void setupDragAndDrop() {
        ItemTouchHelper.Callback callback = new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int fromPos = viewHolder.getAdapterPosition();
                int toPos = target.getAdapterPosition();
                if (fromPos == RecyclerView.NO_POSITION || toPos == RecyclerView.NO_POSITION) return false;
                List<Config> data = listAdapter.getData();
                Config moved = data.remove(fromPos);
                data.add(toPos, moved);
                listAdapter.notifyItemMoved(fromPos, toPos);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                // 不处理滑动删除
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                saveSortOrderToDb();
            }
        };
        itemTouchHelper = new ItemTouchHelper(callback);
        itemTouchHelper.attachToRecyclerView(recyclerView);
        listAdapter.setOnItemLongClickListener((adapter, view, position) -> {
            Config item = listAdapter.getItem(position);
            if (item.getConnecting() != null && item.getConnecting()) return false;
            if (item.getStarting() != null && item.getStarting()) return false;
            RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                itemTouchHelper.startDrag(holder);
            }
            return true;
        });
    }

    private void saveSortOrderToDb() {
        List<Config> list = listAdapter.getData();
        if (list == null || list.isEmpty()) return;
        for (int i = 0; i < list.size(); i++) {
            list.get(i).setSortOrder(i);
        }
        for (Config c : list) {
            FrpsDatabase.getInstance(getContext()).configDao()
                    .update(c)
                    .subscribeOn(Schedulers.io())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe();
        }
    }

    private void checkAndStopService() {
        // 检查是否有任何配置在运行（根据 connecting 状态判断）
        // 由于 frps 运行在独立进程，主进程中无法可靠调用 Server.getUids()
        boolean hasRunning = false;
        for (Config config : listAdapter.getData()) {
            if (config.getConnecting() != null && config.getConnecting()) {
                hasRunning = true;
                break;
            }
        }
        // 如果没有运行中的配置，停止服务（与 frpc 逻辑一致）
        if (!hasRunning) {
            getContext().stopService(new Intent(getContext(), FrpsService.class));
        }
    }


    private void editConfig(int position) {
        Config item = listAdapter.getItem(position);
        LiveEventBus.get(IniEditActivity.INTENT_EDIT_INI).post(item);
        startActivity(new Intent(getContext(), IniEditActivity.class));

    }

    private void deleteConfig(int position) {
        FrpsDatabase.getInstance(getContext())
                .configDao()
                .delete(listAdapter.getItem(position))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {

                    }

                    @Override
                    public void onComplete() {
                        listAdapter.removeAt(position);

                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Toast.makeText(getContext(), e.getMessage(), Toast.LENGTH_SHORT).show();

                    }
                });

    }

    private void clearAll() {
        if (listAdapter.getData() == null || listAdapter.getData().isEmpty()) {
            Toast.makeText(getContext(), R.string.configClearAllEmpty, Toast.LENGTH_SHORT).show();
            return;
        }
        for (Config c : listAdapter.getData()) {
            if (c.getConnecting() != null && c.getConnecting()) {
                LiveEventBus.get(FrpsService.INTENT_KEY_FILE_STOP).postAcrossProcess(c.getUid());
            }
        }
        FrpsDatabase.getInstance(getContext())
                .configDao()
                .deleteAll()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new CompletableObserver() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {}

                    @Override
                    public void onComplete() {
                        listAdapter.setList(new ArrayList<>());
                        checkAndStopService();
                        Toast.makeText(getContext(), R.string.configClearAllSuccess, Toast.LENGTH_SHORT).show();
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Toast.makeText(getContext(), "清除失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void getData() {
        FrpsDatabase.getInstance(getContext())
                .configDao()
                .getAll()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleObserver<List<Config>>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                        refreshView.setRefreshing(true);
                        MainActivity.homeFrmtIsRdy=true;
                    }

                    @Override
                    public void onSuccess(@NonNull List<Config> configs) {
                        refreshView.setRefreshing(false);
                        listAdapter.setList(configs);
                        MainActivity.homeFrmtIsRdy=true;
                        // 触发获取运行中的 uid 列表，恢复 connecting 状态
                        // 延迟触发，确保库已完全初始化，避免在版本切换期间崩溃
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                            try {
                                if (max.plus.frp.library.FrpLibraryManager.getInstance().isLibraryLoaded()) {
                                    Log.e("HomeFragmentFrps", "getData() - triggering EVENT_KEY_FILE_ISRUNNING");
                                    LiveEventBus.get(FrpsService.EVENT_KEY_FILE_ISRUNNING).postAcrossProcess("");
                                } else {
                                    Log.w("HomeFragmentFrps", "Library not loaded, skipping EVENT_KEY_FILE_ISRUNNING");
                                }
                            } catch (Exception e) {
                                Log.w("HomeFragmentFrps", "Failed to check library status or post event: " + e.getMessage());
                            }
                        }, 500); // 延迟 500ms，确保库已完全初始化
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        refreshView.setRefreshing(false);
                        MainActivity.homeFrmtIsRdy=true;
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        bind.unbind();
    }
    
    /**
     * 导出所有配置到文件
     */
    private void exportConfigs() {
        FrpsDatabase.getInstance(getContext())
                .configDao()
                .getAll()
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleObserver<List<Config>>() {
                    @Override
                    public void onSubscribe(@NonNull Disposable d) {
                    }

                    @Override
                    public void onSuccess(@NonNull List<Config> configs) {
                        if (configs == null || configs.isEmpty()) {
                            Toast.makeText(getContext(), "没有配置可导出", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        
                        // 转换为导出格式（导出 uid, name, cfg, format, sortOrder）
                        List<ConfigExport> exportList = new ArrayList<>();
                        for (int i = 0; i < configs.size(); i++) {
                            Config config = configs.get(i);
                            ConfigExport export = new ConfigExport();
                            export.uid = config.getUid();
                            export.name = config.getName();
                            export.cfg = config.getCfg();
                            export.format = config.getFormat();
                            export.sortOrder = i;
                            exportList.add(export);
                        }
                        
                        // 生成 JSON 数据
                        Gson gson = new GsonBuilder().setPrettyPrinting().create();
                        exportJsonData = gson.toJson(exportList);
                        
                        // 生成文件名（带时间戳）
                        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
                        String fileName = "frps_configs_export_" + sdf.format(new Date()) + ".json";
                        
                        // 让用户选择保存位置
                        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                        intent.setType("application/json");
                        intent.addCategory(Intent.CATEGORY_OPENABLE);
                        intent.putExtra(Intent.EXTRA_TITLE, fileName);
                        
                        // 尝试设置初始目录（Android 10+）
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREFS_EXPORT_DIR, android.content.Context.MODE_PRIVATE);
                            String lastExportDir = prefs.getString(KEY_EXPORT_DIR, null);
                            
                            // 如果没有上次导出目录，使用公共下载目录
                            if (lastExportDir == null || lastExportDir.isEmpty()) {
                                File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                                if (downloadsDir.exists()) {
                                    lastExportDir = downloadsDir.getAbsolutePath();
                                }
                            }
                            
                            if (lastExportDir != null && !lastExportDir.isEmpty()) {
                                try {
                                    File dir = new File(lastExportDir);
                                    if (dir.exists() && dir.isDirectory()) {
                                        String relativePath = lastExportDir;
                                        String externalStoragePath = android.os.Environment.getExternalStorageDirectory().getPath();
                                        if (lastExportDir.startsWith(externalStoragePath)) {
                                            relativePath = lastExportDir.substring(externalStoragePath.length());
                                            if (relativePath.startsWith("/")) {
                                                relativePath = relativePath.substring(1);
                                            }
                                            android.net.Uri treeUri = android.provider.DocumentsContract.buildTreeDocumentUri(
                                                "com.android.externalstorage.documents",
                                                "primary:" + relativePath
                                            );
                                            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, treeUri);
                                        }
                                    }
                                } catch (Exception e) {
                                    // 忽略错误
                                }
                            }
                        }
                        
                        try {
                            startActivityForResult(Intent.createChooser(intent, "选择保存位置"), REQUEST_CODE_EXPORT_FILE);
                        } catch (android.content.ActivityNotFoundException ex) {
                            Toast.makeText(getContext(), "未找到文件选择器", Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(@NonNull Throwable e) {
                        Toast.makeText(getContext(), "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }
    
    /**
     * 从文件导入配置
     */
    private void importConfigs() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        
        // 尝试设置初始目录（Android 10+）
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREFS_EXPORT_DIR, android.content.Context.MODE_PRIVATE);
            String lastExportDir = prefs.getString(KEY_EXPORT_DIR, null);
            
            // 如果没有上次导出目录，使用公共下载目录
            if (lastExportDir == null || lastExportDir.isEmpty()) {
                File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS);
                if (downloadsDir.exists()) {
                    lastExportDir = downloadsDir.getAbsolutePath();
                }
            }
            
            if (lastExportDir != null && !lastExportDir.isEmpty()) {
                try {
                    File dir = new File(lastExportDir);
                    if (dir.exists() && dir.isDirectory()) {
                        // 构建目录 URI（适用于外部存储）
                        String relativePath = lastExportDir;
                        String externalStoragePath = android.os.Environment.getExternalStorageDirectory().getPath();
                        if (lastExportDir.startsWith(externalStoragePath)) {
                            relativePath = lastExportDir.substring(externalStoragePath.length());
                            if (relativePath.startsWith("/")) {
                                relativePath = relativePath.substring(1);
                            }
                            // 构建 tree URI
                            android.net.Uri treeUri = android.provider.DocumentsContract.buildTreeDocumentUri(
                                "com.android.externalstorage.documents",
                                "primary:" + relativePath
                            );
                            intent.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, treeUri);
                        }
                    }
                } catch (Exception e) {
                    // 忽略错误，继续使用默认行为
                }
            }
        }
        
        try {
            startActivityForResult(Intent.createChooser(intent, "选择配置文件"), REQUEST_CODE_IMPORT_FILE);
        } catch (android.content.ActivityNotFoundException ex) {
            // 如果 ACTION_OPEN_DOCUMENT 不可用，回退到 ACTION_GET_CONTENT
            Intent fallbackIntent = new Intent(Intent.ACTION_GET_CONTENT);
            fallbackIntent.setType("*/*");
            fallbackIntent.addCategory(Intent.CATEGORY_OPENABLE);
            try {
                startActivityForResult(Intent.createChooser(fallbackIntent, "选择配置文件"), REQUEST_CODE_IMPORT_FILE);
            } catch (android.content.ActivityNotFoundException ex2) {
                Toast.makeText(getContext(), "未找到文件选择器", Toast.LENGTH_SHORT).show();
            }
        }
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == android.app.Activity.RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            if (requestCode == REQUEST_CODE_IMPORT_FILE) {
                importConfigsFromUri(uri);
            } else if (requestCode == REQUEST_CODE_EXPORT_FILE) {
                exportConfigsToUri(uri);
            }
        }
    }
    
    /**
     * 导出配置到用户选择的 URI
     */
    private void exportConfigsToUri(Uri uri) {
        if (exportJsonData == null || exportJsonData.isEmpty()) {
            Toast.makeText(getContext(), "导出数据为空", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            // 写入文件
            android.content.ContentResolver resolver = getContext().getContentResolver();
            java.io.OutputStream outputStream = resolver.openOutputStream(uri);
            if (outputStream != null) {
                OutputStreamWriter writer = new OutputStreamWriter(outputStream, StandardCharsets.UTF_8);
                writer.write(exportJsonData);
                writer.close();
                outputStream.close();
                
                // 保存导出目录路径（从 URI 获取）
                try {
                    String uriString = uri.toString();
                    // 尝试从 URI 中提取路径信息
                    if (uriString.contains("primary:")) {
                        String pathPart = uriString.substring(uriString.indexOf("primary:") + 8);
                        if (pathPart.contains("%")) {
                            pathPart = java.net.URLDecoder.decode(pathPart, "UTF-8");
                        }
                        File externalStorage = android.os.Environment.getExternalStorageDirectory();
                        String fullPath = externalStorage.getPath() + "/" + pathPart;
                        // 获取目录路径（去掉文件名）
                        File file = new File(fullPath);
                        File parentDir = file.getParentFile();
                        if (parentDir != null && parentDir.exists()) {
                            android.content.SharedPreferences prefs = getContext().getSharedPreferences(PREFS_EXPORT_DIR, android.content.Context.MODE_PRIVATE);
                            prefs.edit().putString(KEY_EXPORT_DIR, parentDir.getAbsolutePath()).apply();
                        }
                    }
                } catch (Exception e) {
                    // 忽略路径保存错误
                }
                
                Toast.makeText(getContext(), "导出成功", Toast.LENGTH_SHORT).show();
                exportJsonData = null; // 清空临时数据
            } else {
                Toast.makeText(getContext(), "无法写入文件", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "导出失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 从 URI 导入配置
     */
    private void importConfigsFromUri(Uri uri) {
        try {
            // 读取文件内容
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(getContext().getContentResolver().openInputStream(uri), StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            
            String json = sb.toString();
            
            // 解析 JSON
            Gson gson = new Gson();
            Type listType = new TypeToken<List<ConfigExport>>() {}.getType();
            List<ConfigExport> exportList = gson.fromJson(json, listType);
            
            if (exportList == null || exportList.isEmpty()) {
                Toast.makeText(getContext(), "文件格式错误或为空", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 转换为 Config 并导入数据库，保持 sortOrder 顺序
            List<Config> configs = new ArrayList<>();
            for (int i = 0; i < exportList.size(); i++) {
                ConfigExport export = exportList.get(i);
                Config config = new Config(export.uid, export.name, export.cfg);
                config.setFormat(export.format);
                config.setSortOrder(export.sortOrder >= 0 ? export.sortOrder : i);
                configs.add(config);
            }
            
            // 批量插入数据库（循环插入，如果 UID 已存在则更新）
            final int[] insertCount = {0}; // 新增数量
            final int[] updateCount = {0}; // 更新数量
            final int[] failCount = {0};
            final int totalCount = configs.size();
            
            for (Config config : configs) {
                // 先检查是否存在
                FrpsDatabase.getInstance(getContext())
                        .configDao()
                        .getConfigByUid(config.getUid())
                        .subscribeOn(Schedulers.io())
                        .observeOn(AndroidSchedulers.mainThread())
                        .subscribe(new SingleObserver<Config>() {
                            @Override
                            public void onSubscribe(@NonNull Disposable d) {
                            }

                            @Override
                            public void onSuccess(@NonNull Config existingConfig) {
                                // 已存在，更新
                                config.setUid(existingConfig.getUid());
                                FrpsDatabase.getInstance(getContext())
                                        .configDao()
                                        .update(config)
                                        .subscribeOn(Schedulers.io())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(new CompletableObserver() {
                                            @Override
                                            public void onSubscribe(@NonNull Disposable d) {
                                            }

                                            @Override
                                            public void onComplete() {
                                                updateCount[0]++;
                                                checkImportComplete(totalCount, insertCount[0], updateCount[0], failCount[0]);
                                            }

                                            @Override
                                            public void onError(@NonNull Throwable e) {
                                                failCount[0]++;
                                                checkImportComplete(totalCount, insertCount[0], updateCount[0], failCount[0]);
                                            }
                                        });
                            }

                            @Override
                            public void onError(@NonNull Throwable e) {
                                // 不存在，插入
                                FrpsDatabase.getInstance(getContext())
                                        .configDao()
                                        .insert(config)
                                        .subscribeOn(Schedulers.io())
                                        .observeOn(AndroidSchedulers.mainThread())
                                        .subscribe(new CompletableObserver() {
                                            @Override
                                            public void onSubscribe(@NonNull Disposable d) {
                                            }

                                            @Override
                                            public void onComplete() {
                                                insertCount[0]++;
                                                checkImportComplete(totalCount, insertCount[0], updateCount[0], failCount[0]);
                                            }

                                            @Override
                                            public void onError(@NonNull Throwable e) {
                                                failCount[0]++;
                                                checkImportComplete(totalCount, insertCount[0], updateCount[0], failCount[0]);
                                            }
                                        });
                            }
                        });
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(getContext(), "导入失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 检查导入是否完成
     */
    private void checkImportComplete(int totalCount, int insertCount, int updateCount, int failCount) {
        if (insertCount + updateCount + failCount >= totalCount) {
            // 所有操作完成
            int successCount = insertCount + updateCount;
            String message = "导入完成，成功 " + successCount + " 条";
            if (insertCount > 0 && updateCount > 0) {
                message += "（新增 " + insertCount + " 条，更新 " + updateCount + " 条）";
            } else if (insertCount > 0) {
                message += "（新增 " + insertCount + " 条）";
            } else if (updateCount > 0) {
                message += "（更新 " + updateCount + " 条）";
            }
            if (failCount > 0) {
                message += "，失败 " + failCount + " 条";
            }
            Toast.makeText(getContext(), message, Toast.LENGTH_LONG).show();
            getData();
        }
    }
    
    /**
     * 配置导出数据类（只包含需要导出的字段）
     */
    private static class ConfigExport {
        public String uid;
        public String name;
        public String cfg;
        public String format;
        public int sortOrder = 0;
    }

}
