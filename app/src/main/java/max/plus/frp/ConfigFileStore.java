package max.plus.frp;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigFileStore {
    private static final Pattern TOML_PATH_QUOTED = Pattern.compile("(?m)^(\\s*path\\s*=\\s*)\"\\./([^\"]+)\"(\\s*(?:#.*)?)$");
    private static final Pattern TOML_PATH_RAW = Pattern.compile("(?m)^(\\s*path\\s*=\\s*)\\./([^\\s#;]+)(\\s*(?:#.*)?)$");
    private static final Pattern JSON_YAML_PATH = Pattern.compile("(?m)^(\\s*\"?path\"?\\s*:\\s*)\"\\./([^\"]+)\"(\\s*,?\\s*)$");

    private static final String DIR_FRPC = "frpc";
    private static final String DIR_FRPS = "frps";

    private ConfigFileStore() {
    }

    public static File getConfigFile(Context context, String type, String uid, String name, String format) {
        String safeType = "frps".equalsIgnoreCase(type) ? DIR_FRPS : DIR_FRPC;
        String safeFormat = ConfigFormatUtils.normalizeFormat(format);
        File typeRoot = new File(context.getFilesDir(), safeType);
        if (!typeRoot.exists()) {
            typeRoot.mkdirs();
        }

        String safeUid = sanitizeFileName(uid);
        if (safeUid.isEmpty()) {
            safeUid = "unknown";
        }
        File uidDir = new File(typeRoot, safeUid);
        if (!uidDir.exists()) {
            uidDir.mkdirs();
        }

        String baseName = sanitizeFileName(name);
        if (baseName.isEmpty()) {
            baseName = DIR_FRPS.equals(safeType) ? "frps" : "frpc";
        }
        // DB 中 name 不带格式；落盘文件名统一拼接 format 后缀
        return new File(uidDir, baseName + "." + safeFormat);
    }

    public static File writeConfigAtomic(Context context, String type, String uid, String name, String format, String content) throws IOException {
        File target = getConfigFile(context, type, uid, name, format);
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        String normalized = normalizeStorePath(content == null ? "" : content, parent);
        // 内容一致时直接复用，避免不必要的写盘与 FileObserver 触发
        if (target.exists()) {
            String oldContent = readUtf8(target);
            if (oldContent.equals(normalized)) {
                return target;
            }
        }
        writeExistingFileAtomic(target, normalized);
        return target;
    }

    public static String readUtf8(File file) throws IOException {
        byte[] buf = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int read = fis.read(buf);
            if (read <= 0) {
                return "";
            }
            return new String(buf, 0, read, StandardCharsets.UTF_8);
        }
    }

    public static void writeExistingFileAtomic(File target, String content) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File temp = new File(parent, target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(temp, false)) {
            byte[] data = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
            fos.write(data);
            fos.flush();
        }
        if (target.exists() && !target.delete()) {
            throw new IOException("Failed to replace old config file: " + target.getAbsolutePath());
        }
        if (!temp.renameTo(target)) {
            throw new IOException("Failed to rename temp config file: " + temp.getAbsolutePath());
        }
    }

    public static String normalizeStorePath(String content, File configDir) {
        if (content == null || content.isEmpty() || configDir == null) {
            return content == null ? "" : content;
        }
        String normalized = content;
        normalized = replaceWithAbsolute(TOML_PATH_QUOTED, normalized, configDir);
        normalized = replaceWithAbsolute(TOML_PATH_RAW, normalized, configDir);
        normalized = replaceWithAbsolute(JSON_YAML_PATH, normalized, configDir);
        return normalized;
    }

    private static String replaceWithAbsolute(Pattern pattern, String source, File configDir) {
        Matcher matcher = pattern.matcher(source);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String prefix = matcher.group(1);
            String relative = matcher.group(2);
            String suffix = matcher.group(3);
            File abs = new File(configDir, relative);
            String absPath = abs.getAbsolutePath().replace("\\", "/");
            String replacement = prefix + "\"" + absPath + "\"" + suffix;
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private static String sanitizeFileName(String name) {
        if (name == null) {
            return "";
        }
        String n = name.trim();
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        n = n.replaceAll("\\s+", "_");
        return n;
    }
}
