package com.badukai;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.badukai.engine.KataGoEngine;
import com.badukai.game.GoBoard;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.game.StoneColor;
import com.badukai.ui.GoBoardView;
import com.badukai.ui.TencentHomeScaler;
import com.badukai.util.DebugLog;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private final ExecutorService engineExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private KataGoEngine engine;
    private GoBoard board = new GoBoard(19);
    private StoneColor playerColor = StoneColor.BLACK;
    private StoneColor currentPlayer = StoneColor.BLACK;
    private int boardSize = 19;
    private int aiKyu = 12;
    // Human SL selects moves from its rank profile; search primarily assists pass/resign decisions.
    // Start with 8 visits for responsiveness; keep rank selection independent of this limit.
    private int searchVisits = 8;
    private double searchTime = 8.0;
    private boolean engineReady;
    private boolean engineStarting;
    private boolean thinking;
    private boolean evaluating;
    private boolean gameReady;
    private Point lastMove;

    private View mainPageContainer;
    private View gamePageContainer;
    private GoBoardView boardView;
    private TextView statusText;
    private TextView aiCaptureText;
    private TextView playerCaptureText;
    private View aiStoneView;
    private View playerStoneView;
    private TextView gameTitleText;
    private TextView aiDifficultyText;
    private Button aiBattleButton;
    private Button newGameButton;
    private Button backButton;
    private Button undoButton;
    private Button passButton;
    private Button resignButton;
    private Button moreGameButton;
    private Button newRoundButton;
    private Button situationButton;
    private Button aiSuggestionButton;
    private Button countTerritoryButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DebugLog.enter(TAG, "onCreate in, savedInstanceState=" + savedInstanceState);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        engine = new KataGoEngine(getApplicationContext());
        bindViews();
        TencentHomeScaler.install((ViewGroup) mainPageContainer);
        boardView.setOnIntersectionClickListener(this::onBoardTap);
        aiBattleButton.setOnClickListener(v -> showNewGameDialog());
        newGameButton.setOnClickListener(v -> showNewGameDialog());
        backButton.setOnClickListener(v -> showMainPage());
        undoButton.setOnClickListener(v -> undo());
        passButton.setOnClickListener(v -> pass());
        resignButton.setOnClickListener(v -> resign());
        newRoundButton.setOnClickListener(v -> showNewGameDialog());
        situationButton.setOnClickListener(v -> showSituation());
        aiSuggestionButton.setOnClickListener(v -> Toast.makeText(this, "AI推荐功能待实现", Toast.LENGTH_SHORT).show());
        countTerritoryButton.setOnClickListener(v -> Toast.makeText(this, "数目功能待实现", Toast.LENGTH_SHORT).show());
        moreGameButton.setOnClickListener(v -> Toast.makeText(this, "更多功能待实现", Toast.LENGTH_SHORT).show());
        render("正在启动 AI...");
        showMainPage();
        startEngine();
    }

    private void bindViews() {
        DebugLog.enter(TAG, "bindViews in");
        mainPageContainer = findViewById(R.id.mainPageContainer);
        gamePageContainer = findViewById(R.id.gamePageContainer);
        boardView = findViewById(R.id.boardView);
        statusText = findViewById(R.id.statusText);
        aiCaptureText = findViewById(R.id.aiCaptureText);
        playerCaptureText = findViewById(R.id.playerCaptureText);
        aiStoneView = findViewById(R.id.aiStoneView);
        playerStoneView = findViewById(R.id.playerStoneView);
        gameTitleText = findViewById(R.id.gameTitleText);
        aiDifficultyText = findViewById(R.id.aiDifficultyText);
        aiBattleButton = findViewById(R.id.aiBattleButton);
        newGameButton = findViewById(R.id.newGameButton);
        backButton = findViewById(R.id.backButton);
        undoButton = findViewById(R.id.undoButton);
        passButton = findViewById(R.id.passButton);
        resignButton = findViewById(R.id.resignButton);
        moreGameButton = findViewById(R.id.moreGameButton);
        newRoundButton = findViewById(R.id.newRoundButton);
        situationButton = findViewById(R.id.situationButton);
        aiSuggestionButton = findViewById(R.id.aiSuggestionButton);
        countTerritoryButton = findViewById(R.id.countTerritoryButton);
    }

    private void startEngine() {
        DebugLog.enter(TAG, "startEngine in, engineStarting=" + engineStarting + ", engineReady=" + engineReady + ", boardSize=" + boardSize);
        if (engineStarting || engineReady) return;
        engineStarting = true;
        updateButtons();
        engineExecutor.execute(() -> {
            boolean ok = engine.start(KataGoEngine.Model.HUMAN);
            if (ok) {
                engine.setBoardSize(boardSize);
                engine.clearBoard();
                engine.setKomi(komiFor(boardSize));
            }
            mainHandler.post(() -> {
                engineStarting = false;
                engineReady = ok;
                render(ok ? "准备好了" : "AI 启动失败");
                if (ok && gamePageContainer.getVisibility() == View.VISIBLE && playerColor == StoneColor.WHITE) requestAiMove();
            });
        });
    }

    private void onBoardTap(int x, int y) {
        DebugLog.enter(TAG, "onBoardTap in, x=" + x + ", y=" + y + ", engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        if (!engineReady || !gameReady || thinking || currentPlayer != playerColor || board.isGameOver()) return;
        if (!board.isInside(x, y)) return;
        Point point = new Point(x, y);
        if (!board.isLegalMove(point, currentPlayer)) return;

        StoneColor color = currentPlayer;
        boardView.setOwnership(null);
        board.playMove(new Move.Stone(point, color));
        lastMove = point;
        currentPlayer = color.opposite();
        render("AI 思考中...");

        String gtp = point.toGtp(boardSize);
        engineExecutor.execute(() -> {
            boolean synced = gtp != null && engine.playMove(color.toGtp(), gtp);
            mainHandler.post(() -> {
                if (!synced) {
                    render("落子同步失败");
                    return;
                }
                requestAiMove();
            });
        });
    }

    private void requestAiMove() {
        DebugLog.enter(TAG, "requestAiMove in, engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        if (!engineReady || !gameReady || thinking || currentPlayer == playerColor || board.isGameOver()) return;
        thinking = true;
        render("AI 思考中...");
        StoneColor aiColor = currentPlayer;
        engineExecutor.execute(() -> {
            String move = engine.generateMove(aiColor.toGtp());
            mainHandler.post(() -> handleAiMove(move, aiColor));
        });
    }

    private void handleAiMove(String move, StoneColor aiColor) {
        DebugLog.enter(TAG, "handleAiMove in, move=" + move + ", aiColor=" + aiColor);
        thinking = false;
        if (move == null || move.isEmpty()) {
            render("AI 没有返回落子");
            return;
        }
        boardView.setOwnership(null);
        if ("pass".equalsIgnoreCase(move)) {
            board.playMove(new Move.Pass(aiColor));
            currentPlayer = aiColor.opposite();
            lastMove = null;
            if (board.isGameOver()) finishByScore();
            else render("AI 停一手，你下");
            return;
        }
        if ("resign".equalsIgnoreCase(move)) {
            board.playMove(new Move.Resign(aiColor));
            render("AI 认输，你赢了");
            return;
        }

        Point point = Point.fromGtp(move, boardSize);
        if (point == null || !board.isInside(point.x, point.y)) {
            render("AI 返回了无效坐标: " + move);
            return;
        }
        board.playMove(new Move.Stone(point, aiColor));
        lastMove = point;
        currentPlayer = aiColor.opposite();
        render("轮到你了");
    }

    private void pass() {
        DebugLog.enter(TAG, "pass in, engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        if (!engineReady || !gameReady || thinking || currentPlayer != playerColor || board.isGameOver()) return;
        StoneColor color = currentPlayer;
        boardView.setOwnership(null);
        board.playMove(new Move.Pass(color));
        lastMove = null;
        if (board.isGameOver()) {
            render("正在数目...");
            finishByScore();
            return;
        }
        currentPlayer = color.opposite();
        render("你停一手，AI 思考中...");
        engineExecutor.execute(() -> {
            engine.playMove(color.toGtp(), "pass");
            mainHandler.post(this::requestAiMove);
        });
    }

    private void undo() {
        DebugLog.enter(TAG, "undo in, engineReady=" + engineReady + ", thinking=" + thinking + ", moveCount=" + board.getMoveCount());
        if (!engineReady || !gameReady || thinking || board.getMoveCount() < 2) return;
        boardView.setOwnership(null);
        board.undo();
        board.undo();
        Move last = board.getLastMove();
        lastMove = last instanceof Move.Stone ? ((Move.Stone) last).point : null;
        currentPlayer = playerColor;
        render("已悔棋，轮到你了");
        engineExecutor.execute(() -> {
            engine.undo();
            engine.undo();
        });
    }

    private void resign() {
        DebugLog.enter(TAG, "resign in, thinking=" + thinking + ", moveCount=" + board.getMoveCount() + ", playerColor=" + playerColor);
        if (!gameReady || thinking || board.getMoveCount() == 0 || board.isGameOver()) return;
        boardView.setOwnership(null);
        board.playMove(new Move.Resign(playerColor));
        String winner = playerColor == StoneColor.BLACK ? "白棋" : "黑棋";
        render("你认输了，" + winner + "胜");
    }

    private void finishByScore() {
        DebugLog.enter(TAG, "finishByScore in");
        engineExecutor.execute(() -> {
            String score = engine.getFinalScore();
            mainHandler.post(() -> render(score == null ? "对局结束" : "对局结束：" + score));
        });
    }

    private void showSituation() {
        DebugLog.enter(TAG, "showSituation in, ready=" + gameReady + ", thinking=" + thinking + ", evaluating=" + evaluating);
        if (!engineReady || !gameReady || engineStarting || thinking || evaluating || currentPlayer != playerColor || board.isGameOver()) {
            Toast.makeText(this, "请等 AI 落子结束再判断形势", Toast.LENGTH_SHORT).show();
            return;
        }
        evaluating = true;
        final GoBoard snapshot = board;
        final int moves = board.getMoveCount(), size = boardSize;
        render("正在判断形势...");
        engineExecutor.execute(() -> {
            KataGoEngine.PositionEvaluation result = engine.evaluatePosition(size);
            mainHandler.post(() -> {
                evaluating = false;
                if (snapshot != board || moves != board.getMoveCount() || size != boardSize) {
                    render("棋局已变化，请重新判断形势");
                    return;
                }
                if (result == null) {
                    render("形势判断失败，请查看 KataGo 日志");
                    Toast.makeText(this, "AI 没有返回有效的形势数据", Toast.LENGTH_LONG).show();
                    return;
                }
                boardView.setOwnership(result.whiteOwnership);
                render("形势估算已更新");
                if (gamePageContainer.getVisibility() != View.VISIBLE) return;
                String lead = Math.abs(result.whiteLead) < 0.05
                        ? "双方大致均势"
                        : String.format(Locale.CHINA, "%s预计领先 %.1f 目",
                                result.whiteLead > 0 ? "白棋" : "黑棋", Math.abs(result.whiteLead));
                String details = String.format(Locale.CHINA,
                        "黑棋估算胜率：%.1f%%\n白棋估算胜率：%.1f%%\n\n%s\n\n"
                                + "棋盘黑色方块：黑方地盘倾向\n棋盘白色方块：白方地盘倾向\n"
                                + "未标记位置：归属尚不明确\n\n"
                                + "使用 10b 模型单次快速估算，已考虑贴目；不是精确数目，中盘结果仅供参考。",
                        result.blackWin * 100, result.whiteWin * 100, lead);
                new AlertDialog.Builder(this).setTitle("形势判断 · 第" + moves + "手")
                        .setMessage(details)
                        .setPositiveButton("保留标记", null)
                        .setNeutralButton("清除标记", (dialog, which) -> boardView.setOwnership(null))
                        .show();
            });
        });
    }

    private void showNewGameDialog() {
        DebugLog.enter(TAG, "showNewGameDialog in, boardSize=" + boardSize + ", playerColor=" + playerColor);
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_new_game, null, false);
        Spinner sizeSpinner = content.findViewById(R.id.sizeSpinner);
        RadioGroup colorGroup = content.findViewById(R.id.colorGroup);
        Spinner difficultySpinner = content.findViewById(R.id.difficultySpinner);
        Spinner conditionSpinner = content.findViewById(R.id.conditionSpinner);
        Button startButton = content.findViewById(R.id.startGameButton);
        Button closeButton = content.findViewById(R.id.closeDialogButton);

        int[] boardSizes = {9, 11, 13, 15, 19};
        ArrayAdapter<String> sizeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"9路", "11路", "13路", "15路", "19路"});
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sizeSpinner.setAdapter(sizeAdapter);
        int selectedSize = 4;
        for (int i = 0; i < boardSizes.length; i++) if (boardSizes[i] == boardSize) selectedSize = i;
        sizeSpinner.setSelection(selectedSize);

        ArrayAdapter<String> conditionAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new String[]{"分先", "让先", "让2子", "让3子"});
        conditionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        conditionSpinner.setAdapter(conditionAdapter);

        colorGroup.check(playerColor == StoneColor.WHITE ? R.id.whiteRadio : R.id.blackRadio);
        String[] kyuLabels = new String[18];
        for (int i = 0; i < kyuLabels.length; i++) kyuLabels[i] = (18 - i) + "级";
        ArrayAdapter<String> difficultyAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, kyuLabels);
        difficultyAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        difficultySpinner.setAdapter(difficultyAdapter);
        difficultySpinner.setSelection(18 - aiKyu);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        closeButton.setOnClickListener(v -> dialog.dismiss());
        startButton.setOnClickListener(v -> {
            boardSize = boardSizes[sizeSpinner.getSelectedItemPosition()];

            int colorId = colorGroup.getCheckedRadioButtonId();
            if (colorId == R.id.whiteRadio) playerColor = StoneColor.WHITE;
            else if (colorId == R.id.randomRadio) playerColor = (System.nanoTime() & 1L) == 0L ? StoneColor.BLACK : StoneColor.WHITE;
            else playerColor = StoneColor.BLACK;

            int selectedKyu = 18 - difficultySpinner.getSelectedItemPosition();
            Runnable begin = () -> {
                aiKyu = selectedKyu;
                showGamePage();
                startNewGame();
                dialog.dismiss();
            };

            if (engine.hasHumanModel()) {
                begin.run();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("首次准备棋力模型")
                        .setMessage("18级～1级需要 KataGo Human SL 模型，约 99 MB。首次需联网下载，下载后可离线对弈。现在下载吗？")
                        .setPositiveButton("下载并开始", (confirm, which) -> begin.run())
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        dialog.setOnShowListener(ignored -> {
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                int width = getResources().getDisplayMetrics().widthPixels;
                int maxWidth = (int) (420 * getResources().getDisplayMetrics().density);
                dialog.getWindow().setLayout(Math.min((int) (width * 0.88f), maxWidth), ViewGroup.LayoutParams.WRAP_CONTENT);
            }
        });
        dialog.show();
    }

    private void showMainPage() {
        DebugLog.enter(TAG, "showMainPage in");
        mainPageContainer.setVisibility(View.VISIBLE);
        gamePageContainer.setVisibility(View.GONE);
    }

    private void showGamePage() {
        DebugLog.enter(TAG, "showGamePage in, boardSize=" + boardSize);
        mainPageContainer.setVisibility(View.GONE);
        gamePageContainer.setVisibility(View.VISIBLE);
        gameTitleText.setText(boardSize + "路对局　常见问题　　第" + (board.getMoveCount() + 1) + "手");
    }

    private void startNewGame() {
        DebugLog.enter(TAG, "startNewGame in, engineReady=" + engineReady + ", boardSize=" + boardSize + ", kyu=" + aiKyu);
        if (!engineReady || engineStarting) {
            render(engineStarting ? "AI 正在启动，请稍候..." : "AI 尚未启动");
            return;
        }

        boardView.setOwnership(null);
        board = new GoBoard(boardSize);
        currentPlayer = StoneColor.BLACK;
        lastMove = null;
        gameReady = false;
        engineStarting = true;
        engineReady = false;
        final boolean playerFirst = playerColor == StoneColor.BLACK;
        final int size = boardSize, kyu = aiKyu, visits = searchVisits;
        final double seconds = searchTime;
        render("正在准备人类棋力模型...");

        engineExecutor.execute(() -> {
            String error = null;
            try {
                engine.prepareHumanModel((done, total) -> {
                    int percent = (int) (done * 100 / total);
                    mainHandler.post(() -> {
                        if (engineStarting) render("正在准备棋力模型 " + percent + "%");
                    });
                });
                if (!engine.isHumanSLRunning()) {
                    engine.stop();
                    if (!engine.start(KataGoEngine.Model.HUMAN, true)) throw new IllegalStateException("Human SL 引擎启动失败");
                }
                boolean ok = engine.setBoardSize(size);
                ok = engine.clearBoard() && ok;
                ok = engine.setKomi(komiFor(size)) && ok;
                if (!ok) throw new IllegalStateException("棋盘初始化失败");
                if (!engine.setSearchLimits(visits, seconds)) throw new IllegalStateException("AI 搜索限制设置失败");
                if (!engine.setHumanRank(kyu)) throw new IllegalStateException("AI 棋力等级设置失败");
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                Log.e(TAG, "Human SL game initialization failed", e);
                if (!engine.isReady()) engine.start(KataGoEngine.Model.HUMAN);
            }
            boolean ready = error == null && engine.isReady();
            boolean running = engine.isReady();
            String failure = error;
            mainHandler.post(() -> {
                engineStarting = false;
                engineReady = running;
                gameReady = ready;
                if (!ready) {
                    render("棋力模型准备失败：" + failure);
                    Toast.makeText(this, "未进入对局，请重试下载或检查日志", Toast.LENGTH_LONG).show();
                    return;
                }
                if (playerFirst) render("轮到你了");
                else requestAiMove();
            });
        });
    }

    private float komiFor(int size) {
        DebugLog.enter(TAG, "komiFor in, size=" + size);
        return size <= 11 ? 5.5f : 7.5f;
    }

    private String getDifficultyName() { return aiKyu + "级"; }

    private void render(String message) {
        DebugLog.enter(TAG, "render in, message=" + message + ", engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer);
        statusText.setText(message);
        aiDifficultyText.setText(getDifficultyName() + " · Human SL");
        boardView.setBoard(board);
        boardView.setLastMove(lastMove);
        boardView.setInputEnabled(engineReady && gameReady && !engineStarting && !thinking && !evaluating && currentPlayer == playerColor && !board.isGameOver());
        boolean playerBlack = playerColor == StoneColor.BLACK;
        playerStoneView.setBackgroundResource(playerBlack ? R.drawable.txwq_black_stone : R.drawable.txwq_white_stone);
        aiStoneView.setBackgroundResource(playerBlack ? R.drawable.txwq_white_stone : R.drawable.txwq_black_stone);
        int blackCaptures = board.getCapturedWhite();
        int whiteCaptures = board.getCapturedBlack();
        int playerCaptures = playerBlack ? blackCaptures : whiteCaptures;
        int aiCaptures = playerBlack ? whiteCaptures : blackCaptures;
        playerCaptureText.setText(String.format(Locale.CHINA, "%s棋提子 %d", playerBlack ? "黑" : "白", playerCaptures));
        aiCaptureText.setText(String.format(Locale.CHINA, "%s棋提子 %d", playerBlack ? "白" : "黑", aiCaptures));
        gameTitleText.setText(boardSize + "路对局　常见问题　　第" + (board.getMoveCount() + 1) + "手");
        updateButtons();
    }

    private void updateButtons() {
        DebugLog.enter(TAG, "updateButtons in, engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        boolean playerTurn = engineReady && gameReady && !engineStarting && !thinking && !evaluating && currentPlayer == playerColor && !board.isGameOver();
        newGameButton.setEnabled(!thinking && !engineStarting && !evaluating);
        newRoundButton.setEnabled(!thinking && !engineStarting && !evaluating);
        situationButton.setEnabled(playerTurn);
        undoButton.setEnabled(playerTurn && board.getMoveCount() >= 2);
        passButton.setEnabled(playerTurn);
        resignButton.setEnabled(playerTurn && board.getMoveCount() > 0);
    }

    @Override
    public void onBackPressed() {
        DebugLog.enter(TAG, "onBackPressed in, gameVisible=" + (gamePageContainer.getVisibility() == View.VISIBLE));
        if (gamePageContainer.getVisibility() == View.VISIBLE) showMainPage();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        DebugLog.enter(TAG, "onDestroy in");
        super.onDestroy();
        engineExecutor.execute(() -> engine.stop());
        engineExecutor.shutdown();
    }
}
