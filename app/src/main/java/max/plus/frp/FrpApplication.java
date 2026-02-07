package max.plus.frp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.multidex.MultiDexApplication;

import max.plus.frp.library.FrpLibraryManager;
import max.plus.frp.library.LibraryInstaller;

/**
 * Application 类
 * 在应用启动时初始化，将 libs 目录中的 AAR 文件复制到存储目录
 */
public class FrpApplication extends MultiDexApplication {
    private static final String TAG = "FrpApplication";
    
    @Override
    public void onCreate() {
        super.onCreate();
        
        // 从 assets 复制 AAR 文件到存储目录（首次安装时）
        LibraryInstaller.installFromAssets(this);
        
        // 初始化库管理器并优先加载上次使用的版本，失败时回退到最新版本
        FrpLibraryManager manager = FrpLibraryManager.getInstance(this);
        boolean loaded = manager.loadSavedOrLatestLibrary();
        
        // 延迟检测，确保 UI 已准备好显示 Toast
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            checkLibraryStatus(manager);
        }, 1000);
    }
    
    /**
     * 检测库加载状态
     */
    private void checkLibraryStatus(FrpLibraryManager manager) {
        if (!manager.isLibraryLoaded()) {
            String message;
            if (!manager.hasAvailableVersions()) {
                message = "未找到 FRP 库文件";
            } else {
                message = "FRP 库加载失败：AAR 文件中的 classes.jar 包含 .class 文件（Java bytecode），而 Android 需要 DEX 格式。请确保 AAR 文件在构建时已转换为 DEX 格式。";
            }
            Log.e(TAG, message);
            Toast.makeText(this, "FRP 库加载失败，请检查 AAR 文件格式", Toast.LENGTH_LONG).show();
        } else {
            Log.d(TAG, "FRP 库加载成功，当前版本: " + manager.getCurrentVersion());
        }
    }
}
