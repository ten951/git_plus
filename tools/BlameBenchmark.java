import com.github.ten951.gitplus.blame.BlameLine;
import com.github.ten951.gitplus.blame.BlameProvider;
import kotlin.Unit;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Standalone process: same heap, input, and timing boundaries for both revisions. */
public final class BlameBenchmark {
    private static long usedHeap() throws InterruptedException {
        System.gc();
        Thread.sleep(200);
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    public static void main(String[] args) throws Exception {
        Path file = Path.of(args[0]);
        Path counter = Path.of(args[2]);
        String content = Files.readString(file);
        BlameProvider provider = new BlameProvider(args[1], message -> { throw new AssertionError(message); });
        long before = usedHeap();
        long start = System.nanoTime();
        Map<Integer, BlameLine> result = provider.query(file, content, StandardCharsets.UTF_8, "\n", null, () -> Unit.INSTANCE);
        double coldMs = (System.nanoTime() - start) / 1e6;
        if (result.size() != 40_000) throw new AssertionError("Incomplete blame: " + result.size());
        long retainedBytes = usedHeap() - before;
        long coldProcesses = Files.size(counter);
        start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            if (provider.query(file, content, StandardCharsets.UTF_8, "\n", null, () -> Unit.INSTANCE).size() != 40_000)
                throw new AssertionError("Incomplete warm blame");
        }
        double warmMs = (System.nanoTime() - start) / 1e6;
        Set<Object> commits = Collections.newSetFromMap(new IdentityHashMap<>());
        result.values().forEach(line -> commits.add(line.getCommit()));
        System.out.printf(Locale.ROOT,
            "{\"coldMs\":%.3f,\"warm20Ms\":%.3f,\"retainedBytes\":%d,\"commitObjects\":%d,\"coldProcesses\":%d,\"warm20Processes\":%d}%n",
            coldMs, warmMs, retainedBytes, commits.size(), coldProcesses, Files.size(counter) - coldProcesses);
    }
}
