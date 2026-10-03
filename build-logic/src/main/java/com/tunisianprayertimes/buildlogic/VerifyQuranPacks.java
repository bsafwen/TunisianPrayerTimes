package com.tunisianprayertimes.buildlogic;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** Before a release bundle: every pack staged with the right files, and no media left in the base module. */
public abstract class VerifyQuranPacks extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getManifest();

    /** android-app/quran-packs; read in full by the action, so not tracked as an input. */
    @Internal
    public abstract DirectoryProperty getPacksDirectory();

    /** The base module's assets/quran folder. */
    @Internal
    public abstract DirectoryProperty getBaseAssets();

    @TaskAction
    public void verify() {
        List<String> problems = new ArrayList<>();
        File packs = getPacksDirectory().get().getAsFile();
        for (QuranAssetsPlugin.QuranManifestRow row : QuranAssetsPlugin.QuranManifestRow.read(getManifest().get().getAsFile())) {
            File file = new File(packs, row.pack() + "/src/main/assets/" + row.path());
            if (!file.isFile()) problems.add("missing " + row.pack() + ": " + row.path());
            else if (file.length() != row.bytes()) problems.add("wrong size " + row.pack() + ": " + row.path());
        }
        Path base = getBaseAssets().get().getAsFile().toPath();
        if (Files.isDirectory(base)) {
            try (Stream<Path> files = Files.walk(base)) {
                files.filter(Files::isRegularFile).map(Path::toString).filter(VerifyQuranPacks::isMedia)
                    .forEach(file -> problems.add("media in the base module: " + file));
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
        if (!problems.isEmpty()) {
            int shown = Math.min(problems.size(), 10);
            throw new GradleException("Quran packs are not ready (" + problems.size() + " problems):\n  "
                + String.join("\n  ", problems.subList(0, shown))
                + (problems.size() > shown ? "\n  …" : "")
                + "\nStage them with: python3 scripts/quran_assets.py stage"
                + "\nand move any recitations or page scans out of app/src/main/assets/quran.");
        }
    }

    static boolean isMedia(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".mp3") || lower.endsWith(".webp") || lower.endsWith(".png") || lower.endsWith(".jpg");
    }
}
