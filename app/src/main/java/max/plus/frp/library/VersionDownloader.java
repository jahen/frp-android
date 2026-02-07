package max.plus.frp.library;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 版本下载器
 * 负责从服务器下载 AAR 文件
 */
public class VersionDownloader {
    private static final String TAG = "VersionDownloader";
    // 不再需要 BASE_URL，直接使用 versions.php 返回的 URL
    
    private OkHttpClient httpClient;
    
    public VersionDownloader() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 下载指定版本的 AAR 文件
     * @param context 上下文
     * @param versionInfo 版本信息（包含下载 URL）
     * @param listener 下载监听器
     */
    public void downloadVersion(Context context, VersionApi.VersionInfo versionInfo, DownloadListener listener) {
        if (versionInfo == null || versionInfo.url == null || versionInfo.url.isEmpty()) {
            if (listener != null) {
                listener.onError("Invalid version info or URL");
            }
            return;
        }
        
        // 直接使用 versions.php 返回的 URL，不需要重新构建
        String url = versionInfo.url;
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e(TAG, "Download failed for version: " + versionInfo.version, e);
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (!response.isSuccessful()) {
                    String errorMsg = "Download failed: " + response.code();
                    Log.e(TAG, errorMsg);
                    if (listener != null) {
                        listener.onError(errorMsg);
                    }
                    return;
                }
                
                // 保存文件
                File libVersionsDir = new File(context.getFilesDir(), FrpLibraryManager.LIB_VERSIONS_DIR);
                File versionDir = new File(libVersionsDir, versionInfo.version);
                if (!versionDir.exists()) {
                    versionDir.mkdirs();
                }
                
                File targetFile = new File(versionDir, FrpLibraryManager.AAR_FILE_NAME);
                
                try (InputStream inputStream = response.body().byteStream();
                     FileOutputStream outputStream = new FileOutputStream(targetFile)) {
                    
                    byte[] buffer = new byte[8192];
                    int len;
                    long total = 0;
                    long contentLength = response.body().contentLength();
                    
                    while ((len = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, len);
                        total += len;
                        
                        if (listener != null && contentLength > 0) {
                            int progress = (int) (total * 100 / contentLength);
                            listener.onProgress(progress);
                        }
                    }
                    
                    Log.d(TAG, "Downloaded AAR file: " + targetFile.getAbsolutePath());
                    
                    // 用户重新下载该版本，从已删除列表中移除（允许之后从 assets 再复制）
                    FrpLibraryManager.removeFromDeletedVersions(context, versionInfo.version);
                    
                    // 验证下载的 AAR 文件是否包含 DEX（可选检查）
                    if (!verifyAarFile(targetFile)) {
                        Log.w(TAG, "Downloaded AAR file may not contain DEX format. It will be checked during loading.");
                    }
                    
                    if (listener != null) {
                        listener.onSuccess(targetFile.getAbsolutePath());
                    }
                } catch (IOException e) {
                    Log.e(TAG, "Failed to save AAR file", e);
                    if (listener != null) {
                        listener.onError(e.getMessage());
                    }
                }
            }
        });
    }
    
    /**
     * 验证 AAR 文件是否包含 DEX 格式
     * @param aarFile AAR 文件
     * @return true 如果包含 DEX 或 classes.jar
     */
    private boolean verifyAarFile(File aarFile) {
        try {
            java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(aarFile);
            java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
            boolean hasClassesJar = false;
            boolean hasDex = false;
            
            while (entries.hasMoreElements()) {
                java.util.zip.ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.equals("classes.jar")) {
                    hasClassesJar = true;
                } else if (name.equals("classes.dex")) {
                    hasDex = true;
                }
            }
            zipFile.close();
            
            Log.d(TAG, "AAR verification: hasClassesJar=" + hasClassesJar + ", hasDex=" + hasDex);
            return hasClassesJar || hasDex;
        } catch (IOException e) {
            Log.e(TAG, "Failed to verify AAR file", e);
            return false;
        }
    }
    
    public interface DownloadListener {
        void onProgress(int progress);
        void onSuccess(String filePath);
        void onError(String error);
    }
}
