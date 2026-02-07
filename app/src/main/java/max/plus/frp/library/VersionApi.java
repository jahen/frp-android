package max.plus.frp.library;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 版本 API
 * 负责从服务器获取版本列表
 */
public class VersionApi {
    private static final String TAG = "VersionApi";
    private static final String BASE_URL = "http://yy.tj.cn/frp/"; // 替换为你的服务器地址
    
    private OkHttpClient httpClient;
    
    public VersionApi() {
        httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 获取服务器上的版本列表
     * @param listener 回调监听器
     */
    public void getVersions(VersionListListener listener) {
        String url = BASE_URL + "versions.php";
        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        // 异步请求，避免在主线程访问网络
        httpClient.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(okhttp3.Call call, IOException e) {
                Log.e(TAG, "Failed to get versions", e);
                if (listener != null) {
                    listener.onError(e.getMessage());
                }
            }

            @Override
            public void onResponse(okhttp3.Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        String errorMsg = "Failed to get versions: " + response.code();
                        Log.e(TAG, errorMsg);
                        if (listener != null) {
                            listener.onError(errorMsg);
                        }
                        return;
                    }

                    String jsonString = response.body().string();
                    JSONObject jsonObject = new JSONObject(jsonString);

                    if (jsonObject.getInt("code") == 200) {
                        JSONArray versionsArray = jsonObject.getJSONArray("data");
                        List<VersionInfo> versions = new ArrayList<>();

                        for (int i = 0; i < versionsArray.length(); i++) {
                            JSONObject versionObj = versionsArray.getJSONObject(i);
                            VersionInfo info = new VersionInfo();
                            info.version = versionObj.getString("version");
                            info.url = versionObj.getString("url");
                            info.size = versionObj.optLong("size", 0);
                            info.description = versionObj.optString("description", "");
                            info.releaseDate = versionObj.optString("release_date", "");
                            versions.add(info);
                        }

                        if (listener != null) {
                            listener.onSuccess(versions);
                        }
                    } else {
                        String errorMsg = jsonObject.optString("message", "Unknown error");
                        Log.e(TAG, errorMsg);
                        if (listener != null) {
                            listener.onError(errorMsg);
                        }
                    }
                } catch (JSONException e) {
                    Log.e(TAG, "Failed to parse versions", e);
                    if (listener != null) {
                        listener.onError(e.getMessage());
                    }
                } finally {
                    if (response.body() != null) {
                        response.close();
                    }
                }
            }
        });
    }
    
    public interface VersionListListener {
        void onSuccess(List<VersionInfo> versions);
        void onError(String error);
    }
    
    public static class VersionInfo {
        public String version;
        public String url;
        public long size;
        public String description;
        public String releaseDate;
    }
}
