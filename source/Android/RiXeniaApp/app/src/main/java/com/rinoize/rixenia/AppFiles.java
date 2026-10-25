package com.rinoize.rixenia;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

final class AppFiles {
    private AppFiles() {}
    static String name(Context context,Uri uri) {
        try(Cursor c=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(c!=null && c.moveToFirst() && !c.isNull(0))return c.getString(0);
        }catch(RuntimeException ignored){}
        return "archivo seleccionado";
    }
    static byte[] read(Context context,Uri uri,int limit)throws IOException {
        try(InputStream in=context.getContentResolver().openInputStream(uri)) {
            if(in==null)throw new IOException("Android no pudo abrir el archivo");
            return read(in,limit);
        }
    }
    static byte[] read(File file,int limit)throws IOException {
        try(InputStream in=new FileInputStream(file)){return read(in,limit);}
    }
    private static byte[] read(InputStream in,int limit)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int n;
        while((n=in.read(buffer))!=-1){if(n==0)continue;if(out.size()>limit-n)throw new IOException("Archivo demasiado grande");out.write(buffer,0,n);}
        return out.toByteArray();
    }
    static void atomic(File file,byte[] bytes)throws IOException {
        File temporary=new File(file.getParentFile(),file.getName()+".tmp");
        try(FileOutputStream out=new FileOutputStream(temporary)){out.write(bytes);out.getFD().sync();}
        try { Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
        catch(AtomicMoveNotSupportedException e){Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    static void validateRom(byte[] rom)throws IOException {
        RomFile.validate(rom);
    }
}
