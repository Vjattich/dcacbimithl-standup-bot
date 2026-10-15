import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.spi.ToolProvider;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds a self-contained exe (jpackage app-image) and a zip ready for GitHub Releases.
 *
 * Output:
 *   build/dist/<NAME>/<NAME>.exe   runnable folder
 *   build/<NAME>-<version>.zip     upload this to Releases
 */
public class Build {
    static final String NAME = Path.of("").toAbsolutePath().getFileName().toString();
    static final String MAIN_CLASS = "Main";
    static final boolean CONSOLE = true;

    static final Path SRC = Path.of("src");
    static final Path LIB = Path.of("lib");
    static final Path BUILD = Path.of("build");

    public static void main(String[] args) throws Exception {
        String version = args.length > 0 ? args[0] : "1.0";
        Path classes = BUILD.resolve("classes");
        Path input = BUILD.resolve("input");
        Path dist = BUILD.resolve("dist");

        deleteRecursively(BUILD);
        Files.createDirectories(classes);
        Files.createDirectories(input);

        List<Path> jars = list(LIB, ".jar");
        List<Path> sources = list(SRC, ".java");
        if (sources.isEmpty()) fail("No .java files found in " + SRC.toAbsolutePath());

        step("Compiling " + sources.size() + " source file(s)");
        List<String> javac = new ArrayList<>(List.of("-d", classes.toString(), "-encoding", "UTF-8"));
        if (!jars.isEmpty()) {
            javac.add("-cp");
            javac.add(jars.stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator)));
        }
        sources.forEach(s -> javac.add(s.toString()));
        run("javac", javac);

        step("Packaging app.jar");
        run("jar", List.of("--create", "--file", input.resolve("app.jar").toString(),
                "--main-class", MAIN_CLASS, "-C", classes.toString(), "."));
        for (Path jar : jars) Files.copy(jar, input.resolve(jar.getFileName()));

        step("Finding required JDK modules");
        List<String> jdeps = new ArrayList<>(List.of("--print-module-deps", "--ignore-missing-deps", "--multi-release", "base"));
        if (!jars.isEmpty()) {
            jdeps.add("--class-path");
            jdeps.add(jars.stream().map(j -> input.resolve(j.getFileName()).toString())
                    .collect(Collectors.joining(File.pathSeparator)));
        }
        jdeps.add(input.resolve("app.jar").toString());
        jars.forEach(j -> jdeps.add(input.resolve(j.getFileName()).toString()));
        String modules = capture("jdeps", jdeps).trim();
        System.out.println("    " + modules);

        step("Running jpackage (bundles a trimmed Java runtime, takes a moment)");
        List<String> jpackage = new ArrayList<>(List.of(
                "--type", "app-image",
                "--input", input.toString(),
                "--main-jar", "app.jar",
                "--main-class", MAIN_CLASS,
                "--name", NAME,
                "--app-version", version,
                "--add-modules", modules,
                "--jlink-options", "--strip-debug --no-header-files --no-man-pages --strip-native-commands",
                "--dest", dist.toString()
        ));
        if (CONSOLE && isWindows()) jpackage.add("--win-console");
        run("jpackage", jpackage);

        step("Zipping");
        Path zip = BUILD.resolve(NAME + "-" + version + ".zip");
        zipDirectory(dist.resolve(NAME), zip);

        System.out.println();
        System.out.println("Done.");
        System.out.println("  App: " + dist.resolve(NAME).toAbsolutePath());
        System.out.println("  Zip: " + zip.toAbsolutePath());
    }

    static void run(String toolName, List<String> args) {
        ToolProvider tool = ToolProvider.findFirst(toolName)
                .orElseThrow(() -> new IllegalStateException("'%s' not found. Run this with a full JDK 16+, not a JRE.".formatted(toolName)));
        int code = tool.run(System.out, System.err, args.toArray(String[]::new));
        if (code != 0) fail(toolName + " failed with exit code " + code);
    }

    static String capture(String toolName, List<String> args) {
        ToolProvider tool = ToolProvider.findFirst(toolName)
                .orElseThrow(() -> new IllegalStateException("'%s' not found. Run this with a full JDK 16+, not a JRE.".formatted(toolName)));
        StringWriter out = new StringWriter();
        int code = tool.run(new PrintWriter(out, true), new PrintWriter(System.err, true), args.toArray(String[]::new));
        if (code != 0) fail("%s failed with exit code %d\n%s".formatted(toolName, code, out));
        return out.toString();
    }

    static List<Path> list(Path dir, String extension) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(extension))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).collect(Collectors.toList())) {
                Files.delete(p);
            }
        }
    }

    static void zipDirectory(Path dir, Path zip) throws IOException {
        Path base = dir.getParent();
        try (OutputStream out = Files.newOutputStream(zip);
             ZipOutputStream zos = new ZipOutputStream(out);
             Stream<Path> files = Files.walk(dir)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().collect(Collectors.toList())) {
                String entry = base.relativize(file).toString().replace(File.separatorChar, '/');
                zos.putNextEntry(new ZipEntry(entry));
                Files.copy(file, zos);
                zos.closeEntry();
            }
        }
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    static void step(String message) {
        System.out.println("==> " + message);
    }

    static void fail(String message) {
        System.err.println("ERROR: " + message);
        System.exit(1);
    }
}