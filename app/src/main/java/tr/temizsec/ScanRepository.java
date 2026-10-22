package tr.temizsec;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import java.util.*;

/** Private durable scan checkpoints. A directory and its discovered children commit together. */
public final class ScanRepository extends SQLiteOpenHelper {
    private static ScanRepository instance;
    public static synchronized ScanRepository get(Context context){
        if(instance==null)instance=new ScanRepository(context.getApplicationContext());
        return instance;
    }
    static synchronized void resetForTests(){if(instance!=null)instance.close();instance=null;}
    private ScanRepository(Context context){super(context,"scan-checkpoints.db",null,1);setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE state (id INTEGER PRIMARY KEY, job INTEGER, mode TEXT, status TEXT, phase TEXT, done INTEGER, total INTEGER, skipped INTEGER DEFAULT 0, failed INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE dirs (key TEXT PRIMARY KEY, kind TEXT, uri TEXT, path TEXT, done INTEGER DEFAULT 0)");
        db.execSQL("CREATE INDEX pending_dirs ON dirs(done)");
        db.execSQL("CREATE TABLE files (id TEXT PRIMARY KEY, uri TEXT, filePath TEXT, name TEXT, path TEXT, mime TEXT, size INTEGER, modified INTEGER, deletable INTEGER)");
        db.execSQL("CREATE TABLE results (category INTEGER, grp INTEGER, position INTEGER, file TEXT)");
        db.execSQL("CREATE TABLE signatures (id TEXT PRIMARY KEY, size INTEGER, modified INTEGER, hash TEXT, photo TEXT)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){throw new IllegalStateException("Unsupported checkpoint upgrade");}
    public static final class State {
        public long job,done,total,skipped;public String mode="",status="",phase="";
        public boolean pending(){return job>0&&("running".equals(status)||"paused".equals(status)||"error".equals(status));}
        public boolean complete(){return "complete".equals(status);}
    }
    public static final class Dir {
        public final String key,kind,uri,path;
        public Dir(String key,String kind,String uri,String path){this.key=key;this.kind=kind;this.uri=uri;this.path=path;}
    }
    public static final class Snapshot {
        public final List<FileRecord> files;public final CleanupAnalyzer.Result result;
        Snapshot(List<FileRecord> files,CleanupAnalyzer.Result result){this.files=files;this.result=result;}
    }
    public State state(){
        State state=new State();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT job,mode,status,phase,done,total,skipped FROM state WHERE id=1",null)){
            if(c.moveToFirst()){state.job=c.getLong(0);state.mode=c.getString(1);state.status=c.getString(2);state.phase=c.getString(3);state.done=c.getLong(4);state.total=c.getLong(5);state.skipped=c.getLong(6);}
        }
        return state;
    }
    public void create(String mode,List<Dir> roots){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            db.delete("files",null,null);db.delete("dirs",null,null);db.delete("results",null,null);db.delete("state",null,null);
            ContentValues v=new ContentValues();v.put("id",1);v.put("job",System.currentTimeMillis());v.put("mode",mode);v.put("status","running");v.put("phase","Dosyalar listeleniyor");v.put("done",0);v.put("total",0);db.insertOrThrow("state",null,v);
            for(Dir dir:roots)insertDir(db,dir);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public Dir nextDirectory(){
        try(Cursor c=getReadableDatabase().rawQuery("SELECT key,kind,uri,path FROM dirs WHERE done=0 LIMIT 1",null)){
            return c.moveToFirst()?new Dir(c.getString(0),c.getString(1),c.getString(2),c.getString(3)):null;
        }
    }
    private void insertDir(SQLiteDatabase db,Dir dir){
        ContentValues v=new ContentValues();v.put("key",dir.key);v.put("kind",dir.kind);v.put("uri",dir.uri);v.put("path",dir.path);v.put("done",0);
        db.insertWithOnConflict("dirs",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    public void commitDirectory(Dir dir,List<FileRecord> files,List<Dir> children,int skipped){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            for(FileRecord file:files){
                ContentValues v=new ContentValues();v.put("id",file.id);v.put("uri",file.uri);v.put("filePath",file.filePath);v.put("name",file.name);v.put("path",file.path);v.put("mime",file.mime);v.put("size",file.size);v.put("modified",file.modified);v.put("deletable",file.deletable?1:0);
                db.insertWithOnConflict("files",null,v,SQLiteDatabase.CONFLICT_IGNORE);
            }
            for(Dir child:children)insertDir(db,child);
            ContentValues finished=new ContentValues();finished.put("done",1);db.update("dirs",finished,"key=?",new String[]{dir.key});
            db.execSQL("UPDATE state SET skipped=skipped+? WHERE id=1",new Object[]{skipped});
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public long fileCount(){try(Cursor c=getReadableDatabase().rawQuery("SELECT COUNT(*) FROM files",null)){c.moveToFirst();return c.getLong(0);}}
    public void progress(String phase,long done,long total){
        ContentValues v=new ContentValues();v.put("status","running");v.put("phase",phase);v.put("done",done);v.put("total",total);getWritableDatabase().update("state",v,"id=1",null);
    }
    public void pause(String status,String message){
        ContentValues v=new ContentValues();v.put("status",status);if(message!=null)v.put("phase",message);getWritableDatabase().update("state",v,"id=1",null);
    }
    public List<FileRecord> files(){
        List<FileRecord> files=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT id,uri,filePath,name,path,mime,size,modified,deletable FROM files ORDER BY id",null)){
            while(c.moveToNext())files.add(new FileRecord(c.getString(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5),c.getLong(6),c.getLong(7),c.getInt(8)!=0));
        }return files;
    }
    public String signature(FileRecord file,String column){
        if(file.modified<=0)return null;
        if(!column.equals("hash")&&!column.equals("photo"))throw new IllegalArgumentException();
        try(Cursor c=getReadableDatabase().query("signatures",new String[]{column},"id=? AND size=? AND modified=?",new String[]{file.id,Long.toString(file.size),Long.toString(file.modified)},null,null,null)){
            return c.moveToFirst()&&!c.isNull(0)?c.getString(0):null;
        }
    }
    public void signature(FileRecord file,String column,String value){
        if(file.modified<=0)return;
        if(!column.equals("hash")&&!column.equals("photo"))throw new IllegalArgumentException();
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            db.delete("signatures","id=? AND (size!=? OR modified!=?)",new String[]{file.id,Long.toString(file.size),Long.toString(file.modified)});
            ContentValues base=new ContentValues();base.put("id",file.id);base.put("size",file.size);base.put("modified",file.modified);db.insertWithOnConflict("signatures",null,base,SQLiteDatabase.CONFLICT_IGNORE);
            ContentValues update=new ContentValues();update.put(column,value);db.update("signatures",update,"id=?",new String[]{file.id});db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    public void complete(CleanupAnalyzer.Result result){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{
            db.delete("results",null,null);int group=0;
            for(List<FileRecord> files:result.duplicates)insertResult(db,0,group++,files);
            group=0;for(List<FileRecord> files:result.similar)insertResult(db,1,group++,files);
            insertResult(db,2,0,result.old);insertResult(db,3,0,result.junk);insertResult(db,4,0,result.large);
            ContentValues v=new ContentValues();v.put("status","complete");v.put("phase","Tarama tamamlandı");v.put("failed",result.failedReads);db.update("state",v,"id=1",null);
            db.setTransactionSuccessful();
        }finally{db.endTransaction();}
    }
    private void insertResult(SQLiteDatabase db,int category,int group,List<FileRecord> files){
        int position=0;for(FileRecord file:files){ContentValues v=new ContentValues();v.put("category",category);v.put("grp",group);v.put("position",position++);v.put("file",file.id);db.insertOrThrow("results",null,v);}
    }
    public Snapshot snapshot(){
        List<FileRecord> files=files();Map<String,FileRecord> byId=new HashMap<>();for(FileRecord file:files)byId.put(file.id,file);
        CleanupAnalyzer.Result result=new CleanupAnalyzer.Result();Map<String,List<FileRecord>> grouped=new LinkedHashMap<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT category,grp,file FROM results ORDER BY category,grp,position",null)){
            while(c.moveToNext()){
                int category=c.getInt(0);FileRecord file=byId.get(c.getString(2));if(file==null)continue;
                if(category<2)grouped.computeIfAbsent(category+":"+c.getInt(1),k->new ArrayList<>()).add(file);
                else (category==2?result.old:category==3?result.junk:result.large).add(file);
            }
        }
        for(Map.Entry<String,List<FileRecord>> entry:grouped.entrySet())if(entry.getValue().size()>1)(entry.getKey().startsWith("0:")?result.duplicates:result.similar).add(entry.getValue());
        try(Cursor c=getReadableDatabase().rawQuery("SELECT failed FROM state WHERE id=1",null)){if(c.moveToFirst())result.failedReads=c.getInt(0);}
        return new Snapshot(files,result);
    }
    public void remove(Set<String> ids){
        SQLiteDatabase db=getWritableDatabase();db.beginTransaction();
        try{for(String id:ids){db.delete("files","id=?",new String[]{id});db.delete("results","file=?",new String[]{id});db.delete("signatures","id=?",new String[]{id});}db.setTransactionSuccessful();}
        finally{db.endTransaction();}
    }
}
