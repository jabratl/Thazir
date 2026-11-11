import com.mgb.keyboard.typing.BackupPlan;
import com.mgb.keyboard.typing.BackupCodec;
import com.mgb.keyboard.typing.HealthCheck;
import java.util.*;

/** v0.10.7 (W2) BackupPlan tests (computer only).
 *  javac -encoding UTF-8 -d out app/src/main/java/com/mgb/keyboard/typing/*.java app/src/main/java/com/mgb/keyboard/clip/*.java app/src/main/java/com/mgb/keyboard/vault/*.java tools/BackupPlanTest.java && java -cp out BackupPlanTest */
public class BackupPlanTest {
    static int ok = 0, bad = 0;
    static void t(String name, boolean cond) { if (cond) ok++; else { bad++; System.out.println("FAIL: " + name); } }

    static long at(int y, int m, int d, int h) {
        Calendar c = Calendar.getInstance(); c.clear(); c.set(y, m - 1, d, h, 0, 0); return c.getTimeInMillis();
    }

    public static void main(String[] a) {
        // ---- file whitelist ----
        t("files has vault", BackupPlan.isAllowedFile("vault_v1.json"));
        t("files has profiles", BackupPlan.isAllowedFile("profiles_v1.json"));
        t("files has learn (new)", BackupPlan.isAllowedFile("learn_v1.txt"));
        t("files: emoji user list NOT", !BackupPlan.isAllowedFile("emoji_user_v1.txt"));
        t("files: .bad NOT", !BackupPlan.isAllowedFile("learn_v1.bad"));
        t("files: .damaged NOT", !BackupPlan.isAllowedFile("vault_v1.json.damaged"));
        t("files: null / empty", !BackupPlan.isAllowedFile(null) && !BackupPlan.isAllowedFile(""));
        t("files: path trick", !BackupPlan.isAllowedFile("../vault_v1.json") && !BackupPlan.isAllowedFile("/vault_v1.json"));
        t("files: case", !BackupPlan.isAllowedFile("Vault_v1.json"));
        t("files count 3", BackupPlan.FILES.length == 3);

        // ---- external name ----
        t("name basic", BackupPlan.externalName(2026, 11, 13).equals("jabra_20261113.json"));
        t("name zero pad", BackupPlan.externalName(2026, 1, 5).equals("jabra_20260105.json"));
        t("name from millis", BackupPlan.externalName(at(2026, 12, 31, 23)).equals("jabra_20261231.json"));
        t("name from millis early", BackupPlan.externalName(at(2027, 1, 1, 0)).equals("jabra_20270101.json"));
        t("name is ours", BackupPlan.isExternalName(BackupPlan.externalName(2026, 11, 13)));
        t("isExternal ok", BackupPlan.isExternalName("jabra_20261113.json"));
        t("isExternal: other app file", !BackupPlan.isExternalName("photo.jpg"));
        t("isExternal: manual export name", !BackupPlan.isExternalName("jabra_backup_20261113_1200.json"));
        t("isExternal: internal name", !BackupPlan.isExternalName("jabra_20261113_1200.json"));
        t("isExternal: month 13", !BackupPlan.isExternalName("jabra_20261313.json"));
        t("isExternal: day 00", !BackupPlan.isExternalName("jabra_20261100.json"));
        t("isExternal: letters", !BackupPlan.isExternalName("jabra_2026111a.json"));
        t("isExternal: null / empty", !BackupPlan.isExternalName(null) && !BackupPlan.isExternalName(""));
        t("isExternal: extra ext", !BackupPlan.isExternalName("jabra_20261113.json.bak"));
        t("isExternal: before_restore", !BackupPlan.isExternalName("before_restore_20261113_1200.json"));

        // ---- prune list ----
        List<String> n = new ArrayList<String>(Arrays.asList("jabra_20261101.json", "jabra_20261103.json", "jabra_20261102.json", "jabra_20261104.json", "jabra_20261105.json", "jabra_20261106.json", "jabra_20261107.json"));
        List<String> p = BackupPlan.pruneList(n, 5);
        t("prune 7 keep 5 -> 2", p.size() == 2);
        t("prune oldest first-removed", p.contains("jabra_20261101.json") && p.contains("jabra_20261102.json"));
        t("prune keeps newest", !p.contains("jabra_20261107.json") && !p.contains("jabra_20261103.json"));
        t("prune keep 10 -> none", BackupPlan.pruneList(n, 10).isEmpty());
        t("prune keep 3 -> 4", BackupPlan.pruneList(n, 3).size() == 4);
        t("prune keep 0 = default 5", BackupPlan.pruneList(n, 0).size() == 2);
        t("prune keep -1 = default 5", BackupPlan.pruneList(n, -1).size() == 2);
        List<String> mixed = new ArrayList<String>(Arrays.asList("holiday.jpg", "jabra_20261101.json", "notes.txt", "jabra_20261102.json", "jabra_backup_old.json"));
        t("prune never touches foreign names", BackupPlan.pruneList(mixed, 1).equals(Arrays.asList("jabra_20261101.json")));
        t("prune foreign only = none", BackupPlan.pruneList(Arrays.asList("a.jpg", "b.json"), 1).isEmpty());
        t("prune null / empty", BackupPlan.pruneList(null, 5).isEmpty() && BackupPlan.pruneList(new ArrayList<String>(), 5).isEmpty());
        t("prune duplicates counted once", BackupPlan.pruneList(Arrays.asList("jabra_20261101.json", "jabra_20261101.json", "jabra_20261102.json"), 2).isEmpty());
        t("prune across year", BackupPlan.pruneList(Arrays.asList("jabra_20261231.json", "jabra_20270101.json"), 1).equals(Arrays.asList("jabra_20261231.json")));
        t("prune does not change input", n.size() == 7 && n.get(0).equals("jabra_20261101.json"));

        // ---- days since / due ----
        long now = at(2026, 11, 13, 12);
        t("days none", BackupPlan.daysSinceExternal("", now) == -1 && BackupPlan.daysSinceExternal(null, now) == -1);
        t("days junk", BackupPlan.daysSinceExternal("abc", now) == -1);
        t("days today", BackupPlan.daysSinceExternal("20261113_0800", now) == 0);
        t("days 3", BackupPlan.daysSinceExternal("20261110_1200", now) == 3);
        t("due: folder off", !BackupPlan.isDue(false, "", now));
        t("due: never copied", BackupPlan.isDue(true, "", now));
        t("due: copied today", !BackupPlan.isDue(true, "20261113_0800", now));
        t("due: copied yesterday", BackupPlan.isDue(true, "20261112_2300", now));
        t("due: junk stamp", BackupPlan.isDue(true, "zzz", now));
        t("due: 5 minutes after midnight", BackupPlan.isDue(true, "20261112_2358", at(2026, 11, 13, 0) + 300000L));
        t("due: same day late", !BackupPlan.isDue(true, "20261113_0001", at(2026, 11, 13, 23)));

        // ---- folder label ----
        t("label null", BackupPlan.label(null).equals("not set") && BackupPlan.label("  ").equals("not set"));
        t("label path", BackupPlan.label("primary:Download/Jabra").equals("Download/Jabra"));
        t("label root", BackupPlan.label("primary:").equals("(whole storage)"));
        t("label no colon", BackupPlan.label("Backups").equals("Backups"));
        t("label long cut", BackupPlan.label("primary:" + "x".repeat(80)).length() == 48);
        t("label bangla", BackupPlan.label("primary:Download/ব্যাকআপ").equals("Download/ব্যাকআপ"));

        // ---- health text ----
        t("health: off + empty vault = quiet", BackupPlan.healthWarn(false, false, "", now, 0).isEmpty());
        t("health: off + vault = warn", BackupPlan.healthWarn(false, false, "", now, 3).contains("uninstalled"));
        t("health: off + vault unreadable (-1) = quiet", BackupPlan.healthWarn(false, false, "", now, -1).isEmpty());
        t("health: off but had copies = quiet (user choice)", BackupPlan.healthWarn(false, false, "20261101_1000", now, 3).isEmpty());
        t("health: set no access", BackupPlan.healthWarn(true, false, "20261113_0800", now, 3).contains("not reachable"));
        t("health: set no copy yet", BackupPlan.healthWarn(true, true, "", now, 3).contains("no copy"));
        t("health: set fresh = quiet", BackupPlan.healthWarn(true, true, "20261113_0800", now, 3).isEmpty());
        t("health: set 2 days = quiet", BackupPlan.healthWarn(true, true, "20261111_0800", now, 3).isEmpty());
        t("health: set 3 days = warn", BackupPlan.healthWarn(true, true, "20261110_0800", now, 3).contains("3 days"));
        t("health: set 40 days = warn", BackupPlan.healthWarn(true, true, "20261001_0800", now, 3).contains("days"));
        t("health: ok flag fresh", BackupPlan.externalOk(true, true, "20261113_0800", now));
        t("health: ok flag stale", !BackupPlan.externalOk(true, true, "20261001_0800", now));
        t("health: ok flag no access", !BackupPlan.externalOk(true, false, "20261113_0800", now));
        t("health: ok flag off", !BackupPlan.externalOk(false, true, "20261113_0800", now));

        // ---- the learned-words file really travels through the codec ----
        Map<String, String> files = new LinkedHashMap<String, String>();
        String learn = "#JABRA-LEARN 1\n3\tভালো\n2\thello\n";
        files.put("learn_v1.txt", learn);
        files.put("vault_v1.json", "{\"ver\":1}");
        String text = BackupCodec.build(new LinkedHashMap<String, Object>(), files, "0.10.7", 1000L);
        t("codec valid with learn file", BackupCodec.validate(text) == BackupCodec.OK);
        t("codec returns learn text unchanged (tab, newline, Bangla)", learn.equals(BackupCodec.files(text).get("learn_v1.txt")));
        t("codec: every restored name is whitelisted", allAllowed(BackupCodec.files(text).keySet()));
        t("codec: old backup without learn file still valid", BackupCodec.validate(BackupCodec.build(new LinkedHashMap<String, Object>(), Collections.singletonMap("vault_v1.json", "{}"), "0.10.6", 1L)) == BackupCodec.OK);

        // ---- Health Check line (HealthCheck.run) ----
        HealthCheck.Input in = new HealthCheck.Input();
        in.schemaV = com.mgb.keyboard.typing.Migrate.CURRENT; in.backups = 1; in.now = now;
        t("run: default input = no extbackup line (old behaviour)", find(HealthCheck.run(in)) == null);
        in.vaultCount = 4;
        HealthCheck.Item w = find(HealthCheck.run(in));
        t("run: vault + no folder = WARN", w != null && w.level == HealthCheck.WARN);
        in.extSet = true; in.extAccessOk = true; in.extLast = "20261113_0800";
        w = find(HealthCheck.run(in));
        t("run: fresh copy = OK line", w != null && w.level == HealthCheck.OK && w.text.contains("today"));
        in.extAccessOk = false;
        w = find(HealthCheck.run(in));
        t("run: access revoked = WARN, no crash", w != null && w.level == HealthCheck.WARN);

        System.out.println("BackupPlanTest: " + ok + " ok, " + bad + " fail");
        if (bad > 0) System.exit(1);
    }

    static HealthCheck.Item find(List<HealthCheck.Item> l) { for (HealthCheck.Item i : l) if (i.id.equals("extbackup")) return i; return null; }

    static boolean allAllowed(Set<String> s) { for (String x : s) if (!BackupPlan.isAllowedFile(x)) return false; return true; }
}
