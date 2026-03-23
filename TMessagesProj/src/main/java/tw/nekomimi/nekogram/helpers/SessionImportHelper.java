package tw.nekomimi.nekogram.helpers;

public class SessionImportHelper {

    public static final int REQUEST_PICK = 7002;

    public enum ImportMode { IMPORT_AS_FIRST, IMPORT_AS_SECOND }

    public static volatile ImportMode pendingMode = ImportMode.IMPORT_AS_FIRST;
}