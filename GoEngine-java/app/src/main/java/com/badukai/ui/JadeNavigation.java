package com.badukai.ui;

import android.app.Activity;
import android.content.Intent;
import android.widget.Toast;

import com.badukai.MainActivity;
import com.badukai.ReplayActivity;
import com.badukai.TsumegoActivity;

/** Shared cross-activity destinations for the five-button jade footer. */
public final class JadeNavigation {
    private JadeNavigation() {}

    public static void open(Activity activity, int tab) {
        switch (tab) {
            case JadeBottomNavView.HOME:
                returnToMain(activity, "navigate_home");
                break;
            case JadeBottomNavView.PLAY:
                returnToMain(activity, "navigate_play");
                break;
            case JadeBottomNavView.RECORDS:
                if (!(activity instanceof ReplayActivity))
                    activity.startActivity(new Intent(activity, ReplayActivity.class));
                break;
            case JadeBottomNavView.TRAINING:
                if (!(activity instanceof TsumegoActivity))
                    activity.startActivity(new Intent(activity, TsumegoActivity.class));
                break;
            case JadeBottomNavView.SETTINGS:
                // A dedicated app settings page has not been implemented yet.
                Toast.makeText(activity, "设置页面开发中", Toast.LENGTH_SHORT).show();
                break;
            default:
                throw new IllegalArgumentException("Unsupported jade tab: " + tab);
        }
    }

    private static void returnToMain(Activity activity, String target) {
        Intent intent = new Intent(activity, MainActivity.class);
        intent.putExtra(target, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activity.startActivity(intent);
        if (!(activity instanceof MainActivity)) activity.finish();
    }
}
