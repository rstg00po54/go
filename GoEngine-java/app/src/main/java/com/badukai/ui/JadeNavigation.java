package com.badukai.ui;

import android.app.Activity;
import android.content.Intent;

import com.badukai.JadeSectionActivity;
import com.badukai.MainActivity;
import com.badukai.NewGameActivity;
import com.badukai.SettingsActivity;

/** Cross-page navigation; Records, Training and Settings are proper pages. */
public final class JadeNavigation {
    private JadeNavigation() {}

    public static void open(Activity activity, int tab) {
        switch (tab) {
            case JadeBottomNavView.HOME:
                returnToMain(activity, "navigate_home");
                return;
            case JadeBottomNavView.PLAY:
                if (activity instanceof NewGameActivity) return;
                launchWithoutFlash(activity, new Intent(activity, NewGameActivity.class));
                if (activity instanceof JadeSectionActivity
                        || activity instanceof SettingsActivity) {
                    activity.finish();
                    activity.overridePendingTransition(0, 0);
                }
                return;
            case JadeBottomNavView.SETTINGS:
                if (activity instanceof SettingsActivity) return;
                launchWithoutFlash(activity, new Intent(activity, SettingsActivity.class));
                if (activity instanceof JadeSectionActivity
                        || activity instanceof NewGameActivity) {
                    activity.finish();
                    activity.overridePendingTransition(0, 0);
                }
                return;
            case JadeBottomNavView.RECORDS:
            case JadeBottomNavView.TRAINING:
                if (activity instanceof JadeSectionActivity) {
                    ((JadeSectionActivity) activity).showSection(tab);
                    return;
                }
                Intent section = new Intent(activity, JadeSectionActivity.class);
                section.putExtra(JadeSectionActivity.EXTRA_SECTION, tab);
                launchWithoutFlash(activity, section);
                if (activity instanceof NewGameActivity
                        || activity instanceof SettingsActivity) {
                    activity.finish();
                    activity.overridePendingTransition(0, 0);
                }
                return;
            default:
                throw new IllegalArgumentException("Unsupported jade tab: " + tab);
        }
    }

    public static void openNewGameSettings(Activity activity) {
        launchWithoutFlash(activity, new Intent(activity, NewGameActivity.class));
        if (activity instanceof JadeSectionActivity) {
            activity.finish();
            activity.overridePendingTransition(0, 0);
        }
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
