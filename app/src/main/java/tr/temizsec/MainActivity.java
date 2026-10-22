package tr.temizsec;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
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
    private ScanRepository repository;
    private final Handler scanHandler=new Handler(Looper.getMainLooper());
    private long loadedJob,requestStarted;
    private boolean loadPending,scanRequested,resumeRequested;
    private TextView restartLink;
    private final Runnable scanMonitor=new Runnable(){
        public void run(){refreshScan();scanHandler.postDelayed(this,1000);}
    };
    private final List<FileRecord> files=new ArrayList<>();
    private List<List<FileRecord>> groups=new ArrayList<>();
    private final List<Row> rows=new ArrayList<>();
    private final Set<String> selected=new HashSet<>();
    private Set<String> protectedIds, trees;
    private SharedPreferences prefs;
    private TextView scopeLabel, summary, selectionLabel, notice, foldersLink;
    private LinearLayout folderActions;
    private Button phoneButton, folderButton, foldersScan, autoButton, clearButton, deleteButton;
    private Spinner matchMode;
    private CleanupAnalyzer.Result analysis=new CleanupAnalyzer.Result();
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
        repository=ScanRepository.get(this);
        protectedIds=new HashSet<>(prefs.getStringSet("protected",Collections.emptySet()));
        trees=new LinkedHashSet<>(prefs.getStringSet("trees",Collections.emptySet()));
        String old=prefs.getString("tree",null);
        if(trees.isEmpty()&&old!=null){trees.add(old);saveTrees();}
        lastScope=prefs.getString("scope","folders");
        buildUi();
        updateScope();
        // Opening or rotating the screen never starts a new scan.
        refreshScan();
    }

    private void buildUi() {
        LinearLayout root=column();root.setPadding(dp(16),dp(10),dp(16),dp(8));root.setBackgroundColor(BG);
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v,insets)->{
            root.setPadding(dp(16),dp(10)+insets.getSystemWindowInsetTop(),dp(16),dp(8)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title=text("Temiz Seç",27,INK);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        root.addView(text("Kopyaları bul. Temizlenecek dosyaları seç.",13,MUTED));
        LinearLayout scope=column();scope.setPadding(dp(12),dp(8),dp(12),dp(8));scope.setBackground(shape(Color.WHITE,12));
        add(root,scope,8);
        scopeLabel=text("",13,INK);scope.addView(scopeLabel);
        LinearLayout actions=row();scope.addView(actions);
        phoneButton=button("Telefonu tara",actions,TEAL,Color.WHITE);
        folderButton=button("Klasör ekle",actions,0xffE5F2EF,TEAL);
        phoneButton.setOnClickListener(v->{
            if(busy){if(!deleting){ScanService.pause(this);summary.setText("Tarama duraklatılıyor…");}return;}
            if(repository.state().pending())resumeScan();else requestPhoneScan();
        });
        folderButton.setOnClickListener(v->chooseFolder());
        restartLink=text("Yarım taramayı bırakıp yeni tarama başlat",12,TEAL);
        restartLink.setPadding(dp(4),dp(8),dp(4),dp(8));restartLink.setVisibility(View.GONE);scope.addView(restartLink);
        restartLink.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Baştan taransın mı?")
            .setMessage("Yarım taramanın listesi yenilenir. Tamamlanan dosya karşılaştırmaları yeniden kullanılabilir. “Silme” işaretlerin korunur.")
            .setNegativeButton("Vazgeç",null).setPositiveButton("Yeni tarama",(d,w)->requestPhoneScan()).show());
        folderActions=row();scope.addView(folderActions);
        foldersLink=text("",12,TEAL);foldersLink.setPadding(dp(4),dp(8),dp(4),dp(8));
        folderActions.addView(foldersLink,new LinearLayout.LayoutParams(0,-2,1));
        foldersLink.setOnClickListener(v->manageFolders());
        foldersScan=button("Klasörleri tara",folderActions,Color.WHITE,TEAL);
        foldersScan.setOnClickListener(v->startFolderScan());

        matchMode=new Spinner(this);
        String[] filters=categoryLabels();
        matchMode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,filters));
        matchMode.setBackground(shape(Color.WHITE,10));
        add(root,matchMode,8);
        notice=text("Aynı içerikteki fotoğraf, video ve diğer dosyalar.",12,MUTED);
        root.addView(notice);
        LinearLayout bulk=row();autoButton=button("Bir kopya kalsın",bulk,0xffE5F2EF,TEAL);
        clearButton=button("Seçimi temizle",bulk,Color.WHITE,MUTED);add(root,bulk,4);
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
                if("folders".equals(lastScope)){files.clear();analysis=new CleanupAnalyzer.Result();updateCategories();rebuild();summary.setText("Klasör listesi değişti. Yeniden tara.");}
            }).show();
    }

    private void startPhoneScan(){
        if(busy)return;if(!hasStorageAccess()){requestPhoneScan();return;}
        if(resumeRequested){resumeRequested=false;startServiceScan(true,true);return;}
        lastScope="phone";prefs.edit().putString("scope",lastScope).apply();startServiceScan(false,true);
    }
    private void startFolderScan(){
        if(busy)return;if(trees.isEmpty()){chooseFolder();return;}
        lastScope="folders";prefs.edit().putString("scope",lastScope).apply();startServiceScan(false,false);
    }
    private void resumeScan(){
        boolean phone="phone".equals(repository.state().mode);
        if(phone&&!hasStorageAccess()){resumeRequested=true;requestPhoneScan();return;}
        startServiceScan(true,phone);
    }
    private void startServiceScan(boolean resume,boolean phone){
        if(busy||ScanService.active)return;
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},30);
        files.clear();analysis=new CleanupAnalyzer.Result();selected.clear();loadedJob=0;updateCategories();
        scanRequested=true;requestStarted=SystemClock.elapsedRealtime();setBusy(true,false);rebuild();
        restartLink.setVisibility(View.GONE);summary.setText(resume?"Kaydedilen yerden devam ediliyor…":"Tarama başlatılıyor…");
        try{ScanService.start(this,resume,phone);}
        catch(RuntimeException e){scanRequested=false;setBusy(false,false);summary.setText("Arka plan taraması başlatılamadı. Uygulama açıkken yeniden dene.");}
    }
    @Override protected void onStart(){super.onStart();scanHandler.post(scanMonitor);}
    @Override protected void onStop(){scanHandler.removeCallbacks(scanMonitor);super.onStop();}
    private void refreshScan(){
        if(repository==null||adapter==null||deleting)return;
        ScanRepository.State state=repository.state();
        boolean active=ScanService.active;
        if(scanRequested&&!active&&SystemClock.elapsedRealtime()-requestStarted<5000)return;
        if(active){
            scanRequested=false;if(!busy)setBusy(true,false);
            phoneButton.setText("Duraklat");restartLink.setVisibility(View.GONE);
            summary.setText(state.phase+(state.total>0?" · "+state.done+" / "+state.total:state.done>0?" · "+state.done+" dosya":""));
            if(state.total>0){progress.setIndeterminate(false);progress.setMax((int)state.total);progress.setProgress((int)state.done);}
            else progress.setIndeterminate(true);
            return;
        }
        scanRequested=false;if(busy)setBusy(false,false);
        restartLink.setVisibility(state.pending()?View.VISIBLE:View.GONE);
        if(state.pending()){
            phoneButton.setText("Devam et");
            summary.setText("Tarama kaydedildi. Devam et düğmesiyle kaldığın yerden sürdürebilirsin.\n"
                +state.phase+(state.total>0?" · "+state.done+" / "+state.total:state.done>0?" · "+state.done+" dosya":""));
        }else{
            phoneButton.setText(state.complete()?"Yeniden tara":"Telefonu tara");
            if(state.complete()&&state.job!=loadedJob&&!loadPending){
                loadPending=true;long job=state.job;
                worker.execute(()->{
                    ScanRepository.Snapshot saved=repository.snapshot();
                    runOnUiThread(()->{
                        loadPending=false;
                        if(isDestroyed()||ScanService.active||scanRequested||repository.state().job!=job)return;
                        files.clear();files.addAll(saved.files);analysis=saved.result;loadedJob=job;
                        scannedCount=files.size();unreadableCount=state.skipped;
                        for(FileRecord file:files)if(protectedIds.contains(file.uri))protectedIds.add(file.id);
                        saveProtected();updateCategories();rebuild();
                    });
                });
            }
        }
    }

    private boolean keepsCopy(){return matchMode.getSelectedItemPosition()<2;}
    private String[] categoryLabels(){
        return new String[]{
            "Birebir kopyalar · "+analysis.duplicates.size()+" grup",
            "Benzer fotoğraflar · "+analysis.similar.size()+" grup",
            "Eski dosyalar (6 ay) · "+analysis.old.size(),
            "Geçici / boş dosyalar · "+analysis.junk.size(),
            "Büyük dosyalar (100 MB+) · "+analysis.large.size()
        };
    }
    private void updateCategories(){
        int position=Math.max(0,matchMode.getSelectedItemPosition());
        matchMode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,categoryLabels()));
        matchMode.setSelection(position);
    }
    private void rebuild(){
        if(adapter==null)return;
        selected.clear();int category=matchMode.getSelectedItemPosition();
        if(category==0)groups=new ArrayList<>(analysis.duplicates);
        else if(category==1)groups=new ArrayList<>(analysis.similar);
        else{
            List<FileRecord> candidates=category==2?analysis.old:category==3?analysis.junk:analysis.large;
            groups=new ArrayList<>();
            for(FileRecord file:candidates)groups.add(Collections.singletonList(file));
        }
        rows.clear();int number=0;
        for(List<FileRecord> group:groups){
            number++;if(category<2)rows.add(new Row(group,null,number));
            for(FileRecord file:group)rows.add(new Row(group,file,number));
        }
        autoButton.setVisibility(category<2?View.VISIBLE:View.GONE);
        String[] notes={
            "İçeriği birebir aynı olan dosyalar. Her grupta bir kopya kalır.",
            "Görüntü benzerliği tahminidir; farklı kareler olabilir. Önizlemeyi kontrol et.",
            "Son değişikliği 6 aydan eski. Eski olması gereksiz olduğu anlamına gelmez.",
            "En az 2 gündür değişmeyen geçici veya boş dosya adayları. Silmeden kontrol et.",
            "En az 100 MB alan kaplayan dosyalar. İhtiyacın olmayanları tek tek seç."
        };
        notice.setText(notes[Math.max(0,category)]);
        adapter.notifyDataSetChanged();updateSelection();
        if(!busy){
            int matches=0,photos=0,videos=0;for(List<FileRecord> group:groups)matches+=group.size();
            for(FileRecord file:files){if(MatchRules.isPhoto(file))photos++;else if(isVideo(file))videos++;}
            summary.setText(files.size()+" dosya · "+photos+" fotoğraf · "+videos+" video\n"
                +matches+" sonuç"+(category<2?" / "+groups.size()+" grup":"")
                +(unreadableCount>0?" · "+unreadableCount+" kısıtlı/okunamayan alan":"")
                +(analysis.failedReads>0?"\n"+analysis.failedReads+" dosya içeriği okunamadı veya değişti.":""));
            if(files.isEmpty()&&unreadableCount==0)summary.setText("Telefonu tara veya klasör ekle.");
            if(!files.isEmpty()&&groups.isEmpty())summary.append("\nBu kategoride sonuç yok. Diğer kategorilere bakabilirsin.");
        }
    }
    private void removeAnalysedFiles(Set<String> ids){
        for(List<FileRecord> group:analysis.duplicates)group.removeIf(f->ids.contains(f.id));
        for(List<FileRecord> group:analysis.similar)group.removeIf(f->ids.contains(f.id));
        analysis.duplicates.removeIf(g->g.size()<2);analysis.similar.removeIf(g->g.size()<2);
        analysis.old.removeIf(f->ids.contains(f.id));analysis.junk.removeIf(f->ids.contains(f.id));analysis.large.removeIf(f->ids.contains(f.id));
    }

    private void updateScope(){
        if(scopeLabel==null)return;
        scopeLabel.setText("phone".equals(lastScope)
            ?"Telefon + erişilebilen SD kart · tüm alt klasörler"
            :"Kapsam: "+trees.size()+" seçili klasör · tüm alt klasörler");
        foldersLink.setText("Klasörlerim ("+trees.size()+")  ›");
        folderActions.setVisibility(trees.isEmpty()?View.GONE:View.VISIBLE);
        foldersScan.setEnabled(!busy&&!trees.isEmpty());
    }
    private void setBusy(boolean value,boolean isDeleting){
        busy=value;deleting=isDeleting;
        phoneButton.setText(value&&!isDeleting?"Duraklat":"Telefonu tara");
        phoneButton.setEnabled(!isDeleting);folderButton.setEnabled(!value);foldersLink.setEnabled(!value);
        foldersScan.setEnabled(!value&&!trees.isEmpty());matchMode.setEnabled(!value);
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
        for(List<FileRecord> group:groups)if(keepsCopy()&&!MatchRules.leavesOne(group,selected)){toast("Her grupta en az bir dosya kalmalı.");return;}
        List<FileRecord> targets=new ArrayList<>();int protectedCount=0;StringBuilder names=new StringBuilder();
        for(FileRecord e:files)if(selected.contains(e.id)){
            targets.add(e);if(protectedIds.contains(e.id))protectedCount++;
            names.append("• ").append(e.path).append("\n");
        }
        TextView message=text(targets.size()+" dosya kalıcı olarak silinecek. Geri alma yok."
            +(matchMode.getSelectedItemPosition()==1?"\n\nBenzer fotoğraflar farklı kareler olabilir. Önizlemeleri kontrol ettiğinden emin ol.":"")
            +(protectedCount>0?"\n\n“Silme” işaretli "+protectedCount+" dosyayı elle seçtin. Bunlar da silinecek.":"")
            +"\n\n"+names,14,INK);message.setPadding(dp(20),dp(12),dp(20),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(message);
        new AlertDialog.Builder(this).setTitle("Seçilen dosyaları sil?").setView(scroll)
            .setNegativeButton("Vazgeç",null).setPositiveButton("Kalıcı olarak sil",(d,w)->{
                setBusy(true,true);summary.setText("Seçilen dosyalar siliniyor…");
                worker.execute(()->{
                    Set<String> deleted=new HashSet<>();int changed=0;
                    for(FileRecord e:targets){
                        if(Thread.currentThread().isInterrupted())break;
                        try{
                            if(!unchanged(e)){changed++;continue;}
                            boolean success=e.filePath!=null?new File(e.filePath).delete():DocumentsContract.deleteDocument(getContentResolver(),Uri.parse(e.uri));
                            if(success)deleted.add(e.id);
                        }catch(Exception ignored){}
                    }
                    final int skipped=changed;
                    runOnUiThread(()->{
                        if(isDestroyed())return;
                        repository.remove(deleted);files.removeIf(e->deleted.contains(e.id));removeAnalysedFiles(deleted);protectedIds.removeAll(deleted);saveProtected();
                        setBusy(false,false);updateCategories();rebuild();
                        summary.setText(deleted.size()+" dosya silindi · "+(targets.size()-deleted.size())+" silinmedi"
                            +(skipped>0?"\n"+skipped+" dosya taramadan sonra değiştiği için korunarak atlandı.":""));
                    });
                });
            }).show();
    }
    private boolean unchanged(FileRecord file)throws Exception{return ScanService.isUnchanged(this,file);}

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
                long bytes=0;for(FileRecord f:item.group)bytes+=f.size;
                header.setText((matchMode.getSelectedItemPosition()==0?"KOPYA GRUBU ":"BENZER GRUBU ")+item.number+" · "+item.group.size()+" dosya · "+size(bytes));
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
            h.name.setText(e.name);h.path.setText(e.path);h.size.setText(size(e.size)+(isVideo(e)?" · Video":"")+(e.deletable?"":" · Salt okunur"));
            h.select.setOnCheckedChangeListener(null);h.select.setChecked(selected.contains(e.id));h.select.setEnabled(!busy&&e.deletable);
            h.select.setOnCheckedChangeListener((v,checked)->{
                if(checked){
                    selected.add(e.id);
                    if(keepsCopy()&&!MatchRules.leavesOne(item.group,selected)){selected.remove(e.id);holder.select.setChecked(false);toast("Bu grupta bir dosya bırak.");return;}
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
        String key=file.id+":"+file.size+":"+file.modified;
        image.setTag(key);image.setImageDrawable(null);
        if(!MatchRules.isPhoto(file)){image.setImageResource(isVideo(file)?android.R.drawable.ic_media_play:android.R.drawable.ic_menu_save);return;}
        Bitmap cached=thumbs.get(key);
        if(cached!=null){image.setImageBitmap(cached);return;}
        image.setImageResource(android.R.drawable.ic_menu_gallery);
        images.execute(()->{
            Bitmap bitmap=decode(file,dp(80));
            if(bitmap!=null){
                thumbs.put(key,bitmap);
                runOnUiThread(()->{if(!isDestroyed()&&key.equals(image.getTag()))image.setImageBitmap(bitmap);});
            }
        });
    }
    private Bitmap decode(FileRecord file,int target){return ScanImages.decode(this,file,target);}
    private boolean isVideo(FileRecord file){
        if(file.mime!=null&&file.mime.startsWith("video/"))return true;
        return file.name.toLowerCase(Locale.ROOT).matches(".*\\.(mp4|mkv|mov|avi|webm|3gp|m4v)$");
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
    @Override protected void onDestroy(){scanHandler.removeCallbacks(scanMonitor);worker.shutdownNow();images.shutdownNow();super.onDestroy();}
}
