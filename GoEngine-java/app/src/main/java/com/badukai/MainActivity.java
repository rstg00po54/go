package com.badukai;

import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private int searchVisits = 20;
    private double searchTime = 0.4;
    private boolean engineReady;
    private boolean engineStarting;
    private boolean thinking;
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
        situationButton.setOnClickListener(v -> Toast.makeText(this, "形势功能待实现", Toast.LENGTH_SHORT).show());
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
        if (!engineReady || thinking || currentPlayer != playerColor || board.isGameOver()) return;
        if (!board.isInside(x, y)) return;
        Point point = new Point(x, y);
        if (!board.isLegalMove(point, currentPlayer)) return;

        StoneColor color = currentPlayer;
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
        if (!engineReady || thinking || currentPlayer == playerColor || board.isGameOver()) return;
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
        if (!engineReady || thinking || currentPlayer != playerColor || board.isGameOver()) return;
        StoneColor color = currentPlayer;
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
        if (!engineReady || thinking || board.getMoveCount() < 2) return;
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
        if (thinking || board.getMoveCount() == 0 || board.isGameOver()) return;
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

    private void showNewGameDialog() {
        DebugLog.enter(TAG, "showNewGameDialog in, boardSize=" + boardSize + ", playerColor=" + playerColor);
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_new_game, null, false);
        Spinner sizeSpinner = content.findViewById(R.id.sizeSpinner);
        RadioGroup colorGroup = content.findViewById(R.id.colorGroup);
        RadioGroup difficultyGroup = content.findViewById(R.id.difficultyGroup);
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
        difficultyGroup.check(searchVisits == 500 ? R.id.hardRadio : searchVisits == 100 ? R.id.normalRadio : R.id.easyRadio);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        closeButton.setOnClickListener(v -> dialog.dismiss());
        startButton.setOnClickListener(v -> {
            boardSize = boardSizes[sizeSpinner.getSelectedItemPosition()];

            int colorId = colorGroup.getCheckedRadioButtonId();
            if (colorId == R.id.whiteRadio) playerColor = StoneColor.WHITE;
            else if (colorId == R.id.randomRadio) playerColor = (System.nanoTime() & 1L) == 0L ? StoneColor.BLACK : StoneColor.WHITE;
            else playerColor = StoneColor.BLACK;

            int difficultyId = difficultyGroup.getCheckedRadioButtonId();
            if (difficultyId == R.id.hardRadio) {
                searchVisits = 500;
                searchTime = 5.0;
            } else if (difficultyId == R.id.normalRadio) {
                searchVisits = 100;
                searchTime = 1.5;
            } else {
                searchVisits = 20;
                searchTime = 0.4;
            }

            showGamePage();
            startNewGame();
            dialog.dismiss();
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
        DebugLog.enter(TAG, "startNewGame in, engineReady=" + engineReady + ", engineStarting=" + engineStarting + ", boardSize=" + boardSize + ", playerColor=" + playerColor + ", visits=" + searchVisits + ", time=" + searchTime);
        if (!engineReady) {
            render(engineStarting ? "AI 正在启动，请稍候..." : "AI 尚未启动");
            return;
        }

        board = new GoBoard(boardSize);
        currentPlayer = StoneColor.BLACK;
        lastMove = null;
        thinking = false;
        boolean playerFirst = playerColor == StoneColor.BLACK;
        render("正在初始化棋盘...");

        engineExecutor.execute(() -> {
            boolean ok = engine.setBoardSize(boardSize);
            ok = engine.clearBoard() && ok;
            ok = engine.setKomi(komiFor(boardSize)) && ok;
            boolean success = ok;

            mainHandler.post(() -> {
                if (!success) {
                    render("初始化棋盘失败");
                    return;
                }

                if (playerFirst) render("轮到你了");
                else {
                    render("AI 思考中...");
                    requestAiMove();
                }
            });
        });
    }

    private float komiFor(int size) {
        DebugLog.enter(TAG, "komiFor in, size=" + size);
        return size <= 11 ? 5.5f : 7.5f;
    }

    private String getDifficultyName() {
        if (searchVisits == 500) return "困难";
        if (searchVisits == 100) return "普通";
        return "简单";
    }

    private void render(String message) {
        DebugLog.enter(TAG, "render in, message=" + message + ", engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer);
        statusText.setText(message);
        aiDifficultyText.setText(String.format(Locale.CHINA, "%s · %d次/%.1f秒", getDifficultyName(), searchVisits, searchTime));
        boardView.setBoard(board);
        boardView.setLastMove(lastMove);
        boardView.setInputEnabled(engineReady && !thinking && currentPlayer == playerColor && !board.isGameOver());
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
        boolean playerTurn = engineReady && !thinking && currentPlayer == playerColor && !board.isGameOver();
        newGameButton.setEnabled(!thinking);
        newRoundButton.setEnabled(!thinking);
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
