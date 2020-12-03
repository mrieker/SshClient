/**
 * Implementation of IFile interface to access files on a remote host via SSH connection.
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
import android.support.annotation.NonNull;
import android.util.Log;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicLong;

public class SshIFile extends IFile {
    public final static String TAG = "SshClient";

    private static class ChanEnt {
        public ChannelSftp chan;
        int idle;
    }

    private static class GidUid {
        public int gid;
        public int uid;
    }

    private static AtomicLong lastprobe = new AtomicLong (0);
    private static CleanupThread cleanupThread;
    private static final NNHashMap<Session,LinkedList<ChanEnt>> channelPool = new NNHashMap<> ();

    private GidUid giduid;         // holds the gid/uid of the user@host:port connected to
    private Session session;       // TCP connection that is logged in
    private SftpATTRS cacheLStat;  // attributes for the link itself
    private SftpATTRS cacheStat;   // attributes for the target of the link
    private String abspath;        // absolute path name returned by getAbsolutePath()
                                   // doesn't have '/' on the end, even if directory, unless it is only '/'
    private Uri myUri;             // my URI, eg, ssh://user@host:port/path

    // home directory
    public SshIFile (Session session) throws IOException
    {
        this.session = session;

        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            String home = chanEnt.chan.getHome ();
            if (!home.startsWith ("/")) throw new IllegalArgumentException ("home not absolute");
            abspath = FileUtils.stripDots (home);
            giduid  = probeLinkGidUid (session, chanEnt);
        } catch (SftpException se) {
            throw new SshGetHomeException (se);
        } finally {
            finishedUsingChannel (chanEnt);
        }
    }

    // absolute path on the same connection as given SshIFile
    public SshIFile (SshIFile othr, String path)
    {
        this.session = othr.session;
        this.giduid  = othr.giduid;

        if (!path.startsWith ("/")) throw new IllegalArgumentException ("path not absolute");
        abspath = FileUtils.stripDots (path);
    }

    /*******************************\
     *  Implement IFile interface  *
     *  similar to File class      *
    \*******************************/

    @Override
    public boolean canRead () throws IOException
    {
        return canReadOrWrite (4);
    }

    @Override
    public boolean canWrite () throws IOException
    {
        return canReadOrWrite (2);
    }

    @Override
    public void delete () throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            SftpATTRS lstat = getLStat (chanEnt);
            if ((lstat != null) && lstat.isDir ()) {
                try {
                    chanEnt.chan.rmdir (abspath);
                } catch (SftpException se) {
                    throw new SshRmDirException (se);
                }
            } else {
                try {
                    chanEnt.chan.rm (abspath);
                } catch (SftpException se) {
                    throw new SshRmException (se);
                }
            }
        } finally {
            cacheLStat = null;
            cacheStat  = null;
            finishedUsingChannel (chanEnt);
        }
    }

    @Override
    public boolean equals (Object other)
    {
        if (!(other instanceof SshIFile)) return false;
        SshIFile othr = (SshIFile) other;
        return session.getUserName ().equals (othr.session.getUserName ()) &&
                session.getHost ().equals (othr.session.getHost ()) &&
                (session.getPort () == othr.session.getPort ()) &&
                abspath.equals (othr.abspath);
    }

    @Override
    public boolean exists () throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            return getLStat (chanEnt) != null;
        } finally {
            finishedUsingChannel (chanEnt);
        }
    }

    @Override
    public String getAbsolutePath ()
    {
        return abspath;
    }

    @Override
    public IFile getChildFile (String name)
    {
        if (name.startsWith ("/")) return new SshIFile (this, name);

        StringBuilder childpath = new StringBuilder (abspath.length () + name.length () + 1);
        childpath.append (abspath);
        if (!abspath.endsWith ("/")) childpath.append ('/');
        childpath.append (name);
        return new SshIFile (this, childpath.toString ());
    }

    @Override
    public int hashCode ()
    {
        return session.getUserName ().hashCode () ^
                session.getHost ().hashCode () ^
                session.getPort () ^
                abspath.hashCode ();
    }

    @Override
    public InputStream getInputStream () throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            return new SshRAIStream (chanEnt);
        } catch (Exception e) {
            finishedUsingChannel (chanEnt);
            throw new SftpIOException (e);
        }
    }

    @Override
    public OutputStream getOutputStream (int osmode) throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            return new SshRAOStream (chanEnt, osmode);
        } catch (Exception e) {
            finishedUsingChannel (chanEnt);
            throw new SftpIOException (e);
        } finally {
            cacheLStat = null;
            cacheStat  = null;
        }
    }

    @Override
    public IFile getParentFile ()
    {
        String p = abspath;
        if (p.equals ("/")) return null;
        int j = p.lastIndexOf ('/');
        String parentpath = (j == 0) ? "/" : p.substring (0, j);
        return new SshIFile (this, parentpath);
    }

    @Override
    public RAInputStream getRAInputStream () throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            return new SshRAIStream (chanEnt);
        } catch (Exception e) {
            finishedUsingChannel (chanEnt);
            throw new SftpIOException (e);
        }
    }

    @Override
    public RAOutputStream getRAOutputStream (int osmode) throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            return new SshRAOStream (chanEnt, osmode);
        } catch (Exception e) {
            finishedUsingChannel (chanEnt);
            throw new SftpIOException (e);
        } finally {
            cacheLStat = null;
            cacheStat  = null;
        }
    }

    @Override
    public String getSymLink () throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {

            // check for link first so we don't get grotesque exception
            SftpATTRS lstat = getLStat (chanEnt);
            if (lstat == null) return null;
            if (!lstat.isLink ()) return null;

            // now should be ok to read link
            try {
                return chanEnt.chan.readlink (abspath);
            } catch (SftpException se) {
                throw new SshReadlinkException (se);
            }
        } finally {
            finishedUsingChannel (chanEnt);
        }
    }

    @Override
    public Uri getUri ()  // android.net.Uri
    {
        if (myUri == null) {
            Uri.Builder ub = new Uri.Builder ();
            ub.scheme ("sftp");
            ub.authority (session.getUserName () + "@" + session.getHost () + ":" + session.getPort ());
            ub.path (abspath);
            myUri = ub.build ();
        }
        return myUri;
    }

    @Override
    public boolean isDirectory () throws IOException
    {
        SftpATTRS stat = getStat ();
        return (stat != null) && stat.isDir ();
    }

    @Override
    public boolean isFile () throws IOException
    {
        SftpATTRS stat = getStat ();
        return (stat != null) && stat.isReg ();
    }

    @Override
    public boolean isHidden ()
    {
        return getName ().startsWith (".");
    }

    @Override
    public long lastModified () throws IOException
    {
        SftpATTRS stat = getStat ();
        if (stat == null) throw new NoSuchFileException ();
        return stat.getMTime () * 1000L;
    }

    @Override
    public long length () throws IOException
    {
        SftpATTRS stat = getStat ();
        if (stat == null) throw new NoSuchFileException ();
        return stat.getSize ();
    }

    @Override
    public IFile[] listFiles () throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            ListFilesSelector lfs = new ListFilesSelector ();
            chanEnt.chan.ls (abspath, lfs);
            return lfs.fileList.toArray (zeroIFileArray);
        } catch (SftpException se) {
            throw new SshLsException (se);
        } finally {
            finishedUsingChannel (chanEnt);
        }
    }

    private class ListFilesSelector implements ChannelSftp.LsEntrySelector {
        public LinkedList<IFile> fileList = new LinkedList<> ();
        @Override
        public int select (ChannelSftp.LsEntry entry)
        {
            String fn = entry.getFilename ();
            if (!fn.equals (".") && !fn.equals ("..")) {
                SshIFile sif = (SshIFile) getChildFile (fn);
                sif.cacheLStat = entry.getAttrs ();
                fileList.addLast (sif);
            }
            return ChannelSftp.LsEntrySelector.CONTINUE;
        }
    }

    @Override
    public void mkdir () throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            mkdir (chanEnt, abspath);
        } finally {
            cacheLStat = null;
            cacheStat  = null;
            finishedUsingChannel (chanEnt);
        }
    }

    @Override
    public void mkdirs () throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            mkdirs (chanEnt, abspath);
        } finally {
            cacheLStat = null;
            cacheStat  = null;
            finishedUsingChannel (chanEnt);
        }
    }

    @Override
    public void putSymLink (String link) throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            chanEnt.chan.symlink (link, abspath);
        } catch (SftpException se) {
            throw new SshSymlinkException (se);
        } finally {
            cacheLStat = null;
            cacheStat  = null;
            finishedUsingChannel (chanEnt);
        }
    }

    @Override
    public void renameTo (IFile newPath) throws IOException
    {
        if (!(newPath instanceof SshIFile)) throw new FSMismatchException ("different domain");
        SshIFile newFile = (SshIFile) newPath;
        if (newFile.session != session) {
            if (!newFile.session.getHost ().equals (session.getHost ())) throw new FSMismatchException ("different host");
            if (newFile.session.getPort () != session.getPort ()) throw new FSMismatchException ("different port");
            if (!newFile.session.getUserName ().equals (session.getUserName ())) throw new FSMismatchException ("different user");
        }
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            chanEnt.chan.rename (abspath, newFile.abspath);
        } catch (Exception e) {
            throw new SshRenameException (e);
        } finally {
            cacheLStat = null;
            cacheStat  = null;
            finishedUsingChannel (chanEnt);
        }
    }

    @Override
    public void setLastModified (long time) throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            chanEnt.chan.setMtime (abspath, (int)(time / 1000));
        } catch (Exception e) {
            throw new SshSetMtimeException (e);
        } finally {
            cacheLStat = null;
            cacheStat  = null;
            finishedUsingChannel (chanEnt);
        }
    }

    /**************\
     *  Internal  *
    \**************/

    // find out if the session can read or write this file
    // rwx=4: read; rwx=2: write
    private boolean canReadOrWrite (int rwx) throws IOException
    {
        ChanEnt chanEnt = aboutToUseChannel ();
        try {
            return canReadOrWrite (chanEnt, rwx);
        } finally {
            finishedUsingChannel (chanEnt);
        }
    }

    @SuppressWarnings({ "OctalInteger", "PointlessArithmeticExpression" })
    private boolean canReadOrWrite (ChanEnt chanEnt, int rwx) throws IOException
    {
        SftpATTRS stat = getStat (chanEnt);
        if (stat == null) return false;
        int perms = stat.getPermissions ();
        if ((perms & (rwx * 0001)) != 0) return true;
        if (giduid == null) return true;
        if (giduid.uid == 0) return true;
        if ((giduid.gid == stat.getGId ()) && ((perms & (rwx * 0010)) != 0)) return true;
        return ((giduid.uid == stat.getUId ()) && ((perms & (rwx * 0100)) != 0));
    }

    // create all directories in path
    // succeed if already exists
    private void mkdirs (ChanEnt chanEnt, String ap) throws IOException
    {
        SftpATTRS stat;
        try {
            stat = getStat (chanEnt, ap);
        } catch (SftpException se) {
            throw new SshStatException (se);
        }
        if (stat == null) {
            int j = ap.lastIndexOf ('/');
            mkdirs (chanEnt, ap.substring (0, j));
            mkdir (chanEnt, ap);
        } else if (!stat.isDir ()) {
            throw new NotADirException ();
        }
    }

    // create a directory
    // fail if it already exists
    private void mkdir (ChanEnt chanEnt, String p) throws IOException
    {
        try {
            chanEnt.chan.mkdir (p);
        } catch (SftpException e) {
            throw new SshMkdirException (e);
        }
    }

    // get attributes about the symlink itself
    // null if doesn't exist
    private SftpATTRS getLStat (ChanEnt chanEnt) throws IOException
    {
        if (cacheLStat == null) {
            try {
                cacheLStat = getLStat (chanEnt, abspath);
            } catch (SftpException se) {
                throw new SshLstatException (se);
            }
        }
        return cacheLStat;
    }
    private static SftpATTRS getLStat (ChanEnt chanEnt, String ap) throws SftpException
    {
        try {
            return chanEnt.chan.lstat (ap);
        } catch (SftpException se) {
            if (se.getMessage ().contains ("No such file")) return null;
            throw se;
        }
    }

    // get attributes about target of symlink
    // null if doesn't exist
    private SftpATTRS getStat () throws IOException
    {
        if (cacheStat == null) {
            ChanEnt chanEnt = aboutToUseChannel ();
            try {
                cacheStat = getStat (chanEnt, abspath);
            } catch (SftpException se) {
                throw new SshStatException (se);
            } finally {
                finishedUsingChannel (chanEnt);
            }
        }
        return cacheStat;
    }
    private SftpATTRS getStat (ChanEnt chanEnt) throws IOException
    {
        if (cacheStat == null) {
            try {
                cacheStat = getStat (chanEnt, abspath);
            } catch (SftpException se) {
                throw new SshStatException (se);
            }
        }
        return cacheStat;
    }
    private static SftpATTRS getStat (ChanEnt chanEnt, String ap) throws SftpException
    {
        try {
            return chanEnt.chan.stat (ap);
        } catch (SftpException se) {
            if (se.getMessage ().contains ("No such file")) return null;
            throw se;
        }
    }

    /**
     * Try to figure out what gid/uid the session is logged in as for access to files.
     */
    private static GidUid probeLinkGidUid (Session session, ChanEnt chanEnt)
    {
        String key = session.getUserName () + "@" + session.getHost () + ":" + session.getPort ();

        // try creating a file in /tmp then read that file's gid/uid.
        // that should tell us what the connection's gid/uid are.
        String tmpname = "/tmp/sftpifileprobe." + lastprobe.addAndGet (1) + "." + System.currentTimeMillis ();
        try {
            try {
                OutputStream os = chanEnt.chan.put (tmpname);
                try { os.close (); } catch (IOException ignored) { }
                SftpATTRS at = chanEnt.chan.stat (tmpname);
                GidUid giduid = new GidUid ();
                giduid.gid = at.getGId ();
                giduid.uid = at.getUId ();
                Log.d (TAG, "got gid=" + giduid.gid + " uid=" + giduid.uid + " via probe for " + key);
                return giduid;
            } finally {
                try { chanEnt.chan.rm (tmpname); } catch (Exception ignored) { }
            }
        } catch (SftpException se) {
            Log.d (TAG, "probe() probe exception", se);

            // try to get the gid/uid of the connection's home directory
            try {
                String home = chanEnt.chan.getHome ();
                SftpATTRS at = chanEnt.chan.lstat (home);
                GidUid giduid = new GidUid ();
                giduid.gid = at.getGId ();
                giduid.uid = at.getUId ();
                Log.d (TAG, "got gid=" + giduid.gid + " uid=" + giduid.uid + " via home for " + key);
                return giduid;
            } catch (SftpException se2) {
                Log.d (TAG, "probe() home exception", se2);

                // that failed, gid/uid unknown
                Log.d (TAG, "failed to get gid/uid for " + key);
                return null;
            }
        }
    }

    /**
     * Set up chanEnt pointer to a channel, grabbing an unused one
     * or opening & connecting a new one if necessary.
     */
    private ChanEnt aboutToUseChannel () throws IOException
    {
        ChanEnt chanEnt;
        synchronized (channelPool) {
            if (!channelPool.containsKey (session)) {
                channelPool.put (session, new LinkedList<ChanEnt> ());
            }
            LinkedList<ChanEnt> channelList = channelPool.nnget (session);
            if (channelList.isEmpty ()) {
                try {
                    chanEnt = new ChanEnt ();
                    chanEnt.chan = (ChannelSftp) session.openChannel ("sftp");
                    chanEnt.chan.connect ();
                } catch (JSchException je) {
                    throw new SshOpenException (je);
                }
            } else {
                chanEnt = channelList.removeFirst ();
            }
        }
        return chanEnt;
    }

    /**
     * Put the channel back for something else to use.
     * The cleanup thread will close it out if not needed anytime soon.
     */
    private void finishedUsingChannel (ChanEnt chanEnt)
    {
        synchronized (channelPool) {
            if (!channelPool.containsKey (session)) {
                channelPool.put (session, new LinkedList<ChanEnt> ());
            }
            chanEnt.idle = 0;
            channelPool.nnget (session).addLast (chanEnt);
            if (cleanupThread == null) {
                cleanupThread = new CleanupThread ();
                cleanupThread.start ();
            }
        }
    }

    /**
     * Scan the channelPool for channels that haven't been used in a while
     * and close them.
     */
    private static class CleanupThread extends Thread {
        @Override
        public void run ()
        {
            try {
                //noinspection InfiniteLoopStatement
                while (true) {
                    Thread.sleep (1000, 0);
                    synchronized (channelPool) {
                        for (Iterator<Session> its = channelPool.keySet ().iterator (); its.hasNext ();) {
                            Session session = its.next ();
                            for (Iterator<ChanEnt> itc = channelPool.nnget (session).iterator (); itc.hasNext ();) {
                                ChanEnt chanEnt = itc.next ();
                                if (++ chanEnt.idle > 1) {
                                    chanEnt.chan.disconnect ();
                                    itc.remove ();
                                }
                            }
                            if (channelPool.isEmpty ()) {
                                its.remove ();
                            }
                        }
                    }
                }
            } catch (InterruptedException ie) {
                Log.e (TAG, "CleanupThread.run() exception", ie);
            }
        }
    }

    /*******************************\
     *   Input and Output Streams  *
    \*******************************/

    private class SshRAIStream extends RAInputStream {
        private byte[] bbuf = new byte[1];
        private ChanEnt chanEnt;
        private InputStream wrapped;
        private long markpos;
        private long position;

        public SshRAIStream (ChanEnt ce) throws SftpException
        {
            chanEnt  = ce;
            wrapped  = ce.chan.get (abspath);
            position = 0;
        }

        // RAInputStream

        public long length () throws IOException
        {
            return SshIFile.this.length ();
        }

        public void readFully (byte[] buffer) throws IOException
        {
            readFully (buffer, 0, buffer.length);
        }

        public void readFully (byte[] buffer, int offset, int count) throws IOException
        {
            while (count > 0) {
                int rc = read (buffer, offset, count);
                if (rc <= 0) throw new EOFException ();
                offset += rc;
                count -= rc;
            }
        }

        public void seek (long pos) throws IOException
        {
            while ((pos > position) && (pos <= position + 4096)) {
                long rc = wrapped.skip (pos - position);
                position += rc;
            }
            if (pos != position) {
                wrapped.close ();
                wrapped = null;
                try {
                    wrapped = chanEnt.chan.get (abspath, null, pos);
                } catch (SftpException se) {
                    throw new SftpIOException (se);
                }
                position = pos;
            }
        }

        public long tell ()
        {
            return position;
        }

        // InputStream

        public void mark (int readlimit) { markpos = position; }
        public boolean markSupported () { return true; }
        public int read (@NonNull byte[] buffer) throws IOException { return read (buffer, 0, buffer.length); }
        public int read () throws IOException { return (read (bbuf, 0, 1) <= 0) ? -1 : (int)bbuf[0] & 0xFF; }
        public synchronized void reset () throws IOException { seek (markpos); }
        public long skip (long count) throws IOException { seek (tell () + count); return count; }

        public int read (@NonNull byte[] buffer, int offset, int count) throws IOException
        {
            int rc = wrapped.read (buffer, offset, count);
            if (rc > 0) position += rc;
            return rc;
        }

        public void close () throws IOException
        {
            synchronized (this) {
                if (wrapped != null) {
                    try {
                        wrapped.close ();
                    } finally {
                        finishedUsingChannel (chanEnt);
                        chanEnt = null;
                        wrapped = null;
                    }
                }
            }
        }
    }

    private class SshRAOStream extends RAOutputStream {
        private byte[] bbuf = new byte[1];
        private ChanEnt chanEnt;
        private OutputStream wrapped;
        private long position;

        public SshRAOStream (ChanEnt ce, int osmode) throws SftpException
        {
            int chmode;
            switch (osmode) {
                case OSMODE_APPEND: chmode = ChannelSftp.APPEND; break;
                case OSMODE_CREATE: chmode = ChannelSftp.OVERWRITE; break;
                default: throw new IllegalArgumentException ("bad osmode " + osmode);
            }
            chanEnt  = ce;
            wrapped  = ce.chan.put (abspath, null, chmode, 0);

            // passing 0 to ChannelSftp.put() with APPEND really means end-of-file
            // hopefully we come up with same number it did cuz we can't query it
            // and it throws away any exception it got reading the size
            // and there doesn't seem to be any difference between APPEND and RESUME
            // should be ok for use case cuz nothing else messing with ...$$$PART$$$... files
            cacheStat = ce.chan.stat (getAbsolutePath ());
            position  = (osmode == OSMODE_CREATE) ? 0 : cacheStat.getSize ();
        }

        // RAOutputStream

        public long length () throws IOException
        {
            try {
                cacheStat = chanEnt.chan.stat (getAbsolutePath ());
            } catch (SftpException se) {
                throw new SftpIOException (se);
            }
            return cacheStat.getSize ();
        }

        public void seek (long pos) throws IOException
        {
            if (pos != position) {
                wrapped.close ();
                wrapped = null;
                try {

                    // passing offset to ChannelSftp.put() with APPEND is relative to end-of-file
                    // hopefully we come up with same number it does cuz we can't query it
                    // and it throws away any exception it gets reading the size
                    // and there doesn't seem to be any difference between APPEND and RESUME
                    // should be ok for use case cuz nothing else messing with ...$$$PART$$$... files
                    cacheStat = chanEnt.chan.stat (getAbsolutePath ());
                    position  = cacheStat.getSize ();
                    wrapped   = chanEnt.chan.put (abspath, null, ChannelSftp.APPEND, pos - position);
                } catch (SftpException se) {
                    throw new SftpIOException (se);
                }
                position = pos;
            }
        }

        public long tell ()
        {
            return position;
        }

        // OutputStream

        public void flush () throws IOException { cacheLStat = cacheStat = null; wrapped.flush (); }
        public void write (@NonNull byte[] buffer) throws IOException { write (buffer, 0, buffer.length); }
        public void write (int oneByte) throws IOException { bbuf[0] = (byte) oneByte; write (bbuf, 0, 1); }

        public void write (@NonNull byte[] buffer, int offset, int count) throws IOException
        {
            cacheLStat = null;
            cacheStat  = null;
            wrapped.write (buffer, offset, count);
            position += count;
        }

        public void close () throws IOException
        {
            cacheLStat = null;
            cacheStat  = null;
            synchronized (this) {
                try {
                    wrapped.close ();
                } finally {
                    finishedUsingChannel (chanEnt);
                    chanEnt = null;
                    wrapped = null;
                }
            }
        }
    }
}
