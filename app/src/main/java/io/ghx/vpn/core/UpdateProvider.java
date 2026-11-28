package io.ghx.vpn.core;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Non-exported, temporary URI grants only; can expose only APKs in this app's private update cache. */
public final class UpdateProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File file(Uri uri) throws FileNotFoundException {
        try {
            if (!"content".equals(uri.getScheme()) || !(getContext().getPackageName() + ".updates").equals(uri.getAuthority())
                    || uri.getPathSegments().size() != 1) throw new Exception();
            String name = uri.getLastPathSegment();
            if (name == null || !name.matches("qinglian-[0-9]+\\.apk")) throw new Exception();
            File dir = new File(getContext().getCacheDir(), "updates").getCanonicalFile();
            File f = new File(dir, name).getCanonicalFile();
            if (!dir.equals(f.getParentFile()) || !f.isFile()) throw new Exception();
            return f;
        } catch (Exception e) { throw new FileNotFoundException("Invalid update file"); }
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = file(uri); String[] columns = projection == null ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor c = new MatrixCursor(columns, 1); Object[] values = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) values[i] = OpenableColumns.DISPLAY_NAME.equals(columns[i]) ? f.getName()
                    : OpenableColumns.SIZE.equals(columns[i]) ? f.length() : null;
            c.addRow(values); return c;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
