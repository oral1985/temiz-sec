package tr.temizsec;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.os.storage.*;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.LruCache;
import android.view.*;
import android.webkit.MimeTypeMap;
import android.widget.*;
import androidx.core.content.FileProvider;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int INK=0xff163247, MUTED=0xff667D8D, TEAL=0xff087F75, BG=0xffF4F7FA;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExecutorService images=Executors.newFixedThreadPool(2);
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private final List<FileRecord> files=new ArrayList<>();
    private List<List<FileRecord>> groups=new ArrayList<>();
    private final List<Row> rows=new ArrayList<>();
    private final Set<String> selected=new HashSet<>();
    private Set<String> protectedIds, trees;
    private SharedPreferences prefs;
    private TextView scopeLabel, summary, selectionLabel, notice, foldersLink;
    private Button phoneButton, folderButton, foldersScan, autoButton, clearButton, deleteButton;
    private Spinner matchMode;
    private CheckBox photosOnly;
    private ProgressBar progress;
    private ListView results;
    private ResultsAdapter adapter;
    private boolean busy, deleting, waitingForAccess;
    private String lastScope="folders";
    private long scannedCount, emptyCount, unreadableCount;
    private final LruCache<String,Bitmap> thumbs=new LruCache<String,Bitmap>(12*1024*1024) {
        @Override protected int sizeOf(String key,Bitmap bitmap){return bitmap.getAllocationByteCount();}
    };

    private static final class Row {
        final List<FileRecord> group; final FileRecord file; final int number;
        Row(List<FileRecord> group,FileRecord file,int number){this.group=group;this.file=file;this.number=number;}
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("settings",MODE_PRIVATE);
        protectedIds=new HashSet<>(prefs.getStringSet("protected",Collections.emptySet()));
        trees=new LinkedHashSet<>(prefs.getStringSet("trees",Collections.emptySet()));
        String old=prefs.getString("tree",null);
        if(trees.isEmpty()&&old!=null){trees.add(old);saveTrees();}
        lastScope=prefs.getString("scope","folders");
        buildUi();
        updateScope();
        if("phone".equals(lastScope)&&hasStorageAccess())startPhoneScan();
        else if(!trees.isEmpty())startFolderScan();
    }

    private void buildUi() {
        LinearLayout root=column();root.setPadding(dp(16),dp(10),dp(16),dp(8));root.setBackgroundColor(BG);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            root.setPadding(dp(16),dp(10)+insets.getSystemWindowInsetTop(),dp(16),dp(8)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title=text("Temiz Seç",27,INK);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        root.addView(text("Dosyalarını gör. Bir kopyasını bırak.",13,MUTED));
        LinearLayout scope=column();scope.setPadding(dp(12),dp(8),dp(12),dp(8));scope.setBackground(shape(Color.WHITE,12));
        add(root,scope,8);
        scopeLabel=text("",13,INK);scope.addView(scopeLabel);
        LinearLayout actions=row();scope.addView(actions);
        phoneButton=button("Telefonu tara",actions,TEAL,Color.WHITE);
        folderButton=button("Klasör ekle",actions,0xffE5F2EF,TEAL);
        phoneButton.setOnClickListener(v->{
            if(busy){if(!deleting){cancelled.set(true);summary.setText("Tarama durduruluyor…");}return;}
            requestPhoneScan();
        });
        folderButton.setOnClickListener(v->chooseFolder());
        LinearLayout folderActions=row();scope.addView(folderActions);
        foldersLink=text("",12,TEAL);foldersLink.setPadding(dp(4),dp(8),dp(4),dp(8));
        folderActions.addView(foldersLink,new LinearLayout.LayoutParams(0,-2,1));
        foldersLink.setOnClickListener(v->manageFolders());
        foldersScan=button("Klasörleri tara",folderActions,Color.WHITE,TEAL);
        foldersScan.setOnClickListener(v->startFolderScan());

        matchMode=new Spinner(this);
        String[] filters={"Aynı boyut · tüm isimler","Aynı isim + aynı boyut","Farklı isim + aynı boyut","Aynı KB aralığı · yaklaşık"};
        matchMode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,filters));
        matchMode.setBackground(shape(Color.WHITE,10));
        add(root,matchMode,8);
        LinearLayout options=row();
        photosOnly=new CheckBox(this);photosOnly.setText("Yalnızca fotoğraflar");photosOnly.setTextSize(13);photosOnly.setTextColor(INK);
        options.addView(photosOnly,new LinearLayout.LayoutParams(0,-2,1));
        TextView help=text("Nasıl eşleşir?",12,TEAL);options.addView(help);
        help.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Eşleşme seçenekleri")
            .setMessage("Aynı boyut: adından bağımsız olarak tam bayt sayısı aynı dosyalar.\n\nAynı isim: hem isim hem tam boyut aynı.\n\nFarklı isim: boyut aynı, grupta farklı isimler var.\n\nAynı KB aralığı: boyut / 1024 aynı tam sayıya denk gelir. Örneğin 2048 ve 2050 bayt birlikte görünür.\n\nAynı boyut aynı içerik anlamına gelmez. Fotoğrafa dokunarak kontrol et. Boş dosyalar eşleştirilmez.")
            .setPositiveButton("Tamam",null).show());
        root.addView(options);
        notice=text("Aynı boyut, aynı fotoğraf demek değildir. Önizlemeyi kontrol et.",11,MUTED);
        root.addView(notice);
        LinearLayout bulk=row();autoButton=button("Birini bırak, kalanları seç",bulk,0xffE5F2EF,TEAL);
        clearButton=button("Temizle",bulk,Color.WHITE,MUTED);add(root,bulk,4);
        autoButton.setOnClickListener(v->{
            selected.clear();selected.addAll(MatchRules.selectExtras(groups,protectedIds));
            adapter.notifyDataSetChanged();updateSelection();
        });
        clearButton.setOnClickListener(v->{selected.clear();adapter.notifyDataSetChanged();updateSelection();});
        summary=text("Telefonu tara veya birden fazla klasör ekle.",12,MUTED);add(root,summary,4);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);root.addView(progress,new LinearLayout.LayoutParams(-1,dp(3)));
        results=new ListView(this);results.setDivider(null);results.setDividerHeight(0);results.setClipToPadding(false);
        results.setPadding(0,0,0,dp(8));results.setBackgroundColor(BG);
        adapter=new ResultsAdapter();results.setAdapter(adapter);
        root.addView(results,new LinearLayout.LayoutParams(-1,0,1));
        selectionLabel=text("Seçilen dosya yok",13,INK);root.addView(selectionLabel);
        LinearLayout footer=row();deleteButton=button("Seçilenleri sil",footer,0xffB83A42,Color.WHITE);root.addView(footer);
        deleteButton.setOnClickListener(v->confirmDelete());
        photosOnly.setOnCheckedChangeListener((v,checked)->rebuild());
        matchMode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> p,View v,int position,long id){rebuild();}
            public void onNothingSelected(AdapterView<?> p){}
        });
        updateSelection();
    }

    private boolean hasStorageAccess() {
        if(Build.VERSION.SDK_INT>=30)return Environment.isExternalStorageManager();
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED
            &&checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }

    private void requestPhoneScan() {
        if(hasStorageAccess()){startPhoneScan();return;}
        new AlertDialog.Builder(this).setTitle("Telefonun ortak depolamasını tara")
            .setMessage("Üst klasörleri, DCIM, Pictures, Download ve erişilebilen SD kart klasörlerini birlikte taramak için dosya erişimi gerekiyor.\n\nAndroid 11 ve üzerinde açılan ekranda “Tüm dosyalara erişim” iznini aç. Uygulamalara ait özel Android/data ve Android/obb alanları yine erişilemeyebilir.\n\nİstersen bu izni vermeden birden fazla klasör seçebilirsin.")
            .setNegativeButton("Vazgeç",null).setPositiveButton("İzni aç",(d,w)->{
                if(Build.VERSION.SDK_INT>=30){
                    waitingForAccess=true;
                    try{startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:"+getPackageName())));}
                    catch(ActivityNotFoundException e){
                        try{startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));}
                        catch(ActivityNotFoundException ex){waitingForAccess=false;toast("Bu cihazda izin ekranı açılamadı. Klasör seçimini kullan.");}
                    }
                }else requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.WRITE_EXTERNAL_STORAGE},20);
            }).show();
    }

    @Override protected void onResume(){
        super.onResume();
        if(waitingForAccess){waitingForAccess=false;if(hasStorageAccess())startPhoneScan();else toast("İzin açılmadı. İstersen klasörleri ayrı ayrı ekleyebilirsin.");}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(request==20){if(hasStorageAccess())startPhoneScan();else toast("Telefon taraması için dosya izni gerekli.");}
    }

    private void chooseFolder() {
        if(busy)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent,10);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=10||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        try{
            if((data.getFlags()&Intent.FLAG_GRANT_WRITE_URI_PERMISSION)!=0)
                getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            else getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        }catch(SecurityException e){toast("Klasör izni kaydedilemedi.");return;}
        trees.add(uri.toString());saveTrees();startFolderScan();
    }
    private void saveTrees(){prefs.edit().putStringSet("trees",new HashSet<>(trees)).remove("tree").apply();}
    private void manageFolders() {
        if(busy)return;
        if(trees.isEmpty()){chooseFolder();return;}
        List<String> values=new ArrayList<>(trees);String[] names=new String[values.size()];boolean[] keep=new boolean[values.size()];
        for(int i=0;i<values.size();i++){names[i]=treeLabel(Uri.parse(values.get(i)));keep[i]=true;}
        new AlertDialog.Builder(this).setTitle("Taranacak klasörler")
            .setMultiChoiceItems(names,keep,(d,which,checked)->keep[which]=checked)
            .setNeutralButton("Klasör ekle",(d,w)->chooseFolder())
            .setNegativeButton("Vazgeç",null).setPositiveButton("Kaydet",(d,w)->{
                for(int i=0;i<keep.length;i++)if(!keep[i]){
                    trees.remove(values.get(i));
                    try{getContentResolver().releasePersistableUriPermission(Uri.parse(values.get(i)),Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}
                    catch(SecurityException ignored){}
                }
                saveTrees();updateScope();
                if("folders".equals(lastScope)){files.clear();rebuild();summary.setText("Klasör listesi değişti. Yeniden tara.");}
            }).show();
    }

    private List<File> storageRoots() {
        LinkedHashMap<String,File> roots=new LinkedHashMap<>();
        File primary=Environment.getExternalStorageDirectory();
        roots.put(primary.getAbsolutePath(),primary);
        if(Build.VERSION.SDK_INT>=30){
            StorageManager storage=(StorageManager)getSystemService(STORAGE_SERVICE);
            if(storage!=null)for(StorageVolume volume:storage.getStorageVolumes()){
                File dir=volume.getDirectory();
                if(dir!=null&&(Environment.MEDIA_MOUNTED.equals(volume.getState())||Environment.MEDIA_MOUNTED_READ_ONLY.equals(volume.getState())))
                    roots.put(dir.getAbsolutePath(),dir);
            }
        }
        return new ArrayList<>(roots.values());
    }
    private void startPhoneScan(){
        if(busy)return;if(!hasStorageAccess()){requestPhoneScan();return;}
        lastScope="phone";prefs.edit().putString("scope",lastScope).apply();
        beginScan(true);
    }
    private void startFolderScan(){
        if(busy)return;if(trees.isEmpty()){chooseFolder();return;}
        lastScope="folders";prefs.edit().putString("scope",lastScope).apply();
        beginScan(false);
    }
    private void beginScan(boolean phone) {
        cancelled.set(false);selected.clear();files.clear();scannedCount=emptyCount=unreadableCount=0;
        setBusy(true,false);rebuild();updateScope();summary.setText("Dosyalar sayılıyor…");
        final List<String> folderUris=new ArrayList<>(trees);
        final List<File> roots=storageRoots();
        worker.execute(()->{
            List<FileRecord> found=new ArrayList<>();Set<String> seen=new HashSet<>();
            if(phone)scanStorage(roots,found,seen);
            else for(String uri:folderUris){if(cancelled.get())break;scanTree(Uri.parse(uri),found,seen);}
            runOnUiThread(()->{
                if(isDestroyed())return;
                files.addAll(found);
                for(FileRecord e:files)if(protectedIds.contains(e.uri))protectedIds.add(e.id);
                saveProtected();setBusy(false,false);rebuild();
            });
        });
    }
    private void scanStorage(List<File> roots,List<FileRecord> found,Set<String> seen) {
        ArrayDeque<File> queue=new ArrayDeque<>(roots);Set<String> directories=new HashSet<>();
        while(!queue.isEmpty()&&!cancelled.get()&&!Thread.currentThread().isInterrupted()){
            File dir=queue.remove();
            try{
                if(!directories.add(dir.getCanonicalPath()))continue;
                File[] children=dir.listFiles();
                if(children==null){unreadableCount++;continue;}
                for(File file:children){
                    if(cancelled.get())break;
                    if(file.isDirectory()){
                        if("Android".equals(dir.getName())&&("data".equals(file.getName())||"obb".equals(file.getName()))){unreadableCount++;continue;}
                        queue.add(file);
                    }else if(file.isFile()){
                        scannedCount++;
                        long size=file.length();
                        if(size==0){emptyCount++;continue;}
                        String canonical=file.getCanonicalPath(),id="file:"+canonical;
                        if(seen.add(id))found.add(new FileRecord(id,Uri.fromFile(file).toString(),canonical,file.getName(),canonical,mime(file.getName()),size,file.lastModified(),file.canWrite()&&dir.canWrite()));
                        reportProgress();
                    }
                }
            }catch(Exception e){unreadableCount++;}
        }
    }
    private void scanTree(Uri tree,List<FileRecord> found,Set<String> seen) {
        ArrayDeque<String[]> queue=new ArrayDeque<>();Set<String> visited=new HashSet<>();
        try{queue.add(new String[]{DocumentsContract.getTreeDocumentId(tree),treeLabel(tree)});}
        catch(Exception e){unreadableCount++;return;}
        while(!queue.isEmpty()&&!cancelled.get()&&!Thread.currentThread().isInterrupted()){
            String[] dir=queue.remove();if(!visited.add(dir[0]))continue;
            String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
            try(Cursor cursor=getContentResolver().query(DocumentsContract.buildChildDocumentsUriUsingTree(tree,dir[0]),projection,null,null,null)){
                if(cursor==null){unreadableCount++;continue;}
                while(cursor.moveToNext()&&!cancelled.get()){
                    String id=cursor.getString(0),name=cursor.getString(1),mime=cursor.getString(2);
                    if(name==null)name="İsimsiz";
                    String path=dir[1]+"/"+name;
                    if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)){queue.add(new String[]{id,path});continue;}
                    scannedCount++;
                    if(cursor.isNull(3)){unreadableCount++;continue;}
                    long size=cursor.getLong(3);if(size<=0){emptyCount++;continue;}
                    Uri uri=DocumentsContract.buildDocumentUriUsingTree(tree,id);
                    String identity=documentIdentity(tree,id,uri);
                    if(seen.add(identity))found.add(new FileRecord(identity,uri.toString(),null,name,path,mime,size,cursor.getLong(5),(cursor.getLong(4)&DocumentsContract.Document.FLAG_SUPPORTS_DELETE)!=0));
                    reportProgress();
                }
            }catch(Exception e){unreadableCount++;}
        }
    }
    private String documentIdentity(Uri tree,String documentId,Uri uri){
        if("com.android.externalstorage.documents".equals(tree.getAuthority())){
            int colon=documentId.indexOf(':');
            if(colon>=0){
                String volume=documentId.substring(0,colon),relative=documentId.substring(colon+1);
                File base="primary".equalsIgnoreCase(volume)?Environment.getExternalStorageDirectory():new File("/storage",volume);
                try{return "file:"+new File(base,relative).getCanonicalPath();}catch(IOException ignored){}
            }
        }
        return uri.toString();
    }
    private void reportProgress(){
        if(scannedCount%150!=0)return;
        long count=scannedCount;
        runOnUiThread(()->{if(!isDestroyed()&&busy&&!deleting)summary.setText(count+" dosya tarandı…");});
    }

    private void rebuild(){
        if(adapter==null)return;
        selected.clear();
        groups=MatchRules.groups(files,matchMode.getSelectedItemPosition(),photosOnly.isChecked());
        rows.clear();int number=0;
        for(List<FileRecord> group:groups){
            number++;rows.add(new Row(group,null,number));
            for(FileRecord e:group)rows.add(new Row(group,e,number));
        }
        adapter.notifyDataSetChanged();updateSelection();
        notice.setText(matchMode.getSelectedItemPosition()==MatchRules.SAME_KB
            ?"Yaklaşık eşleşme: aynı 1 KB aralığı. İçerikler farklı olabilir."
            :"Aynı boyut, aynı fotoğraf demek değildir. Önizlemeyi kontrol et.");
        if(!busy){
            int matches=0;for(List<FileRecord> group:groups)matches+=group.size();
            summary.setText((cancelled.get()?"Durduruldu · ":"")+scannedCount+" dosya · "+groups.size()+" grup / "+matches+" eşleşme"
                +(emptyCount>0?" · "+emptyCount+" boş dosya atlandı":"")
                +(unreadableCount>0?"\n"+unreadableCount+" alan/dosya okunamadı veya Android tarafından kısıtlandı.":""));
            if(files.isEmpty()&&scannedCount==0&&unreadableCount==0)summary.setText("Telefonu tara veya birden fazla klasör ekle.");
            if(!files.isEmpty()&&groups.isEmpty())summary.append("\nBu filtrede eşleşme yok. Diğer eşleşme seçeneklerini deneyebilirsin.");
        }
    }

    private void updateScope(){
        if(scopeLabel==null)return;
        scopeLabel.setText("phone".equals(lastScope)
            ?"Kapsam: telefon + erişilebilen SD kart · tüm alt klasörler"
            :"Kapsam: "+trees.size()+" seçili klasör · tüm alt klasörler");
        foldersLink.setText("Klasörlerim ("+trees.size()+")  ›");
        foldersScan.setEnabled(!busy&&!trees.isEmpty());
    }
    private void setBusy(boolean value,boolean isDeleting){
        busy=value;deleting=isDeleting;
        phoneButton.setText(value&&!isDeleting?"Taramayı durdur":"Telefonu tara");
        phoneButton.setEnabled(!isDeleting);folderButton.setEnabled(!value);foldersLink.setEnabled(!value);
        foldersScan.setEnabled(!value&&!trees.isEmpty());matchMode.setEnabled(!value);photosOnly.setEnabled(!value);
        autoButton.setEnabled(!value);clearButton.setEnabled(!value);progress.setVisibility(value?View.VISIBLE:View.GONE);
        updateSelection();if(adapter!=null)adapter.notifyDataSetChanged();
    }
    private void updateSelection(){
        if(selectionLabel==null)return;
        long bytes=0;for(FileRecord e:files)if(selected.contains(e.id))bytes+=e.size;
        selectionLabel.setText(selected.isEmpty()?"Seçilen dosya yok":selected.size()+" dosya seçildi · "+size(bytes));
        deleteButton.setEnabled(!busy&&!selected.isEmpty());
        deleteButton.setAlpha(deleteButton.isEnabled()?1f:0.45f);
    }
    private void saveProtected(){prefs.edit().putStringSet("protected",new HashSet<>(protectedIds)).apply();}

    private void confirmDelete(){
        if(busy||selected.isEmpty())return;
        for(List<FileRecord> group:groups)if(!MatchRules.leavesOne(group,selected)){toast("Her grupta en az bir dosya kalmalı.");return;}
        List<FileRecord> targets=new ArrayList<>();int protectedCount=0;StringBuilder names=new StringBuilder();
        for(FileRecord e:files)if(selected.contains(e.id)){
            targets.add(e);if(protectedIds.contains(e.id))protectedCount++;
            names.append("• ").append(e.path).append("\n");
        }
        TextView message=text(targets.size()+" dosya kalıcı olarak silinecek. Geri alma yok."
            +(protectedCount>0?"\n\n“Silme” işaretli "+protectedCount+" dosyayı elle seçtin. Bunlar da silinecek.":"")
            +"\n\n"+names,14,INK);message.setPadding(dp(20),dp(12),dp(20),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(message);
        new AlertDialog.Builder(this).setTitle("Seçilen dosyaları sil?").setView(scroll)
            .setNegativeButton("Vazgeç",null).setPositiveButton("Kalıcı olarak sil",(d,w)->{
                setBusy(true,true);summary.setText("Seçilen dosyalar siliniyor…");
                worker.execute(()->{
                    Set<String> deleted=new HashSet<>();int changed=0;
                    for(FileRecord e:targets){
                        try{
                            if(!unchanged(e)){changed++;continue;}
                            boolean success=e.filePath!=null?new File(e.filePath).delete():DocumentsContract.deleteDocument(getContentResolver(),Uri.parse(e.uri));
                            if(success)deleted.add(e.id);
                        }catch(Exception ignored){}
                    }
                    final int skipped=changed;
                    runOnUiThread(()->{
                        if(isDestroyed())return;
                        files.removeIf(e->deleted.contains(e.id));protectedIds.removeAll(deleted);saveProtected();
                        setBusy(false,false);rebuild();
                        summary.setText(deleted.size()+" dosya silindi · "+(targets.size()-deleted.size())+" silinmedi"
                            +(skipped>0?"\n"+skipped+" dosya taramadan sonra değiştiği için korunarak atlandı.":""));
                    });
                });
            }).show();
    }
    private boolean unchanged(FileRecord e) throws Exception {
        if(e.filePath!=null){
            File file=new File(e.filePath);
            return file.isFile()&&file.length()==e.size&&file.lastModified()==e.modified;
        }
        String[] projection={DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try(Cursor c=getContentResolver().query(Uri.parse(e.uri),projection,null,null,null)){
            return c!=null&&c.moveToFirst()&&!c.isNull(0)&&c.getLong(0)==e.size
                &&(e.modified==0||c.getLong(1)==e.modified);
        }
    }

    private final class ResultsAdapter extends BaseAdapter {
        public int getCount(){return rows.size();}
        public Object getItem(int position){return rows.get(position);}
        public long getItemId(int position){return position;}
        @Override public int getViewTypeCount(){return 2;}
        @Override public int getItemViewType(int position){return rows.get(position).file==null?0:1;}
        public View getView(int position,View convert,ViewGroup parent){
            Row item=rows.get(position);
            if(item.file==null){
                TextView header=convert instanceof TextView?(TextView)convert:text("",13,TEAL);
                header.setTypeface(null,Typeface.BOLD);header.setPadding(dp(10),dp(16),dp(8),dp(8));
                header.setText("GRUP "+item.number+"   ·   "+item.group.size()+" dosya   ·   "
                    +(matchMode.getSelectedItemPosition()==MatchRules.SAME_KB?item.group.get(0).size/1024+" KB aralığı":size(item.group.get(0).size)));
                return header;
            }
            Holder h;
            if(convert==null){
                h=new Holder();LinearLayout card=column();card.setPadding(dp(10),dp(8),dp(10),dp(8));card.setBackground(shape(Color.WHITE,10));
                LinearLayout main=row();card.addView(main);
                h.select=new CheckBox(MainActivity.this);main.addView(h.select,new LinearLayout.LayoutParams(dp(38),dp(64)));
                h.image=new ImageView(MainActivity.this);h.image.setScaleType(ImageView.ScaleType.CENTER_CROP);h.image.setBackground(shape(0xffE8EFF3,8));
                main.addView(h.image,new LinearLayout.LayoutParams(dp(64),dp(64)));
                LinearLayout words=column();words.setPadding(dp(10),0,0,0);main.addView(words,new LinearLayout.LayoutParams(0,-2,1));
                h.name=text("",14,INK);h.name.setTypeface(null,Typeface.BOLD);h.name.setMaxLines(2);h.name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);words.addView(h.name);
                h.path=text("",11,MUTED);h.path.setMaxLines(2);h.path.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);words.addView(h.path);
                h.size=text("",11,MUTED);words.addView(h.size);
                LinearLayout actions=row();card.addView(actions);
                h.protect=new CheckBox(MainActivity.this);h.protect.setText("Silme · hatırla");h.protect.setTextSize(12);h.protect.setTextColor(TEAL);
                actions.addView(h.protect,new LinearLayout.LayoutParams(0,dp(40),1));
                h.open=button("Aç",actions,Color.WHITE,TEAL);
                LinearLayout wrapper=column();wrapper.setPadding(0,0,0,dp(4));wrapper.addView(card);wrapper.setTag(h);convert=wrapper;
            }else h=(Holder)convert.getTag();
            final Holder holder=h;FileRecord e=item.file;
            h.name.setText(e.name);h.path.setText(e.path);h.size.setText(size(e.size)+" · "+e.size+" bayt"+(e.deletable?"":" · Salt okunur"));
            h.select.setOnCheckedChangeListener(null);h.select.setChecked(selected.contains(e.id));h.select.setEnabled(!busy&&e.deletable);
            h.select.setOnCheckedChangeListener((v,checked)->{
                if(checked){
                    selected.add(e.id);
                    if(!MatchRules.leavesOne(item.group,selected)){selected.remove(e.id);holder.select.setChecked(false);toast("Bu grupta bir dosya bırak.");return;}
                }else selected.remove(e.id);
                updateSelection();
            });
            h.protect.setOnCheckedChangeListener(null);h.protect.setChecked(protectedIds.contains(e.id));h.protect.setEnabled(!busy);
            h.protect.setOnCheckedChangeListener((v,checked)->{
                if(checked){protectedIds.add(e.id);selected.remove(e.id);holder.select.setChecked(false);}
                else {protectedIds.remove(e.id);protectedIds.remove(e.uri);}
                saveProtected();updateSelection();
            });
            h.open.setEnabled(!busy);h.open.setOnClickListener(v->open(e));h.image.setOnClickListener(v->{if(!busy)open(e);});
            loadThumbnail(e,h.image);
            return convert;
        }
    }
    private static final class Holder {CheckBox select,protect;ImageView image;TextView name,path,size;Button open;}
    private void loadThumbnail(FileRecord file,ImageView image){
        image.setTag(file.id);image.setImageDrawable(null);
        if(!MatchRules.isPhoto(file)){image.setImageResource(android.R.drawable.ic_menu_save);return;}
        Bitmap cached=thumbs.get(file.id);
        if(cached!=null){image.setImageBitmap(cached);return;}
        image.setImageResource(android.R.drawable.ic_menu_gallery);
        images.execute(()->{
            Bitmap bitmap=decode(file,dp(80));
            if(bitmap!=null){
                thumbs.put(file.id,bitmap);
                runOnUiThread(()->{if(!isDestroyed()&&file.id.equals(image.getTag()))image.setImageBitmap(bitmap);});
            }
        });
    }
    private InputStream stream(FileRecord file)throws IOException{
        return file.filePath!=null?new FileInputStream(file.filePath):getContentResolver().openInputStream(Uri.parse(file.uri));
    }
    private Bitmap decode(FileRecord file,int target){
        try{
            BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
            try(InputStream in=stream(file)){BitmapFactory.decodeStream(in,null,options);}
            int sample=1;while(options.outWidth/sample>target*2||options.outHeight/sample>target*2)sample*=2;
            options.inJustDecodeBounds=false;options.inSampleSize=sample;
            try(InputStream in=stream(file)){return BitmapFactory.decodeStream(in,null,options);}
        }catch(Exception|OutOfMemoryError e){return null;}
    }
    private void open(FileRecord file){
        if(MatchRules.isPhoto(file)){
            ImageView image=new ImageView(this);image.setAdjustViewBounds(true);image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setMinimumHeight(dp(180));image.setBackgroundColor(0xff13232E);
            LinearLayout panel=column();panel.addView(image,new LinearLayout.LayoutParams(-1,dp(360)));
            TextView path=text(file.path+"\n"+size(file.size),12,INK);path.setPadding(dp(16),dp(10),dp(16),dp(10));panel.addView(path);
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle(file.name).setView(panel).setPositiveButton("Kapat",null).create();dialog.show();
            images.execute(()->{
                Bitmap bitmap=decode(file,Math.min(1600,getResources().getDisplayMetrics().widthPixels));
                runOnUiThread(()->{if(!isDestroyed()&&dialog.isShowing()){if(bitmap!=null)image.setImageBitmap(bitmap);else toast("Bu fotoğrafın önizlemesi açılamadı.");}});
            });
        }else{
            try{
                Uri uri=file.filePath!=null?FileProvider.getUriForFile(this,getPackageName()+".files",new File(file.filePath)):Uri.parse(file.uri);
                startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri,file.mime==null?"*/*":file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            }catch(Exception e){toast("Bu dosya için uygun görüntüleyici bulunamadı.");}
        }
    }

    private String treeLabel(Uri uri){
        try{
            String id=DocumentsContract.getTreeDocumentId(uri);
            return id.replace("primary:","Telefon/").replace(':','/');
        }catch(Exception e){return "Seçili klasör";}
    }
    private String mime(String name){
        int dot=name.lastIndexOf('.');String type=dot<0?null:MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot+1).toLowerCase(Locale.ROOT));
        return type==null?"application/octet-stream":type;
    }
    private String size(long bytes){
        if(bytes>=1073741824L)return String.format(Locale.forLanguageTag("tr"),"%.2f GB",bytes/1073741824.0);
        if(bytes>=1048576)return String.format(Locale.forLanguageTag("tr"),"%.2f MB",bytes/1048576.0);
        return String.format(Locale.forLanguageTag("tr"),"%.2f KB",bytes/1024.0);
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private TextView text(String value,int sp,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setPadding(0,dp(2),0,dp(2));return t;}
    private void add(LinearLayout parent,View view,int top){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(top);parent.addView(view,p);}
    private Button button(String value,LinearLayout parent,int background,int foreground){
        Button b=new Button(this);b.setText(value);b.setTextSize(12);b.setAllCaps(false);b.setTextColor(foreground);
        b.setBackgroundTintList(null);b.setBackground(shape(background,10));b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));b.setPadding(dp(8),dp(6),dp(8),dp(6));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,-2,1);p.setMargins(dp(3),dp(4),dp(3),dp(4));parent.addView(b,p);return b;
    }
    private void toast(String value){Toast.makeText(this,value,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){cancelled.set(true);worker.shutdownNow();images.shutdownNow();super.onDestroy();}
}
