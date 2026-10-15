package com.badukai.engine;

/** Pure Java, testable KataGo GTP config overrides used by MediaTek GTP smoke. */
public final class KataGoGtpConfig {
    private KataGoGtpConfig() {}

    /**
     * Replace the first active key and discard duplicate active definitions.
     * A commented example is not an active definition.
     */
    public static String set(String config, String key, String value) {
        if (config == null || value == null || key == null
                || !key.matches("[A-Za-z][A-Za-z0-9]*"))
            throw new IllegalArgumentException("Invalid KataGo GTP config override");
        StringBuilder result = new StringBuilder(config.length() + key.length() + value.length() + 32);
        boolean found = false;
        for (String line : config.split("\\r?\\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("#")
                    && trimmed.matches(java.util.regex.Pattern.quote(key) + "\\s*=.*")) {
                if (!found) {
                    result.append(key).append(" = ").append(value).append('\n');
                    found = true;
                }
            } else if (!line.isEmpty()) {
                result.append(line).append('\n');
            }
        }
        if (!found) result.append(key).append(" = ").append(value).append('\n');
        return result.toString();
    }
}
