package tr.temizsec;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.os.storage.*;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ScanService extends Service {
    public static volatile boolean active;
    public static final String NEW="tr.temizsec.NEW_SCAN",RESUME="tr.temizsec.RESUME_SCAN",PAUSE="tr.temizsec.PAUSE_SCAN";
    private static final String CHANNEL="scan-progress";
    private static final int NOTIFICATION=40;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private ScanRepository repository;
    private NotificationManager notifications;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean taskRunning;
    private long lastProgress;
    private String resumePhase;
    private long resumeDone;

    public static void start(Context context,boolean resume,boolean phone){
        Intent intent=new Intent(context,ScanService.class).setAction(resume?RESUME:NEW).putExtra("phone",phone);
        context.startForegroundService(intent);
    }
    public static void pause(Context context){
        context.startService(new Intent(context,ScanService.class).setAction(PAUSE));
    }
    @Override public void onCreate(){
        super.onCreate();repository=ScanRepository.get(this);
        notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,"Dosya taraması",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        if(intent==null){stopSelf();return START_NOT_STICKY;}
        if(PAUSE.equals(intent.getAction())){cancelled.set(true);if(!taskRunning)stopSelf();return START_NOT_STICKY;}
        if(taskRunning)return START_NOT_STICKY;
        Notification initial=notification("Tarama hazırlanıyor",0,0,true);
        if(Build.VERSION.SDK_INT>=29)startForeground(NOTIFICATION,initial,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(NOTIFICATION,initial);
        taskRunning=true;active=true;cancelled.set(false);
        boolean resume=RESUME.equals(intent.getAction()),phone=intent.getBooleanExtra("phone",true);
        worker.execute(()->runScan(resume,phone));
        return START_NOT_STICKY;
    }
    private void runScan(boolean resume,boolean phone){
        String finish="Tarama duraklatıldı. Uygulamadan devam edebilirsin.";
        try{
            PowerManager power=(PowerManager)getSystemService(POWER_SERVICE);
            wakeLock=power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"temizsec:scan");
            wakeLock.acquire(2*60*60*1000L);
            if(!resume){
                List<ScanRepository.Dir> roots=new ArrayList<>();
                if(phone)for(File root:storageRoots()){
                    String path=root.getCanonicalPath();roots.add(new ScanRepository.Dir("file:"+path,"file","",path));
                }else{
                    Set<String> trees=getSharedPreferences("settings",MODE_PRIVATE).getStringSet("trees",Collections.emptySet());
                    for(String saved:new ArrayList<>(trees)){
                        Uri uri=Uri.parse(saved);String id=DocumentsContract.getTreeDocumentId(uri);
                        roots.add(new ScanRepository.Dir(saved+"#"+id,"tree",saved,id));
                    }
                }
                repository.create(phone?"phone":"folders",roots);
            }else if(!repository.state().pending()){finish="Devam edecek yarım tarama yok.";return;}
            if(resume){ScanRepository.State state=repository.state();resumePhase=state.phase;resumeDone=state.done;}
            lastProgress=0;
            ScanRepository.Dir directory;
            while(!stopped()&&(directory=repository.nextDirectory())!=null){
                if("file".equals(directory.kind))scanDirectory(directory);else scanTreeDirectory(directory);
                progress("Dosyalar listeleniyor",(int)repository.fileCount(),0);
            }
            if(stopped()){repository.pause("paused",null);return;}
            List<FileRecord> files=repository.files();
            CleanupAnalyzer.Result result=CleanupAnalyzer.analyze(files,new CleanupAnalyzer.Source(){
                public InputStream open(FileRecord file)throws Exception{return ScanImages.open(ScanService.this,file);}
                public boolean unchanged(FileRecord file)throws Exception{return isUnchanged(ScanService.this,file);}
                public String cachedDigest(FileRecord file){return repository.signature(file,"hash");}
                public void saveDigest(FileRecord file,String hash){repository.signature(file,"hash",hash);}
                public CleanupAnalyzer.Photo photo(FileRecord file){
                    String saved=repository.signature(file,"photo");
                    if(saved!=null){
                        if(saved.isEmpty())return null;
                        try{String[] p=saved.split(",");return new CleanupAnalyzer.Photo(Long.parseLong(p[0]),Long.parseLong(p[1]),Double.parseDouble(p[2]),Double.parseDouble(p[3]),Integer.parseInt(p[4]),Integer.parseInt(p[5]),Integer.parseInt(p[6]));}
                        catch(RuntimeException ignored){}
                    }
                    CleanupAnalyzer.Photo photo=ScanImages.signature(ScanService.this,file);
                    if(photo==null)repository.signature(file,"photo","");
                    else repository.signature(file,"photo",photo.difference+","+photo.average+","+photo.aspect+","+photo.variance+","+photo.red+","+photo.green+","+photo.blue);
                    return photo;
                }
            },this::stopped,this::progress,System.currentTimeMillis());
            if(stopped()){repository.pause("paused",null);return;}
            repository.complete(result);finish="Tarama tamamlandı. Sonuçları açmak için dokun.";
        }catch(Exception e){
            repository.pause("error","Tarama kesildi. Kaydedilen yerden devam edebilirsin.");
            finish="Tarama kesildi. Uygulamadan devam edebilirsin.";
        }finally{
            if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();
            active=false;taskRunning=false;
            stopForeground(STOP_FOREGROUND_REMOVE);
            notifyIfAllowed(notification(finish,0,0,false));
            stopSelf();
        }
    }
    private boolean stopped(){return cancelled.get()||Thread.currentThread().isInterrupted();}
    private void progress(String phase,int done,int total){
        if(resumePhase!=null){
            int stage=stage(phase),savedStage=stage(resumePhase);
            if(stage<savedStage||(stage==savedStage&&done<resumeDone))return;
            resumePhase=null;
        }
        long now=SystemClock.elapsedRealtime();if(now-lastProgress<750&&done!=total)return;lastProgress=now;
        repository.progress(phase,done,total);notifyIfAllowed(notification(phase,done,total,true));
    }
    private int stage(String phase){return phase.startsWith("Benzer")?2:phase.startsWith("Kopyalar")?1:0;}
    private Notification notification(String title,int done,int total,boolean running){
        Intent open=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder=new Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_search)
            .setContentTitle("Temiz Seç").setContentText(title+(total>0?" · "+done+" / "+total:done>0?" · "+done+" dosya":""))
            .setContentIntent(content).setOnlyAlertOnce(true).setOngoing(running).setAutoCancel(!running);
        if(running){
            builder.setProgress(total,done,total==0);
            PendingIntent pause=PendingIntent.getService(this,1,new Intent(this,ScanService.class).setAction(PAUSE),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(new Notification.Action.Builder(null,"Duraklat",pause).build());
        }
        return builder.build();
    }
    private void notifyIfAllowed(Notification notification){
        if(Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
            notifications.notify(NOTIFICATION,notification);
    }
    private List<File> storageRoots(){
        LinkedHashMap<String,File> roots=new LinkedHashMap<>();File primary=Environment.getExternalStorageDirectory();roots.put(primary.getAbsolutePath(),primary);
        if(Build.VERSION.SDK_INT>=30){
            StorageManager manager=(StorageManager)getSystemService(STORAGE_SERVICE);
            for(StorageVolume volume:manager.getStorageVolumes()){
                File dir=volume.getDirectory();
                if(dir!=null&&(Environment.MEDIA_MOUNTED.equals(volume.getState())||Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState())))roots.put(dir.getAbsolutePath(),dir);
            }
        }return new ArrayList<>(roots.values());
    }
    private void scanDirectory(ScanRepository.Dir directory)throws IOException{
        File parent=new File(directory.path);File[] entries=parent.listFiles();
        List<FileRecord> files=new ArrayList<>();List<ScanRepository.Dir> children=new ArrayList<>();int skipped=0;
        if(entries==null)skipped++;
        else for(File file:entries){
            if(stopped())return; // Do not mark a half-read directory complete.
            if(file.isDirectory()){
                if("Android".equals(parent.getName())&&("data".equals(file.getName())||"obb".equals(file.getName()))){skipped++;continue;}
                String path=file.getCanonicalPath();children.add(new ScanRepository.Dir("file:"+path,"file","",path));
            }else if(file.isFile()){
                String path=file.getCanonicalPath();
                files.add(new FileRecord("file:"+path,Uri.fromFile(file).toString(),path,file.getName(),path,mime(file.getName()),file.length(),file.lastModified(),file.canWrite()&&parent.canWrite()));
            }
        }
        repository.commitDirectory(directory,files,children,skipped);
    }
    private void scanTreeDirectory(ScanRepository.Dir directory){
        Uri tree=Uri.parse(directory.uri);
        List<FileRecord> files=new ArrayList<>();List<ScanRepository.Dir> children=new ArrayList<>();int skipped=0;
        String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try(Cursor cursor=getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,directory.path),projection,null,null,null)){
            if(cursor==null)skipped++;
            else while(cursor.moveToNext()){
                if(stopped())return;
                String id=cursor.getString(0),name=cursor.getString(1),mime=cursor.getString(2);if(name==null)name="İsimsiz";
                if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){children.add(new ScanRepository.Dir(directory.uri+"#"+id,"tree",directory.uri,id));continue;}
                if(cursor.isNull(3)||cursor.getLong(3)<0){skipped++;continue;}
                Uri uri=DocumentsContract.buildDocumentUriUsingTree(tree,id);
                String path=id.replace("primary:","Telefon/").replace(':','/');
                files.add(new FileRecord(documentIdentity(tree,id,uri),uri.toString(),null,name,path,mime,cursor.getLong(3),cursor.getLong(5),(cursor.getLong(4)&DocumentsContract.Document.FLAG_SUPPORTS_DELETE)!=0));
            }
        }catch(Exception e){skipped++;}
        repository.commitDirectory(directory,files,children,skipped);
    }
    private String documentIdentity(Uri tree,String id,Uri uri){
        if("com.android.externalstorage.documents".equals(tree.getAuthority())){
            int colon=id.indexOf(':');if(colon>=0){
                String volume=id.substring(0,colon),relative=id.substring(colon+1);
                File base="primary".equalsIgnoreCase(volume)?Environment.getExternalStorageDirectory():new File("/storage",volume);
                try{return "file:"+new File(base,relative).getCanonicalPath();}catch(IOException ignored){}
            }
        }return uri.toString();
    }
    private String mime(String name){
        int dot=name.lastIndexOf('.');String mime=dot<0?null:MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(Locale.ROOT));return mime==null?"application/octet-stream":mime;
    }
    public static boolean isUnchanged(Context context,FileRecord file)throws Exception{
        if(file.filePath!=null){File current=new File(file.filePath);return current.isFile()&&current.length()==file.size&&current.lastModified()==file.modified;}
        String[] projection={DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try(Cursor c=context.getContentResolver().query(Uri.parse(file.uri),projection,null,null,null)){
            return c!=null&&c.moveToFirst()&&!c.isNull(0)&&c.getLong(0)==file.size&&(file.modified==0||c.getLong(1)==file.modified);
        }
    }
    @Override public void onTimeout(int startId,int foregroundServiceType){cancelled.set(true);stopSelf();}
    @Override public void onDestroy(){cancelled.set(true);worker.shutdownNow();super.onDestroy();}
}
