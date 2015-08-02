/**
 * Interface similar to java.io.File class.
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

public abstract class IFile {
    public final static int OSMODE_CREATE = 0;
    public final static int OSMODE_APPEND = 1;

    public final static IFile[] zeroIFileArray = new IFile[0];

    public abstract boolean        canRead () throws IOException;       // target of symlink; false if doesn't exist
    public abstract boolean        canWrite () throws IOException;      // target of symlink; false if doesn't exist
    public abstract void           delete () throws IOException;        // symlink itself; exception if doesn't exist
    public abstract boolean        exists () throws IOException;        // symlink itself
    public abstract String         getAbsolutePath ();
    public abstract IFile          getChildFile (String name);
    public abstract InputStream    getInputStream () throws IOException;
    public abstract OutputStream   getOutputStream (int osmode) throws IOException;
    public abstract IFile          getParentFile ();
    public abstract RAInputStream  getRAInputStream () throws IOException;
    public abstract RAOutputStream getRAOutputStream (int osmode) throws IOException;
    public abstract String         getSymLink () throws IOException;    // null if doesn't exist; null if not symlink
    public abstract Uri            getUri ();
    public abstract boolean        isDirectory () throws IOException;   // target of symlink; false if doesn't exist
    public abstract boolean        isFile () throws IOException;        // target of symlink; false if doesn't exist
    public abstract boolean        isHidden () throws IOException;      // false if doesn't exist
    public abstract long           lastModified () throws IOException;  // target of symlink; exception if doesn't exist
    public abstract long           length () throws IOException;        // target of symlink; exception if doesn't exist
    public abstract IFile[]        listFiles () throws IOException;     // exception on any inability to read as a directory
    public abstract void           mkdir () throws IOException;         // exception if already exists
    public abstract void           mkdirs () throws IOException;        // ok if already exists as directory
    public abstract void           putSymLink (String link) throws IOException;     // exception if already exists
    public abstract void           renameTo (IFile newFile) throws IOException;     // overwrite if already exists
    public abstract void           setLastModified (long time) throws IOException;

    /**
     * Get final component of name.
     */
    public final String getName ()
    {
        String name = getAbsolutePath ();
        if (name.equals ("/")) return name;
        int j = name.lastIndexOf ('/');
        return name.substring (++ j);
    }

    /**
     * Get absolute path, but with a slash on the end if it is a directory.
     * Normal getAbsolutePath() returns a slash on the end only if the whole
     * name is just "/".
     *
     * If file is a symlink pointing to a directory, the "/" is not appended.
     */
    public final String getAPWithSlashNX ()
    {
        try {
            return getAPWithSlash ();
        } catch (IOException ioe) {
            return getAbsolutePath ();
        }
    }

    public final String getAPWithSlash () throws IOException
    {
        String apws = getAbsolutePath ();
        if (!apws.endsWith ("/") && (getSymLink () == null) && isDirectory ()) apws += "/";
        return apws;
    }

    /**
     * Follow symlinks to the final target file.
     */
    public final IFile getTarget () throws IOException
    {
        IFile file = this;
        int i = 20;
        String link;
        while ((link = file.getSymLink ()) != null) {
            if (-- i < 0) throw new IOException ("symlinks too deep");
            IFile parent = file.getParentFile ();
            if (parent == null) {
                if (!link.startsWith ("/")) link = "/" + link;
                file = file.getChildFile (link);
            } else {
                file = parent.getChildFile (link);
            }
        }
        return file;
    }

    /**
     * List files in a directory, but return null if not a directory.
     * Throws exception for any other error.
     */
    public final IFile[] listFilesNull () throws IOException
    {
        return isDirectory () ? listFiles () : null;
    }

    /**
     * Exceptions related to this file.
     */
    public class IFileException extends IOException {
        public IFileException (String msg)
        {
            super (msg);
        }

        @Override
        public String getMessage ()
        {
            StringBuilder sb = new StringBuilder ();
            sb.append (super.getMessage ());
            sb.append (' ');
            sb.append (getAbsolutePath ());

            Throwable cause = this;
            while ((cause = cause.getCause ()) != null) {
                sb.append ('\n');
                sb.append (cause.getClass ().getSimpleName ());
                String txt = cause.getMessage ();
                if (txt != null) {
                    sb.append (": ");
                    sb.append (txt);
                }
            }

            return sb.toString ();
        }
    }

    public class FileDeleteException extends IFileException {
        public FileDeleteException ()
        {
            super ("File.delete() failed");
        }
    }

    public class FileListFilesException extends IFileException {
        public FileListFilesException ()
        {
            super ("File.listFiles() failed");
        }
    }

    public class FileLn_SException extends IFileException {
        public FileLn_SException (String err)
        {
            super ("ln -s failed: " + err);
        }
    }

    public class FileMkDirException extends IFileException {
        public FileMkDirException ()
        {
            super ("File.mkdir() failed");
        }
    }

    public class FileMkDirsException extends IFileException {
        public FileMkDirsException ()
        {
            super ("File.mkdirs() failed");
        }
    }

    public class FileSetModTimeException extends IFileException {
        public FileSetModTimeException ()
        {
            super ("File.setModTime() failed");
        }
    }

    public class FSMismatchException extends IFileException {
        public FSMismatchException (String msg)
        {
            super (msg);
        }
    }

    public class IllRecurException extends IFileException {
        public IllRecurException ()
        {
            super ("illegal recursion");
        }
    }

    public class NoSuchFileException extends IFileException {
        public NoSuchFileException ()
        {
            super ("no such file");
        }
    }

    public class NotADirException extends IFileException {
        public NotADirException ()
        {
            super ("not a directory");
        }
    }

    public class ReadOnlyException extends IFileException {
        public ReadOnlyException ()
        {
            super ("write to read-only dir/file");
        }
    }

    public class SftpIOException extends IFileException {
        public SftpIOException (Exception cause)
        {
            super ("sftp io error");
            initCause (cause);
        }
    }

    public class SshGetHomeException extends IFileException {
        public SshGetHomeException (Exception cause)
        {
            super ("getHome() failed");
            initCause (cause);
        }
    }

    public class SshLsException extends IFileException {
        public SshLsException (Exception cause)
        {
            super ("ls() failed");
            initCause (cause);
        }
    }

    public class SshLstatException extends IFileException {
        public SshLstatException (Exception cause)
        {
            super ("lstat() failed");
            initCause (cause);
        }
    }

    public class SshMkdirException extends IFileException {
        public SshMkdirException (Exception cause)
        {
            super ("mkdir() failed");
            initCause (cause);
        }
    }

    public class SshOpenException extends IFileException {
        public SshOpenException (Exception cause)
        {
            super ("openChannel() failed");
            initCause (cause);
        }
    }

    public class SshReadlinkException extends IFileException {
        public SshReadlinkException (Exception cause)
        {
            super ("readlink() failed");
            initCause (cause);
        }
    }

    public class SshRenameException extends IFileException {
        public SshRenameException (Exception cause)
        {
            super ("rename() failed");
            initCause (cause);
        }
    }

    public class SshRmDirException extends IFileException {
        public SshRmDirException (Exception cause)
        {
            super ("rmdir() failed");
            initCause (cause);
        }
    }

    public class SshRmException extends IFileException {
        public SshRmException (Exception cause)
        {
            super ("rm() failed");
            initCause (cause);
        }
    }

    public class SshStatException extends IFileException {
        public SshStatException (Exception cause)
        {
            super ("stat() failed");
            initCause (cause);
        }
    }

    public class SshSetMtimeException extends IFileException {
        public SshSetMtimeException (Exception cause)
        {
            super ("setMtime() failed");
            initCause (cause);
        }
    }

    public class SshSymlinkException extends IFileException {
        public SshSymlinkException (Exception cause)
        {
            super ("symlink() failed");
            initCause (cause);
        }
    }
}
