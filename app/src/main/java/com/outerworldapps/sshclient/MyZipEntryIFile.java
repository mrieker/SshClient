/**
 * Access a Zip entry like a read-only file.
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collection;

public class MyZipEntryIFile extends IFile {
    public static final String TAG = "SshClient";

    private MyZipEntryIFile mzeif;  // parent MyZipEntryIFile, null if top level
    private MyZipFileIFile mzfif;   // containing MyZipFileIFile
    private String memnts;          // member name without trailing slash
    private String parent;          // parent+member = which member of zipFile we are accessing
    private Uri myUri;              // my URI, eg, <parenturi>/memnts
    private ZipEntry zipEntry;      // zipEntry of parent+member
    private ZipFile zipFile;        // zip file being accessed

    // created if accessing top-level member of zip file
    public MyZipEntryIFile (IFile zf, String mem)
    {
        // save member name, always without trailing slash cuz that's IFile convention
        memnts   = mem.endsWith ("/") ? mem.substring (0, mem.length () - 1) : mem;

        // see if top level entry or one of the sub levels
        if (zf instanceof MyZipFileIFile) {

            // top level, the whole name within the zip archive is given by memnts
            mzfif  = (MyZipFileIFile) zf;
            parent = "";
        } else {

            // sub level, the whole name within the zip archive is given by the parent name plus memnts
            mzeif  = (MyZipEntryIFile) zf;
            mzfif  = mzeif.mzfif;
            parent = mzeif.parent + mzeif.memnts + "/";
        }
    }

    @Override
    public boolean canRead () {
        return true;
    }

    @Override
    public boolean canWrite () {
        return false;
    }

    @Override
    public void delete () throws IOException {
        throw new ReadOnlyException ();
    }

    @Override
    public boolean exists () throws IOException {
        return isPhonyDir () || (zipEntry != null);
    }

    @Override
    public String getAbsolutePath () {
        return mzfif.getAbsolutePath () + "/" + parent + memnts;
    }

    @Override
    public IFile getChildFile (String name) {
        return new MyZipEntryIFile (this, name);
    }

    @Override
    public InputStream getInputStream () throws IOException {
        if (zipFile == null) {
            zipFile  = mzfif.getZipFile ();
            zipEntry = zipFile.getEntry (parent + memnts);
        }
        return zipFile.getInputStream (zipEntry);
    }

    @Override
    public OutputStream getOutputStream (int osmode) {
        return null;
    }

    @Override
    public IFile getParentFile () {
        return (mzeif != null) ? mzeif : mzfif;
    }

    @Override
    public RAInputStream getRAInputStream () {
        return null;
    }

    @Override
    public RAOutputStream getRAOutputStream (int osmode) {
        return null;
    }

    @Override
    public String getSymLink () {
        return null;
    }

    @Override
    public Uri getUri () {
        if (myUri == null) {
            myUri = mzfif.getContainer ().getChildFile (parent + memnts).getUri ();
        }
        return myUri;
    }

    @Override
    public boolean isDirectory () throws IOException {
        return isPhonyDir () || (exists () && zipEntry.isDirectory ());
    }

    @Override
    public boolean isFile () {
        return true;
    }

    @Override
    public boolean isHidden () {
        return getName ().startsWith (".");
    }

    @Override
    public long lastModified () throws IOException {
        if (!exists ()) throw new NoSuchFileException ();
        return isPhonyDir () ? 0 : zipEntry.getTime ();
    }

    @Override
    public long length () throws IOException {
        if (!exists ()) throw new NoSuchFileException ();
        return isPhonyDir () ? 0 : zipEntry.getSize ();
    }

    @Override
    public IFile[] listFiles () throws IOException {
        if (!isDirectory ()) throw new NotADirException ();
        return mzfif.listFiles (this, parent + memnts + "/");
    }

    @Override
    public void mkdir () throws IOException {
        throw new ReadOnlyException ();
    }

    @Override
    public void mkdirs () throws IOException {
        throw new ReadOnlyException ();
    }

    @Override
    public void putSymLink (String link) throws IOException {
        throw new ReadOnlyException ();
    }

    @Override
    public void renameTo (IFile newFile) throws IOException {
        throw new ReadOnlyException ();
    }

    @Override
    public void setLastModified (long time) throws IOException {
        throw new ReadOnlyException ();
    }

    /**
     * See if this entry represents a phony directory or not.
     * Phony directories are when we have entries like
     * 'myShoes/chaChaHeels' without a 'myShoes' entry.  They
     * allow a user to navigate through to 'myShoes/chaChaHeels'.
     */
    private boolean isPhonyDir () throws IOException
    {
        // we need the archive open to make this check
        if (zipFile == null) {
            zipFile  = mzfif.getZipFile ();
            zipEntry = zipFile.getEntry (parent + memnts);
        }

        // if there's an actual entry with that name,
        // it isn't a phony directory, it is either a
        // real file in the archive or a directory in
        // the archive
        if (zipEntry != null) return false;

        // see if there are any entries beginning with that name
        // if so, this is a phony directory, otherwise not.
        String pdname = parent + memnts + "/";
        Collection<ZipEntry> ents;
        ents = zipFile.entries ();
        for (ZipEntry ze : ents) {
            String name = ze.getName ();
            if (name.startsWith (pdname)) return true;
        }
        return false;
    }
}
