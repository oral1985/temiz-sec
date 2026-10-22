package tr.temizsec;

/** Scan snapshot; deliberately independent of Android for matching tests. */
public final class FileRecord {
    public final String id, uri, filePath, name, path, mime;
    public final long size, modified;
    public final boolean deletable;
    public FileRecord(String id, String uri, String filePath, String name, String path,
                      String mime, long size, long modified, boolean deletable) {
        this.id=id; this.uri=uri; this.filePath=filePath; this.name=name;
        this.path=path; this.mime=mime; this.size=size; this.modified=modified; this.deletable=deletable;
    }
}
