package org.lilyscan.engine.audiveris;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.lilyscan.core.omr.OmrBookJson;
import org.lilyscan.core.omr.OmrException;

/**
 * The forked engine worker (D16): a child JVM on Lilyscan's own classpath, which runs Audiveris
 * in-process and exits, so a crash, a leak or Audiveris's global state never reaches the parent.
 *
 * <ul>
 * <li>{@code audiveris <args...>}: Audiveris's own batch CLI ({@code org.audiveris.omr.Main})
 * with the arguments the Python runner gave the Audiveris launcher; the exit status is
 * Audiveris's.
 * <li>{@code read-omr <book.omr> <book.json>}: the {@code .omr} project read through Audiveris's
 * model ({@link OmrReader}), written as {@link OmrBookJson}; exit 2 if it is not readable.
 * </ul>
 */
public final class EngineWorker {
    private EngineWorker() {}

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("usage: EngineWorker audiveris <args...> | read-omr <book.omr> <out.json>");
            System.exit(64);
        }
        Engine.init();
        switch (args[0]) {
            case "audiveris" -> {
                org.audiveris.omr.Main.main(Arrays.copyOfRange(args, 1, args.length));
                // Audiveris exits by itself on failure or timeout; a normal end returns here.
                System.exit(0);
            }
            case "read-omr" -> {
                if (args.length != 3) {
                    System.err.println("usage: EngineWorker read-omr <book.omr> <out.json>");
                    System.exit(64);
                }
                try {
                    String json = OmrBookJson.write(OmrReader.read(Path.of(args[1])));
                    Files.writeString(Path.of(args[2]), json, StandardCharsets.UTF_8);
                } catch (OmrException e) {
                    System.err.println("OmrError: " + e.getMessage());
                    System.exit(2);
                }
                System.exit(0);
            }
            default -> {
                System.err.println("unknown engine worker operation: " + args[0]);
                System.exit(64);
            }
        }
    }
}
