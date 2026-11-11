package com.mgb.keyboard.typing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v0.10.7 (W2) pure rules for the backup that survives an uninstall. No Android classes, no java.nio.
 *  - FILES = data files (filesDir) that go into every backup (v0.10.7: + learn_v1.txt).
 *  - external copy = file jabra_YYYYMMDD.json in the folder the user picked (one per day, same day = overwritten).
 *  - pruneList = which old external files to delete (newest N kept, only names of OUR pattern are ever touched).
 *  - healthWarn = text for the Health Check ("" = nothing to say).
 * Stamps use the same format as pref bk_last: "yyyyMMdd_HHmm" (local time).
 */
public final class BackupPlan {
    private BackupPlan() { }

    /** Extra data files (filesDir) included in a backup when they exist. emoji_user_v1.txt is NOT user data (re-downloadable). */
    public static final String[] FILES = { "vault_v1.json", "profiles_v1.json", "learn_v1.txt" };
    public static final int KEEP_DEFAULT = 5;
    /** Health Check warns when the newest external copy is this many days old (or older). */
    public static final int STALE_DAYS = 3;

    public static boolean isAllowedFile(String name) {
        if (name == null) return false;
        for (String f : FILES) if (f.equals(name)) return true;
        return false;
    }

    /** "jabra_20261113.json" */
    public static String externalName(int year, int month, int day) {
        return String.format(Locale.US, "jabra_%04d%02d%02d.json", year, month, day);
    }

    /** Name for a point in time (device local time). */
    public static String externalName(long millis) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(millis);
        return externalName(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /** True only for names we made: jabra_ + 8 digits (real month / day) + .json. Anything else in the user's folder is never touched. */
    public static boolean isExternalName(String n) {
        try {
            if (n == null || n.length() != 19 || !n.startsWith("jabra_") || !n.endsWith(".json")) return false;
            for (int i = 6; i < 14; i++) { char c = n.charAt(i); if (c < '0' || c > '9') return false; }
            int m = Integer.parseInt(n.substring(10, 12)), d = Integer.parseInt(n.substring(12, 14));
            return m >= 1 && m <= 12 && d >= 1 && d <= 31;
        } catch (Throwable t) { return false; }
    }

    /** Names to DELETE so that only the newest `keep` external files stay. keep < 1 = KEEP_DEFAULT. Foreign names are ignored, duplicates counted once. */
    public static List<String> pruneList(List<String> names, int keep) {
        List<String> out = new ArrayList<String>();
        try {
            if (names == null) return out;
            int k = keep < 1 ? KEEP_DEFAULT : keep;
            Set<String> seen = new HashSet<String>();
            List<String> mine = new ArrayList<String>();
            for (String n : names) if (isExternalName(n) && seen.add(n)) mine.add(n);
            Collections.sort(mine, Collections.reverseOrder());   // names carry the date: newest first
            for (int i = k; i < mine.size(); i++) out.add(mine.get(i));
        } catch (Throwable t) { out.clear(); }
        return out;
    }

    /** Whole days since a bk_ext_last stamp; -1 = never / unreadable. */
    public static int daysSinceExternal(String stamp, long now) {
        return HealthCheck.daysSince(stamp, now);
    }

    /** Folder set and no copy made on today's calendar date yet. */
    public static boolean isDue(boolean folderSet, String lastStamp, long now) {
        if (!folderSet) return false;
        try {
            if (lastStamp == null || lastStamp.length() != 13) return true;
            String today = externalName(now).substring(6, 14);   // calendar day, not 24 h: a copy from yesterday 23:00 is due at 00:05
            return !lastStamp.startsWith(today);
        } catch (Throwable t) { return true; }
    }

    /** Short readable folder name from a tree document id such as "primary:Download/Jabra" -> "Download/Jabra". */
    public static String label(String treeDocId) {
        try {
            if (treeDocId == null || treeDocId.trim().isEmpty()) return "not set";
            String s = treeDocId.trim();
            int i = s.indexOf(':');
            String path = i >= 0 ? s.substring(i + 1) : s;
            if (path.isEmpty()) return "(whole storage)";
            return path.length() > 48 ? "..." + path.substring(path.length() - 45) : path;
        } catch (Throwable t) { return "set"; }
    }

    /**
     * Health Check text for the external backup. "" = nothing to say.
     * folderSet: pref bk_ext_uri not empty. accessOk: the app still holds the permission for that folder.
     * lastStamp: pref bk_ext_last. vaultCount: Vault entries on disk (0 / -1 = none).
     */
    public static String healthWarn(boolean folderSet, boolean accessOk, String lastStamp, long now, int vaultCount) {
        try {
            int d = daysSinceExternal(lastStamp, now);
            if (!folderSet) {
                if (vaultCount > 0 && d < 0) return "No external backup: your Vault is lost if the app is uninstalled. Open Data & Backup > Choose folder.";
                return "";
            }
            if (!accessOk) return "External backup folder is not reachable (access removed?). Open Data & Backup > Choose folder again.";
            if (d < 0) return "External backup folder is set but no copy was made yet. Tap Backup to folder now.";
            if (d >= STALE_DAYS) return "No external backup for " + d + " days. Tap Backup to folder now.";
            return "";
        } catch (Throwable t) { return ""; }
    }

    /** True when folderSet, access fine and the newest copy is younger than STALE_DAYS (Health shows an OK line). */
    public static boolean externalOk(boolean folderSet, boolean accessOk, String lastStamp, long now) {
        if (!folderSet || !accessOk) return false;
        int d = daysSinceExternal(lastStamp, now);
        return d >= 0 && d < STALE_DAYS;
    }
}
