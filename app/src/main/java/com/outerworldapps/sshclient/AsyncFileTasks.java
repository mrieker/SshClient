/**
 * Performs copy/move/delete operations as AsyncTask threads.
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

import java.util.Collection;
import java.util.concurrent.atomic.AtomicLong;

public class AsyncFileTasks {
    public final static String TAG = "SshClient";

    // an existing file to be copied/moved/deleted
    public static class Selected {
        public IFile file;    // selected file for input to copy/move or to delete
        public IFile outmap;  // what it maps to for copy/move
    }

    // replies to overwrite dialog
    public final static int OA_NOAN = -1;
    public final static int OA_ONE  =  0;
    public final static int OA_ALL  =  1;
    public final static int OA_SKIP =  2;
    public final static int OA_STOP =  3;

    // what to call when the answer to an overwrite is available
    public interface IOverAnswer {
        void overAnswer (int oa);
        void excpAnswer (Exception e);
    }

    // callbacks called while copy/move/delete is in progress
    // called in GUI thread
    public interface ICopyMoveDelCB {
        void selectedStart (Selected selected);
        void selectedDone ();
        void overwrite (IFile newFile, IOverAnswer overAnswer);
        void exception (IFile oldFile, IFile newFile, Exception e, IOverAnswer overAnswer);
        void completed (Exception e);

        void startFile (IFile oldFile, IFile newFile, long bytes);
        void partialCopy (long bytes);
        void endOfFile ();
    }

    private final static Object[]   zeroObjectArray   = new Object[0];
    private final static Selected[] zeroSelectedArray = new Selected[0];
    private final static Void[]     zeroVoidArray     = new Void[0];

    /**
     * Start copying/moving a list of files.
     * @param selecteds = files to be copied/moved
     * @param moveMode = true: move files
     *                  false: copy files
     * @param preScan = whether or not to do pre-scan pass
     * @param callbacks = various callbacks
     */
    public static CopyMoveDelFilesThread copyMoveFiles (
            Collection<? extends Selected> selecteds,
            boolean moveMode,
            boolean preScan,
            ICopyMoveDelCB callbacks)
    {
        CopyMoveFilesThread cmft = new CopyMoveFilesThread ();
        cmft.selecteds = selecteds.toArray (zeroSelectedArray);
        cmft.moveMode  = moveMode;
        cmft.preScan   = preScan;
        cmft.setCallbacks (callbacks);
        cmft.execute (zeroVoidArray);
        return cmft;
    }

    private static class CopyMoveFilesThread extends CopyMoveDelFilesThread {
        public boolean moveMode;
        public boolean preScan;

        /**
         * Per-selection processing for copy/move.
         */
        @Override  // CopyMoveDelFilesThread
        protected boolean forEachSelected (Selected selected)
                throws Exception
        {
            IFile oldFile = selected.file;
            IFile newFile = selected.outmap;

            // if new file already exists, ax user if they want it overwritten
            if ((overwriteAns != OA_ALL) && newFile.exists ()) {
                overwriteQuery (newFile);
                if (overwriteAns == OA_STOP) return false;
                if (overwriteAns == OA_SKIP) return true;
            }

            // starting the selected
            sendSelectedStart (selected);
            try {

                // copy/move files
                FileUtils.DirPreScan dps = preScan ? new FileUtils.DirPreScan () : null;
                if (moveMode) FileUtils.moveFile (oldFile, newFile, dps, this);
                         else FileUtils.copyFile (oldFile, newFile, dps, this);

            } finally {
                // that selected is done
                sendSelectedDone ();
            }

            return true;
        }
    }


    /**
     * Start deleting a list of files.
     * @param selecteds = files to be deleted
     * @param callbacks = various callbacks
     */
    public static CopyMoveDelFilesThread deleteFiles (
            Collection<? extends Selected> selecteds,
            ICopyMoveDelCB callbacks)
    {
        DeleteFilesThread dft = new DeleteFilesThread ();
        dft.selecteds = selecteds.toArray (zeroSelectedArray);
        dft.setCallbacks (callbacks);
        dft.execute (zeroVoidArray);
        return dft;
    }

    private static class DeleteFilesThread extends CopyMoveDelFilesThread {

        /**
         * Per-selection processing for delete.
         */
        @Override  // CopyMoveDelFilesThread
        protected boolean forEachSelected (Selected selected)
                throws Exception
        {
            IFile oldFile = selected.file;
            sendSelectedStart (selected);
            try {
                FileUtils.deleteFile (oldFile, this);
            } finally {
                sendSelectedDone ();
            }
            return true;
        }
    }


    /**
     * Common task for copy/move/delete.
     */
    public static abstract class CopyMoveDelFilesThread extends DetachableAsyncTask<Void,Object,Exception>
            implements IOverAnswer, FileUtils.XferListener {
        public Selected[] selecteds;         // files that have been selected for processing

        private final Object ppWaitLock = new Object ();

        private boolean exceptionPending;    // there was an exception during copy
        private boolean isPaused;
        private Exception exceptionExceptn;  // exception during copy: exception thrown
        private Exception progUpdException;  // one of the callbacks threw this exception
        private ICopyMoveDelCB callbacks;    // null: detached from GUI; else: callbacks to update GUI
        private IFile exceptionNewFile;      // exception during copy: dest file
        private IFile exceptionOldFile;      // exception during copy: src file
        private StackEntry lastActive;       // stacked (directory tree) transfers that are currently active
        private StackEntry lastPosted;       // those transfers that have been posted to GUI thread

        protected int overwriteAns;          // response from overwriteQuery() call
        private IFile overwriteNewFile;      // file used for overwriteQuery() call

        // subclasses instantiate this to process each file selected for processing
        protected abstract boolean forEachSelected (Selected selected) throws Exception;

        /**
         * Call this to attach/detatch the callbacks.
         * Callbacks are made in this thread.
         * @param cb = null to detach; else the attached callbacks
         */
        public void setCallbacks (ICopyMoveDelCB cb)
        {
            if (callbacks != null) detachGUI ();  // guarantee no calls to onProgressUpdate() below after this
            callbacks = cb;
            if (cb != null) attachGUI ();  // we can get calls to onProgressUpdate() below after this
        }

        /**
         * Signal through to the thread to pause/resume processing.
         */
        public void setPaused (boolean paused)
        {
            synchronized (ppWaitLock) {
                isPaused = paused;
                ppWaitLock.notifyAll ();
            }
        }

        /**
         * Called in the spawned thread to perform all the transfers in the selecteds list.
         */
        @Override
        protected Exception doInBackground (Void[] params)
        {
            try {
                for (Selected selected : selecteds) {
                    if (!forEachSelected (selected)) break;
                }
                return null;
            } catch (Exception e) {
                return e;
            }
        }

        /**
         * Called by an implementation of forEachSelected() to prompt the user if they want a
         * particular existing file overwritten by the transfer.  Waits for the reply.
         * @param newFile = file that is about to be overwritten
         * returns overwriteAns = answer
         */
        protected void overwriteQuery (IFile newFile)
                throws Exception
        {
            if (progUpdException != null) throw progUpdException;
            synchronized (ppWaitLock) {
                overwriteAns = OA_NOAN;
                overwriteNewFile = newFile;
            }
            publishProgress (zeroObjectArray);
            synchronized (ppWaitLock) {
                while (overwriteAns == OA_NOAN) {
                    try { ppWaitLock.wait (); } catch (InterruptedException ignored) { }
                }
            }
        }

        /**
         * Notify GUI callbacks that we are about to start processing the selection.
         * If no GUI callbacks attached, stack it for notification later.
         */
        protected void sendSelectedStart (Selected selected)
                throws Exception
        {
            if (progUpdException != null) throw progUpdException;
            SelectionEntry entry = new SelectionEntry (selected);
            synchronized (ppWaitLock) {
                entry.popToEntry = lastActive;
                lastActive = entry;
            }
            publishProgress (zeroObjectArray);
        }

        /**
         * Notify GUI callbacks that we have finished processing the selection.
         * If no GUI callbacks attached, stack it for notification later.
         */
        protected void sendSelectedDone ()
                throws Exception
        {
            if (progUpdException != null) throw progUpdException;
            synchronized (ppWaitLock) {
                lastActive.completed = true;
                lastActive = lastActive.popToEntry;
            }
            publishProgress (zeroObjectArray);
        }

        /**
         * Progress updates from FileUtils.{move,copy,delete}File().
         * Called at thread level, we pass the callback up to GUI level.
         * The GUI method might throw an exception (eg, to cancel the
         * operation), in which case we throw it to our caller, which
         * then throws it to the forEachSelected() which then throws it
         * to doInBackground() where it is finally caught and the
         * thread is terminated.
         *
         * If no GUI attached, stack it for notification later.
         */
        @Override  // XferListener
        public void startFile (IFile oldFile, IFile newFile, long bytes)
                throws Exception
        {
            if (progUpdException != null) throw progUpdException;
            FileXferEntry entry = new FileXferEntry (oldFile, newFile, bytes);
            synchronized (ppWaitLock) {
                entry.popToEntry = lastActive;
                lastActive = entry;
            }
            publishProgress (zeroObjectArray);
        }

        @Override  // XferListener
        public void partialCopy (long bytes)
                throws Exception
        {
            if (progUpdException != null) throw progUpdException;
            long old = ((FileXferEntry)lastActive).sofar.getAndSet (bytes);
            if (old == 0) publishProgress (zeroObjectArray);
        }

        @Override  // XferListener
        public void endOfFile ()
                throws Exception
        {
            if (progUpdException != null) throw progUpdException;
            synchronized (ppWaitLock) {
                lastActive.completed = true;
                lastActive = lastActive.popToEntry;
            }
            publishProgress (zeroObjectArray);
        }

        // there was an exception while copying a single file
        // we give the user a choice to continue on with the next file
        // ... or throw the exception out to abort the whole thing
        @Override  // XferListener
        public void exception (IFile oldFile, IFile newFile, Exception e) throws Exception
        {
            synchronized (ppWaitLock) {
                exceptionOldFile = oldFile;
                exceptionNewFile = newFile;
                exceptionExceptn = e;
                exceptionPending = true;
            }
            publishProgress (zeroObjectArray);
            synchronized (ppWaitLock) {
                while (exceptionPending) {
                    try { ppWaitLock.wait (); } catch (InterruptedException ignored) { }
                }
                e = exceptionExceptn;
            }
            if (e != null) throw e;
        }

        /**
         * The copy/move/delete thread calls this to find out if it should suspend or not.
         * @return null: continue processing; else: suspend and wait on this object
         */
        @Override  // XferListener
        public Object paused ()
                throws Exception
        {
            if (progUpdException != null) throw progUpdException;
            return isPaused ? ppWaitLock : null;
        }

        /**
         * In GUI thread, perform callbacks.
         * Doesn't get called when detached from GUI.
         */
        @Override  // com.outerworldapps.sshclient.DetachableAsyncTask
        protected void onProgressUpdate (Object[] params)
        {
            try {
                StackEntry completionsBeg;
                StackEntry completionsEnd;
                StackEntry notifications;
                StackEntry sendProgress;

                synchronized (ppWaitLock) {
                    StackEntry entry;

                    /*
                     * Send completions to GUI thread for everything that has been both notified
                     * to the GUI thread and completed by the async thread.
                     */
                    completionsBeg = lastPosted;
                    //noinspection StatementWithEmptyBody
                    for (entry = completionsBeg; (entry != null) && entry.completed; entry = entry.popToEntry) { }
                    completionsEnd = entry;

                    /*
                     * Maybe send a progress notification for what the GUI thread thinks is the top file.
                     */
                    sendProgress = entry;

                    /*
                     * Send GUI thread notifications for all new files that are being transferred.
                     */
                    notifications = null;
                    for (entry = lastActive; (entry != null) && !entry.notified; entry = entry.popToEntry) {
                        entry.notified  = true;
                        entry.nextNotif = notifications;
                        notifications   = entry;
                    }

                    /*
                     * We have posted everything that is active at this point.
                     */
                    lastPosted = lastActive;
                }

                /*
                 * Well actually send the stuff out now that we aren't locked.
                 */
                while (completionsBeg != completionsEnd) {
                    completionsBeg.sendCompletionNotification ();
                    completionsBeg = completionsBeg.popToEntry;
                }

                if (sendProgress != null) {
                    sendProgress.sendProgressNotification ();
                }

                while (notifications != null) {
                    notifications.sendStartNotification ();
                    notifications.sendProgressNotification ();
                    notifications = notifications.nextNotif;
                }

                /*
                 * If thread is stopped waiting for overwrite query, post query.
                 * We get a callback to overAnswer() below eventually.
                 */
                IFile onf;
                synchronized (ppWaitLock) {
                    onf = overwriteNewFile;
                    overwriteNewFile = null;
                }
                if (onf != null) {
                    callbacks.overwrite (onf, this);
                }

                /*
                 * If thread is stopped waiting for copy exception query, post query.
                 * We get a callback to excpAnswer() below eventually.
                 */
                Exception ecx = null;
                IFile ncx = null;
                IFile ocx = null;
                synchronized (ppWaitLock) {
                    if (exceptionPending) {
                        ocx = exceptionOldFile;
                        ncx = exceptionNewFile;
                        ecx = exceptionExceptn;
                    }
                }
                if (ecx != null) {
                    callbacks.exception (ocx, ncx, ecx, this);
                }
            } catch (Exception e) {
                progUpdException = e;
            }
        }

        /**
         * The thread has completed one way or the other, notify callback.
         */
        @Override  // com.outerworldapps.sshclient.DetachableAsyncTask
        public void onPostExecute (Exception e)
        {
            callbacks.completed (e);
        }

        /**
         * The user has replied to an overwrite dialog,
         * notify the thread of the answer given by user.
         */
        @Override  // IOverAnswer
        public void overAnswer (int oa)
        {
            synchronized (ppWaitLock) {
                overwriteAns = oa;
                ppWaitLock.notifyAll ();
            }
        }

        /**
         * The user has replied to a copy exception dialog
         * saying whether they want to go on to the next file
         * or abort the copy altogether.
         * @param e = null: continue on with next file
         *            else: abort the copy with this exception
         */
        @Override  // IOverAnswer
        public void excpAnswer (Exception e)
        {
            synchronized (ppWaitLock) {
                exceptionExceptn = e;
                exceptionPending = false;
                ppWaitLock.notifyAll ();
            }
        }

        /**
         * Transfers-in-progress stack entries.
         * Transfers will stack as we nest through the directory tree.
         */
        private static abstract class StackEntry {
            public StackEntry popToEntry;  // next outer entry
            public StackEntry nextNotif;   // next notification to output
            public boolean notified;       // GUI has been notified about this file
            public boolean completed;      // the transfer has completed

            public abstract void sendStartNotification ();
            public abstract void sendProgressNotification ();
            public abstract void sendCompletionNotification ();
        }

        private class SelectionEntry extends StackEntry {
            public Selected selected;

            public SelectionEntry (Selected sel)
            {
                selected = sel;
            }

            public void sendStartNotification ()
            {
                callbacks.selectedStart (selected);
            }

            public void sendProgressNotification ()
            { }

            public void sendCompletionNotification ()
            {
                callbacks.selectedDone ();
            }
        }

        private class FileXferEntry extends StackEntry {
            public IFile newFile;                         // copy destination file
            public IFile oldFile;                         // copy source file
            public AtomicLong sofar = new AtomicLong ();  // bytes transferred so far
            public long total;                            // total bytes to be transferred

            public FileXferEntry (IFile oldFile, IFile newFile, long total)
            {
                this.oldFile = oldFile;
                this.newFile = newFile;
                this.total   = total;
            }

            public void sendStartNotification ()
            {
                callbacks.startFile (oldFile, newFile, total);
            }

            public void sendProgressNotification ()
            {
                long bytes = sofar.getAndSet (0);
                if (bytes != 0) callbacks.partialCopy (bytes);
            }

            public void sendCompletionNotification ()
            {
                callbacks.endOfFile ();
            }
        }
    }
}
