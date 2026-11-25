package com.dualtorch.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 极简文件提供者：只暴露 cacheDir/update/ 下的安装包。
 *
 * 没有引入 AndroidX，所以不能用 FileProvider；这里只实现 openFile，
 * 并且 exported=false，只有拿到临时授权的安装器能读。
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.dualtorch.app.apk";
    private static final String DIR = "update";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) {
            throw new FileNotFoundException("非法文件名");
        }
        File f = new File(new File(getContext().getCacheDir(), DIR), name);
        if (!f.exists()) {
            throw new FileNotFoundException(name);
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
