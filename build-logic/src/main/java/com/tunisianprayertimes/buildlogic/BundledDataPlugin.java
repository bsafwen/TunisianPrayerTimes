package com.tunisianprayertimes.buildlogic;

import com.android.build.api.variant.AndroidComponentsExtension;
import com.android.build.api.variant.SourceDirectories;
import java.util.List;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.tasks.TaskProvider;

/**
 * Bundles the repository's offline data into an Android app's assets:
 * <ul>
 *   <li>prayer-formula: INM's coordinates and elevations, from which prayer times are computed;</li>
 *   <li>official-islamic-dates: announced Ramadan and Eid dates, for offline calendars.</li>
 * </ul>
 * Apply it in an app module after the Android application plugin.
 */
public class BundledDataPlugin implements Plugin<Project> {

    private static final List<String> FOLDERS = List.of("prayer-formula", "official-islamic-dates");

    @Override
    public void apply(Project project) {
        project.getPluginManager().withPlugin("com.android.application", applied -> {
            List<TaskProvider<BundleDataAssets>> tasks = FOLDERS.stream().map(folder -> register(project, folder)).toList();
            AndroidComponentsExtension<?, ?, ?> components = project.getExtensions().getByType(AndroidComponentsExtension.class);
            components.onVariants(components.selector().all(), variant -> {
                SourceDirectories.Layered assets = variant.getSources().getAssets();
                if (assets == null) return;
                for (TaskProvider<BundleDataAssets> task : tasks) {
                    assets.addGeneratedSourceDirectory(task, BundleDataAssets::getOutputDirectory);
                }
            });
        });
    }

    private static TaskProvider<BundleDataAssets> register(Project project, String folder) {
        String name = "bundle" + camel(folder);
        return project.getTasks().register(name, BundleDataAssets.class, task -> {
            task.getSource().set(project.getRootProject().getLayout().getProjectDirectory().dir("../data/" + folder));
            task.getAssetFolder().set(folder);
            task.getOutputDirectory().set(project.getLayout().getBuildDirectory().dir("generated/" + name + "Assets"));
        });
    }

    private static String camel(String folder) {
        StringBuilder out = new StringBuilder();
        for (String part : folder.split("-")) {
            out.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return out.toString();
    }
}
