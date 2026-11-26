package cdc;

import java.sql.SQLException;
import java.util.Set;

/**
 * Write-through (GridGain to Sybase) CLI.   writethrough.sh run | once | status | verify
 * Connection settings from the environment: ASE_URL, ASE_USER, ASE_PASSWORD, GG_URL, WT_POLL_MS, WT_ALLOW_MASS_DELETE.
 */
public final class WriteThroughMain {
    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v;
    }

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "help";
        if (!Set.of("run", "once", "status", "verify").contains(cmd)) {
            System.err.println("usage: writethrough.sh run | once | status | verify");
            System.exit(2);
        }
        long intervalMs = Long.parseLong(env("WT_POLL_MS", "2000"));
        try (WriteThrough wt = new WriteThrough(
                env("ASE_URL", "jdbc:jtds:sybase://llama.local:5000/testdb"), env("ASE_USER", "tester"),
                env("ASE_PASSWORD", "guest1234"),
                env("GG_URL", "jdbc:ignite:thin://127.0.0.1:11800,127.0.0.1:11801,127.0.0.1:11802"),
                Boolean.parseBoolean(env("WT_ALLOW_MASS_DELETE", "false")))) {
            switch (cmd) {
                case "status" -> wt.status();
                case "verify" -> System.exit(wt.verify() ? 0 : 1);
                case "once" -> print(wt.sweep());
                default -> stream(wt, intervalMs);
            }
        }
    }

    private static void print(WriteThrough.Stats s) {
        System.out.printf("inserted=%d updated=%d deleted=%d unchanged=%d failed=%d backed-off=%d blocked=%d%n",
            s.inserted(), s.updated(), s.deleted(), s.unchanged(), s.failed(), s.backedOff(), s.blocked());
    }

    private static void stream(WriteThrough wt, long intervalMs) throws Exception {
        System.out.println("GridGain -> Sybase write-through (sweep every " + intervalMs + " ms); Ctrl-C to stop");
        while (true) {
            try {
                WriteThrough.Stats s = wt.sweep();
                if (s.applied() > 0 || s.failed() > 0 || s.blocked() > 0) {
                    System.out.printf("%tT ", new java.util.Date());
                    print(s);
                }
                Thread.sleep(intervalMs);
            } catch (SQLException e) {
                System.err.println("error: " + e.getMessage() + " - nothing is lost (the difference stays queued in GridGain); reconnecting in 5s");
                Thread.sleep(5000);
                try { wt.connect(); } catch (SQLException ce) { System.err.println("reconnect failed: " + ce.getMessage()); }
            }
        }
    }
}
