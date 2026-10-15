package com.badukai;

import android.app.Activity;
import android.app.AlertDialog;
import com.badukai.game.SgfArchive;
import android.os.Bundle;
import android.content.Intent;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Gravity;
import android.widget.LinearLayout;
import com.badukai.game.GoBoard;
import com.badukai.game.GoSgf;
import com.badukai.game.Move;
import com.badukai.ui.GoBoardView;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Read-only SGF playback; never attaches to the playing engine. */
public class ReplayActivity extends Activity {
    private static final int PICK_SGF=101;
    private static final int EXPORT_SGF=102;
    private String currentSgf;
    private GoSgf.Game game;
    private GoBoardView boardView;
    private SeekBar seek;
    private TextView counter;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        LinearLayout page=new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(12),dp(12),dp(12),dp(12));
        page.setBackgroundColor(0xfff5eedf);
        TextView title=new TextView(this);
        title.setText("棋谱复盘");
        title.setTextSize(23);
        title.setTextColor(0xff285b42);
        title.setGravity(Gravity.CENTER);
        page.addView(title,new LinearLayout.LayoutParams(-1,dp(56)));
        boardView=new GoBoardView(this);
        page.addView(boardView,new LinearLayout.LayoutParams(-1,0,1));
        counter=new TextView(this);
        counter.setGravity(Gravity.CENTER);
        counter.setTextSize(17);
        counter.setTextColor(0xff245039);
        page.addView(counter,new LinearLayout.LayoutParams(-1,dp(40)));
        seek=new SeekBar(this);
        page.addView(seek,new LinearLayout.LayoutParams(-1,dp(50)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int progress,boolean fromUser) {
                if(fromUser)showStep(progress);
            }
            public void onStartTrackingTouch(SeekBar b){}
            public void onStopTrackingTouch(SeekBar b){}
        });
        LinearLayout controls=new LinearLayout(this);
        for(String label:new String[]{"|◀","◀","▶","▶|"}){
            Button button=new Button(this);
            button.setText(label);
            controls.addView(button,new LinearLayout.LayoutParams(0,dp(55),1));
            button.setOnClickListener(v->{
                int current=seek.getProgress();
                int total=game==null?0:game.moves.size();
                if(label.equals("|◀"))current=0;
                else if(label.equals("◀"))current=Math.max(0,current-1);
                else if(label.equals("▶"))current=Math.min(total,current+1);
                else current=total;
                showStep(current);
            });
        }
        page.addView(controls);
        LinearLayout actions=new LinearLayout(this);
        for(String label:new String[]{"历史棋谱","导出 SGF","分享棋谱"}){
            Button action=new Button(this);
            action.setText(label);
            actions.addView(action,new LinearLayout.LayoutParams(0,dp(52),1));
            action.setOnClickListener(v->{
                if(label.equals("历史棋谱"))chooseHistory();
                else if(label.equals("导出 SGF"))exportSgf();
                else shareSgf();
            });
        }
        page.addView(actions);
        Button importButton=new Button(this);
        importButton.setText("导入 SGF 文件");
        importButton.setOnClickListener(v->{
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent,PICK_SGF);
        });
        page.addView(importButton);
        Button close=new Button(this);
        close.setText("返回");
        close.setOnClickListener(v->finish());
        page.addView(close);
        setContentView(page);
        String source=getIntent().getStringExtra("sgf");
        if(source==null){
            try {source=new String(Files.readAllBytes(
                    new File(getFilesDir(),"last_game.sgf").toPath()),StandardCharsets.UTF_8);}
            catch(Exception ex){Toast.makeText(this,"还没有保存的棋谱，可先导入 SGF",Toast.LENGTH_LONG).show();}
        }
        if(source!=null)load(source);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void load(String sgf){
        try{
            GoSgf.Game parsed=GoSgf.decode(sgf);
            GoSgf.position(parsed,parsed.moves.size()); // Verify main line before showing it.
            game=parsed;
            currentSgf=sgf;
            seek.setMax(game.moves.size());
            showStep(game.moves.size());
        }catch(Exception ex){
            Toast.makeText(this,"棋谱无法解析："+ex.getMessage(),Toast.LENGTH_LONG).show();
        }
    }
    private void showStep(int step){
        if(game==null){counter.setText("暂无棋谱");return;}
        try{
            GoBoard position=GoSgf.position(game,step);
            boardView.setBoard(position);
            Move last=step>0?game.moves.get(step-1):null;
            boardView.setLastMove(last instanceof Move.Stone?((Move.Stone)last).point:null);
            seek.setProgress(step);
            counter.setText("第 "+step+" / "+game.moves.size()+" 手 · "+game.size+" 路");
        }catch(Exception e){Toast.makeText(this,"无法复盘："+e.getMessage(),Toast.LENGTH_SHORT).show();}
    }
    private void chooseHistory() {
        java.util.List<File> files=SgfArchive.list(this);
        if(files.isEmpty()){
            Toast.makeText(this,"暂无历史棋谱",Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels=new String[files.size()];
        for(int i=0;i<files.size();i++){
            try {
                GoSgf.Game item=GoSgf.decode(new String(
                        Files.readAllBytes(files.get(i).toPath()),StandardCharsets.UTF_8));
                labels[i]=(i+1)+".  "+item.size+"路 · "+item.moves.size()+"手 · "
                        +new java.text.SimpleDateFormat("MM-dd HH:mm",java.util.Locale.getDefault())
                        .format(new java.util.Date(files.get(i).lastModified()));
            }catch(Exception ex){labels[i]=(i+1)+".  "+files.get(i).getName();}
        }
        new AlertDialog.Builder(this).setTitle("历史棋谱")
                .setItems(labels,(dialog,which)->{
                    try{
                        load(new String(Files.readAllBytes(files.get(which).toPath()),
                                StandardCharsets.UTF_8));
                    }catch(Exception ex){Toast.makeText(this,"读取失败："+ex.getMessage(),Toast.LENGTH_LONG).show();}
                }).setNegativeButton("取消",null).show();
    }
    private void exportSgf() {
        if(currentSgf==null){Toast.makeText(this,"没有可导出的棋谱",Toast.LENGTH_SHORT).show();return;}
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/x-go-sgf");
        intent.putExtra(Intent.EXTRA_TITLE,"badukai_"+game.size+"x"+game.size+".sgf");
        startActivityForResult(intent,EXPORT_SGF);
    }
    private void shareSgf(){
        if(currentSgf==null){Toast.makeText(this,"没有可分享的棋谱",Toast.LENGTH_SHORT).show();return;}
        Intent send=new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT,"围棋棋谱 SGF");
        send.putExtra(Intent.EXTRA_TEXT,currentSgf);
        startActivity(Intent.createChooser(send,"分享棋谱"));
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null||data.getData()==null)return;
        if(request==EXPORT_SGF){
            if(currentSgf==null)return;
            try(java.io.OutputStream out=getContentResolver().openOutputStream(data.getData())){
                if(out==null)throw new java.io.IOException("No output stream");
                out.write(currentSgf.getBytes(StandardCharsets.UTF_8));
                Toast.makeText(this,"SGF 已导出",Toast.LENGTH_SHORT).show();
            }catch(Exception ex){Toast.makeText(this,"导出失败："+ex.getMessage(),Toast.LENGTH_LONG).show();}
            return;
        }
        if(request!=PICK_SGF)return;
        try(java.io.InputStream in=getContentResolver().openInputStream(data.getData())){
            java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int n;
            while((n=in.read(buffer))!=-1){bytes.write(buffer,0,n);if(bytes.size()>2_000_000)throw new IllegalArgumentException("文件太大");}
            String sgf=bytes.toString("UTF-8");
            GoSgf.Game checked=GoSgf.decode(sgf);
            GoSgf.position(checked,checked.moves.size());
            SgfArchive.save(this,sgf);
            load(sgf);
        }catch(Exception ex){Toast.makeText(this,"导入失败："+ex.getMessage(),Toast.LENGTH_LONG).show();}
    }
}
