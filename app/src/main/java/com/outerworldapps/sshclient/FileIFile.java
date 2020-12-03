/**
 * Wrapper about File class that implements IFile interface.
 */

//    Copyright (C) 2014, Mike Rieker, Beverly, MA USA
//    www.outerworldapps.com
//
//    This program is free software; you can redistribute it and/or modify
//    it under the terms of the GNU General Public License as published by
//    the Free Software Foundation; version 2 of the License.
//
//    This program is distributed in the hope that it will be useful,
//    but WITHOUT ANY WARRANTY; without even the implied warranty of
//    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//    GNU General Public License for more details.
//
//    EXPECT it to FAIL when someone's HeALTh or PROpeRTy is at RISk.
//
//    You should have received a copy of the GNU General Public License
//    along with this program; if not, write to the Free Software
//    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
//    http://www.gnu.org/licenses/gpl-2.0.html

package com.outerworldapps.sshclient;


import android.net.Uri;
import android.os.Environment;
import android.support.annotation.NonNull;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;

public class FileIFile extends IFile {
    public final static String TAG = "SshClient";

    private File file;
    private SshClient context;
    private String abspath;
    private String[] dirwords;

    public FileIFile (@NonNull SshClient ctx, @NonNull File f)
    {
        context  = ctx;
        file     = f;
        abspath  = FileUtils.stripDots (file.getAbsolutePath ());
        dirwords = makeDirWords (abspath);
    }

    // file is one of this app's directories
    // or a parent of them all the way to root
    public boolean isKnownReadable ()
    {
        if (abspath.equals ("/")) {
            Log.w (TAG, "isKnownReadable*: root dir");
        }
        KnownReadable kr = getKnownReadablesRoot ();
        for (String dirword : dirwords) {
            kr = kr.childs.get (dirword);
            if (kr == null) return false;
        }
        return true;
    }

    private KnownReadable getKnownReadablesRoot ()
    {
        KnownReadable kr = context.knownReadables;
        if (kr == null) {
            kr = new KnownReadable ();
            kr.name = "";

            addReadable (kr, context.getCacheDir ());
            addReadable (kr, context.getExternalCacheDir ());
            addReadable (kr, context.getFilesDir ());
            addReadable (kr, context.getExternalFilesDir (null));

            addReadable (kr, Environment.getDataDirectory ());
            addReadable (kr, Environment.getDownloadCacheDirectory ());
            addReadable (kr, Environment.getExternalStorageDirectory ());
            addReadable (kr, Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_ALARMS));
            addReadable (kr, Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_DCIM));
            addReadable (kr, Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_DOWNLOADS));
            addReadable (kr, Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_MOVIES));
            addReadable (kr, Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_MUSIC));
            addReadable (kr, Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_NOTIFICATIONS));
            addReadable (kr, Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_PICTURES));
            addReadable (kr, Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_PODCASTS));
            addReadable (kr, Environment.getExternalStoragePublicDirectory (Environment.DIRECTORY_RINGTONES));
            addReadable (kr, Environment.getRootDirectory ());

            kr.log ("getKnownReadablesRoot*: ");

            context.knownReadables = kr;
        }
        return kr;
    }

    private static void addReadable (KnownReadable kr, File dir)
    {
        if (dir != null) {
            addReadable (kr, FileUtils.stripDots (dir.getAbsolutePath ()));
            try {
                addReadable (kr, FileUtils.stripDots (dir.getCanonicalPath ()));
            } catch (IOException ignored) {
            }
        }
    }

    private static void addReadable (KnownReadable kr, String path)
    {
        String[] dirwords = makeDirWords (path);
        for (String dirword : dirwords) {
            KnownReadable childkr = kr.childs.get (dirword);
            if (childkr == null) {
                childkr = new KnownReadable ();
                childkr.name = dirword;
                kr.childs.put (dirword, childkr);
            }
            kr = childkr;
        }
    }

    private static String[] makeDirWords (String abspath)
    {
        return abspath.equals ("/") ? new String[0] : abspath.substring (1).split ("/");
    }

    /***********\
     *  IFile  *
    \***********/

    public boolean canRead () { return isKnownReadable () || file.canRead (); }
    public boolean canWrite () { return file.canWrite (); }
    public boolean exists () { return isKnownReadable () || file.exists (); }
    public Uri getUri () { return Uri.fromFile (file); }

    public long length () throws IOException
    {
        if (!file.exists ()) throw new NoSuchFileException ();
        return file.length ();
    }

    public void delete () throws IOException
    {
        if (!file.delete ()) throw new FileDeleteException ();
    }

    public void mkdir () throws IOException
    {
        if (!file.mkdir ()) throw new FileMkDirException ();
    }

    public void mkdirs () throws IOException
    {
        if (!file.isDirectory () && !file.mkdirs ()) throw new FileMkDirsException ();
    }

    public long lastModified () throws IOException
    {
        if (!file.exists ()) throw new NoSuchFileException ();
        return file.lastModified ();
    }

    public void setLastModified (long time) throws IOException
    {
        if (!file.setLastModified (time)) throw new FileSetModTimeException ();
    }

    // returns absolute path name without redundant dots and
    // without trailing slash (unless / is all there is)
    public String getAbsolutePath ()
    {
        return abspath;
    }

    // based on sanitized absolute path names being equal
    public boolean equals (Object other)
    {
        if (!(other instanceof FileIFile)) return false;
        FileIFile othr = (FileIFile) other;
        return getAbsolutePath ().equals (othr.getAbsolutePath ());
    }

    // based on sanitized absolute path name
    public int hashCode ()
    {
        return getAbsolutePath ().hashCode ();
    }

    public boolean isDirectory ()
    {
        return isKnownReadable () || file.isDirectory ();
    }

    public boolean isFile ()
    {
        return file.isFile ();
    }

    public boolean isHidden ()
    {
        return file.isHidden ();
    }

    // if symlink, return what it points to
    //       else, return null
    public String getSymLink () throws IOException
    {
        String abspath = getAbsolutePath ();   // eg, /proc/net
        String canpath = getCanonicalPath ();  // eg, /proc/12345/net
        if (canpath.equals (abspath)) return null;
        int i = abspath.lastIndexOf ('/');
        if (canpath.startsWith (abspath.substring (0, ++ i))) {
            // user is reading symlink /proc/net
            // ... and it points to 12345/net
            return canpath.substring (i);
        }
        // the symlink points to something weird so return its absolute path
        return canpath;
    }

    public IFile[] listFiles () throws IOException
    {
        IFile[] ifiles;
        File[] files = file.listFiles ();
        if (files != null) {
            ifiles = new IFile[files.length];
            for (int i = files.length; -- i >= 0;) {
                ifiles[i] = new FileIFile (context, files[i]);
            }
        } else {

            // this file, presumably a directory, is not scannable
            // but if it is along the path of known readable directories,
            // make up contents based on those known readables
            KnownReadable kr = getKnownReadablesRoot ();
            for (String dirword : dirwords) {
                kr = kr.childs.get (dirword);
                if (kr == null) throw new FileListFilesException ();
            }
            ifiles = new IFile[kr.childs.size()];
            int i = 0;
            for (String child : kr.childs.keySet ()) {
                ifiles[i++] = new FileIFile (context, new File (file, child));
            }
        }
        return ifiles;
    }

    public IFile getParentFile ()
    {
        File parentFile = file.getParentFile ();
        if (parentFile == null) return null;
        return new FileIFile (context, parentFile);
    }

    public IFile getChildFile (String name)
    {
        if (name.startsWith ("/")) return new FileIFile (context, new File (name));
        return new FileIFile (context, new File (file, name));
    }

    public InputStream getInputStream () throws IOException
    {
        return new FileInputStream (file);
    }

    public OutputStream getOutputStream (int osmode) throws IOException
    {
        switch (osmode) {
            case OSMODE_APPEND: return new FileOutputStream (file, true);
            case OSMODE_CREATE: return new FileOutputStream (file);
            default: throw new IllegalArgumentException ("bad osmode " + osmode);
        }
    }

    public RAInputStream getRAInputStream () throws IOException
    {
        return new MyRAInputStream (file);
    }

    public RAOutputStream getRAOutputStream (int osmode) throws IOException
    {
        return new MyRAOutputStream (file, osmode);
    }

    public void putSymLink (String link) throws IOException
    {
        ProcessBuilder pb = new ProcessBuilder ("ln", "-s", link, abspath);
        pb.redirectErrorStream (true);
        Process p = pb.start ();
        try {
            BufferedReader stdout = new BufferedReader (new InputStreamReader (p.getInputStream ()), 256);
            try {
                String line = stdout.readLine ();
                if (line != null) throw new FileLn_SException (line);
            } finally {
                stdout.close ();
            }
        } finally {
            p.destroy ();
            try { p.waitFor (); } catch (InterruptedException ignored) { }
        }
    }

    public void renameTo (IFile newFile) throws IOException
    {
        if (!(newFile instanceof FileIFile)) throw new FSMismatchException ("different domain");
        if (!file.renameTo (((FileIFile)newFile).file)) {
            throw new FSMismatchException ("File.renameTo() failed");
        }
    }

    private String getCanonicalPath () throws IOException
    {
        return FileUtils.stripDots (file.getCanonicalPath ());
    }

    private static class MyRAInputStream extends RAInputStream {
        private IOException markioe;
        private long markpos;
        private RandomAccessFile raFile;

        public MyRAInputStream (File f) throws IOException { raFile = new RandomAccessFile (f, "r"); }

        // RAInputStream
        public long length () throws IOException { return raFile.length (); }
        public void readFully (byte[] buffer) throws IOException { raFile.readFully (buffer); }
        public void readFully (byte[] buffer, int offset, int count) throws IOException { raFile.readFully (buffer, offset, count); }
        public void seek (long pos) throws IOException { raFile.seek (pos); }
        public long tell () throws IOException { return raFile.getFilePointer (); }

        // InputStream
        public void close () throws IOException { raFile.close (); }
        public void mark (int readlimit) { markioe = null; try { markpos = tell (); } catch (IOException ioe) { markioe = ioe; } }
        public boolean markSupported () { return true; }
        public int read (@NonNull byte[] buffer) throws IOException { return raFile.read (buffer); }
        public int read (@NonNull byte[] buffer, int offset, int count) throws IOException { return raFile.read (buffer, offset, count); }
        public int read () throws IOException { return raFile.read (); }
        public void reset () throws IOException { if (markioe != null) throw markioe; seek (markpos); }
        public long skip (long count) throws IOException { long oldpos = tell (); seek (oldpos + count); return tell () - oldpos; }
    }

    private static class MyRAOutputStream extends RAOutputStream {
        private RandomAccessFile raFile;

        public MyRAOutputStream (File f, int osmode) throws IOException
        {
            switch (osmode) {
                case OSMODE_APPEND:
                case OSMODE_CREATE: break;
                default: throw new IllegalArgumentException ("bad osmode " + osmode);
            }
            raFile = new RandomAccessFile (f, "rw");
            if (osmode == OSMODE_APPEND) raFile.seek (raFile.length ());
        }

        // RAOutputStream
        public long length () throws IOException { return raFile.length (); }
        public void seek (long pos) throws IOException { raFile.seek (pos); }
        public long tell () throws IOException { return raFile.getFilePointer (); }

        // OutputStream
        public void close () throws IOException { raFile.close (); }
        public void flush () { }
        public void write (@NonNull byte[] buffer) throws IOException { raFile.write (buffer); }
        public void write (@NonNull byte[] buffer, int offset, int count) throws IOException { raFile.write (buffer, offset, count); }
        public void write (int oneByte) throws IOException { raFile.write (oneByte); }
    }
}
