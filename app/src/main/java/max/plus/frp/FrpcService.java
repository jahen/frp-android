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
import android.os.IBinder;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Observer;

import max.plus.frp.R;

import max.plus.frp.database.FrpcDatabase;
import max.plus.frp.database.Config;
import max.plus.frp.ui.HomeFragment;
import max.plus.frp.ui.MainActivity;
import com.jeremyliao.liveeventbus.LiveEventBus;

import max.plus.frp.library.Client;
import io.reactivex.Single;
import io.reactivex.SingleObserver;
import io.reactivex.SingleSource;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Function;
import io.reactivex.schedulers.Schedulers;

public class FrpcService extends Service {
    public static final String INTENT_KEY_FILE = "INTENT_KEY_FILE_FRPC";
    public static final int NOTIFY_ID = 0x1010;
    private final CompositeDisposable compositeDisposable = new CompositeDisposable();
    private NotificationManager notificationManager;


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        LiveEventBus.get(INTENT_KEY_FILE, String.class).observeForever(keyObserver);

        startForeground(NOTIFY_ID, createForegroundNotification());
    }

    Observer<String> keyObserver = uid -> {
        try {
            // 检查库是否已加载，避免在版本切换期间崩溃
            if (!max.plus.frp.library.FrpLibraryManager.getInstance().isLibraryLoaded()) {
                Log.w("FrpcService", "Library not loaded, skipping isRunning check");
                return;
            }
            if (Client.isRunning(uid)) {
                return;
            }
            new Thread(() -> {
                RunContent(uid);
            }).start();
        } catch (UnsatisfiedLinkError e) {
            Log.w("FrpcService", "Native library not available: " + e.getMessage());
        } catch (NoClassDefFoundError e) {
            Log.w("FrpcService", "Class definition not found: " + e.getMessage());
        } catch (Throwable t) {
            Log.w("FrpcService", "Error checking isRunning: " + t.getMessage());
        }
    };

    public void RunContent(String uid){
        Log.e("item","FRPC RunContent " + uid);
        FrpcDatabase.getInstance(FrpcService.this)
                .configDao()
                .getConfigByUid(uid)
                .flatMap((Function<Config, SingleSource<String>>) config -> {
                    try {
                        // 检查库是否已加载，避免在版本切换期间崩溃
                        if (!max.plus.frp.library.FrpLibraryManager.getInstance().isLibraryLoaded()) {
                            Log.w("FrpcService", "Library not loaded, cannot run content");
                            return Single.just("Library not loaded");
                        }
                        String error = Client.runContent(config.getUid(), config.getCfg());
                        return Single.just(error);
                    } catch (UnsatisfiedLinkError e) {
                        Log.w("FrpcService", "Native library not available: " + e.getMessage());
                        return Single.just("Native library not available");
                    } catch (NoClassDefFoundError e) {
                        Log.w("FrpcService", "Class definition not found: " + e.getMessage());
                        return Single.just("Class definition not found");
                    } catch (Throwable t) {
                        Log.w("FrpcService", "Error running content: " + t.getMessage());
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
                            Toast.makeText(FrpcService.this, error, Toast.LENGTH_SHORT).show();
                            LiveEventBus.get(HomeFragment.EVENT_RUNNING_ERROR, String.class).post(uid);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {

                    }
                });

    }



    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }


    private Notification createForegroundNotification() {

        String notificationChannelId = "frpc_android_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            String channelName = "FRP Client Service Notification";
            int importance = NotificationManager.IMPORTANCE_HIGH;
            NotificationChannel notificationChannel = new NotificationChannel(notificationChannelId, channelName, importance);
            notificationChannel.setDescription("FRP Client Foreground Service");
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
        builder.setContentTitle("FRP Client Foreground Service");
        builder.setContentText("FRP Client Service is running");
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
        stopForeground(true);
        compositeDisposable.dispose();
    }


}
