package com.tunisianprayertimes.buildlogic;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/** After a release bundle: the Quran media is in its packs, at full size, and none of it in the base module. */
public abstract class CheckQuranBundle extends DefaultTask {

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getBundle();

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract RegularFileProperty getManifest();

    @TaskAction
    public void check() {
        List<String> problems = new ArrayList<>();
        try (ZipFile bundle = new ZipFile(getBundle().get().getAsFile())) {
            bundle.stream().map(ZipEntry::getName)
                .filter(name -> name.startsWith("base/assets/quran/") && VerifyQuranPacks.isMedia(name))
                .forEach(name -> problems.add("media in the base module: " + name));
            for (QuranAssetsPlugin.QuranManifestRow row : QuranAssetsPlugin.QuranManifestRow.read(getManifest().get().getAsFile())) {
                ZipEntry entry = bundle.getEntry(row.pack() + "/assets/" + row.path());
                if (entry == null) problems.add("missing from " + row.pack() + ": " + row.path());
                else if (entry.getSize() != row.bytes()) problems.add("wrong size in " + row.pack() + ": " + row.path());
            }
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
        if (!problems.isEmpty()) {
            int shown = Math.min(problems.size(), 10);
            throw new GradleException("The bundle's Quran packs are wrong (" + problems.size() + " problems):\n  "
                + String.join("\n  ", problems.subList(0, shown)) + (problems.size() > shown ? "\n  …" : ""));
        }
    }
}
