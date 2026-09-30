package com.badukai;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.badukai.engine.KataGoEngine;
import com.badukai.game.GoBoard;
import com.badukai.game.Move;
import com.badukai.game.Point;
import com.badukai.game.StoneColor;
import com.badukai.ui.GoBoardView;
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
    private boolean engineReady;
    private boolean engineStarting;
    private boolean thinking;
    private Point lastMove;

    private GoBoardView boardView;
    private TextView statusText;
    private TextView blackCaptureText;
    private TextView whiteCaptureText;
    private Button newGameButton;
    private Button undoButton;
    private Button passButton;
    private Button resignButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        DebugLog.enter(TAG, "onCreate in, savedInstanceState=" + savedInstanceState);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        engine = new KataGoEngine(getApplicationContext());
        bindViews();
        boardView.setOnIntersectionClickListener(this::onBoardTap);
        newGameButton.setOnClickListener(v -> showNewGameDialog());
        undoButton.setOnClickListener(v -> undo());
        passButton.setOnClickListener(v -> pass());
        resignButton.setOnClickListener(v -> resign());
        render("正在启动 AI...");
        startEngine();
    }

    private void bindViews() {
        DebugLog.enter(TAG, "bindViews in");
        boardView = findViewById(R.id.boardView);
        statusText = findViewById(R.id.statusText);
        blackCaptureText = findViewById(R.id.blackCaptureText);
        whiteCaptureText = findViewById(R.id.whiteCaptureText);
        newGameButton = findViewById(R.id.newGameButton);
        undoButton = findViewById(R.id.undoButton);
        passButton = findViewById(R.id.passButton);
        resignButton = findViewById(R.id.resignButton);
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
                if (ok && playerColor == StoneColor.WHITE) requestAiMove();
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
        RadioGroup colorGroup = content.findViewById(R.id.colorGroup);
        Spinner sizeSpinner = content.findViewById(R.id.sizeSpinner);
        Button startButton = content.findViewById(R.id.startGameButton);
        Integer[] sizes = {9, 11, 13, 15, 19};
        ArrayAdapter<Integer> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, sizes);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sizeSpinner.setAdapter(adapter);
        sizeSpinner.setSelection(4);

        AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        startButton.setOnClickListener(v -> {
            playerColor = colorGroup.getCheckedRadioButtonId() == R.id.whiteRadio ? StoneColor.WHITE : StoneColor.BLACK;
            boardSize = (Integer) sizeSpinner.getSelectedItem();
            startNewGame();
            dialog.dismiss();
        });
        dialog.show();
    }

    private void startNewGame() {
        DebugLog.enter(TAG, "startNewGame in, engineReady=" + engineReady + ", engineStarting=" + engineStarting + ", boardSize=" + boardSize + ", playerColor=" + playerColor);
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

    private void render(String message) {
        DebugLog.enter(TAG, "render in, message=" + message + ", engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer);
        statusText.setText(message);
        boardView.setBoard(board);
        boardView.setLastMove(lastMove);
        boardView.setInputEnabled(engineReady && !thinking && currentPlayer == playerColor && !board.isGameOver());
        blackCaptureText.setText(String.format(Locale.CHINA, "黑棋提子 %d", board.getCapturedWhite()));
        whiteCaptureText.setText(String.format(Locale.CHINA, "白棋提子 %d", board.getCapturedBlack()));
        updateButtons();
    }

    private void updateButtons() {
        DebugLog.enter(TAG, "updateButtons in, engineReady=" + engineReady + ", thinking=" + thinking + ", currentPlayer=" + currentPlayer + ", playerColor=" + playerColor);
        boolean playerTurn = engineReady && !thinking && currentPlayer == playerColor && !board.isGameOver();
        newGameButton.setEnabled(!thinking);
        undoButton.setEnabled(playerTurn && board.getMoveCount() >= 2);
        passButton.setEnabled(playerTurn);
        resignButton.setEnabled(playerTurn && board.getMoveCount() > 0);
    }

    @Override
    protected void onDestroy() {
        DebugLog.enter(TAG, "onDestroy in");
        super.onDestroy();
        engineExecutor.execute(() -> engine.stop());
        engineExecutor.shutdown();
    }
}
