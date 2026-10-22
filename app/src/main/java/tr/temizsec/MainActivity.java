package tr.temizsec;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Entry> files = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private Set<String> protectedIds;
    private android.content.SharedPreferences prefs;
    private LinearLayout root, list;
    private TextView status, selectedLabel, folderLabel;
    private Button scan, choose, auto, clear, delete;
    private Spinner mode;
    private Uri tree;
    private boolean busy;
    private final Map<String, CheckBox> checks = new HashMap<>();
    private final Map<String, String> scanErrors = new LinkedHashMap<>();

    static class Entry {
        final Uri uri;
        final String name, path, mime;
        final long size;
        final boolean deletable;
        Entry(Uri uri, String name, String path, String mime, long size, boolean deletable) {
            this.uri=uri; this.name=name; this.path=path; this.mime=mime; this.size=size; this.deletable=deletable;
        }
        String id() { return uri.toString(); }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs=getSharedPreferences("settings", MODE_PRIVATE);
        protectedIds=new HashSet<>(prefs.getStringSet("protected", Collections.emptySet()));
        String saved=prefs.getString("tree", null);
        if(saved!=null) tree=Uri.parse(saved);
        root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18),dp(16),dp(18),dp(8)); root.setBackgroundColor(Color.rgb(246,248,251));
        setContentView(root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            root.setPadding(dp(18), dp(16)+insets.getSystemWindowInsetTop(),dp(18),dp(8)+insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView title=label("Temiz Seç",26); title.setTextColor(Color.rgb(24,60,86)); root.addView(title);
        root.addView(label("Aynı boyuttaki dosyaları bul, kontrol et, seç.",14));
        folderLabel=label(tree==null?"Başlamak için bir klasör seç.":"Kayıtlı klasör hazır.",13); root.addView(folderLabel);
        LinearLayout row=row(); choose=button("Klasör seç",row); scan=button("Tara",row); root.addView(row);
        choose.setOnClickListener(v -> {
            Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION|Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
            startActivityForResult(intent,10);
        });
        scan.setOnClickListener(v->scan());
        mode=new Spinner(this);
        mode.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Aynı isim + aynı boyut", "Farklı isim + aynı boyut"}));
        root.addView(mode);
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> p,View v,int pos,long id){ selected.clear(); render(); }
            public void onNothingSelected(AdapterView<?> p){}
        });
        TextView note=label("Boyutlar tam bayt üzerinden karşılaştırılır. Aynı boyut, aynı içerik demek değildir. Hiçbir dosya otomatik silinmez.",12);
        root.addView(note);
        row=row(); auto=button("Birini bırak, diğerlerini seç",row); clear=button("Seçimi temizle",row); root.addView(row);
        auto.setOnClickListener(v->{
            selected.clear();
            for(List<Entry> group:groups()) {
                Entry keep=group.get(0);
                for(Entry e:group) if(protectedIds.contains(e.id()) || !e.deletable){ keep=e; break; }
                for(Entry e:group) if(e!=keep && e.deletable && !protectedIds.contains(e.id())) selected.add(e.id());
            }
            render();
        });
        clear.setOnClickListener(v->{ selected.clear(); render(); });
        status=label("Klasör izni yalnızca seçtiğin alan için geçerlidir.",13); root.addView(status);
        ScrollView scroll=new ScrollView(this); list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list); root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        selectedLabel=label("Seçilen: 0",14); root.addView(selectedLabel);
        delete=new Button(this); delete.setText("Seçilenleri sil"); root.addView(delete);
        delete.setOnClickListener(v->confirmDelete());
        if(tree!=null) scan(); else updateSelection();
    }

    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=10 || result!=RESULT_OK || data==null || data.getData()==null) return;
        Uri next=data.getData();
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try { getContentResolver().takePersistableUriPermission(next,flags); }
        catch(SecurityException e){ toast("Kalıcı klasör izni alınamadı. Başka bir klasör seç."); return; }
        Uri previous=tree; tree=next; prefs.edit().putString("tree",tree.toString()).apply();
        if(previous!=null && !previous.equals(next)) {
            try { getContentResolver().releasePersistableUriPermission(previous,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }
            catch(SecurityException ignored){}
        }
        folderLabel.setText("Klasör seçildi. Alt klasörler de taranır."); scan();
    }

    private void scan(){
        if(busy)return;
        if(tree==null){toast("Önce bir klasör seç.");return;}
        setBusy(true); selected.clear(); files.clear(); render(); status.setText("Dosyalar taranıyor…");
        final Uri scanTree=tree;
        worker.execute(()->{
            List<Entry> result=new ArrayList<>(); scanErrors.clear();
            ArrayDeque<String[]> queue=new ArrayDeque<>();
            queue.add(new String[]{DocumentsContract.getTreeDocumentId(scanTree),""});
            Set<String> visited=new HashSet<>();
            while(!queue.isEmpty() && !Thread.currentThread().isInterrupted()) {
                String[] dir=queue.remove(); if(!visited.add(dir[0]))continue;
                Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(scanTree,dir[0]);
                String[] projection={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS};
                try(Cursor cursor=getContentResolver().query(children,projection,null,null,null)) {
                    if(cursor==null){scanErrors.put(dir[0],dir[1]);continue;}
                    while(cursor.moveToNext()) {
                        String id=cursor.getString(0), name=cursor.getString(1), mime=cursor.getString(2);
                        if(name==null)name="İsimsiz";
                        String path=dir[1]+name;
                        if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) queue.add(new String[]{id,path+"/"});
                        else if(!cursor.isNull(3)) result.add(new Entry(DocumentsContract.buildDocumentUriUsingTree(scanTree,id),name,path,mime,cursor.getLong(3),(cursor.getLong(4)&DocumentsContract.Document.FLAG_SUPPORTS_DELETE)!=0));
                    }
                } catch(Exception e){ scanErrors.put(dir[0],dir[1]); }
            }
            runOnUiThread(()->{
                if(isDestroyed())return;
                files.addAll(result); setBusy(false); render();
                status.setText(files.size()+" dosya tarandı. "+groups().size()+" grup bulundu."+(scanErrors.isEmpty()?"":" "+scanErrors.size()+" klasör okunamadı; sonuçlar eksik olabilir."));
            });
        });
    }

    private List<List<Entry>> groups(){
        boolean sameName=mode!=null && mode.getSelectedItemPosition()==0;
        Map<String,List<Entry>> map=new TreeMap<>();
        for(Entry e:files){
            String key=Long.toString(e.size)+(sameName?"\n"+e.name:"");
            map.computeIfAbsent(key,k->new ArrayList<>()).add(e);
        }
        List<List<Entry>> result=new ArrayList<>();
        for(List<Entry> group:map.values()){
            if(group.size()<2)continue;
            if(!sameName){ Set<String> names=new HashSet<>(); for(Entry e:group)names.add(e.name); if(names.size()<2)continue; }
            group.sort(Comparator.comparing(e->e.path)); result.add(group);
        }
        result.sort((a,b)->Long.compare(b.get(0).size,a.get(0).size)); return result;
    }

    private void render(){
        if(list==null)return;
        list.removeAllViews(); checks.clear();
        List<List<Entry>> groups=groups();
        if(groups.isEmpty())list.addView(label(files.isEmpty()?"Tarama sonuçları burada görünecek.":"Bu filtrede eşleşen dosya yok.",15));
        int index=0;
        for(List<Entry> group:groups){
            LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(10),dp(12),dp(10),dp(12)); card.setBackgroundColor(Color.WHITE);
            LinearLayout.LayoutParams cardParams=new LinearLayout.LayoutParams(-1,-2); cardParams.setMargins(0,dp(10),0,0);
            list.addView(card,cardParams);
            card.addView(label("Grup "+(++index)+" · "+group.size()+" dosya · "+size(group.get(0).size),16));
            for(Entry e:group){
                CheckBox check=new CheckBox(this); checks.put(e.id(),check);
                check.setText(e.name+"\n"+e.path+"\n"+e.size+" bayt"+(e.deletable?"":" · Silme izni yok"));
                check.setTextSize(14); check.setChecked(selected.contains(e.id())); check.setEnabled(e.deletable&&!busy);
                card.addView(check);
                check.setOnCheckedChangeListener((v,checked)->{
                    if(checked){
                        boolean allOthers=true;
                        for(Entry other:group)if(!other.id().equals(e.id())&&!selected.contains(other.id())){allOthers=false;break;}
                        if(allOthers){ check.setChecked(false); toast("Her grupta en az bir dosya kalmalı."); return; }
                        selected.add(e.id());
                    } else selected.remove(e.id());
                    updateSelection();
                });
                LinearLayout actions=row(); card.addView(actions);
                Button preview=button(e.mime!=null&&e.mime.startsWith("image/")?"Fotoğrafı aç":"Dosyayı aç",actions);
                preview.setOnClickListener(v->{
                    try{startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(e.uri,e.mime==null?"*/*":e.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));}
                    catch(ActivityNotFoundException ex){toast("Bu dosyayı açabilen uygulama bulunamadı.");}
                });
                CheckBox protect=new CheckBox(this); protect.setText("Silme · hatırla"); protect.setTextSize(12);
                protect.setChecked(protectedIds.contains(e.id())); actions.addView(protect,new LinearLayout.LayoutParams(0,-2,1));
                protect.setOnCheckedChangeListener((v,checked)->{
                    if(checked){protectedIds.add(e.id());check.setChecked(false);}
                    else protectedIds.remove(e.id());
                    prefs.edit().putStringSet("protected",new HashSet<>(protectedIds)).apply();
                    updateSelection();
                });
            }
        }
        updateSelection();
    }

    private void confirmDelete(){
        if(busy || selected.isEmpty())return;
        List<Entry> targets=new ArrayList<>(); StringBuilder names=new StringBuilder(); int protectedCount=0;
        for(Entry e:files) if(selected.contains(e.id())){
            targets.add(e); names.append("• ").append(e.path).append("\n"); if(protectedIds.contains(e.id()))protectedCount++;
        }
        for(List<Entry> group:groups()){
            boolean all=true; for(Entry e:group)if(!selected.contains(e.id())){all=false;break;}
            if(all){toast("Her grupta en az bir dosya bırak."); return;}
        }
        if(targets.isEmpty())return;
        TextView message=label("Seçilen "+targets.size()+" dosya kalıcı olarak silinecek. Geri alma yok.\n"+(protectedCount>0?"\nDikkat: 'Silme' işaretli "+protectedCount+" dosyayı elle seçtin; bunlar da silinecek.\n":"")+"\n"+names,14);
        message.setPadding(dp(20),dp(12),dp(20),dp(12));
        ScrollView scroll=new ScrollView(this); scroll.addView(message);
        new AlertDialog.Builder(this).setTitle("Seçilenleri sil?").setView(scroll)
            .setNegativeButton("Vazgeç",null).setPositiveButton("Kalıcı olarak sil",(d,w)->{
                setBusy(true); status.setText("Seçilen dosyalar siliniyor…");
                worker.execute(()->{
                    Set<String> deleted=new HashSet<>();
                    for(Entry e:targets){try{if(DocumentsContract.deleteDocument(getContentResolver(),e.uri))deleted.add(e.id());}catch(Exception ignored){}}
                    runOnUiThread(()->{
                        if(isDestroyed())return;
                        files.removeIf(e->deleted.contains(e.id())); protectedIds.removeAll(deleted);
                        prefs.edit().putStringSet("protected",new HashSet<>(protectedIds)).apply();
                        selected.clear(); setBusy(false); render();
                        status.setText(deleted.size()+" dosya silindi. "+(targets.size()-deleted.size())+" dosya silinemedi.");
                    });
                });
            }).show();
    }

    private void updateSelection(){
        if(selectedLabel==null)return;
        long bytes=0; for(Entry e:files)if(selected.contains(e.id()))bytes+=e.size;
        selectedLabel.setText("Seçilen: "+selected.size()+" · "+size(bytes));
        if(delete!=null)delete.setEnabled(!busy&&!selected.isEmpty());
    }
    private void setBusy(boolean value){
        busy=value; choose.setEnabled(!value);scan.setEnabled(!value);mode.setEnabled(!value);auto.setEnabled(!value);clear.setEnabled(!value);
        for(CheckBox c:checks.values())c.setEnabled(!value);
        updateSelection();
    }
    private String size(long n){return n>=1048576?String.format(Locale.forLanguageTag("tr"),"%.2f MB",n/1048576.0):String.format(Locale.forLanguageTag("tr"),"%.2f KB",n/1024.0);}
    private int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int sp){TextView v=new TextView(this);v.setText(text);v.setTextSize(sp);v.setPadding(0,dp(3),0,dp(3));return v;}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private Button button(String text,LinearLayout row){Button b=new Button(this);b.setText(text);b.setTextSize(12);b.setAllCaps(false);row.addView(b,new LinearLayout.LayoutParams(0,-2,1));return b;}
    private void toast(String s){Toast.makeText(this,s,Toast.LENGTH_LONG).show();}
    @Override protected void onDestroy(){worker.shutdownNow();super.onDestroy();}
}
