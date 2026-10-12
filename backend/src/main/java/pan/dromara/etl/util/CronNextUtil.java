package pan.dromara.etl.util;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.IntPredicate;

/**
 * 7-field cron next-run-time calculator.
 * <p>
 * Fields: second minute hour day month week year (7 fields, 6-field cron
 * compatible, missing year defaults to '*'). Each field supports: * ? number
 * comma-list range(a-b) step-with-slash(star/n, a-b/n). Semantics
 * (Quartz-like): when both day and week are concrete values, OR rule applies
 * (either matches passes); week 1=Sunday, 2=Monday ... 7=Saturday.
 */
public final class CronNextUtil {

    private static final long MAX_WINDOW_SECONDS = 366L * 24 * 3600;

    private CronNextUtil() {
    }

    /** Compute next run time; null if invalid or not found. */
    public static Date next(String cron) {
        try {
            String[] parts = cron.trim().split("\\s+");
            if (parts.length < 6 || parts.length > 7) {
                return null;
            }
            Field[] fields = new Field[7];
            int[][] ranges = {{0, 59}, {0, 59}, {0, 23}, {1, 31}, {1, 12}, {1, 7}, {1970, 2199}};
            for (int i = 0; i < parts.length; i++) {
                fields[i] = Field.parse(parts[i], ranges[i][0], ranges[i][1], i);
                if (fields[i] == null) {
                    return null;
                }
            }
            if (parts.length == 6) {
                fields[6] = Field.any();
            }
            LocalDateTime cursor = LocalDateTime.now().plusSeconds(1).withNano(0);
            LocalDateTime deadline = cursor.plusSeconds(MAX_WINDOW_SECONDS);
            while (cursor.isBefore(deadline)) {
                if (matches(cursor, fields)) {
                    return Date.from(cursor.atZone(ZoneId.systemDefault()).toInstant());
                }
                cursor = cursor.plusSeconds(1);
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean matches(LocalDateTime t, Field[] f) {
        if (!f[0].test(t.getSecond())) return false;
        if (!f[1].test(t.getMinute())) return false;
        if (!f[2].test(t.getHour())) return false;
        boolean dayOk = f[3].test(t.getDayOfMonth());
        boolean weekOk = f[5].test(toWeekNum(t));
        if (!f[3].any && !f[5].any) {
            // day and week both concrete: OR rule
            if (!dayOk && !weekOk) return false;
        } else {
            if (!f[3].any && !dayOk) return false;
            if (!f[5].any && !weekOk) return false;
        }
        if (!f[4].test(t.getMonthValue())) return false;
        if (!f[6].test(t.getYear())) return false;
        return true;
    }

    /** week field: 1=Sunday ... 7=Saturday */
    private static int toWeekNum(LocalDateTime t) {
        int d = t.getDayOfWeek().getValue(); // 1=Monday ... 7=Sunday
        return d == 7 ? 1 : d + 1;
    }

    /** single field; any=true means * or ? (match anything) */
    private static final class Field {
        boolean any;
        final List<IntPredicate> preds = new ArrayList<>();

        static Field any() {
            Field f = new Field();
            f.any = true;
            return f;
        }

        boolean test(int v) {
            if (any) {
                return true;
            }
            for (IntPredicate p : preds) {
                if (p.test(v)) {
                    return true;
                }
            }
            return false;
        }

        /** parse one field; null if invalid. fieldIdx 4=month(JAN-DEC), 5=week(SUN-SAT) */
        static Field parse(String expr, int min, int max, int fieldIdx) {
            if (expr == null) {
                return null;
            }
            String v = expr.trim();
            if (v.isEmpty()) {
                return null;
            }
            if ("*".equals(v) || "?".equals(v)) {
                return any();
            }
            Field f = new Field();
            for (String s : v.split(",")) {
                IntPredicate p = parseSingle(s.trim(), min, max, fieldIdx);
                if (p == null) {
                    return null;
                }
                f.preds.add(p);
            }
            return f;
        }

        private static IntPredicate parseSingle(String v, int min, int max, int fieldIdx) {
            String upper = v.toUpperCase();
            // week abbreviations SUN=1 MON=2 ... SAT=7; month JAN=1 ... DEC=12
            if (fieldIdx == 5) {
                String[] names = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(upper)) {
                        final int val = i + 1;
                        return x -> x == val;
                    }
                }
            }
            if (fieldIdx == 4) {
                String[] names = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(upper)) {
                        final int val = i + 1;
                        return x -> x == val;
                    }
                }
            }
            int slash = v.indexOf('/');
            if (slash >= 0) {
                String base = v.substring(0, slash).trim();
                String stepStr = v.substring(slash + 1).trim();
                int step;
                try {
                    step = Integer.parseInt(stepStr);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (step <= 0) {
                    return null;
                }
                if (base.isEmpty() || "*".equals(base) || "?".equals(base)) {
                    return x -> x >= min && x <= max && (x - min) % step == 0;
                }
                int dash = base.indexOf('-');
                if (dash >= 0) {
                    Integer lo = toNum(base.substring(0, dash).trim(), fieldIdx);
                    Integer hi = toNum(base.substring(dash + 1).trim(), fieldIdx);
                    if (lo == null || hi == null || lo > hi) {
                        return null;
                    }
                    final int fl = lo, fh = hi;
                    return x -> x >= fl && x <= fh && (x - fl) % step == 0;
                }
                try {
                    int start = Integer.parseInt(base);
                    return x -> x >= start && x <= max && (x - start) % step == 0;
                } catch (NumberFormatException e) {
                    return null;
                }
            }
            int dash = v.indexOf('-');
            if (dash >= 0) {
                Integer lo = toNum(v.substring(0, dash).trim(), fieldIdx);
                Integer hi = toNum(v.substring(dash + 1).trim(), fieldIdx);
                if (lo == null || hi == null || lo > hi) {
                    return null;
                }
                final int fl = lo, fh = hi;
                return x -> x >= fl && x <= fh;
            }
            try {
                int num = Integer.parseInt(v);
                if (num < min || num > max) {
                    return null;
                }
                return x -> x == num;
            } catch (NumberFormatException e) {
                return null;
            }
        }

        /** 把字段值(数字或缩写)转成数值; 失败返回 null */
        private static Integer toNum(String s, int fieldIdx) {
            String u = s.toUpperCase();
            if (fieldIdx == 5) {
                String[] names = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(u)) {
                        return i + 1;
                    }
                }
            }
            if (fieldIdx == 4) {
                String[] names = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
                for (int i = 0; i < names.length; i++) {
                    if (names[i].equals(u)) {
                        return i + 1;
                    }
                }
            }
            try {
                return Integer.parseInt(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
