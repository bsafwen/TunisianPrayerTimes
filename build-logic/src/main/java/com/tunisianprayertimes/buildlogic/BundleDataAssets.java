package com.tunisianprayertimes.buildlogic;

import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileSystemOperations;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

/**
 * Packages canonical JSON from the repository's data/ folder as assets, without
 * maintaining hand-copied duplicates in app/src/main/assets.
 */
public abstract class BundleDataAssets extends DefaultTask {

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getSource();

    @Input
    public abstract Property<String> getAssetFolder();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDirectory();

    @Inject
    protected abstract FileSystemOperations getFileSystem();

    @TaskAction
    public void bundle() {
        getFileSystem().sync(spec -> {
            spec.from(getSource(), from -> {
                from.include("*.json");
                from.into(getAssetFolder().get());
            });
            spec.into(getOutputDirectory());
        });
    }
}
