package com.badukai;

import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.badukai.engine.DeviceHardwareInfo;
import com.badukai.engine.KataGoEngine;
import com.badukai.engine.NpuHardware;
import com.badukai.ui.JadeBottomNavView;
import com.badukai.ui.JadeIconBinder;
import com.badukai.ui.JadeNavigation;

import java.io.File;
import java.io.InputStream;

/** A real full-screen Activity for new game setup. The running engine remains in MainActivity. */
public final class NewGameActivity extends AppCompatActivity {
    public static final String EXTRA_SIZE = "new_game_size";
    public static final String EXTRA_RANK = "new_game_rank";
    public static final String EXTRA_COLOR = "new_game_color";
    public static final String EXTRA_CONDITION = "new_game_condition";
    public static final String EXTRA_BACKEND = "new_game_backend";
    public static final String EXTRA_FORCE_LEGACY = "new_game_force_legacy";
    public static final String EXTRA_START = "navigate_start_game";

    private static final String TAG = "NewGameActivity";
    private static final int[] BOARD_SIZES = {9, 11, 13, 15, 19};

    private boolean hasBundledNpuModel(int size) {
        if (NpuHardware.current() == NpuHardware.Vendor.MEDIATEK)
            return KataGoEngine.supportsMediaTekGame(this, size);
        if (!KataGoEngine.supportsRknnHardware()) return false;
        if (size != 9 && size != 11 && size != 13 && size != 15 && size != 19) return false;
        try (InputStream in = getAssets().open("engine/katago_10b_" + size + "x" + size + ".rknn")) {
            return in.read() != -1;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static int normalizeSize(int size) {
        for (int n : BOARD_SIZES) if (size == n) return size;
        return 9;
    }

    private void navigate(int tab) {
        if (tab == JadeBottomNavView.PLAY) {
            findViewById(R.id.newGameSettingsScroll).scrollTo(0, 0);
            return;
        }
        JadeNavigation.open(this, tab);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.dialog_new_game);
        JadeIconBinder.bindNewGame(findViewById(R.id.jadeNewGameRoot));

        final android.content.SharedPreferences prefs =
                getSharedPreferences("katago_settings", MODE_PRIVATE);
        final Intent initial = getIntent();
        final int boardSize = normalizeSize(initial.getIntExtra(
                EXTRA_SIZE, prefs.getInt("board_size", 9)));
        final int aiKyu = Math.max(1, Math.min(18, initial.getIntExtra(EXTRA_RANK, 12)));
        final int gameCondition = Math.max(0, Math.min(9, initial.getIntExtra(EXTRA_CONDITION, 0)));
        final String backend = initial.getStringExtra(EXTRA_BACKEND) == null
                ? prefs.getString("backend", "CPU") : initial.getStringExtra(EXTRA_BACKEND);
        final boolean forceLegacy = initial.getBooleanExtra(EXTRA_FORCE_LEGACY, false);

        try (InputStream in = getAssets().open("home/background.png")) {
            ((ImageView) findViewById(R.id.newGameBackdrop))
                    .setImageBitmap(BitmapFactory.decodeStream(in));
        } catch (Exception e) {
            Log.w(TAG, "Jade background unavailable", e);
        }

        Spinner boardSizeSpinner = findViewById(R.id.boardSizeSpinner);
        String[] boardLabels = new String[BOARD_SIZES.length];
        int initialBoardIndex = 0;
        for (int i = 0; i < BOARD_SIZES.length; i++) {
            boardLabels[i] = BOARD_SIZES[i] + " × " + BOARD_SIZES[i];
            if (BOARD_SIZES[i] == boardSize) initialBoardIndex = i;
        }
        ArrayAdapter<String> boardAdapter =
                new ArrayAdapter<>(this, R.layout.item_jade_dark_spinner, boardLabels);
        boardAdapter.setDropDownViewResource(R.layout.item_jade_dark_dropdown);
        boardSizeSpinner.setAdapter(boardAdapter);
        boardSizeSpinner.setSelection(initialBoardIndex);

        final RadioGroup modeGroup = findViewById(R.id.modeGroup);
        final RadioGroup colorGroup = findViewById(R.id.colorGroup);
        final RadioGroup backendGroup = findViewById(R.id.backendGroup);
        final android.widget.RadioButton gpuChoice = findViewById(R.id.backendGpuButton);
        final android.widget.RadioButton npuChoice = findViewById(R.id.backendNpuButton);

        final boolean gpuBundled = new File(getApplicationInfo().nativeLibraryDir,
                "libkatago_gpu.so").isFile();
        final boolean mtkBundled = KataGoEngine.supportsMediaTekGame(this, 9)
                && KataGoEngine.supportsMediaTekGame(this, 13)
                && KataGoEngine.supportsMediaTekGame(this, 19);
        final boolean npuBundled = mtkBundled
                || (KataGoEngine.supportsRknnHardware()
                    && new File(getApplicationInfo().nativeLibraryDir, "libkatago_npu.so").isFile()
                    && new File(getApplicationInfo().nativeLibraryDir, "librknnrt.so").isFile());

        gpuChoice.setEnabled(gpuBundled && !forceLegacy);
        gpuChoice.setAlpha(gpuChoice.isEnabled() ? 1f : .42f);
        backendGroup.check("NPU".equals(backend) ? R.id.backendNpuButton
                : "GPU".equals(backend) ? R.id.backendGpuButton : R.id.backendCpuButton);
        if (forceLegacy) backendGroup.check(R.id.backendCpuButton);
        final int[] selectedSize = {boardSize};
        final Runnable updateNpu = () -> {
            boolean supported = npuBundled && !forceLegacy && hasBundledNpuModel(selectedSize[0]);
            npuChoice.setEnabled(supported);
            npuChoice.setAlpha(supported ? 1f : .42f);
            if (!supported && backendGroup.getCheckedRadioButtonId() == R.id.backendNpuButton) {
                backendGroup.check(R.id.backendCpuButton);
                Toast.makeText(NewGameActivity.this,
                        selectedSize[0] + "路暂未提供对应 NPU 模型，已选 CPU",
                        Toast.LENGTH_SHORT).show();
            }
        };
        boardSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,
                    View view, int position, long id) {
                selectedSize[0] = BOARD_SIZES[position];
                updateNpu.run();
                Log.i(TAG, "JADE_BOARD_SELECTED size=" + selectedSize[0]
                        + " npuAvailable=" + npuChoice.isEnabled());
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        updateNpu.run();
        if (!gpuChoice.isEnabled() && backendGroup.getCheckedRadioButtonId()
                == R.id.backendGpuButton) backendGroup.check(R.id.backendCpuButton);

        final int[] chosenRank = {aiKyu};
        SeekBar rankSeek = findViewById(R.id.rankSeekBar);
        TextView rankValue = findViewById(R.id.aiRankValueText);
        rankSeek.setMax(17);
        rankSeek.setProgress(aiKyu - 1);
        rankValue.setText(aiKyu + "级");
        rankSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                chosenRank[0] = progress + 1;
                rankValue.setText(chosenRank[0] + "级");
            }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });

        colorGroup.check("WHITE".equals(initial.getStringExtra(EXTRA_COLOR))
                ? R.id.whiteRadio : R.id.blackRadio);
        modeGroup.check(R.id.modeAiButton);
        findViewById(R.id.modeLocalButton).setEnabled(false);
        Spinner conditionSpinner = findViewById(R.id.conditionSpinner);
        ArrayAdapter<String> conditionAdapter = new ArrayAdapter<>(this,
                R.layout.item_jade_dark_spinner, new String[]{
                    "分先", "让先", "让2子", "让3子", "让4子", "让5子",
                    "让6子", "让7子", "让8子", "让9子"});
        conditionAdapter.setDropDownViewResource(R.layout.item_jade_dark_dropdown);
        conditionSpinner.setAdapter(conditionAdapter);
        conditionSpinner.setSelection(gameCondition);

        TextView deviceText = findViewById(R.id.deviceModelText);
        TextView hardwareText = findViewById(R.id.hardwareInfoText);
        final String cpuName = DeviceHardwareInfo.cpuName();
        final String npuInfo = DeviceHardwareInfo.npuDescription(npuBundled);
        deviceText.setText("设备：" + DeviceHardwareInfo.deviceName()
                + " · " + DeviceHardwareInfo.socName());
        hardwareText.setText("CPU：" + cpuName + " · GPU：识别中…\n" + npuInfo);
        new Thread(() -> {
            String gpuName = DeviceHardwareInfo.queryGpuRenderer();
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed())
                    hardwareText.setText("CPU：" + cpuName + " · GPU：" + gpuName + "\n" + npuInfo);
            });
        }, "Jade-Game-GPU-Info").start();

        findViewById(R.id.closeDialogButton).setOnClickListener(v -> finish());
        final android.widget.ScrollView settingsScroll = findViewById(R.id.newGameSettingsScroll);
        findViewById(R.id.dialogSettingsButton).setOnClickListener(v ->
                settingsScroll.smoothScrollTo(0, findViewById(R.id.aiBackendSection).getTop()));
        JadeBottomNavView nav = findViewById(R.id.newGameSharedNav);
        nav.setSelectedTab(JadeBottomNavView.PLAY);
        nav.setOnTabClickListener(this::navigate);
        findViewById(R.id.modeReplayButton).setOnClickListener(v ->
                navigate(JadeBottomNavView.RECORDS));

        findViewById(R.id.startGameButton).setOnClickListener(v -> {
            if (modeGroup.getCheckedRadioButtonId() != R.id.modeAiButton) {
                Toast.makeText(this, "请选择人机对弈模式", Toast.LENGTH_SHORT).show();
                return;
            }
            int size = selectedSize[0];
            String chosenBackend = backendGroup.getCheckedRadioButtonId() == R.id.backendNpuButton
                    ? "NPU" : backendGroup.getCheckedRadioButtonId() == R.id.backendGpuButton
                    ? "GPU" : "CPU";
            if ("NPU".equals(chosenBackend)
                    && (!npuChoice.isEnabled() || !hasBundledNpuModel(size))) {
                Toast.makeText(this, size + "路 NPU 模型不可用，请换路数或后端",
                        Toast.LENGTH_LONG).show();
                return;
            }
            int colorId = colorGroup.getCheckedRadioButtonId();
            String color = colorId == R.id.whiteRadio ? "WHITE"
                    : colorId == R.id.randomRadio
                    ? ((System.nanoTime() & 1L) == 0L ? "BLACK" : "WHITE")
                    : "BLACK";
            Intent game = new Intent(this, MainActivity.class);
            game.putExtra(EXTRA_START, true);
            game.putExtra(EXTRA_SIZE, size);
            game.putExtra(EXTRA_RANK, chosenRank[0]);
            game.putExtra(EXTRA_CONDITION, conditionSpinner.getSelectedItemPosition());
            game.putExtra(EXTRA_COLOR, color);
            game.putExtra(EXTRA_BACKEND, chosenBackend);
            game.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(game);
            overridePendingTransition(0, 0);
            finish();
            overridePendingTransition(0, 0);
        });
        Log.i(TAG, "JADE_NEW_GAME_ACTIVITY created board=" + boardSize + " backend=" + backend);
    }
}
