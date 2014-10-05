/**
 * Access a Zip file as a whole like a read-only directory.
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
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;

public class MyZipFileIFile extends IFile {
    public static final String TAG = "SshClient";

    private static final MyZipEntryIFile[] zeroMyZipEntryIFile = new MyZipEntryIFile[0];

    private IFile container;
    private ZipFile zipFile;

    /**
     * See if the given file is a zip file or not.
     */
    public static boolean isZip (IFile zif)
    {
        try {
            RAInputStream rais = zif.getRAInputStream ();
            try {
                new ZipFile (rais).close ();
                return true;
            } finally {
                rais.close ();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Create a IFile for the given zip file.
     * This IFile looks like a directory containing a bunch of files.
     */
    public MyZipFileIFile (IFile zif)
    {
        container = zif;
    }

    /**
     * For use by MyZipEntryIFile to get the zip file.
     */
    public synchronized ZipFile getZipFile ()
            throws IOException
    {
        if (zipFile == null) {
            zipFile = new ZipFile (container.getRAInputStream ());
        }
        return zipFile;
    }

    public IFile getContainer ()
    {
        return container;
    }

    /**
     * Can read the contents of the zip file if can read the container.
     * Likewise with other similar tests.
     */
    @Override
    public boolean canRead () throws IOException {
        return container.canRead ();
    }

    @Override
    public void delete () throws IOException {
        container.delete ();
    }

    @Override
    public boolean exists () throws IOException {
        return container.exists ();
    }

    @Override
    public String getAbsolutePath () {
        return container.getAbsolutePath ();
    }

    @Override
    public InputStream getInputStream () throws IOException {
        return container.getInputStream ();
    }

    @Override
    public OutputStream getOutputStream (int osmode) throws IOException {
        return container.getOutputStream (osmode);
    }

    @Override
    public IFile getParentFile () {
        return container.getParentFile ();
    }

    @Override
    public RAInputStream getRAInputStream () throws IOException {
        return container.getRAInputStream ();
    }

    @Override
    public RAOutputStream getRAOutputStream (int osmode) throws IOException {
        return container.getRAOutputStream (osmode);
    }

    @Override
    public Uri getUri () {
        return container.getUri ();
    }

    @Override
    public long lastModified () throws IOException {
        return container.lastModified ();
    }

    @Override
    public long length () throws IOException {
        return container.length ();
    }

    @Override
    public void mkdir () throws IOException {
        container.mkdir ();
    }

    @Override
    public void mkdirs () throws IOException {
        container.mkdirs ();
    }

    @Override
    public void putSymLink (String link) throws IOException {
        container.putSymLink (link);
    }

    @Override
    public void renameTo (IFile newFile) throws IOException {
        container.renameTo (newFile);
    }

    @Override
    public void setLastModified (long time) throws IOException {
        container.setLastModified (time);
    }

    /**
     * We don't allow modifications to this 'directory',
     * ie, no creates, deletes, renames.
     */
    @Override
    public boolean canWrite () {
        return false;
    }

    /**
     * Getting a child means getting the entry of that particular name.
     */
    @Override
    public IFile getChildFile (String name) {
        return new MyZipEntryIFile (this, name);
    }

    /**
     * These are never symlinks.
     */
    @Override
    public String getSymLink () {
        return null;
    }

    /**
     * These should always be considered directories.
     */
    @Override
    public boolean isDirectory () throws IOException {
        return exists ();
    }

    /**
     * These are never regular files, they are always directories.
     */
    @Override
    public boolean isFile ()
    {
        return false;
    }

    /**
     * Whether or not the zip file is hidden.
     */
    @Override
    public boolean isHidden () throws IOException
    {
        return container.isHidden ();
    }

    /**
     * Get list of files contained in the zip file.
     * Since we want this to work like a directory file,
     * only return top-level entries.
     */
    @Override
    public IFile[] listFiles () throws IOException
    {
        getZipFile ();
        return listFiles (this, "");
    }

    /**
     * Get list of files that start with 'filter'.
     * Only get the first level just below filter.
     * Eg, if filter is 'myShoes/', get 'myShoes/chaChaHeels'
     * but not 'myShoes/chaChaHeels/black'.
     */
    public IFile[] listFiles (IFile parent, String filter) throws IOException
    {
        // get list of all entries in the zip file
        Collection<ZipEntry> collection = zipFile.entries ();

        // make array of only first-level entries under this one
        ArrayList<IFile> array = new ArrayList<IFile> (collection.size ());
        int filterLen = filter.length ();
        for (ZipEntry ze : collection) {

            // get 'myShoes/chaChaHeels/black'
            String name = ze.getName ();

            // see if it starts with filter of 'myShoes/' and has more than
            // just the filter of 'myShoes/'
            if ((name.length () > filterLen) && name.startsWith (filter)) {

                // get portion beyond the filter (eg 'chaChaHeels/black'
                String filteredName = name.substring (filterLen);

                // see if that contains a '/' other than as the last character
                int j = filteredName.indexOf ('/');
                if ((j < 0) || (j == name.length () - 1)) {
                    if (j >= 0) filteredName = filteredName.substring (0, j);

                    // if not, it is a properly selected first-level entry,
                    // eg, 'myShoes/chaChaHeels'

                    // but we might have made a phony directory entry for it
                    // and if so, overwrite that entry, otherwise make new entry
                } else {

                    // it is something like 'myShoes/chaChaHeels/black',
                    // so get the 'chaChaHeels' part
                    filteredName = filteredName.substring (0, j);

                    // make sure we have an entry for 'chaChaHeels' so user
                    // will be able to navigate to 'chaChaHeels/black' if
                    // they want
                }

                for (IFile dup : array) {
                    if (dup.getName ().equals (filteredName)) {
                        filteredName = null;
                        break;
                    }
                }
                if (filteredName != null) {
                    array.add (new MyZipEntryIFile (parent, filteredName));
                }
            }
        }

        // trim array down to size needed
        return array.toArray (zeroIFileArray);
    }
}
