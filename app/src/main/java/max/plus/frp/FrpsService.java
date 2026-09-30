package max.plus.frp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.FileObserver;
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Observer;

import max.plus.frp.R;

import max.plus.frp.database.FrpsDatabase;
import max.plus.frp.database.Config;
import max.plus.frp.ui.HomeFragmentFrps;
import max.plus.frp.ui.MainActivity;
import com.jeremyliao.liveeventbus.LiveEventBus;

import max.plus.frp.library.Server;
import io.reactivex.Single;
import io.reactivex.SingleObserver;
import io.reactivex.SingleSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FrpsService extends Service {
    public static final String INTENT_KEY_FILE = "INTENT_KEY_FILE_FRPS";
    public static final String INTENT_KEY_FILE_STOP = "INTENT_KEY_FILE_STOP_FRPS";
    public static final String EVENT_KEY_FILE_ISRUNNING = "EVENT_KEY_FILE_ISRUNNING_FRPS";
    public static final String EVENT_SERVICE_READY = "EVENT_SERVICE_READY_FRPS";
    public static final int NOTIFY_ID = 0x1011;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private NotificationManager notificationManager;
    private final Map<String, FileObserver> configObservers = new HashMap<>();
    private final Set<String> selfWritingUids = new HashSet<>();


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        // FrpsService 运行在独立进程，需要自己加载库
        // 从 assets 复制 AAR 文件到存储目录（首次安装时）
        max.plus.frp.library.LibraryInstaller.installFromAssets(this);
        // 初始化库管理器并加载库
        max.plus.frp.library.FrpLibraryManager manager = max.plus.frp.library.FrpLibraryManager.getInstance(this);
        boolean loaded = manager.loadSavedOrLatestLibrary();
        if (!loaded) {
            android.util.Log.w("FrpsService", "Failed to load library in frps process");
        } else {
            android.util.Log.d("FrpsService", "Library loaded successfully in frps process, version: " + manager.getCurrentVersion());
        }
        flushAllConfigsToFilesAsync();

        LiveEventBus.get(INTENT_KEY_FILE, String.class).observeForever(keyObserver);

        // 停止事件：在独立进程中执行 Server.close，无剩余配置时停止 Service（触发 onDestroy 杀进程）
        LiveEventBus.get(INTENT_KEY_FILE_STOP, String.class).observeForever(uid->{
            if (uid == null || uid.isEmpty()) return;
            Log.e("FrpsService", "Stop event received - uid: " + uid);
            try {
                if (max.plus.frp.library.FrpLibraryManager.getInstance().isLibraryLoaded() && Server.isRunning(uid)) {
                    Server.close(uid);
                    Log.e("FrpsService", "Server.close() called for uid: " + uid);
                }
                FileObserver observer = configObservers.remove(uid);
                if (observer != null) {
                    observer.stopWatching();
                }
            } catch (Exception e) {
                Log.e("FrpsService", "Error closing uid " + uid + ": " + e.getMessage());
            }
            LiveEventBus.get(EVENT_KEY_FILE_ISRUNNING).post("");
            // 若无运行中配置，停止 Service → onDestroy → 杀进程
            try {
                if (max.plus.frp.library.FrpLibraryManager.getInstance().isLibraryLoaded()) {
                    String remaining = Server.getUids();
                    if (remaining == null || remaining.trim().isEmpty()) {
                        Log.e("FrpsService", "No more running configs, stopping service");
                        stopSelf();
                    }
                }
            } catch (Exception e) {
                Log.e("FrpsService", "Error checking remaining uids: " + e.getMessage());
            }
        });

        // 获取运行中的 uid 列表并通知 UI（用于刷新状态）
        LiveEventBus.get(EVENT_KEY_FILE_ISRUNNING, String.class).observeForever(str->{
            try {
                // 检查库是否已加载，避免在版本切换期间崩溃
                if (!max.plus.frp.library.FrpLibraryManager.getInstance().isLibraryLoaded()) {
                    Log.w("FrpsService", "Library not loaded, skipping getUids()");
                    // 通知 UI 没有运行中的配置
                    LiveEventBus.get(MainActivity.EVENT_KEY_FILE_GETUIDS).postAcrossProcess("");
                    return;
                }
                String uids=Server.getUids();
                Log.e("FrpsService", "EVENT_KEY_FILE_ISRUNNING received, Server.getUids(): " + uids);
                // 无论是否有运行中的配置，都要通知 UI（空的也要通知，用于停止服务）
                LiveEventBus.get(MainActivity.EVENT_KEY_FILE_GETUIDS).postAcrossProcess(uids != null ? uids : "");
            } catch (UnsatisfiedLinkError e) {
                // Native 库未加载或已被卸载
                Log.w("FrpsService", "Native library not available: " + e.getMessage());
                LiveEventBus.get(MainActivity.EVENT_KEY_FILE_GETUIDS).postAcrossProcess("");
            } catch (NoClassDefFoundError e) {
                // 类定义未找到（可能 ClassLoader 已被清理）
                Log.w("FrpsService", "Class definition not found: " + e.getMessage());
                LiveEventBus.get(MainActivity.EVENT_KEY_FILE_GETUIDS).postAcrossProcess("");
            } catch (Throwable t) {
                // 捕获所有其他异常
                Log.w("FrpsService", "Error getting UIDs: " + t.getMessage());
                LiveEventBus.get(MainActivity.EVENT_KEY_FILE_GETUIDS).postAcrossProcess("");
            }
        });

        startForeground(NOTIFY_ID, createForegroundNotification());
        
        // 通知主进程：FrpsService 已完全初始化，可以接收启动事件
        Log.d("FrpsService", "Service ready, notifying main process");
        LiveEventBus.get(EVENT_SERVICE_READY).postAcrossProcess(true);
    }

    Observer<String> keyObserver = uid -> {
        try {
            // 检查库是否已加载，避免在库未加载时崩溃
            if (!max.plus.frp.library.FrpLibraryManager.getInstance().isLibraryLoaded()) {
                Log.w("FrpsService", "Library not loaded, skipping isRunning check");
                // 通知 UI 启动失败
                LiveEventBus.get(HomeFragmentFrps.EVENT_RUNNING_ERROR, String.class).postAcrossProcess(uid);
                return;
            }
            if (Server.isRunning(uid)) {
                return;
            }
            new Thread(() -> {
                RunContent(uid);
            }).start();
        } catch (UnsatisfiedLinkError e) {
            Log.w("FrpsService", "Native library not available: " + e.getMessage());
            LiveEventBus.get(HomeFragmentFrps.EVENT_RUNNING_ERROR, String.class).postAcrossProcess(uid);
        } catch (NoClassDefFoundError e) {
            Log.w("FrpsService", "Class definition not found: " + e.getMessage());
            LiveEventBus.get(HomeFragmentFrps.EVENT_RUNNING_ERROR, String.class).postAcrossProcess(uid);
        } catch (Throwable t) {
            Log.w("FrpsService", "Error checking isRunning: " + t.getMessage());
            LiveEventBus.get(HomeFragmentFrps.EVENT_RUNNING_ERROR, String.class).postAcrossProcess(uid);
        }
    };

    public void RunContent(String uid){
        Log.e("item","FRPS RunContent " + uid);
        FrpsDatabase.getInstance(FrpsService.this)
                .configDao()
                .getConfigByUid(uid)
                .flatMap((Function<Config, SingleSource<String>>) config -> {
                    try {
                        // 检查库是否已加载，避免在版本切换期间崩溃
                        if (!max.plus.frp.library.FrpLibraryManager.getInstance().isLibraryLoaded()) {
                            Log.w("FrpsService", "Library not loaded, cannot run content");
                            return Single.just("Library not loaded");
                        }
                        File file = persistConfigForRun(config);
                        if (Server.supportsRunFile()) {
                            String error = Server.runFile(config.getUid(), file.getAbsolutePath());
                            if (TextUtils.isEmpty(error)) {
                                watchConfigFile(config.getUid(), file);
                                return Single.just("");
                            }
                            Log.w("FrpsService", "runFile failed, fallback to runContent. error: " + error);
                        } else {
                            Log.d("FrpsService", "Current library does not support runFile, using runContent");
                        }
                        String error = Server.runContent(config.getUid(), config.getCfg());
                        return Single.just(error);
                    } catch (UnsatisfiedLinkError e) {
                        Log.w("FrpsService", "Native library not available: " + e.getMessage());
                        return Single.just("Native library not available");
                    } catch (NoClassDefFoundError e) {
                        Log.w("FrpsService", "Class definition not found: " + e.getMessage());
                        return Single.just("Class definition not found");
                    } catch (Throwable t) {
                        Log.w("FrpsService", "Error running content: " + t.getMessage());
                        return Single.just("Error: " + t.getMessage());
                    }
                })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new SingleObserver<String>() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        compositeDisposable.add(d);
                    }

                    @Override
                    public void onSuccess(String error) {
                        if (!TextUtils.isEmpty(error)) {
                            Log.e("FrpsService", "RunContent error for uid: " + uid + ", error: " + error);
                            // 检测是否是端口冲突错误
                            String errorLower = error.toLowerCase();
                            Log.e("FrpsService", "Error lower case: " + errorLower);
                            boolean isPortConflict = errorLower.contains("bind") 
                                    || errorLower.contains("port") 
                                    || errorLower.contains("address already in use")
                                    || errorLower.contains("address in use")
                                    || errorLower.contains("already in use")
                                    || errorLower.contains("端口")
                                    || errorLower.contains("绑定")
                                    || errorLower.contains("error") && (errorLower.contains("bind") || errorLower.contains("port"));
                            
                            Log.e("FrpsService", "Is port conflict: " + isPortConflict);
                            if (isPortConflict) {
                                Toast.makeText(FrpsService.this, getString(R.string.tipPortConflict), Toast.LENGTH_LONG).show();
                            } else {
                                Toast.makeText(FrpsService.this, error, Toast.LENGTH_SHORT).show();
                            }
                            // FrpsService 运行在独立进程，需要使用跨进程通信通知 UI
                            LiveEventBus.get(HomeFragmentFrps.EVENT_RUNNING_ERROR, String.class).postAcrossProcess(uid);
                        } else {
                            // 启动成功，延迟检查运行状态（给启动一些时间）
                            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                                boolean isRunning = Server.isRunning(uid);
                                Log.e("FrpsService", "RunContent success - uid: " + uid + ", Server.isRunning in frps process: " + isRunning);
                                if (!isRunning) {
                                    // 虽然返回无错误，但实际上未运行，可能是配置错误
                                    Log.e("FrpsService", "RunContent returned no error but Server.isRunning is false for uid: " + uid);
                                    // 触发错误事件，让 UI 更新状态
                                    LiveEventBus.get(HomeFragmentFrps.EVENT_RUNNING_ERROR, String.class).postAcrossProcess(uid);
                                } else {
                                    // 启动成功，触发获取运行中的 uid 列表，更新 UI
                                    LiveEventBus.get(EVENT_KEY_FILE_ISRUNNING).post("");
                                }
                            }, 1000);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {

                    }
                });

    }

    private File persistConfigForRun(Config config) throws IOException {
        synchronized (selfWritingUids) {
            selfWritingUids.add(config.getUid());
        }
        try {
            return ConfigFileStore.writeConfigAtomic(
                    this,
                    "frps",
                    config.getUid(),
                    config.getName(),
                    config.getFormat(),
                    config.getCfg()
            );
        } finally {
            synchronized (selfWritingUids) {
                selfWritingUids.remove(config.getUid());
            }
        }
    }

    private void watchConfigFile(String uid, File configFile) {
        FileObserver old = configObservers.remove(uid);
        if (old != null) {
            old.stopWatching();
        }
        File parent = configFile.getParentFile();
        if (parent == null) return;
        FileObserver observer = new FileObserver(parent.getAbsolutePath(), FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                if (TextUtils.isEmpty(path)) return;
                if (!path.equals(configFile.getName())) return;
                synchronized (selfWritingUids) {
                    if (selfWritingUids.contains(uid)) return;
                }
                syncConfigFileToDb(uid, configFile);
            }
        };
        observer.startWatching();
        configObservers.put(uid, observer);
    }

    private void syncConfigFileToDb(String uid, File configFile) {
        try {
            if (!configFile.exists()) return;
            String fileContentRaw = ConfigFileStore.readUtf8(configFile);
            String fileContent = ConfigFileStore.normalizeStorePath(fileContentRaw, configFile.getParentFile());
            if (!TextUtils.equals(fileContentRaw, fileContent)) {
                synchronized (selfWritingUids) {
                    selfWritingUids.add(uid);
                }
                try {
                    ConfigFileStore.writeExistingFileAtomic(configFile, fileContent);
                } finally {
                    synchronized (selfWritingUids) {
                        selfWritingUids.remove(uid);
                    }
                }
            }
            FrpsDatabase.getInstance(this)
                    .configDao()
                    .getConfigByUid(uid)
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.io())
                    .subscribe(new SingleObserver<Config>() {
                        @Override
                        public void onSubscribe(Disposable d) {
                            compositeDisposable.add(d);
                        }

                        @Override
                        public void onSuccess(Config config) {
                            String inferredFormat = ConfigFormatUtils.inferFormatAfterFileEdited(
                                    configFile.getName(),
                                    fileContent,
                                    config.getFormat()
                            );
                            if (TextUtils.equals(config.getCfg(), fileContent)
                                    && TextUtils.equals(ConfigFormatUtils.normalizeFormat(config.getFormat()), inferredFormat)) {
                                return;
                            }
                            config.setCfg(fileContent);
                            config.setFormat(inferredFormat);
                            FrpsDatabase.getInstance(FrpsService.this)
                                    .configDao()
                                    .update(config)
                                    .subscribeOn(Schedulers.io())
                                    .subscribe();
                        }

                        @Override
                        public void onError(Throwable e) {
                            Log.w("FrpsService", "syncConfigFileToDb getConfigByUid failed: " + e.getMessage());
                        }
                    });
        } catch (IOException e) {
            Log.w("FrpsService", "syncConfigFileToDb read file failed: " + e.getMessage());
        }
    }

    private void flushAllConfigsToFilesAsync() {
        FrpsDatabase.getInstance(this)
                .configDao()
                .getAll()
                .subscribeOn(Schedulers.io())
                .observeOn(Schedulers.io())
                .subscribe(new SingleObserver<List<Config>>() {
                    @Override
                    public void onSubscribe(Disposable d) {
                        compositeDisposable.add(d);
                    }

                    @Override
                    public void onSuccess(List<Config> configs) {
                        for (Config config : configs) {
                            try {
                                File file = persistConfigForRun(config);
                                watchConfigFile(config.getUid(), file);
                            } catch (IOException e) {
                                Log.w("FrpsService", "flush config failed uid=" + config.getUid() + ", error=" + e.getMessage());
                            }
                        }
                        Log.d("FrpsService", "flush completed, total configs: " + configs.size());
                    }

                    @Override
                    public void onError(Throwable e) {
                        Log.w("FrpsService", "flushAllConfigsToFilesAsync failed: " + e.getMessage());
                    }
                });
    }



    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }


    private Notification createForegroundNotification() {

        String notificationChannelId = "frps_android_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelName = "FRP Server Service Notification";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel notificationChannel = new NotificationChannel(notificationChannelId, channelName, importance);
            notificationChannel.setDescription("FRP Server Foreground Service");
            notificationChannel.enableLights(true);
            notificationChannel.setLightColor(Color.GREEN);
            notificationChannel.setVibrationPattern(new long[]{0, 1000, 500, 1000});
            notificationChannel.enableVibration(true);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(notificationChannel);
            }
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, notificationChannelId);
        builder.setSmallIcon(R.mipmap.ic_launcher);
        builder.setContentTitle("FRP Server Foreground Service");
        builder.setContentText("FRP Server Service is running");
        builder.setWhen(System.currentTimeMillis());
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            pendingIntent = PendingIntent.getActivity(this, 0, activityIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }
        builder.setContentIntent(pendingIntent);

        return builder.build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.e("FrpsService", "onDestroy called, killing process");
        stopForeground(true);
        for (FileObserver observer : configObservers.values()) {
            observer.stopWatching();
        }
        configObservers.clear();
        compositeDisposable.dispose();
        // frps 运行在独立进程，直接杀进程，端口立即释放
        android.os.Process.killProcess(android.os.Process.myPid());
    }


}
