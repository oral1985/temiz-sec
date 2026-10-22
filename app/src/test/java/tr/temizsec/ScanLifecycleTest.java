package tr.temizsec;

import android.app.Application;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class ScanLifecycleTest {
    private Application app;private ScanRepository repository;
    @Before public void prepare(){
        app=RuntimeEnvironment.getApplication();ScanRepository.resetForTests();app.deleteDatabase("scan-checkpoints.db");
        app.getSharedPreferences("settings",0).edit().clear().commit();repository=ScanRepository.get(app);ScanService.active=false;
    }
    @After public void finish(){ScanRepository.resetForTests();}
    private FileRecord file(String id){return new FileRecord(id,"file:///"+id,"/"+id,id,"/"+id,"image/jpeg",10,100,true);}
    @Test public void directoryCheckpointAndSignaturesSurviveDatabaseReopen(){
        ScanRepository.Dir root=new ScanRepository.Dir("root","file","","/root"),child=new ScanRepository.Dir("child","file","","/root/child");
        repository.create("phone",Collections.singletonList(root));long job=repository.state().job;
        FileRecord file=file("a");repository.commitDirectory(root,Collections.singletonList(file),Collections.singletonList(child),2);
        repository.signature(file,"hash","saved-hash");ScanRepository.resetForTests();repository=ScanRepository.get(app);
        assertEquals(job,repository.state().job);assertEquals("child",repository.nextDirectory().key);
        assertEquals(1,repository.fileCount());assertEquals(2,repository.state().skipped);assertEquals("saved-hash",repository.signature(file,"hash"));
        FileRecord changed=new FileRecord("a","file:///a","/a","a","/a","image/jpeg",10,101,true);
        assertNull(repository.signature(changed,"hash"));
    }
    @Test public void openingAndRecreatingActivityNeverStartsThePendingScan(){
        repository.create("phone",Collections.singletonList(new ScanRepository.Dir("root","file","","/root")));
        repository.progress("Benzer fotoğraflar aranıyor",300,1000);long job=repository.state().job;
        app.getSharedPreferences("settings",0).edit().putString("scope","phone").commit();
        ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup();
        assertNull(Shadows.shadowOf(app).getNextStartedService());assertEquals(job,repository.state().job);
        controller.recreate();
        assertNull(Shadows.shadowOf(app).getNextStartedService());assertEquals(300,repository.state().done);assertEquals(job,repository.state().job);
        controller.pause().stop().destroy();
    }
    @Test public void finishedResultsRemainAvailableWithoutAnotherScan(){
        ScanRepository.Dir root=new ScanRepository.Dir("root","file","","/root");repository.create("phone",Collections.singletonList(root));
        FileRecord a=file("a"),b=file("b");repository.commitDirectory(root,Arrays.asList(a,b),Collections.emptyList(),0);
        CleanupAnalyzer.Result result=new CleanupAnalyzer.Result();result.duplicates.add(new ArrayList<>(Arrays.asList(a,b)));repository.complete(result);
        long job=repository.state().job;ScanRepository.resetForTests();repository=ScanRepository.get(app);
        ScanRepository.Snapshot saved=repository.snapshot();assertEquals(2,saved.files.size());assertEquals(1,saved.result.duplicates.size());
        assertTrue(repository.state().complete());assertEquals(job,repository.state().job);assertNull(repository.nextDirectory());
        repository.remove(Collections.singleton("a"));assertTrue(repository.snapshot().result.duplicates.isEmpty());
    }
}
