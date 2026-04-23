package max.plus.frp.library;

import android.content.Context;

/**
 * Server 包装类
 * 提供与原生 Server 类相同的静态方法接口
 * 内部通过 FrpLibraryManager 动态加载和调用
 */
public class Server {
    
    /**
     * 初始化，加载库
     * 在使用前必须先调用此方法
     */
    public static void init(Context context) {
        FrpLibraryManager.getInstance(context).loadLatestLibrary();
    }
    
    /**
     * 初始化指定版本
     */
    public static void init(Context context, String version) {
        FrpLibraryManager.getInstance(context).loadLibrary(version);
    }
    
    /**
     * 检查指定 UID 的服务器是否正在运行
     */
    public static boolean isRunning(String uid) {
        Object result = FrpLibraryManager.getInstance().invokeServerMethod("isRunning", uid);
        return result != null && (Boolean) result;
    }
    
    /**
     * 关闭指定 UID 的服务器
     */
    public static void close(String uid) {
        FrpLibraryManager.getInstance().invokeServerMethod("close", uid);
    }
    
    /**
     * 运行配置内容
     * @param uid 配置 UID
     * @param cfg 配置内容
     * @return 错误信息，如果成功返回 null 或空字符串
     */
    public static String runContent(String uid, String cfg) {
        Object result = FrpLibraryManager.getInstance().invokeServerMethod("runContent", uid, cfg);
        return result != null ? result.toString() : null;
    }

    /**
     * 运行配置文件
     * @param uid 配置 UID
     * @param filePath 配置文件路径
     * @return 错误信息，如果成功返回 null 或空字符串
     */
    public static String runFile(String uid, String filePath) {
        Object result = FrpLibraryManager.getInstance().invokeServerMethod("runFile", uid, filePath);
        return result != null ? result.toString() : null;
    }

    public static boolean supportsRunFile() {
        return FrpLibraryManager.getInstance().isRunFileReliable();
    }
    
    /**
     * 获取所有运行中的 UID 列表
     * @return 逗号分隔的 UID 列表
     */
    public static String getUids() {
        Object result = FrpLibraryManager.getInstance().invokeServerMethod("getUids");
        return result != null ? result.toString() : "";
    }
}
