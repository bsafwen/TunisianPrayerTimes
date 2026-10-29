package com.tunisianprayertimes.stage0.dnd

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** Valid system Settings destination for the isolated stage-0 rules; opening it changes nothing. */
class DndRuleConfigurationActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Stage 0 DND feasibility rule\n\n" +
                "This temporary rule belongs to the isolated feasibility probe. " +
                "The probe removes only the rules it created. Opening this screen does not activate it."
            setPadding(32, 48, 32, 32)
        })
    }
}
