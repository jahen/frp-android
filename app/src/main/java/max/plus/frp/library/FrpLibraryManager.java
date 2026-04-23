package max.plus.frp.library;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.os.Build;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import dalvik.system.DexClassLoader;

/**
 * FRP 库管理器
 * 负责动态加载存储目录中的 AAR 文件
 * 使用方式：只需要将 AAR 文件放入 getFilesDir()/lib_versions/{version}/frp.aar
 */
public class FrpLibraryManager {
    private static final String TAG = "FrpLibraryManager";
    public static final String LIB_VERSIONS_DIR = "lib_versions";
    public static final String AAR_FILE_NAME = "frp.aar";
    private static final String PREF_NAME = "frp_lib_prefs";
    private static final String KEY_DELETED_VERSIONS = "deleted_versions";
    private static final String EXTRACTED_DIR = "extracted";
    private static final String CLASSES_JAR = "classes.jar";
    private static final String JNI_DIR = "jni";
    
    private static FrpLibraryManager instance;
    private static Context globalContext;
    private Context context;
    private String currentVersion;
    private DexClassLoader classLoader;
    private Class<?> clientClass;
    private Class<?> serverClass;
    
    private FrpLibraryManager(Context context) {
        this.context = context.getApplicationContext();
        if (globalContext == null) {
            globalContext = this.context;
        }
    }
    
    public static synchronized FrpLibraryManager getInstance(Context context) {
        if (instance == null) {
            if (context == null && globalContext == null) {
                throw new IllegalStateException("Context must be provided for first initialization");
            }
            if (context != null) {
                instance = new FrpLibraryManager(context);
            } else {
                instance = new FrpLibraryManager(globalContext);
            }
        }
        return instance;
    }
    
    /**
     * 获取全局实例（需要先初始化）
     */
    public static FrpLibraryManager getInstance() {
        if (instance == null) {
            throw new IllegalStateException("FrpLibraryManager not initialized. Call getInstance(Context) first.");
        }
        return instance;
    }
    
    /**
     * 加载指定版本的库
     * @param version 版本号，如 "0.68.0"
     * @return 是否加载成功
     */
    public boolean loadLibrary(String version) {
        if (version == null || version.isEmpty()) {
            Log.e(TAG, "Version cannot be null or empty");
            return false;
        }
        
        // 如果已经加载了该版本，直接返回
        if (version.equals(currentVersion) && classLoader != null) {
            Log.d(TAG, "Library version " + version + " already loaded");
            return true;
        }
        
        // 保存旧的 ClassLoader 引用（用于后续清理）
        DexClassLoader oldClassLoader = null;
        Class<?> oldClientClass = null;
        Class<?> oldServerClass = null;
        if (classLoader != null && !version.equals(currentVersion)) {
            oldClassLoader = classLoader;
            oldClientClass = clientClass;
            oldServerClass = serverClass;
            Log.d(TAG, "Switching version, will cleanup old ClassLoader after new version loads successfully");
        }
        
        try {
            // 1. 检查 AAR 文件是否存在
            File aarFile = getAarFile(version);
            if (!aarFile.exists()) {
                Log.e(TAG, "AAR file not found: " + aarFile.getAbsolutePath());
                return false;
            }
            
            // 2. 解压 AAR 文件
            File extractedDir = getExtractedDir(version);
            Log.d(TAG, "Extracting AAR to: " + extractedDir.getAbsolutePath());
            if (!extractAar(aarFile, extractedDir)) {
                Log.e(TAG, "Failed to extract AAR file");
                return false;
            }
            Log.d(TAG, "AAR extracted successfully");
            
            // 3. 检查解压后的文件结构
            File[] extractedFiles = extractedDir.listFiles();
            if (extractedFiles != null) {
                Log.d(TAG, "Extracted AAR contents:");
                for (File f : extractedFiles) {
                    Log.d(TAG, "  - " + f.getName() + " (" + (f.isDirectory() ? "dir" : "file") + ")");
                }
            }
            
            // 检查是否有 classes.dex（Android DEX 文件）
            File classesDex = new File(extractedDir, "classes.dex");
            File classesJar = new File(extractedDir, CLASSES_JAR);
            File nativeLibDir = new File(extractedDir, JNI_DIR);
            File optimizedDir = context.getDir("dex", Context.MODE_PRIVATE);
            
            // 优先使用 classes.dex，如果没有则使用 classes.jar
            String dexPath;
            if (classesDex.exists()) {
                Log.d(TAG, "Found classes.dex, using it for loading");
                dexPath = classesDex.getAbsolutePath();
            } else if (classesJar.exists()) {
                Log.d(TAG, "No classes.dex found, trying classes.jar");
                dexPath = classesJar.getAbsolutePath();
            } else {
                Log.e(TAG, "Neither classes.dex nor classes.jar found in AAR");
                return false;
            }
            
            // 如果使用 classes.jar，检查其内容并尝试提取或转换
            if (dexPath.equals(classesJar.getAbsolutePath())) {
                Log.d(TAG, "classes.jar found, size: " + classesJar.length() + " bytes");
                try {
                    java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(classesJar);
                    java.util.Enumeration<? extends java.util.zip.ZipEntry> entries = zipFile.entries();
                    Log.d(TAG, "classes.jar contents:");
                    boolean hasDex = false;
                    int entryCount = 0;
                    while (entries.hasMoreElements()) {
                        java.util.zip.ZipEntry entry = entries.nextElement();
                        String entryName = entry.getName();
                        Log.d(TAG, "  - " + entryName + " (" + entry.getSize() + " bytes)");
                        // 检查是否有 classes.dex 在 jar 内部
                        if (entryName.equals("classes.dex")) {
                            hasDex = true;
                            Log.d(TAG, "Found classes.dex inside classes.jar! Extracting...");
                            // 提取 classes.dex
                            File extractedDex = new File(extractedDir, "classes.dex");
                            try (java.io.InputStream is = zipFile.getInputStream(entry);
                                 java.io.FileOutputStream fos = new java.io.FileOutputStream(extractedDex)) {
                                byte[] buffer = new byte[8192];
                                int len;
                                while ((len = is.read(buffer)) != -1) {
                                    fos.write(buffer, 0, len);
                                }
                                Log.d(TAG, "Extracted classes.dex to: " + extractedDex.getAbsolutePath());
                                dexPath = extractedDex.getAbsolutePath();
                            }
                            break;
                        }
                        entryCount++;
                        if (entryCount > 20) { // 只列出前20个条目
                            Log.d(TAG, "  ... (more entries, total: " + zipFile.size() + ")");
                            // 继续检查剩余条目是否有 classes.dex
                            while (entries.hasMoreElements()) {
                                java.util.zip.ZipEntry e = entries.nextElement();
                                if (e.getName().equals("classes.dex")) {
                                    hasDex = true;
                                    Log.d(TAG, "Found classes.dex inside classes.jar (after first 20 entries)! Extracting...");
                                    File extractedDex = new File(extractedDir, "classes.dex");
                                    try (java.io.InputStream is = zipFile.getInputStream(e);
                                         java.io.FileOutputStream fos = new java.io.FileOutputStream(extractedDex)) {
                                        byte[] buffer = new byte[8192];
                                        int len;
                                        while ((len = is.read(buffer)) != -1) {
                                            fos.write(buffer, 0, len);
                                        }
                                        Log.d(TAG, "Extracted classes.dex to: " + extractedDex.getAbsolutePath());
                                        dexPath = extractedDex.getAbsolutePath();
                                    }
                                    break;
                                }
                            }
                            break;
                        }
                    }
                    zipFile.close();
                    if (!hasDex) {
                        Log.w(TAG, "No classes.dex found inside classes.jar, only .class files.");
                        Log.w(TAG, "These AAR files need to be preprocessed. The classes.jar should contain classes.dex, not .class files.");
                        Log.w(TAG, "Please ensure server-side AAR files have been converted to DEX format.");
                    }
                } catch (IOException e) {
                    Log.w(TAG, "Failed to list classes.jar contents: " + e.getMessage());
                }
            }
            
            // 4. 加载 DEX
            String nativeLibraryPath = null;
            if (nativeLibDir.exists()) {
                // 优先使用与当前 CPU ABI 匹配的子目录（例如 jni/x86、jni/arm64-v8a）
                String abi = null;
                if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
                    abi = Build.SUPPORTED_ABIS[0];
                } else {
                    abi = Build.CPU_ABI; // 兼容旧版
                }
                File abiDir = abi != null ? new File(nativeLibDir, abi) : null;
                if (abiDir != null && abiDir.exists()) {
                    nativeLibraryPath = abiDir.getAbsolutePath();
                    Log.d(TAG, "Using ABI-specific nativeLibraryPath: " + nativeLibraryPath);
                } else {
                    nativeLibraryPath = nativeLibDir.getAbsolutePath();
                    Log.d(TAG, "ABI-specific dir not found, fallback nativeLibraryPath: " + nativeLibraryPath);
                }
            } else {
                Log.w(TAG, "Native lib dir not found: " + nativeLibDir.getAbsolutePath());
            }

            Log.d(TAG, "Creating DexClassLoader with:");
            Log.d(TAG, "  - dexPath: " + dexPath);
            Log.d(TAG, "  - optimizedDir: " + optimizedDir.getAbsolutePath());
            Log.d(TAG, "  - nativeLibraryPath: " + (nativeLibraryPath != null ? nativeLibraryPath : "null"));
            
            try {
                classLoader = new DexClassLoader(
                    dexPath,
                    optimizedDir.getAbsolutePath(),
                    nativeLibraryPath,
                    context.getClassLoader()
                );
                Log.d(TAG, "DexClassLoader created successfully");
            } catch (Exception e) {
                Log.e(TAG, "Failed to create DexClassLoader: " + e.getMessage(), e);
                return false;
            }
            
            // 5. 加载 Client 和 Server 类
            try {
                clientClass = classLoader.loadClass("com.android.http.client.Client");
                Log.d(TAG, "Client class loaded successfully");
                serverClass = classLoader.loadClass("com.android.http.server.Server");
                Log.d(TAG, "Server class loaded successfully");
            } catch (ClassNotFoundException e) {
                Log.e(TAG, "Failed to load Client or Server class: " + e.getMessage(), e);
                Log.e(TAG, "ClassLoader: " + classLoader);
                Log.e(TAG, "DexPath: " + dexPath);
                Log.e(TAG, "File exists: " + new File(dexPath).exists());
                
                // 提供更详细的错误信息
                if (dexPath.contains("classes.jar") && !new File(extractedDir, "classes.dex").exists()) {
                    Log.e(TAG, "AAR file contains classes.jar with .class files, but no classes.dex found.");
                    Log.e(TAG, "This AAR file needs to be preprocessed to convert .class to .dex format.");
                    Log.e(TAG, "Please ensure AAR files are converted before using them.");
                }
                
                return false;
            }
            
            currentVersion = version;
            // 持久化当前版本，应用重启后可以恢复
            try {
                android.content.SharedPreferences sp =
                        context.getSharedPreferences("frp_prefs", android.content.Context.MODE_PRIVATE);
                sp.edit().putString("current_version", version).apply();
            } catch (Exception ignore) {
            }
            
            // 新版本加载成功后，不立即清理旧的 ClassLoader
            // 让旧的 ClassLoader 自然被 GC 回收，避免在清理时导致正在进行的调用崩溃
            // 由于新的 ClassLoader 已经加载，所有新的调用都会使用新的 ClassLoader
            // 旧的 ClassLoader 会在没有引用后自动被 GC 回收
            if (oldClassLoader != null) {
                Log.d(TAG, "New version loaded successfully, old ClassLoader will be GC'd automatically");
                // 不立即清理，避免在清理时导致正在进行的调用崩溃
                // 旧的 ClassLoader 会在没有引用后自动被 GC 回收
            }
            
            Log.d(TAG, "Library version " + version + " loaded successfully");
            return true;
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to load library version " + version, e);
            Log.e(TAG, "Exception type: " + e.getClass().getName());
            Log.e(TAG, "Exception message: " + e.getMessage());
            if (e.getCause() != null) {
                Log.e(TAG, "Exception cause: " + e.getCause().getMessage());
            }
            return false;
        }
    }
    
    /**
     * 自动加载最新版本的库
     * @return 是否加载成功
     */
    public boolean loadLatestLibrary() {
        File versionsDir = new File(context.getFilesDir(), LIB_VERSIONS_DIR);
        if (!versionsDir.exists() || !versionsDir.isDirectory()) {
            Log.e(TAG, "Versions directory not found");
            return false;
        }
        
        File[] versionDirs = versionsDir.listFiles(File::isDirectory);
        if (versionDirs == null || versionDirs.length == 0) {
            Log.e(TAG, "No version directories found");
            return false;
        }
        
        // 查找最新版本（按目录名排序，取最后一个）
        String latestVersion = null;
        for (File dir : versionDirs) {
            String version = dir.getName();
            File aarFile = new File(dir, AAR_FILE_NAME);
            if (aarFile.exists()) {
                if (latestVersion == null || version.compareTo(latestVersion) > 0) {
                    latestVersion = version;
                }
            }
        }
        
        if (latestVersion == null) {
            Log.e(TAG, "No valid AAR file found");
            return false;
        }
        
        return loadLibrary(latestVersion);
    }

    /**
     * 加载上次保存的版本，如果失败则退回到最新版本
     */
    public boolean loadSavedOrLatestLibrary() {
        try {
            android.content.SharedPreferences sp =
                    context.getSharedPreferences("frp_prefs", android.content.Context.MODE_PRIVATE);
            String savedVersion = sp.getString("current_version", null);
            if (savedVersion != null && !savedVersion.isEmpty()) {
                Log.d(TAG, "Trying to load saved version: " + savedVersion);
                if (loadLibrary(savedVersion)) {
                    return true;
                }
                Log.w(TAG, "Failed to load saved version, fallback to latest.");
            }
        } catch (Exception ignore) {
        }
        return loadLatestLibrary();
    }
    
    /**
     * 调用 Client 类的静态方法
     */
    public Object invokeClientMethod(String methodName, Object... args) {
        return invokeStaticMethod(clientClass, methodName, args);
    }
    
    /**
     * 调用 Server 类的静态方法
     */
    public Object invokeServerMethod(String methodName, Object... args) {
        return invokeStaticMethod(serverClass, methodName, args);
    }

    /**
     * 检查 Client 类是否存在指定签名的方法
     */
    public boolean hasClientMethod(String methodName, int argCount) {
        return hasMethod(clientClass, methodName, argCount);
    }

    /**
     * 检查 Server 类是否存在指定签名的方法
     */
    public boolean hasServerMethod(String methodName, int argCount) {
        return hasMethod(serverClass, methodName, argCount);
    }
    
    /**
     * 调用静态方法
     */
    private Object invokeStaticMethod(Class<?> clazz, String methodName, Object... args) {
        if (clazz == null) {
            Log.w(TAG, "Class is null, library not loaded. Method: " + methodName);
            return null;
        }
        
        // 检查 ClassLoader 是否有效
        if (classLoader == null) {
            Log.w(TAG, "ClassLoader is null, library not loaded. Method: " + methodName);
            return null;
        }
        
        try {
            // 查找方法（兼容旧版 Android，不能使用 getParameterCount）
            Method[] methods = clazz.getDeclaredMethods();
            Method targetMethod = null;
            int argCount = args == null ? 0 : args.length;
            for (Method method : methods) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                Class<?>[] paramTypes = method.getParameterTypes();
                if (paramTypes.length == argCount) {
                    targetMethod = method;
                    break;
                }
            }
            
            if (targetMethod == null) {
                Log.w(TAG, "Method not found: " + methodName);
                return null;
            }
            
            targetMethod.setAccessible(true);
            return targetMethod.invoke(null, args);
            
        } catch (UnsatisfiedLinkError e) {
            // Native 库未加载或已被卸载
            Log.w(TAG, "Native library not available for method: " + methodName + ", error: " + e.getMessage());
            return null;
        } catch (NoClassDefFoundError e) {
            // 类定义未找到（可能 ClassLoader 已被清理）
            Log.w(TAG, "Class definition not found for method: " + methodName + ", error: " + e.getMessage());
            return null;
        } catch (Exception e) {
            Log.w(TAG, "Failed to invoke method: " + methodName + ", error: " + e.getMessage());
            return null;
        } catch (Throwable t) {
            // 捕获所有其他异常（包括 Error）
            Log.w(TAG, "Unexpected error invoking method: " + methodName + ", error: " + t.getMessage());
            return null;
        }
    }

    private boolean hasMethod(Class<?> clazz, String methodName, int argCount) {
        if (clazz == null || classLoader == null) {
            return false;
        }
        try {
            Method[] methods = clazz.getDeclaredMethods();
            for (Method method : methods) {
                if (!method.getName().equals(methodName)) {
                    continue;
                }
                if (method.getParameterTypes().length == argCount) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }
    
    /**
     * 获取 AAR 文件路径
     */
    private File getAarFile(String version) {
        return new File(context.getFilesDir(), LIB_VERSIONS_DIR + File.separator + version + File.separator + AAR_FILE_NAME);
    }
    
    /**
     * 获取解压目录
     */
    private File getExtractedDir(String version) {
        return new File(context.getFilesDir(), LIB_VERSIONS_DIR + File.separator + version + File.separator + EXTRACTED_DIR);
    }

    /**
     * 删除指定版本的本地库文件
     * 用户删除后记录到 "已删除" 列表，installFromAssets 不会再从 assets 重新复制
     */
    public boolean deleteVersion(String version) {
        try {
            File versionDir = new File(context.getFilesDir(),
                    LIB_VERSIONS_DIR + File.separator + version);
            if (!versionDir.exists()) {
                addDeletedVersion(context, version);
                return true;
            }
            deleteDirectory(versionDir);
            Log.d(TAG, "Deleted version directory: " + versionDir.getAbsolutePath());
            addDeletedVersion(context, version);
            // 如果删除的是当前版本，清空 currentVersion，下次会重新选择
            if (version.equals(currentVersion)) {
                currentVersion = null;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete version: " + version, e);
            return false;
        }
    }

    /** 检查版本是否被用户删除过（不再从 assets 重新复制） */
    public static boolean isVersionDeleted(Context ctx, String version) {
        Set<String> set = getDeletedVersions(ctx);
        return set != null && set.contains(version);
    }

    /** 用户重新下载某版本后，从已删除列表中移除，允许 installFromAssets 再次复制 */
    public static void removeFromDeletedVersions(Context ctx, String version) {
        Set<String> set = getDeletedVersions(ctx);
        if (!set.contains(version)) return;
        Set<String> newSet = new HashSet<>(set);
        newSet.remove(version);
        ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putStringSet(KEY_DELETED_VERSIONS, newSet).apply();
        Log.d(TAG, "Removed version " + version + " from deleted list (user re-downloaded)");
    }

    private static void addDeletedVersion(Context ctx, String version) {
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Set<String> set = new HashSet<>(getDeletedVersions(ctx));
        set.add(version);
        prefs.edit().putStringSet(KEY_DELETED_VERSIONS, set).apply();
    }

    private static Set<String> getDeletedVersions(Context ctx) {
        SharedPreferences prefs = ctx.getApplicationContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        Set<String> set = prefs.getStringSet(KEY_DELETED_VERSIONS, null);
        return set != null ? new HashSet<>(set) : new HashSet<>();
    }
    
    /**
     * 解压 AAR 文件
     */
    private boolean extractAar(File aarFile, File outputDir) {
        try {
            if (outputDir.exists()) {
                deleteDirectory(outputDir);
            }
            outputDir.mkdirs();
            
            ZipInputStream zipInputStream = new ZipInputStream(new FileInputStream(aarFile));
            ZipEntry entry;
            
            while ((entry = zipInputStream.getNextEntry()) != null) {
                // 统一使用正斜杠，兼容 Windows 下打包产生的反斜杠路径（如 jni\armeabi-v7a\libgojni.so）
                String entryName = entry.getName().replace("\\", "/");
                File outputFile = new File(outputDir, entryName);
                
                if (entry.isDirectory()) {
                    outputFile.mkdirs();
                } else {
                    outputFile.getParentFile().mkdirs();
                    FileOutputStream fileOutputStream = new FileOutputStream(outputFile);
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zipInputStream.read(buffer)) != -1) {
                        fileOutputStream.write(buffer, 0, len);
                    }
                    fileOutputStream.close();
                }
                
                zipInputStream.closeEntry();
            }
            
            zipInputStream.close();
            return true;
            
        } catch (IOException e) {
            Log.e(TAG, "Failed to extract AAR", e);
            return false;
        }
    }
    
    /**
     * 删除目录
     */
    private void deleteDirectory(File directory) {
        if (directory.exists()) {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                }
            }
            directory.delete();
        }
    }
    
    /**
     * 获取当前加载的版本
     */
    public String getCurrentVersion() {
        return currentVersion;
    }

    /**
     * runFile 在 0.68.0 之前版本存在已知问题（uid 不生效）
     */
    public boolean isRunFileReliable() {
        return isVersionAtLeast(currentVersion, "0.68.0");
    }

    private boolean isVersionAtLeast(String version, String minVersion) {
        if (version == null || version.trim().isEmpty()) {
            return false;
        }
        int[] left = parseSemver(version);
        int[] right = parseSemver(minVersion);
        for (int i = 0; i < 3; i++) {
            if (left[i] > right[i]) {
                return true;
            }
            if (left[i] < right[i]) {
                return false;
            }
        }
        return true;
    }

    private int[] parseSemver(String version) {
        int[] out = new int[]{0, 0, 0};
        if (version == null) {
            return out;
        }
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        String[] parts = normalized.split("\\.");
        for (int i = 0; i < out.length && i < parts.length; i++) {
            out[i] = parseLeadingInt(parts[i]);
        }
        return out;
    }

    private int parseLeadingInt(String part) {
        if (part == null || part.isEmpty()) {
            return 0;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
    
    /**
     * 获取所有可用的版本
     */
    public String[] getAvailableVersions() {
        File versionsDir = new File(context.getFilesDir(), LIB_VERSIONS_DIR);
        if (!versionsDir.exists() || !versionsDir.isDirectory()) {
            return new String[0];
        }
        
        File[] versionDirs = versionsDir.listFiles(File::isDirectory);
        if (versionDirs == null) {
            return new String[0];
        }
        
        String[] versions = new String[versionDirs.length];
        for (int i = 0; i < versionDirs.length; i++) {
            versions[i] = versionDirs[i].getName();
        }
        return versions;
    }
    
    /**
     * 检查库是否已成功加载
     * @return true 如果库已加载成功
     */
    public boolean isLibraryLoaded() {
        return clientClass != null && serverClass != null && classLoader != null;
    }
    
    /**
     * 检查是否有可用版本
     * @return true 如果有可用版本
     */
    public boolean hasAvailableVersions() {
        String[] versions = getAvailableVersions();
        return versions != null && versions.length > 0;
    }
}
