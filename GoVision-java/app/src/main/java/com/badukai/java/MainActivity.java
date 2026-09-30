package com.badukai.java;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends AppCompatActivity {
    private FrameLayout content;
    private TextView title;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private EngineManager engine;
    private EngineConfig settingsConfig;
    private EditText editModel;
    private ActivityResultLauncher<String[]> modelPicker;
    private GoBoard playBoard = new GoBoard(19);
    private Stone turn = Stone.BLACK;
    private BoardView playBoardView;
    private TextView playStatus, playMessage;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        content = findViewById(R.id.content); title = findViewById(R.id.title);
        engine = EngineManager.get(this);

        modelPicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null && editModel != null) copyPickedModel(uri);
        });

        BottomNavigationView nav = findViewById(R.id.bottomNav);
        nav.getMenu().add(0,1,0,"对弈").setIcon(android.R.drawable.ic_media_play);
        nav.getMenu().add(0,2,1,"分析").setIcon(android.R.drawable.ic_menu_view);
        nav.getMenu().add(0,3,2,"设置").setIcon(android.R.drawable.ic_menu_preferences);
        nav.setOnItemSelectedListener(item -> {
            if (item.getItemId() == 1) showPlay();
            else if (item.getItemId() == 2) showAnalysis();
            else showSettings();
            return true;
        });
        nav.setSelectedItemId(1);
    }

    private void replace(int layout) {
        content.removeAllViews(); getLayoutInflater().inflate(layout, content, true);
    }

    private void showPlay() {
        title.setText("对弈"); replace(R.layout.view_play);
        playBoardView = findViewById(R.id.boardView); playStatus = findViewById(R.id.playStatus); playMessage = findViewById(R.id.playMessage);
        playBoardView.setBoard(playBoard);
        playBoardView.setListener(point -> {
            if (turn != Stone.BLACK) { toast("现在轮到 AI"); return; }
            if (!playBoard.play(point, Stone.BLACK)) { toast("这里不能下"); return; }
            playBoardView.invalidate(); turn = Stone.WHITE; playMessage.setText("白方行棋");
            io.execute(() -> {
                try {
                    GtpClient g = engine.start(playBoard.getSize());
                    GtpClient.Response r = g.send("play black " + CoordinateUtils.toGtp(point, playBoard.getSize()));
                    if (!r.success) throw new IOException(r.text);
                    runOnUiThread(() -> playStatus.setText("引擎就绪"));
                } catch (Exception e) { runOnUiThread(() -> showError(e)); }
            });
        });
        findViewById(R.id.btnStart).setOnClickListener(v -> startEngine());
        findViewById(R.id.btnAi).setOnClickListener(v -> aiMove());
        findViewById(R.id.btnNew).setOnClickListener(v -> newGame());
        findViewById(R.id.btnPass).setOnClickListener(v -> passMove());
        findViewById(R.id.btnAnalyze).setOnClickListener(v -> demoAnalysis(playBoardView));
        findViewById(R.id.btnClearMarks).setOnClickListener(v -> playBoardView.setAnalysis(Collections.emptyList()));
        playStatus.setText(engine.isReady() ? "已配置，点击启动引擎" : "未配置模型，请到设置页选择模型");
    }

    private void startEngine() {
        playStatus.setText("正在启动 KataGo…");
        io.execute(() -> {
            try {
                GtpClient g = engine.start(playBoard.getSize());
                GtpClient.Response name = g.send("name"), ver = g.send("version");
                runOnUiThread(() -> playStatus.setText("引擎就绪：" + name.text + " " + ver.text));
            } catch (Exception e) { runOnUiThread(() -> showError(e)); }
        });
    }

    private void aiMove() {
        if (turn != Stone.WHITE) { toast("现在轮到黑方"); return; }
        playMessage.setText("AI 思考中…");
        io.execute(() -> {
            try {
                GtpClient g = engine.start(playBoard.getSize());
                GtpClient.Response r = g.send("genmove white");
                if (!r.success) throw new IOException(r.text);
                int point = CoordinateUtils.fromGtp(r.text, playBoard.getSize());
                if (point >= 0) playBoard.play(point, Stone.WHITE);
                turn = Stone.BLACK;
                runOnUiThread(() -> { playBoardView.invalidate(); playMessage.setText("黑方行棋，AI: " + r.text); });
            } catch (Exception e) { runOnUiThread(() -> showError(e)); }
        });
    }

    private void passMove() {
        Stone who = turn; turn = turn.opposite();
        io.execute(() -> {
            try {
                GtpClient g = engine.start(playBoard.getSize());
                g.send("play " + (who == Stone.BLACK ? "black" : "white") + " pass");
            } catch (Exception ignored) {}
        });
        playMessage.setText(turn == Stone.BLACK ? "黑方行棋" : "白方行棋");
    }

    private void newGame() {
        engine.stop(); playBoard = new GoBoard(19); turn = Stone.BLACK;
        if (playBoardView != null) { playBoardView.setBoard(playBoard); playBoardView.setAnalysis(Collections.emptyList()); }
        if (playMessage != null) playMessage.setText("黑方行棋");
        if (playStatus != null) playStatus.setText("新对局，点击启动引擎");
    }

    private void showAnalysis() {
        title.setText("分析"); replace(R.layout.view_analysis);
        BoardView b = findViewById(R.id.analysisBoard); b.setBoard(playBoard); demoAnalysis(b);
    }

    private void demoAnalysis(BoardView b) {
        int n = playBoard.getSize();
        List<AnalysisMove> list = Arrays.asList(
                new AnalysisMove(3*n+3, 1400, .557f, 2.8f),
                new AnalysisMove(3*n+15, 920, .542f, 1.9f),
                new AnalysisMove(15*n+3, 510, .521f, .8f),
                new AnalysisMove(9*n+9, 190, .487f, -.4f));
        b.setAnalysis(list);
    }

    private void showSettings() {
        title.setText("设置"); replace(R.layout.view_settings);
        EditText exe = findViewById(R.id.editExe), cfg = findViewById(R.id.editCfg);
        editModel = findViewById(R.id.editModel);
        EditText threads = findViewById(R.id.editThreads), visits = findViewById(R.id.editVisits);
        TextView msg = findViewById(R.id.settingsMessage);
        try {
            settingsConfig = engine.load();
            exe.setText(settingsConfig.executablePath); editModel.setText(settingsConfig.modelPath); cfg.setText(settingsConfig.configPath);
            threads.setText(String.valueOf(settingsConfig.threads)); visits.setText(String.valueOf(settingsConfig.visits));
        } catch (Exception e) { msg.setText(e.toString()); settingsConfig = new EngineConfig(); }
        findViewById(R.id.btnPickModel).setOnClickListener(v -> modelPicker.launch(new String[]{"*/*"}));
        findViewById(R.id.btnSaveSettings).setOnClickListener(v -> {
            settingsConfig.executablePath = exe.getText().toString().trim();
            settingsConfig.modelPath = editModel.getText().toString().trim(); settingsConfig.configPath = cfg.getText().toString().trim();
            settingsConfig.threads = parseInt(threads,2); settingsConfig.visits = parseInt(visits,800);
            engine.stop(); engine.save(settingsConfig); msg.setText("设置已保存");
        });
        findViewById(R.id.btnTestEngine).setOnClickListener(v -> {
            settingsConfig.executablePath = exe.getText().toString().trim(); settingsConfig.modelPath = editModel.getText().toString().trim();
            settingsConfig.configPath = cfg.getText().toString().trim(); settingsConfig.threads = parseInt(threads,2); settingsConfig.visits = parseInt(visits,800);
            engine.stop(); engine.save(settingsConfig); msg.setText("正在测试…");
            io.execute(() -> {
                try {
                    GtpClient g = engine.start(19); GtpClient.Response a = g.send("name"), b = g.send("version");
                    runOnUiThread(() -> msg.setText("✅ 引擎测试成功：" + a.text + " " + b.text));
                } catch (Exception e) { runOnUiThread(() -> msg.setText("❌ " + e.getMessage())); }
            });
        });
    }

    private void copyPickedModel(Uri uri) {
        io.execute(() -> {
            try {
                String display = queryName(uri); if (display == null) display = "model.bin.gz";
                File dir = new File(getFilesDir(), "engine"); dir.mkdirs(); File dst = new File(dir, display);
                try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(dst)) {
                    byte[] buf = new byte[65536]; int n; while ((n = in.read(buf)) > 0) out.write(buf,0,n);
                }
                runOnUiThread(() -> editModel.setText(dst.getAbsolutePath()));
            } catch (Exception e) { runOnUiThread(() -> toast("复制模型失败：" + e.getMessage())); }
        });
    }
    private String queryName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {} return null;
    }
    private int parseInt(EditText e, int d) { try { return Integer.parseInt(e.getText().toString().trim()); } catch (Exception x) { return d; } }
    private void showError(Exception e) { if (playStatus != null) playStatus.setText("引擎错误：" + e.getMessage()); toast(e.getMessage()); }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    @Override protected void onDestroy() { super.onDestroy(); io.shutdownNow(); }
}
