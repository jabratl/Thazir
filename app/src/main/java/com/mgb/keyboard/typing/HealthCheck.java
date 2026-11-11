package com.mgb.keyboard.typing;

import com.mgb.keyboard.clip.ClipList;
import com.mgb.keyboard.vault.VaultList;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v0.5.3 pure health check (no Android classes, tested by tools/HealthTest.java).
 * run(Input) -> one line per data store: OK / WARN / BAD + short text + optional fix id.
 * Fix ids: "clips" (drop bad lines after a backup copy), "vault" / "profiles" (restore the file from the newest good backup),
 * "crash" (re-enable a panel). Everything else is report-only (never changes user data by itself).
 * Also holds the panel crash-guard rules used by the one IME hook (PanelGuard in HealthPanel.kt).
 */
public final class HealthCheck {
    private HealthCheck() { }

    public static final int OK = 0, WARN = 1, BAD = 2;
    public static final long BLOCK_MS = 24L * 60 * 60 * 1000;
    public static final int CRASH_LIMIT = 2;
    public static final long STORAGE_WARN = 30L * 1024 * 1024;

    public static final class Item {
        public final String id;      // store id: clips fmt vault profiles theme backups storage schema crash
        public final int level;
        public final String text;
        public final String fix;     // "" = report only
        Item(String id, int level, String text, String fix) { this.id = id; this.level = level; this.text = text; this.fix = fix; }
        public String label() { return (level == OK ? "OK   " : level == WARN ? "WARN " : "BAD  ") + text; }
    }

    /** All values are plain strings / numbers read by the caller. null text = the file / pref does not exist. */
    public static final class Input {
        public String clips = "";        // pref clips_v1
        public String fmtCur = "";       // pref fmt_cur
        public String fmtPresets = "";   // pref fmt_presets
        public String ctJson = "";       // pref ct_json
        public int theme = 0;            // pref theme
        public String vault = null;      // text of vault_v1.json, null = no file
        public String profiles = null;   // text of profiles_v1.json, null = no file
        public int backups = 0;          // backup files found
        public int badBackups = 0;       // of those: damaged
        public long storageBytes = 0;
        public int schemaV = 1;
        public String crashPanel = "";   // pref crash_p
        public int crashN = 0;           // pref crash_n
        public long crashT = 0;          // pref crash_t
        public long now = 0;
        public boolean extSet = false;      // v0.10.7: pref bk_ext_uri not empty
        public boolean extAccessOk = false; // v0.10.7: app still holds the permission for that folder
        public String extLast = "";         // v0.10.7: pref bk_ext_last
        public int vaultCount = 0;          // v0.10.7: Vault entries on disk (<= 0 = none / unreadable)
    }

    public static List<Item> run(Input in) {
        List<Item> out = new ArrayList<Item>();
        // clipboard
        try {
            int lines = countLines(in.clips), good = ClipList.parse(in.clips).size(), bad = lines - good;
            if (bad <= 0) out.add(new Item("clips", OK, "Clipboard: " + good + " clips readable", ""));
            else out.add(new Item("clips", WARN, "Clipboard: " + bad + " unreadable line(s), " + good + " clips fine", "clips"));
        } catch (Throwable t) { out.add(new Item("clips", BAD, "Clipboard: could not check", "")); }
        // formatter
        try {
            boolean a = isJson(in.fmtCur), b = isJson(in.fmtPresets);
            if (a && b) out.add(new Item("fmt", OK, "Formatter settings: valid", ""));
            else out.add(new Item("fmt", WARN, "Formatter " + (!a ? "current options" : "presets") + " damaged (formatter uses defaults). Restore a backup to get them back.", ""));
        } catch (Throwable t) { out.add(new Item("fmt", BAD, "Formatter: could not check", "")); }
        // vault
        try {
            if (in.vault == null || in.vault.trim().isEmpty()) out.add(new Item("vault", OK, "Vault: no file yet (nothing saved)", ""));
            else {
                VaultList v = VaultList.parse(in.vault);
                if (v != null) out.add(new Item("vault", OK, "Vault: " + v.size() + " entries readable", ""));
                else out.add(new Item("vault", BAD, "Vault file is damaged or from a newer version", "vault"));
            }
        } catch (Throwable t) { out.add(new Item("vault", BAD, "Vault file is damaged", "vault")); }
        // profiles
        try {
            if (in.profiles == null || in.profiles.trim().isEmpty()) out.add(new Item("profiles", OK, "Profiles: no file yet (nothing saved)", ""));
            else {
                List<ProfileCodec.Profile> p = ProfileCodec.parse(in.profiles);
                if (p != null) out.add(new Item("profiles", OK, "Profiles: " + p.size() + " saved", ""));
                else out.add(new Item("profiles", BAD, "Profiles file is damaged or from a newer version", "profiles"));
            }
        } catch (Throwable t) { out.add(new Item("profiles", BAD, "Profiles file is damaged", "profiles")); }
        // custom theme
        try {
            boolean has = in.ctJson != null && !in.ctJson.trim().isEmpty();
            boolean valid = has && CustomTheme.parse(in.ctJson) != null;
            if (has && !valid) out.add(new Item("theme", WARN, "Custom theme data is damaged (default theme is used). Theme Studio > Reset clears it.", ""));
            else if (in.theme == 20 && !valid) out.add(new Item("theme", WARN, "Custom theme is selected but has no colours (default theme is used)", ""));
            else out.add(new Item("theme", OK, "Custom theme: " + (valid ? "valid" : "not used"), ""));
        } catch (Throwable t) { out.add(new Item("theme", BAD, "Theme: could not check", "")); }
        // backups
        if (in.backups == 0) out.add(new Item("backups", WARN, "Backups: none yet (tap Backup now)", ""));
        else if (in.badBackups > 0) out.add(new Item("backups", WARN, "Backups: " + in.badBackups + " of " + in.backups + " damaged (delete them in the list)", ""));
        else out.add(new Item("backups", OK, "Backups: " + in.backups + " readable", ""));
        // external backup (v0.10.7)
        try {
            String w = BackupPlan.healthWarn(in.extSet, in.extAccessOk, in.extLast, in.now, in.vaultCount);
            if (!w.isEmpty()) out.add(new Item("extbackup", WARN, w, ""));
            else if (BackupPlan.externalOk(in.extSet, in.extAccessOk, in.extLast, in.now)) out.add(new Item("extbackup", OK, "External backup: " + ago(BackupPlan.daysSinceExternal(in.extLast, in.now)), ""));
        } catch (Throwable t) { }
        // storage
        out.add(new Item("storage", in.storageBytes > STORAGE_WARN ? WARN : OK,
            "Storage: " + size(in.storageBytes) + (in.storageBytes > STORAGE_WARN ? " (big: lower \"Keep newest copies\")" : ""), ""));
        // schema
        if (in.schemaV > Migrate.CURRENT) out.add(new Item("schema", WARN, "Settings are from a newer app version (schema " + in.schemaV + "). Nothing was changed.", ""));
        else if (in.schemaV < Migrate.CURRENT) out.add(new Item("schema", WARN, "Settings update pending (schema " + in.schemaV + " -> " + Migrate.CURRENT + "). Close and reopen this app screen.", ""));
        else out.add(new Item("schema", OK, "Settings version: " + in.schemaV, ""));
        // panel crash guard
        if (in.crashPanel != null && !in.crashPanel.isEmpty() && in.crashN >= CRASH_LIMIT) {
            boolean active = in.now - in.crashT < BLOCK_MS;
            out.add(new Item("crash", active ? BAD : WARN, "Panel \"" + in.crashPanel + "\" " + (active ? "is switched off after " : "crashed ") + in.crashN + " crashes in a row", "crash"));
        } else out.add(new Item("crash", OK, "Panels: no repeated crash", ""));
        return out;
    }

    public static int worst(List<Item> items) { int w = OK; for (Item i : items) if (i.level > w) w = i.level; return w; }

    // ---------- repairs (pure text) ----------
    /** Lines of the saved clipboard text that are not empty. */
    public static int countLines(String raw) {
        if (raw == null || raw.isEmpty()) return 0;
        int n = 0;
        for (String l : raw.split("\n")) if (!l.trim().isEmpty()) n++;
        return n;
    }

    /** Same text without the lines ClipList cannot read (order + pins kept). Caller saves a backup first. */
    public static String repairClips(String raw) { return ClipList.parse(raw).serialize(); }

    // ---------- backup age (v0.5.8) ----------
    public static final int STALE_DAYS = 7;

    /** Whole days between a bk_last stamp "yyyyMMdd_HHmm" (local time) and now. -1 = empty / unreadable stamp. Never negative otherwise. */
    public static int daysSince(String stamp, long now) {
        try {
            if (stamp == null || stamp.length() != 13) return -1;
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyyMMdd_HHmm", Locale.US);
            f.setLenient(false);
            long t = f.parse(stamp).getTime();
            long d = (now - t) / 86400000L;
            return d < 0 ? 0 : (int) Math.min(d, 99999);
        } catch (Throwable e) { return -1; }
    }

    /** v0.5.9: "today" / "yesterday" / "N days ago" for the Data & Backup page. "" when days < 0. */
    public static String ago(int days) {
        if (days < 0) return "";
        if (days == 0) return "today";
        if (days == 1) return "yesterday";
        return days + " days ago";
    }

    /** Text for the app screen, "" = nothing to say. Only when auto backup is OFF (ON = a backup is made every day) and the last one is missing or older than STALE_DAYS. */
    public static String backupNote(boolean autoOn, String stamp, long now) {
        if (autoOn) return "";
        int d = daysSince(stamp, now);
        if (d < 0) return "No backup yet and Auto backup is OFF. Open Data & Backup > Backup now.";
        if (d >= STALE_DAYS) return "Last backup is " + d + " days old and Auto backup is OFF. Open Data & Backup > Backup now.";
        return "";
    }

    // ---------- crash guard rules ----------
    /** True when the panel failed CRASH_LIMIT times in a row within the last 24 h. */
    public static boolean blocked(String lastPanel, int n, long t, String name, long now) {
        return name != null && name.equals(lastPanel) && n >= CRASH_LIMIT && now - t < BLOCK_MS && now >= t;
    }

    /** New counter after the panel `name` failed: same panel again = n + 1, another panel = 1. */
    public static int nextCount(String lastPanel, int n, String name) {
        return (name != null && name.equals(lastPanel)) ? Math.min(n + 1, 99) : 1;
    }

    // ---------- tiny JSON validator ----------
    /** Blank = OK (pref not set). Otherwise the text must be exactly one valid JSON value. */
    public static boolean isJson(String s) {
        if (s == null || s.trim().isEmpty()) return true;
        try { J j = new J(s); j.ws(); j.value(0); j.ws(); return j.i == s.length(); } catch (RuntimeException e) { return false; }
    }

    private static final class J {
        final String s; int i = 0;
        J(String s) { this.s = s; }
        void ws() { while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++; }
        char peek() { if (i >= s.length()) throw new IllegalStateException(); return s.charAt(i); }
        void value(int depth) {
            if (depth > 40) throw new IllegalStateException();
            char c = peek();
            if (c == '{') { i++; ws(); if (peek() == '}') { i++; return; }
                while (true) { ws(); str(); ws(); expect(':'); ws(); value(depth + 1); ws(); char d = peek(); i++; if (d == '}') return; if (d != ',') throw new IllegalStateException(); } }
            else if (c == '[') { i++; ws(); if (peek() == ']') { i++; return; }
                while (true) { ws(); value(depth + 1); ws(); char d = peek(); i++; if (d == ']') return; if (d != ',') throw new IllegalStateException(); } }
            else if (c == '"') str();
            else if (s.startsWith("true", i)) i += 4;
            else if (s.startsWith("false", i)) i += 5;
            else if (s.startsWith("null", i)) i += 4;
            else num();
        }
        void expect(char c) { if (peek() != c) throw new IllegalStateException(); i++; }
        void str() {
            expect('"');
            while (true) {
                char c = peek(); i++;
                if (c == '"') return;
                if (c < 0x20) throw new IllegalStateException();
                if (c == '\\') { char e = peek(); i++;
                    if (e == 'u') { for (int k = 0; k < 4; k++) { char h = peek(); i++; if (Character.digit(h, 16) < 0) throw new IllegalStateException(); } }
                    else if ("\"\\/bfnrt".indexOf(e) < 0) throw new IllegalStateException(); }
            }
        }
        void num() {
            int st = i;
            if (peek() == '-') i++;
            int d = 0; while (i < s.length() && Character.isDigit(s.charAt(i))) { i++; d++; }
            if (d == 0) throw new IllegalStateException();
            if (i < s.length() && s.charAt(i) == '.') { i++; int f = 0; while (i < s.length() && Character.isDigit(s.charAt(i))) { i++; f++; } if (f == 0) throw new IllegalStateException(); }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) { i++; if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++; int e = 0; while (i < s.length() && Character.isDigit(s.charAt(i))) { i++; e++; } if (e == 0) throw new IllegalStateException(); }
            if (i == st) throw new IllegalStateException();
        }
    }

    static String size(long n) {
        if (n < 1024) return n + " B";
        if (n < 1048576) return (n / 1024) + " KB";
        return String.format(Locale.US, "%.1f MB", n / 1048576.0);
    }
}
