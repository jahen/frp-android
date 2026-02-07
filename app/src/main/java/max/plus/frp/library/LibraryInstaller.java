package max.plus.frp.library;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 库安装器
 * 负责从应用包中复制 AAR 文件到存储目录
 */
public class LibraryInstaller {
    private static final String TAG = "LibraryInstaller";
    private static final String ASSETS_LIB_VERSIONS = "lib_versions";
    
    /**
     * 从 assets 目录安装 AAR 文件到存储目录
     */
    public static void installFromAssets(Context context) {
        try {
            String[] versions = context.getAssets().list(ASSETS_LIB_VERSIONS);
            if (versions == null || versions.length == 0) {
                Log.d(TAG, "No AAR files in assets/" + ASSETS_LIB_VERSIONS);
                return;
            }
            
            File libVersionsDir = new File(context.getFilesDir(), FrpLibraryManager.LIB_VERSIONS_DIR);
            if (!libVersionsDir.exists()) {
                libVersionsDir.mkdirs();
            }
            
            for (String version : versions) {
                // 用户已删除的版本不再从 assets 重新复制
                if (FrpLibraryManager.isVersionDeleted(context, version)) {
                    Log.d(TAG, "Skip installing " + version + " (user deleted)");
                    continue;
                }
                File versionDir = new File(libVersionsDir, version);
                if (!versionDir.exists()) {
                    versionDir.mkdirs();
                }
                
                File targetFile = new File(versionDir, FrpLibraryManager.AAR_FILE_NAME);
                if (targetFile.exists()) {
                    Log.d(TAG, "AAR file already exists: " + targetFile.getAbsolutePath());
                    continue;
                }
                
                String assetPath = ASSETS_LIB_VERSIONS + "/" + version + "/" + FrpLibraryManager.AAR_FILE_NAME;
                try (InputStream inputStream = context.getAssets().open(assetPath);
                     FileOutputStream outputStream = new FileOutputStream(targetFile)) {
                    
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, len);
                    }
                    
                    Log.d(TAG, "Installed AAR file: " + assetPath + " -> " + targetFile.getAbsolutePath());
                } catch (IOException e) {
                    Log.e(TAG, "Failed to install AAR file: " + assetPath, e);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, "Failed to list assets/" + ASSETS_LIB_VERSIONS, e);
        }
    }
}
