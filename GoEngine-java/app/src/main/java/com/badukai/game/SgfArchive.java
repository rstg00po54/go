package com.badukai.game;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Private SGF archive; repeated saves of the same game never create duplicates. */
public final class SgfArchive {
    private SgfArchive() {}
    public static File directory(Context context) {
        File dir=new File(context.getFilesDir(),"sgf_games");
        if(!dir.isDirectory()&&!dir.mkdirs())throw new IllegalStateException("Cannot create SGF archive");
        return dir;
    }
    public static File save(Context context,String sgf) throws Exception {
        byte[] data=sgf.getBytes(StandardCharsets.UTF_8);
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        byte[] digest=hash.digest(data);
        StringBuilder name=new StringBuilder();
        for(int i=0;i<12;i++)name.append(String.format(java.util.Locale.US,"%02x",digest[i]&0xff));
        File file=new File(directory(context),name+".sgf");
        if(!file.exists())Files.write(file.toPath(),data);
        return file;
    }
    public static List<File> list(Context context) {
        File[] files=directory(context).listFiles((dir,name)->name.endsWith(".sgf"));
        if(files==null)return new ArrayList<>();
        Arrays.sort(files,Comparator.comparingLong(File::lastModified).reversed());
        return new ArrayList<>(Arrays.asList(files));
    }
}
