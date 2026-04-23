package max.plus.frp;

public class ConfigFormatUtils {

    private ConfigFormatUtils() {
    }

    public static String normalizeFormat(String format) {
        if (format == null) {
            return "toml";
        }
        String f = format.trim().toLowerCase();
        if (f.isEmpty()) {
            return "toml";
        }
        if ("yml".equals(f)) {
            return "yaml";
        }
        if ("ini".equals(f) || "toml".equals(f) || "yaml".equals(f) || "json".equals(f)) {
            return f;
        }
        return "toml";
    }

    public static String inferFormatFromContent(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "toml";
        }

        String trimmed = content.trim();
        // JSON object or array
        if (trimmed.startsWith("{") || startsWithJsonArray(trimmed)) {
            return "json";
        }

        // YAML: key: value 风格，且通常不使用 '='
        if (trimmed.contains(":") && !trimmed.contains("=") && !trimmed.startsWith("[")) {
            String[] lines = trimmed.split("\n");
            for (String line : lines) {
                if (line.matches("^\\s*[a-zA-Z_][a-zA-Z0-9_\\-]*:.*")) {
                    return "yaml";
                }
            }
        }

        // TOML 常见特征：[[array]]、引号值、驼峰键/点键
        if (trimmed.contains("[[")) {
            return "toml";
        }
        if (trimmed.matches("(?s).*^[a-zA-Z0-9_.-]+\\s*=\\s*\".*$.*")) {
            return "toml";
        }

        // INI 常见特征：[section] + key = value（含下划线键）
        if (trimmed.contains("[") && trimmed.contains("]") && trimmed.contains("=")) {
            return "ini";
        }

        return "toml";
    }

    public static String inferFormatFromFileName(String fileName) {
        if (fileName == null) {
            return "";
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx >= fileName.length() - 1) {
            return "";
        }
        String ext = normalizeFormat(fileName.substring(idx + 1));
        if ("ini".equals(ext) || "toml".equals(ext) || "yaml".equals(ext) || "json".equals(ext)) {
            return ext;
        }
        return "";
    }

    public static String inferFormatAfterFileEdited(String fileName, String content, String currentFormat) {
        String byContent = inferFormatFromContent(content);
        if (!byContent.isEmpty()) {
            return byContent;
        }
        String byName = inferFormatFromFileName(fileName);
        if (!byName.isEmpty()) {
            return byName;
        }
        return normalizeFormat(currentFormat);
    }

    private static boolean startsWithJsonArray(String trimmed) {
        if (!trimmed.startsWith("[")) {
            return false;
        }
        int i = 1;
        while (i < trimmed.length() && Character.isWhitespace(trimmed.charAt(i))) {
            i++;
        }
        if (i >= trimmed.length()) {
            return false;
        }
        char c = trimmed.charAt(i);
        return c == '{' || c == '\"' || c == '[' || c == '-' || Character.isDigit(c);
    }
}
