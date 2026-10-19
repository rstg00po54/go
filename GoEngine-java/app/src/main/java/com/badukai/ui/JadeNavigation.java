package com.badukai.ui;

import android.app.Activity;
import android.content.Intent;

import com.badukai.JadeSectionActivity;
import com.badukai.MainActivity;

/** Cross-page navigation; Records, Training and Settings are proper pages. */
public final class JadeNavigation {
    private JadeNavigation() {}

    public static void open(Activity activity, int tab) {
        switch (tab) {
            case JadeBottomNavView.HOME:
                returnToMain(activity, "navigate_home");
                return;
            case JadeBottomNavView.PLAY:
                returnToMain(activity, "navigate_play");
                return;
            case JadeBottomNavView.RECORDS:
            case JadeBottomNavView.TRAINING:
            case JadeBottomNavView.SETTINGS:
                if (activity instanceof JadeSectionActivity) {
                    ((JadeSectionActivity) activity).showSection(tab);
                    return;
                }
                Intent section = new Intent(activity, JadeSectionActivity.class);
                section.putExtra(JadeSectionActivity.EXTRA_SECTION, tab);
                launchWithoutFlash(activity, section);
                return;
            default:
                throw new IllegalArgumentException("Unsupported jade tab: " + tab);
        }
    }

    public static void openNewGameSettings(Activity activity) {
        Intent intent = new Intent(activity, MainActivity.class);
        intent.putExtra("navigate_new_game", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launchWithoutFlash(activity, intent);
    }

    private static void returnToMain(Activity activity, String target) {
        if (activity instanceof MainActivity) return;
        Intent intent = new Intent(activity, MainActivity.class);
        intent.putExtra(target, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launchWithoutFlash(activity, intent);
        activity.finish();
        activity.overridePendingTransition(0, 0);
    }

    private static void launchWithoutFlash(Activity activity, Intent intent) {
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }
}
