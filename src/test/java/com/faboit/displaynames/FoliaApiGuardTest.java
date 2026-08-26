package com.faboit.displaynames;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fails the build on API that a region-threaded server refuses at runtime.
 *
 * <p>These calls compile against Paper and work on Paper, so nothing catches them before a
 * server does - and when one does, it is by throwing on the hot path, once per player per tick.
 * Shipping {@code Entity#teleport} in the follow loop cost a release exactly that way, so the
 * ban is enforced here rather than remembered.
 */
class FoliaApiGuardTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** Each entry is a banned call, why it is banned, and what to write instead. */
    private record Ban(Pattern pattern, String what, String instead) {
    }

    private static final List<Ban> BANS = List.of(
            new Ban(Pattern.compile("\\.teleport\\s*\\("), "the synchronous Entity#teleport",
                    "teleportAsync, which is the only form region threading allows"),
            new Ban(Pattern.compile("Bukkit\\s*\\.\\s*getScheduler\\s*\\(|getServer\\s*\\(\\s*\\)\\s*\\.\\s*getScheduler\\s*\\("),
                    "the legacy BukkitScheduler",
                    "the entity, region or global scheduler for the thread that owns the work"),
            new Ban(Pattern.compile("\\bnew\\s+BukkitRunnable\\b"), "BukkitRunnable",
                    "a Runnable handed to one of the region schedulers"));

    @Test
    void noSourceFileCallsApiThatRegionThreadingRefuses() throws IOException {
        List<String> offences = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(FoliaApiGuardTest::isJava).toList()) {
                scan(file, offences);
            }
        }
        assertTrue(offences.isEmpty(), () -> "Folia-illegal API in main sources:\n  "
                + String.join("\n  ", offences));
    }

    private static boolean isJava(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }

    private static void scan(Path file, List<String> offences) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            String line = stripped(lines.get(i));
            if (line.isEmpty()) continue;
            for (Ban ban : BANS) {
                if (!ban.pattern().matcher(line).find()) continue;
                offences.add(file + ":" + (i + 1) + " uses " + ban.what()
                        + " - use " + ban.instead() + ".");
            }
        }
    }

    /**
     * Drops comments and string literals so prose about a banned call is not itself an offence.
     *
     * <p>Deliberately crude - it only has to be right about whether a line still contains real
     * code, and the javadoc above is the reason it has to be right at all.
     */
    private static String stripped(String source) {
        String line = source.trim();
        if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) return "";
        int comment = line.indexOf("//");
        if (comment >= 0) line = line.substring(0, comment);
        return line.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
    }

    @Test
    void theGuardWouldActuallyCatchTheCallItIsHereFor() {
        // A guard that quietly stops matching is worse than none, so prove it still bites.
        String offending = "current.teleport(anchorLocation(options, false));";
        assertTrue(BANS.get(0).pattern().matcher(stripped(offending)).find());
        assertFalse(BANS.get(0).pattern().matcher(stripped("entity.teleportAsync(where);")).find(),
                "teleportAsync is the fix, not the offence");
        assertFalse(BANS.get(0).pattern().matcher(stripped("e.setTeleportDuration(2);")).find(),
                "setTeleportDuration is a display setting, not a teleport");
        assertTrue(stripped("// never call .teleport( here").isEmpty(),
                "a comment naming the call must not fail the build");
    }

    @Test
    void theSourceTreeIsActuallyBeingRead() {
        // Guards that silently scan nothing pass forever; anchor this one to a real path.
        assertTrue(Files.isDirectory(SOURCES),
                "expected to run from the module root, but " + SOURCES.toAbsolutePath()
                        + " is not a directory");
    }
}
