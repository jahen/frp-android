package max.plus.frp;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 用户导入的外部文件（证书、密钥等），存放在应用私有目录 files/imported/ 下。
 */
public class ImportedFileStore {
    private static final String DIR_IMPORTED = "imported";

    private ImportedFileStore() {
    }

    public static class ImportedFile {
        public String name;
        public File file;
        public long size;
        public long lastModified;
        public String absolutePath;

        public String getFormattedSize() {
            return formatSize(size);
        }
    }

    public static File getImportedDir(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), DIR_IMPORTED);
    }

    public static List<ImportedFile> listFiles(Context context) {
        File dir = getImportedDir(context);
        if (!dir.exists()) {
            return new ArrayList<>();
        }
        File[] files = dir.listFiles();
        if (files == null || files.length == 0) {
            return new ArrayList<>();
        }
        List<ImportedFile> result = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            ImportedFile item = new ImportedFile();
            item.name = file.getName();
            item.file = file;
            item.size = file.length();
            item.lastModified = file.lastModified();
            item.absolutePath = file.getAbsolutePath().replace("\\", "/");
            result.add(item);
        }
        Collections.sort(result, new Comparator<ImportedFile>() {
            @Override
            public int compare(ImportedFile a, ImportedFile b) {
                return Long.compare(b.lastModified, a.lastModified);
            }
        });
        return result;
    }

    public static File importFromUri(Context context, Uri uri) throws IOException {
        if (uri == null) {
            throw new IOException("Uri is null");
        }
        File dir = getImportedDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Failed to create imported directory");
        }
        String displayName = queryDisplayName(context, uri);
        String fileName = uniqueName(dir, displayName);
        File dest = new File(dir, fileName);
        InputStream in = context.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("Cannot open input stream");
        }
        try (InputStream input = in; FileOutputStream out = new FileOutputStream(dest)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = input.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            out.flush();
        }
        return dest;
    }

    public static boolean deleteFile(Context context, String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return false;
        }
        File target = new File(getImportedDir(context), sanitizeFileName(fileName));
        if (!target.exists() || !target.isFile()) {
            return false;
        }
        File parent = target.getParentFile();
        if (parent == null || !getImportedDir(context).equals(parent)) {
            return false;
        }
        return target.delete();
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.getDefault(), "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String queryDisplayName(Context context, Uri uri) {
        String name = null;
        if ("content".equals(uri.getScheme())) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    name = cursor.getString(0);
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        if (TextUtils.isEmpty(name)) {
            name = uri.getLastPathSegment();
        }
        if (TextUtils.isEmpty(name)) {
            name = "imported_file";
        }
        return sanitizeFileName(name);
    }

    private static String uniqueName(File dir, String name) {
        if (!new File(dir, name).exists()) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 1000; i++) {
            String candidate = base + "(" + i + ")" + ext;
            if (!new File(dir, candidate).exists()) {
                return candidate;
            }
        }
        return base + "_" + System.currentTimeMillis() + ext;
    }

    private static String sanitizeFileName(String name) {
        if (name == null) {
            return "imported_file";
        }
        String n = name.trim();
        int slash = Math.max(n.lastIndexOf('/'), n.lastIndexOf('\\'));
        if (slash >= 0 && slash < n.length() - 1) {
            n = n.substring(slash + 1);
        }
        n = n.replaceAll("[\\\\/:*?\"<>|]", "_");
        n = n.replaceAll("\\s+", "_");
        if (n.isEmpty()) {
            return "imported_file";
        }
        return n;
    }
}
