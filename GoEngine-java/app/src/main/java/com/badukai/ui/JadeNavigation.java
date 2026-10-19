package com.badukai.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.Toast;

import com.badukai.MainActivity;
import com.badukai.ReplayActivity;
import com.badukai.TsumegoActivity;
import com.badukai.game.TsumegoLessons;

import java.util.List;

/**
 * Shared destinations for the five jade footer tabs.
 * Records and Training always offer real, available choices first; opening
 * their menu does not hide the active board or go through the Home screen.
 */
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
                showRecordChoices(activity);
                break;
            case JadeBottomNavView.TRAINING:
                showTrainingChoices(activity);
                break;
            case JadeBottomNavView.SETTINGS:
                // A dedicated settings page is not implemented yet.
                Toast.makeText(activity, "设置页面开发中", Toast.LENGTH_SHORT).show();
                break;
            default:
                throw new IllegalArgumentException("Unsupported jade tab: " + tab);
        }
    }

    private static void launchWithoutFlash(Activity activity, Intent intent) {
        // Keep the active Game/Replay page visible until the actual destination
        // is ready. Do not route via MainActivity or its Home container.
        activity.startActivity(intent);
        activity.overridePendingTransition(0, 0);
    }

    private static void returnToMain(Activity activity, String target) {
        if (activity instanceof MainActivity) {
            // MainActivity handles its existing direct Home/Game navigation.
            return;
        }
        Intent intent = new Intent(activity, MainActivity.class);
        intent.putExtra(target, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        launchWithoutFlash(activity, intent);
        activity.finish();
        activity.overridePendingTransition(0, 0);
    }

    private static void showRecordChoices(Activity activity) {
        boolean alreadyReplaying = activity instanceof ReplayActivity;
        String[] choices = {
                alreadyReplaying ? "继续当前复盘" : "复盘最近一局",
                "选择历史棋谱",
                "导入 SGF 棋谱"
        };
        new AlertDialog.Builder(activity)
                .setTitle("棋谱 · 选择功能")
                .setItems(choices, (dialog, choice) -> {
                    if (alreadyReplaying) {
                        ReplayActivity replay = (ReplayActivity) activity;
                        if (choice == 1) replay.openHistoryFromNavigation();
                        else if (choice == 2) replay.openImportFromNavigation();
                        // choice 0 keeps the current replay visible.
                    } else {
                        Intent intent = new Intent(activity, ReplayActivity.class);
                        if (choice == 1) intent.putExtra("replay_entry_action", "history");
                        else if (choice == 2) intent.putExtra("replay_entry_action", "import");
                        launchWithoutFlash(activity, intent);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showTrainingChoices(Activity activity) {
        String[] choices = {"继续上次练习", "从第一题开始", "按题目选择"};
        new AlertDialog.Builder(activity)
                .setTitle("训练 · 选择内容")
                .setItems(choices, (dialog, choice) -> {
                    if (choice == 2) {
                        showPuzzleChoices(activity);
                    } else if (activity instanceof TsumegoActivity) {
                        if (choice == 1) ((TsumegoActivity) activity).openPuzzleFromNavigation(0);
                        // choice 0 keeps the current exercise and progress.
                    } else {
                        Intent intent = new Intent(activity, TsumegoActivity.class);
                        if (choice == 1) intent.putExtra("puzzle_index", 0);
                        launchWithoutFlash(activity, intent);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void showPuzzleChoices(Activity activity) {
        List<TsumegoLessons.Puzzle> puzzles = TsumegoLessons.all();
        String[] labels = new String[puzzles.size()];
        for (int i = 0; i < puzzles.size(); i++) {
            labels[i] = (i + 1) + ". " + puzzles.get(i).title;
        }
        new AlertDialog.Builder(activity)
                .setTitle("选择死活题")
                .setItems(labels, (dialog, which) -> {
                    if (activity instanceof TsumegoActivity) {
                        ((TsumegoActivity) activity).openPuzzleFromNavigation(which);
                    } else {
                        Intent intent = new Intent(activity, TsumegoActivity.class);
                        intent.putExtra("puzzle_index", which);
                        launchWithoutFlash(activity, intent);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }
}
