package tr.temizsec;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;

public class MatchRulesTest {
    private FileRecord f(String id,String name,long size,boolean deletable) {
        return new FileRecord(id,"content://"+id,null,name,id+"/"+name,"image/jpeg",size,0,deletable);
    }
    @Test public void defaultFindsRenamedCopiesAndDeduplicatesOverlappingFolders() {
        FileRecord a=f("a","photo.jpg",392323,true),b=f("b","photo (1).jpg",392323,true);
        List<List<FileRecord>> g=MatchRules.groups(Arrays.asList(a,b,a),0,false);
        assertEquals(1,g.size());assertEquals(2,g.get(0).size());
        assertEquals(0,MatchRules.groups(Arrays.asList(a,b),1,false).size());
        assertEquals(1,MatchRules.groups(Arrays.asList(a,b),2,false).size());
    }
    @Test public void exactBytesAndApproximateKbAreSeparate() {
        List<FileRecord> files=Arrays.asList(f("a","a.jpg",2048,true),f("b","b.jpg",2050,true));
        assertTrue(MatchRules.groups(files,0,false).isEmpty());
        assertEquals(1,MatchRules.groups(files,3,false).size());
    }
    @Test public void emptyFilesAreExcludedAndDifferentNameModeRequiresDifferentNames() {
        List<FileRecord> empty=Arrays.asList(f("a","a",0,true),f("b","b",0,true));
        assertTrue(MatchRules.groups(empty,0,false).isEmpty());
        List<FileRecord> same=Arrays.asList(f("a","a.jpg",10,true),f("b","a.jpg",10,true));
        assertEquals(1,MatchRules.groups(same,1,false).size());
        assertTrue(MatchRules.groups(same,2,false).isEmpty());
    }
    @Test public void bulkSelectionProtectsRememberedFilesAndKeepsAtLeastOne() {
        List<FileRecord> group=Arrays.asList(f("a","a",100,true),f("b","b",100,true),f("c","c",100,true));
        Set<String> selected=MatchRules.selectExtras(Collections.singletonList(group),new HashSet<>(Arrays.asList("a","b")));
        assertEquals(Collections.singleton("c"),selected);
        assertTrue(MatchRules.leavesOne(group,selected));
        selected=MatchRules.selectExtras(Collections.singletonList(group),Collections.emptySet());
        assertEquals(2,selected.size());assertTrue(MatchRules.leavesOne(group,selected));
    }
    @Test public void nonDeletableEntryCanBeTheRetainedCopy() {
        List<FileRecord> group=Arrays.asList(f("a","a",10,true),f("b","b",10,false));
        assertEquals(Collections.singleton("a"),MatchRules.selectExtras(Collections.singletonList(group),Collections.emptySet()));
        assertFalse(MatchRules.leavesOne(group,new HashSet<>(Arrays.asList("a","b"))));
    }
    @Test public void photoFilterSkipsOtherFiles() {
        FileRecord document=new FileRecord("c","",null,"report.pdf","report.pdf","application/pdf",100,0,true);
        assertTrue(MatchRules.groups(Arrays.asList(f("a","a.jpg",100,true),document),0,true).isEmpty());
        assertEquals(1,MatchRules.groups(Arrays.asList(f("a","a.jpg",100,true),document),0,false).size());
    }
}
