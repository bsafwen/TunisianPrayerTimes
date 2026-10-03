package com.tunisianprayertimes.buildlogic;

import com.android.build.api.dsl.AssetPackExtension;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * One Play Asset Delivery pack of Quran media (android-app/quran-packs/&lt;pack&gt;), named after its
 * module and downloaded on demand. scripts/quran_assets.py stage puts its files in src/main/assets.
 */
public class QuranAssetPackPlugin implements Plugin<Project> {
    @Override
    public void apply(Project project) {
        project.getPluginManager().apply("com.android.asset-pack");
        AssetPackExtension pack = project.getExtensions().getByType(AssetPackExtension.class);
        pack.getPackName().set(project.getName());
        pack.getDynamicDelivery().getDeliveryType().set("on-demand");
    }
}
