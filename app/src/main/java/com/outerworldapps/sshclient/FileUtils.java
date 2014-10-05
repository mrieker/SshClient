/**
 * Various file handling utility functions.
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


import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;

public class FileUtils {
    public static final String TAG = "SshClient";

    public static final int PARTIALUPDATEMILLIS = 123;

    public static final int DIRENTRYOVERHEAD = 60000;  // how many bytes we could transfer in time it takes to
                                                       // begin & end transferring a file
                                                       // = bytes/second nominal large-file transfer rate
                                                       //   * seconds to transfer 100 empty files
                                                       //   / 100

    /**
     * Compare IFile filenames.
     * THey are assumed to be children of the same directory.
     */
    private final static Comparator<IFile> compareFilenames = new Comparator<IFile> () {
        @Override
        public int compare (IFile file1, IFile file2)
        {
            String name1 = file1.getName ();
            String name2 = file2.getName ();
            return name1.compareTo (name2);
        }
    };

    public static class DirPreScan {
        public long total;
        public HashMap<String,DirPreScan> subScan;
    }

    /**
     * Callbacks for copy/delete/move progress.
     */
    public interface XferListener {
        void startFile (IFile oldFile, IFile newFile, long bytes) throws Exception;
        void partialCopy (long bytes) throws Exception;
        void endOfFile () throws Exception;
        Object paused () throws Exception;
        void exception (IFile oldFile, IFile newFile, Exception e) throws Exception;
    }

    /**
     * Copy a file and all its descendants.
     * Returns number of bytes copied (-1 if files identical).
     */
    public static long copyFile (IFile oldFile, IFile newFile, DirPreScan preScan, XferListener xferListener)
            throws Exception
    {
        if (oldFile.equals (newFile)) return -1;

        try {

            /*
             * Make sure new directory exists and that we can write it.
             */
            IFile newDir = newFile.getParentFile ();
            if (!newDir.exists ()) newDir.mkdirs ();
            if (!newDir.isDirectory ()) throw newDir.new NotADirException ();
            if (!newDir.canWrite ()) throw newDir.new ReadOnlyException ();

            // might be trying to copy a symlink
            String symlink = oldFile.getSymLink ();
            if (symlink != null) {
                try { newFile.delete (); } catch (IOException ioe) { }
                newFile.putSymLink (symlink);
                return symlink.length ();
            }

            // we copy first to a temp named with the mtime of the input file or directory
            long mtime = oldFile.lastModified ();
            IFile tmpFile = newFile.getParentFile ().getChildFile (newFile.getName () + ".$$$PART$$$." + mtime);

            // see if the file is a directory or a regular file
            // if directory, maybe we need to prescan it to get all file sizes
            IFile[] childs = oldFile.listFilesNull ();
            if ((preScan != null) && (preScan.subScan == null) && (childs != null)) {
                xferListener.startFile (oldFile, null, childs.length);
                try {
                    preScanDirectory (preScan, childs, xferListener);
                } finally {
                    xferListener.endOfFile ();
                }
            }

            // tell callback that we are starting to process a file or directory
            // the size given is:
            //  - regular files: the actual size in bytes
            //  - directories without prescan: the number of entries
            //  - directories with prescan : total bytes of all data files + some overhead for entries themselves
            long total = (childs == null) ? oldFile.length () : (preScan == null) ? childs.length : preScan.total;
            xferListener.startFile (oldFile, newFile, total);

            // haven't copied anything so far
            long sofar = 0;

            try {
                if (childs != null) {

                    // copy directory
                    String oldAP = oldFile.getAPWithSlash ();
                    String newAP = newFile.getAPWithSlash ();
                    if (newAP.startsWith (oldAP)) {
                        throw oldFile.new IllRecurException ();
                    }

                    // if same $$$PART$$$.mtime exists, it is a partial copy of the same input directory
                    // otherwise create it
                    if (!tmpFile.exists ()) tmpFile.mkdir ();

                    // copy each of the input children to the output directory
                    // if output file already exists with its permanent name,
                    // assume it is a fully copied file/directory and don't redo.
                    // if partial output file exists, call in to copyFile() anyway,
                    // and let it sort out if it can use the partial copy.
                    sortDirectory (childs);
                    int i = 0;
                    for (IFile oldChild : childs) {

                        // allow a few bytes copied for the directory entry itself
                        String oldName = oldChild.getName ();
                        sofar += oldName.length () + DIRENTRYOVERHEAD;

                        // compute corresponding output file name
                        IFile newChild = tmpFile.getChildFile (oldName);

                        // get the pre-scan info for the sub-directory if any
                        DirPreScan subScan = null;
                        if (preScan != null) subScan = preScan.subScan.get (oldChild.getName ());

                        // copy the file or sub-directory
                        // We can safely skip if already exists cuz that means we copied it previously as this is a temp
                        // directory.
                        if (!newChild.exists ()) sofar += copyFile (oldChild, newChild, subScan, xferListener);
                        else if (!newChild.isDirectory ()) sofar += newChild.length ();
                        else if (subScan != null) sofar += subScan.total;

                        // update amount done in this directory
                        xferListener.partialCopy ((preScan == null) ? ++ i : sofar);
                    }
                } else {

                    // copy file
                    boolean paused;

                    do {
                        // wait here as long as we are paused
                        Object pauseLock = xferListener.paused ();
                        if (pauseLock != null) {
                            synchronized (pauseLock) {
                                while (xferListener.paused () != null) {
                                    try { pauseLock.wait (); } catch (InterruptedException ie) { }
                                }
                            }
                        }

                        // assume we don't get paused during copy
                        paused = false;

                        // open source file first to make sure it is readable before attempting to create destination file
                        InputStream seqis = null;
                        RAInputStream ranis = oldFile.getRAInputStream ();
                        if (ranis == null) seqis = oldFile.getInputStream ();
                        try {
                            // copy to temp first.  name temp using mtime so we can't use an old stale partial copy.
                            // but if valid partial exists, try to append on to it.

                            RAOutputStream ranos = tmpFile.getRAOutputStream (IFile.OSMODE_APPEND);

                            try {
                                // if at least 16K or 1% of file already done,
                                // resume copying where we left off.
                                long skip = ranos.length ();
                                if ((ranis != null) && (skip > 16384) && (skip > total / 128)) {
                                    skip = (skip - 4096) & -4096;
                                    ranis.seek (skip);
                                } else {
                                    skip = 0;
                                }
                                ranos.seek (skip);
                                if (seqis == null) {
                                    seqis = ranis;
                                    ranis = null;
                                }

                                // copy...
                                long nextupd = 0;
                                byte[] buf = new byte[16384];
                                int len = buf.length;
                                int ofs;
                                do {
                                    // fill buffer from input file
                                    ofs = 0;
                                    do {
                                        int rc = seqis.read (buf, ofs, len - ofs);
                                        if (rc < 0) break;
                                        ofs += rc;
                                    } while (ofs < len);

                                    // write the buffer to output file
                                    ranos.write (buf, 0, ofs);

                                    // if it has been a while since sending update, send it.
                                    // also, if paused, abort copying then loop back for retry
                                    // ...hopefully restarting where we left off
                                    sofar += ofs;
                                    long now = SystemClock.uptimeMillis ();
                                    if (nextupd <= now) {
                                        nextupd = now + PARTIALUPDATEMILLIS;
                                        xferListener.partialCopy (sofar + skip);
                                        if (xferListener.paused () != null) {
                                            paused = true;
                                            break;
                                        }
                                    }
                                } while (ofs == len);
                                ranos.flush ();
                            } finally {
                                ranos.close ();
                            }
                        } finally {
                            if (ranis != null) ranis.close ();
                            if (seqis != null) seqis.close ();
                        }
                    } while (paused);
                }

                // whole directory/file successfully copied, rename temp file to permanent name
                // and try to give it the input file's modification time
                try {
                    tmpFile.setLastModified (mtime);
                } catch (IOException ioe) {
                    Log.w (TAG, "setLastModified() failed " + tmpFile.getAbsolutePath (), ioe);
                }
                tmpFile.renameTo (newFile);
            } finally {

                // tell callback we are done processing that directory/file
                xferListener.endOfFile ();
            }

            return sofar;
        } catch (Exception e) {
            xferListener.exception (oldFile, newFile, e);
            return 0;
        }
    }

    /**
     * Given a list of files in a directory, compute the directory's total disk usage.
     * @param preScan = filled in with directory's total disk usage
     * @param childs  = list of files in the directory
     */
    private static void preScanDirectory (DirPreScan preScan, IFile[] childs, XferListener xferListener)
            throws Exception
    {
        HashMap<String,DirPreScan> subScan = new HashMap<String,DirPreScan> ();
        for (IFile child : childs) {
            String name = child.getName ();
            preScan.total += name.length () + DIRENTRYOVERHEAD;
            String symlink = child.getSymLink ();
            if (symlink != null) {
                preScan.total += symlink.length ();
            } else {
                IFile[] subChilds = child.listFilesNull ();
                if (subChilds != null) {
                    DirPreScan dirPreScan = new DirPreScan ();
                    xferListener.startFile (child, null, subChilds.length);
                    try {
                        preScanDirectory (dirPreScan, subChilds, xferListener);
                    } finally {
                        xferListener.endOfFile ();
                    }
                    subScan.put (name, dirPreScan);
                    preScan.total += dirPreScan.total;
                } else {
                    preScan.total += child.length ();
                }
            }
        }
        preScan.subScan = subScan;
    }

    /**
     * Delete a file and all its descendants.
     */
    public static void deleteFile (IFile file, XferListener xferListener)
            throws Exception
    {
        IFile[] childs = file.listFilesNull ();
        if (childs != null) {
            xferListener.startFile (file, null, childs.length);
            try {
                sortDirectory (childs);
                int i = 0;
                for (IFile child : childs) {
                    deleteFile (child, xferListener);
                    xferListener.partialCopy (++ i);
                }
            } finally {
                xferListener.endOfFile ();
            }
        }

        // wait here as long as we are paused
        Object pauseLock = xferListener.paused ();
        if (pauseLock != null) {
            synchronized (pauseLock) {
                while (xferListener.paused () != null) {
                    try { pauseLock.wait (); } catch (InterruptedException ie) { }
                }
            }
        }

        // delete
        file.delete ();
    }

    /**
     * Move a file and all its descendants.
     */
    public static void moveFile (IFile oldFile, IFile newFile, DirPreScan preScan, XferListener xferListener)
            throws Exception
    {
        if (oldFile.equals (newFile)) return;

        /*
         * Make sure we can write old directory.
         */
        if (!oldFile.exists ()) throw oldFile.new NoSuchFileException ();
        IFile oldDir = oldFile.getParentFile ();
        if (!oldDir.canWrite ()) throw oldDir.new ReadOnlyException ();

        /*
         * Make sure new directory exists and that we can write it.
         */
        IFile newDir = newFile.getParentFile ();
        if (!newDir.exists ()) newDir.mkdirs ();
        if (!newDir.canWrite ()) throw newDir.new ReadOnlyException ();

        /*
         * First try a simple rename after having made sure both directories exist and are writable.
         */
        try {
            oldFile.renameTo (newFile);
        } catch (IOException ioe) {

            /*
             * Failed, try copy then delete old files.
             */
            copyFile (oldFile, newFile, preScan, xferListener);

            IFile tmpFile = oldFile.getParentFile ().getChildFile (oldFile.getName () + ".$$$DEAD$$$");
            oldFile.renameTo (tmpFile);
            deleteFile (tmpFile, xferListener);
        }
    }

    /**
     * Sort a directory listing.
     */
    public static void sortDirectory (IFile[] childs)
    {
        Arrays.sort (childs, 0, childs.length, compareFilenames);
    }

    /**
     * Strip redundant dots from an absolute path name and also trailing slash.
     */
    public static String stripDots (String name)
    {
        // we should only be getting absolute path names
        if (!name.startsWith ("/")) throw new IllegalArgumentException (name + " does not begin with /");

        // make sure there is a slash on the end for making the searches easier,
        // whether or not it is a directory
        StringBuilder sb = new StringBuilder (name.length () + 1);
        sb.append (name);
        if (!name.endsWith ("/")) sb.append ('/');

        // get rid of all funny stuff
        int i;
        while (true) {
            i = sb.indexOf ("//");
            if (i >= 0) {
                sb.deleteCharAt (i);
                continue;
            }
            i = sb.indexOf ("/./");
            if (i >= 0) {
                sb.delete (i, i + 2);
                continue;
            }
            i = sb.indexOf ("/../");
            if (i >= 0) {
                int j = (i > 0) ? sb.lastIndexOf ("/", i - 1) : 0;
                sb.delete (j, i + 3);
                continue;
            }
            break;
        }

        // we should still have a slash on the end
        i = sb.length ();
        if ((-- i < 0) || (sb.charAt (i) != '/')) {
            throw new IllegalArgumentException (sb.toString () + " does not end with /");
        }

        // get rid of trailing slash unless that's all we got
        // ...because that's the rule of getAbsolutePath()
        //    and we don't know if this is a directory or not
        if (i > 0) sb.deleteCharAt (i);

        return sb.toString ();
    }

    /**
     * Match the given name with the wildcard
     * @param wildcard = wildcard string to match against
     * @param name = name to match to the wildcard
     * @param i = where in wildcard to start matching at
     * @param j = where in name to start matching at
     */
    public static boolean wildcardMatch (String wildcard, String name, int i, int j)
    {
        int wcend = wildcard.length ();
        int nmend = name.length ();

        while (i < wcend) {
            char wc = wildcard.charAt (i);
            if (wc == '*') {

                // if '*' is on the end of wild, it matches the rest of the name
                do if (++ i >= wcend) return true;

                // ignore redundant '*' wildcards from string
                while (wildcard.charAt (i) == '*');

                // optimization:
                //   if there are no more '*'s in wildcard
                //   we need only check the remaining wildcard
                //   against that many chars on end of name
                if (wildcard.indexOf ('*', i) < 0) {
                    if (wcend - i > nmend - j) return false;
                    j = nmend - (wcend - i);
                    return wildcardMatch (wildcard, name, i, j);
                }

                // see if what is left of name matches what is left of wild
                do if (wildcardMatch (wildcard, name, i, j)) return true;

                // if not, skip over a char of name and try matching that
                while (++ j < nmend);

                // we tried matching taking one char away from name at a time
                // without success, and we know the next wild char isn't a '*'
                // so the match fails
                return false;
            }

            // not a '*', there must be at least one name char left to match
            if (j >= nmend) return false;

            // '?' means any char in name matches
            if (wc != '?') {

                // backslash means next char is literal
                if (wc == '\\') {
                    if (++ i >= wcend) break;
                    wc = wildcard.charAt (i);
                }

                // ok make sure it matches exactly
                if (name.charAt (j) != wc) return false;
            }

            // success on that char, check out next
            i ++;
            j ++;
        }

        // we ran out of wildcard chars, we better be out of name chars too
        return j >= nmend;
    }
}
