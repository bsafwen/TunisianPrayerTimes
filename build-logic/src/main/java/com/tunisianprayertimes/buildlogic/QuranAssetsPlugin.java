package com.tunisianprayertimes.buildlogic;

import com.android.build.api.artifact.SingleArtifact;
import com.android.build.api.dsl.ApplicationExtension;
import com.android.build.api.variant.AndroidComponentsExtension;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.file.RegularFile;
import org.gradle.api.tasks.TaskProvider;

/**
 * Ships the Quran's large media as Play Asset Delivery packs instead of base-module assets.
 * The packs come from android-app/quran-assets/manifest.tsv (scripts/quran_assets.py layout):
 * <ul>
 *   <li>the app declares every Play pack scripts/quran_assets.py stage has filled;</li>
 *   <li>{@code verifyQuranPacks} stops bundleRelease when a pack's files were not staged, or when
 *       media still sits in the base module's assets;</li>
 *   <li>{@code check<Variant>QuranBundle} reads the finished bundle and fails if any media reached
 *       the base module or any listed file is missing from its pack.</li>
 * </ul>
 * Without a manifest nothing changes: the build bundles whatever its assets hold.
 */
public class QuranAssetsPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().withPlugin("com.android.application", applied -> {
            Directory root = project.getRootProject().getLayout().getProjectDirectory();
            RegularFile manifest = root.file("quran-assets/manifest.tsv");
            List<String> packs = packs(manifest.getAsFile());
            if (packs.isEmpty()) return;

            // Packs not staged yet stay out of the bundle: debug builds download them from the CDN, and
            // verifyQuranPacks stops a release bundle until every pack is staged.
            ApplicationExtension android = project.getExtensions().getByType(ApplicationExtension.class);
            for (String pack : packs) {
                if (root.file("quran-packs/" + pack + "/src/.staged").getAsFile().isFile()) {
                    android.getAssetPacks().add(":" + pack);
                }
            }

            TaskProvider<VerifyQuranPacks> verify = project.getTasks().register("verifyQuranPacks", VerifyQuranPacks.class, task -> {
                task.setGroup("verification");
                task.setDescription("Checks that every Quran pack is staged and the base module carries no Quran media.");
                task.getManifest().set(manifest);
                task.getPacksDirectory().set(root.dir("quran-packs"));
                task.getBaseAssets().set(project.getLayout().getProjectDirectory().dir("src/main/assets/quran"));
            });

            AndroidComponentsExtension<?, ?, ?> components = project.getExtensions().getByType(AndroidComponentsExtension.class);
            components.onVariants(components.selector().withBuildType("release"), variant -> {
                String name = "check" + capitalize(variant.getName()) + "QuranBundle";
                project.getTasks().register(name, CheckQuranBundle.class, task -> {
                    task.setGroup("verification");
                    task.setDescription("Checks where the Quran media landed in the " + variant.getName() + " bundle.");
                    task.getBundle().set(variant.getArtifacts().get(SingleArtifact.BUNDLE.INSTANCE));
                    task.getManifest().set(manifest);
                });
            });
            project.getTasks().configureEach(task -> {
                String name = task.getName();
                if (name.startsWith("bundle") && name.endsWith("Release")) {
                    task.dependsOn(verify);
                    task.finalizedBy("check" + name.substring("bundle".length()) + "QuranBundle");
                }
            });
        });
    }

    /** Pack names in the order the manifest first lists them. */
    static List<String> packs(File manifest) {
        if (!manifest.isFile()) return List.of();
        Set<String> names = new LinkedHashSet<>();
        for (QuranManifestRow row : QuranManifestRow.read(manifest)) names.add(row.pack());
        return new ArrayList<>(names);
    }

    private static String capitalize(String value) {
        return value.isEmpty() ? value : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    /** One line of manifest.tsv for a pack Google Play delivers: a file, its pack and its size. */
    record QuranManifestRow(String pack, String path, long bytes) {
        /** The rows of Play packs; packs only the quran-cdn Worker serves ("cdn" delivery) never enter the bundle. */
        static List<QuranManifestRow> read(File manifest) {
            try {
                List<QuranManifestRow> rows = new ArrayList<>();
                for (String line : Files.readAllLines(manifest.toPath())) {
                    if (line.isBlank() || line.startsWith("#") || line.startsWith("pack\t")) continue;
                    String[] columns = line.split("\t");
                    if (columns.length < 3) throw new IllegalStateException("Malformed line in " + manifest + ": " + line);
                    if (columns.length > 5 && columns[5].equals("cdn")) continue;
                    rows.add(new QuranManifestRow(columns[0], columns[1], Long.parseLong(columns[2])));
                }
                return rows;
            } catch (IOException error) {
                throw new UncheckedIOException(error);
            }
        }
    }
}
